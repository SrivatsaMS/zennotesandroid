package md.zennotes;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.Window;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Status and navigation bar icons in the ZenNotes theme's contrast, not the
 * system's. Two Capacitor plugins drive the bars' icon color and neither
 * knows the theme: core's SystemBars resolves its default style from the
 * system's night mode when it loads (dark icons whenever the phone is in
 * light mode) and posts it after the StatusBar plugin has applied the
 * config's light icons, so it always wins; both then re-apply their own
 * stale choice on every configuration change. On a WebView 140+ phone the
 * page draws under the bars, so a dark theme got near-black icons on its
 * dark background.
 *
 * The JS shell (src/bridge/system-bar-theme.ts) reads the theme's background
 * and hands it here whenever it changes. This paints every strip the app
 * owns with it, so what sits under the icons is the theme on any Android and
 * WebView: from Android 15 the bars are transparent and below WebView 140
 * Capacitor pads the decor view, whose background shows through; before
 * Android 15 the window's bar colors are what show. Then the icons take the
 * contrasting color. A configuration change (rotation, the system's dark
 * mode) re-applies it after the other two plugins have had their say.
 * Android-only; the iPhone status bar sits over the page already.
 */
@CapacitorPlugin(name = "SystemBarTheme")
public class SystemBarThemePlugin extends Plugin {

    /** Null until the JS shell has measured the theme: the launch theme's
     *  dark chrome stays until then. */
    private Integer color = null;
    private boolean light = false;

    @PluginMethod
    public void setTheme(PluginCall call) {
        String raw = call.getString("color");
        if (raw == null || raw.isEmpty()) {
            call.reject("color must be #rrggbb");
            return;
        }
        int parsed;
        try {
            parsed = Color.parseColor(raw);
        } catch (IllegalArgumentException e) {
            call.reject("color must be #rrggbb");
            return;
        }
        boolean isLight = Boolean.TRUE.equals(call.getBoolean("light", false));
        getActivity().runOnUiThread(() -> {
            color = parsed;
            light = isLight;
            apply();
            call.resolve();
        });
    }

    @Override
    protected void handleOnConfigurationChanged(Configuration newConfig) {
        super.handleOnConfigurationChanged(newConfig);
        // SystemBars and StatusBar re-apply their styles in this same pass,
        // in an order nothing promises; go after both.
        getActivity().getWindow().getDecorView().post(this::apply);
    }

    @SuppressWarnings("deprecation")
    private void apply() {
        if (color == null) return;
        Window window = getActivity().getWindow();
        window.getDecorView().setBackgroundColor(color);
        boolean edgeToEdge = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM;
        if (!edgeToEdge) {
            window.setStatusBarColor(color);
        }
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, window.getDecorView());
        bars.setAppearanceLightStatusBars(light);
        // Dark navigation buttons arrived in Android 8; before that a light
        // bar would hide its white buttons, so it keeps the launch theme's.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!edgeToEdge) {
                window.setNavigationBarColor(color);
            }
            bars.setAppearanceLightNavigationBars(light);
        }
    }
}
