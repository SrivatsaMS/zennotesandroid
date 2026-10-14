package md.zennotes;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The native half of background sync (src/bridge/background-sync.ts). The
 * sync engine is JavaScript in this activity's WebView, so the jobs scheduled
 * here only ask it to run: {@link BackgroundSyncWorker} finds the live plugin,
 * sends a {@code sync} event and waits for JS to answer {@code finished}.
 * Once the activity is gone there is no WebView to ask, and a job does
 * nothing; the next launch syncs.
 */
@CapacitorPlugin(name = "ZenBackgroundSync")
public class BackgroundSyncPlugin extends Plugin {

    static final String PERIODIC_WORK = "zennotes-cloud-sync";
    static final String CATCH_UP_WORK = "zennotes-cloud-sync-catch-up";
    /** An ordinary final sync ends well before this and cancels the catch-up. */
    private static final long CATCH_UP_DELAY_SECONDS = 30;

    private static volatile BackgroundSyncPlugin current;
    private final Map<String, CountDownLatch> pending = new ConcurrentHashMap<>();

    static BackgroundSyncPlugin current() {
        return current;
    }

    @Override
    public void load() {
        current = this;
    }

    @Override
    protected void handleOnDestroy() {
        if (current == this) current = null;
        // Nothing will answer a job still waiting: release it now.
        for (CountDownLatch answered : pending.values()) answered.countDown();
        pending.clear();
    }

    /** Leaving: queue a catch-up run for a final sync Android freezes midway. */
    @PluginMethod
    public void begin(PluginCall call) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(BackgroundSyncWorker.class)
            .setConstraints(networkConstraints())
            .setInitialDelay(CATCH_UP_DELAY_SECONDS, TimeUnit.SECONDS)
            .build();
        WorkManager.getInstance(getContext()).enqueueUniqueWork(CATCH_UP_WORK, ExistingWorkPolicy.REPLACE, request);
        call.resolve();
    }

    /** The final sync ended: the catch-up run is not needed. */
    @PluginMethod
    public void end(PluginCall call) {
        WorkManager.getInstance(getContext()).cancelUniqueWork(CATCH_UP_WORK);
        call.resolve();
    }

    @PluginMethod
    public void schedule(PluginCall call) {
        // WorkManager refuses shorter repeat intervals.
        long minutes = Math.max(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS / 60_000L, call.getInt("minutes", 15));
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(BackgroundSyncWorker.class, minutes, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .build();
        WorkManager.getInstance(getContext()).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        );
        call.resolve();
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        WorkManager.getInstance(getContext()).cancelUniqueWork(PERIODIC_WORK);
        call.resolve();
    }

    /** JS answering a {@code sync} event: the job that sent it can end. */
    @PluginMethod
    public void finished(PluginCall call) {
        String id = call.getString("id");
        CountDownLatch answered = id == null ? null : pending.remove(id);
        if (answered != null) answered.countDown();
        call.resolve();
    }

    /**
     * Asks JS for a sync. The latch opens when JS answers, or when the
     * activity goes away; null when nothing listens yet (the app is still
     * starting), in which case the job ends at once.
     */
    CountDownLatch requestSync(String id) {
        if (!hasListeners("sync")) return null;
        CountDownLatch answered = new CountDownLatch(1);
        pending.put(id, answered);
        JSObject data = new JSObject();
        data.put("id", id);
        notifyListeners("sync", data);
        return answered;
    }

    void forget(String id) {
        pending.remove(id);
    }

    private static Constraints networkConstraints() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }
}
