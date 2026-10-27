package md.zennotes;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.util.Locale;

/**
 * Opens a vault file in the app the user keeps for its type, through a VIEW
 * intent: the bridge contract's openAssetExternally, which the shell never
 * had. The WebView has no PDF viewer, so a PDF embed or reference pane drew
 * an empty page in place (Dave, Discord, 2026-10-27); this is how a PDF shows
 * at all on Android, and how any other attachment opens.
 *
 * A file:// path (the app's own vault folder, or a copy staged out of a remote
 * vault) goes out through the app's FileProvider. A content:// document under
 * a picked folder goes out as it is, and the read grant this app holds on it
 * is passed along. Rejects with NO_APP when nothing on the phone views the
 * type, so the shell can say so instead of failing silently.
 */
@CapacitorPlugin(name = "OpenFile")
public class OpenFilePlugin extends Plugin {

    @PluginMethod
    public void open(PluginCall call) {
        String raw = call.getString("uri");
        if (raw == null || raw.isEmpty()) {
            call.reject("No file to open.");
            return;
        }
        Context context = getContext();
        Uri uri;
        try {
            Uri parsed = Uri.parse(raw);
            if ("content".equals(parsed.getScheme())) {
                uri = parsed;
            } else if ("file".equals(parsed.getScheme()) && parsed.getPath() != null) {
                uri = FileProvider.getUriForFile(
                        context, context.getPackageName() + ".fileprovider", new File(parsed.getPath()));
            } else {
                call.reject("This file is not on this phone.");
                return;
            }
        } catch (IllegalArgumentException e) {
            call.reject("This file cannot be handed to another app.", e);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType(context, uri, raw))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        getActivity().runOnUiThread(() -> {
            try {
                getActivity().startActivity(intent);
                call.resolve();
            } catch (ActivityNotFoundException e) {
                call.reject("No app on this phone opens this kind of file.", "NO_APP");
            }
        });
    }

    /** The provider's answer first (a picked folder knows its documents' types),
     *  then the file name's extension, then any type at all. */
    private static String mimeType(Context context, Uri uri, String raw) {
        String type = null;
        try {
            type = context.getContentResolver().getType(uri);
        } catch (RuntimeException ignored) {
            // Fall back to the name below.
        }
        if (type != null) return type;
        String name = Uri.decode(raw);
        int dot = name.lastIndexOf('.');
        int slash = name.lastIndexOf('/');
        if (dot > slash && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        }
        return type != null ? type : "*/*";
    }
}
