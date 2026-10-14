package md.zennotes;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The periodic and catch-up job of background sync (BackgroundSyncPlugin).
 * It hands the sync to the WebView and waits for the answer. It reports
 * success whatever happened: a sync that failed, or could not run because
 * the app is not in memory, waits for the next period rather than for
 * WorkManager's retry backoff.
 */
public class BackgroundSyncWorker extends Worker {

    /** WorkManager stops a job after ten minutes; give up well before that. */
    private static final long ANSWER_TIMEOUT_MINUTES = 5;

    public BackgroundSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        BackgroundSyncPlugin plugin = BackgroundSyncPlugin.current();
        if (plugin == null) return Result.success();
        String id = UUID.randomUUID().toString();
        CountDownLatch answered = plugin.requestSync(id);
        if (answered == null) return Result.success();
        try {
            answered.await(ANSWER_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            plugin.forget(id);
        }
        return Result.success();
    }
}
