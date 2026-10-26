package md.zennotes;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Drops an upload whose bytes stopped leaving.
 *
 * Android's HttpURLConnection has a read timeout but no write timeout, so a
 * PUT on a connection that went quiet (a filtering network can hold one open
 * and pass nothing) blocks in write until the kernel gives up, many minutes
 * later. This watches the bytes the upload hands to the connection and
 * disconnects once none moved for the stall bound, which turns the blocked
 * write into an IOException. Writes go out in small slices, so a slow link
 * that still moves a few hundred bytes a second never looks stalled.
 */
final class UploadStallWatchdog implements AutoCloseable {
    private static final int SLICE_BYTES = 8 * 1024;
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "zen-upload-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final HttpURLConnection connection;
    private final long stallNanos;
    private volatile long lastProgress = System.nanoTime();
    private volatile ScheduledFuture<?> check;

    UploadStallWatchdog(HttpURLConnection connection, int stallMs) {
        this.connection = connection;
        this.stallNanos = TimeUnit.MILLISECONDS.toNanos(stallMs);
        long period = Math.max(250, stallMs / 10);
        this.check = TIMER.scheduleWithFixedDelay(this::check, period, period, TimeUnit.MILLISECONDS);
    }

    OutputStream watch(OutputStream output) {
        return new FilterOutputStream(output) {
            @Override
            public void write(int value) throws IOException {
                out.write(value);
                lastProgress = System.nanoTime();
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                for (int end = offset + length; offset < end; offset += SLICE_BYTES) {
                    out.write(bytes, offset, Math.min(SLICE_BYTES, end - offset));
                    lastProgress = System.nanoTime();
                }
            }
        };
    }

    private void check() {
        if (System.nanoTime() - lastProgress < stallNanos) return;
        close();
        connection.disconnect();
    }

    @Override
    public void close() {
        ScheduledFuture<?> scheduled = check;
        if (scheduled != null) scheduled.cancel(false);
    }
}
