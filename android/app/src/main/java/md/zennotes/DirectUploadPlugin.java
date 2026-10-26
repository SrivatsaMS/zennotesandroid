package md.zennotes;

import android.util.Base64;
import android.util.Base64InputStream;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;
import android.provider.DocumentsContract;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Streams a vault file to its short-lived signed object-storage URL.
 *
 * Capacitor's Android `dataType: file` implementation only decodes the body
 * on API 26+, while ZenNotes supports API 24. Keeping this tiny uploader in
 * the app makes the same direct-upload contract work on every supported OS.
 * The account bearer token never enters this plugin; it receives only the
 * signed URL and storage headers returned for this one upload.
 */
@CapacitorPlugin(name = "ZenDirectUpload")
public class DirectUploadPlugin extends Plugin {
    // The phones' stall bounds (shared-domain cloud-sync-stall.ts). The read
    // timeout starts over with every byte that arrives, so it is how long a
    // transfer may go silent, not how long a large one may take; JavaScript
    // runs a transfer that went silent again.
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int STALL_TIMEOUT_MS = 30_000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int HEADER_READ_LIMIT = 64 * 1024;

    @PluginMethod
    public void inspect(PluginCall call) {
        execute(() -> {
            try (InputStream input = openVaultFile(call.getString("uri"))) {
                CloudFileStream.Fingerprint fingerprint = CloudFileStream.inspect(input, call.getBoolean("textCandidate", false));
                JSObject result = new JSObject();
                result.put("uri", call.getString("uri"));
                result.put("byteLength", fingerprint.byteLength);
                result.put("sha256", fingerprint.sha256);
                result.put("utf8", fingerprint.utf8);
                if (fingerprint.inlineBytes != null) {
                    result.put("inlineBase64", Base64.encodeToString(fingerprint.inlineBytes, Base64.NO_WRAP));
                }
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Cloud file inspection failed.", "CLOUD_FILE_READ_FAILED", error);
            }
        });
    }

    @PluginMethod
    public void copy(PluginCall call) {
        execute(() -> {
            Long bytes = call.getLong("byteLength");
            if (bytes == null && call.getInt("byteLength") != null) bytes = call.getInt("byteLength").longValue();
            String hash = call.getString("sha256");
            if (bytes == null || bytes < 0 || hash == null || !hash.matches("[0-9a-f]{64}")) {
                call.reject("Invalid Cloud copy request.", "INVALID_COPY_REQUEST");
                return;
            }
            try {
                Uri target = vaultFileUri(call.getString("to"), Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                Uri source = vaultFileUri(call.getString("from"), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (source.equals(target)) throw new IOException("Cloud copy source and destination must differ.");
                try (InputStream input = openVaultFile(source.toString());
                     OutputStream output = "content".equals(target.getScheme())
                         ? getContext().getContentResolver().openOutputStream(target, "wt")
                         : new FileOutputStream(target.getPath())) {
                    if (output == null) throw new IOException("Cloud copy destination is unavailable.");
                    CloudFileStream.copyVerified(input, output, bytes, hash);
                }
                call.resolve();
            } catch (Exception error) {
                call.reject("Cloud file copy failed.", "CLOUD_FILE_COPY_FAILED", error);
            }
        });
    }

    /**
     * Stream a signed Cloud revision GET into a vault staging file. The body
     * never enters the WebView; a length or SHA-256 mismatch deletes the
     * partial file and rejects, so a truncated download can never be published.
     */
    @PluginMethod
    public void download(PluginCall call) {
        execute(() -> {
            Long bytes = call.getLong("byteLength");
            if (bytes == null && call.getInt("byteLength") != null) bytes = call.getInt("byteLength").longValue();
            String hash = call.getString("sha256");
            String value = call.getString("url");
            JSObject headers = call.getObject("headers", new JSObject());
            if (bytes == null || bytes < 0 || hash == null || !hash.matches("[0-9a-f]{64}") || value == null) {
                call.reject("Invalid Cloud download request.", "INVALID_DOWNLOAD_REQUEST");
                return;
            }
            HttpURLConnection connection = null;
            Uri target = null;
            try {
                target = vaultFileUri(call.getString("to"), Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                URL url = new URL(value);
                validateUrl(url);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(STALL_TIMEOUT_MS);
                connection.setRequestProperty("Accept-Encoding", "identity");
                applyHeaders(connection, headers);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    drain(connection.getErrorStream());
                    JSObject data = new JSObject();
                    data.put("status", status);
                    call.reject("Cloud object download failed (" + status + ").", "DIRECT_DOWNLOAD_FAILED", null, data);
                    return;
                }
                long declared = connection.getContentLengthLong();
                if (declared >= 0 && declared != bytes) throw new IOException("Cloud download length did not match its reference.");
                try (InputStream input = connection.getInputStream();
                     OutputStream output = "content".equals(target.getScheme())
                         ? getContext().getContentResolver().openOutputStream(target, "wt")
                         : new FileOutputStream(target.getPath())) {
                    if (output == null) throw new IOException("Cloud download destination is unavailable.");
                    CloudFileStream.copyVerified(input, output, bytes, hash);
                }
                call.resolve();
            } catch (Exception error) {
                if (target != null && "file".equals(target.getScheme())) new File(target.getPath()).delete();
                call.reject("Cloud object download failed.", "DIRECT_DOWNLOAD_FAILED", error);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    /**
     * Read the first bytes of a vault file, so JavaScript can check a staged
     * ZNE1 object's header and key commitment before decryptFile opens its
     * chunks. At most one chunk, so this never becomes a way to carry a whole
     * file across the bridge.
     */
    @PluginMethod
    public void readHeader(PluginCall call) {
        execute(() -> {
            Integer length = call.getInt("length");
            if (length == null || length < 1 || length > HEADER_READ_LIMIT) {
                call.reject("Invalid Cloud file header request.", "INVALID_HEADER_REQUEST");
                return;
            }
            try (InputStream input = openVaultFile(call.getString("source"))) {
                JSObject result = new JSObject();
                result.put("base64", Base64.encodeToString(CloudFileStream.readPrefix(input, length), Base64.NO_WRAP));
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Cloud file header read failed.", "CLOUD_FILE_READ_FAILED", error);
            }
        });
    }

    /**
     * Seal a vault file into a ZNE1 object for an end-to-end encrypted Cloud
     * vault. JavaScript derives the object key, builds the header and computes
     * the AAD, so no parent key crosses the bridge; the file moves in 64 KiB
     * chunks and its bytes never enter the WebView. Resolves the ciphertext's
     * length and SHA-256, which the upload declares, and the plaintext's,
     * which the item metadata records.
     */
    @PluginMethod
    public void encryptFile(PluginCall call) {
        execute(() -> transformZne1(call, true));
    }

    /**
     * Open a staged ZNE1 object into a vault staging file. JavaScript has
     * already checked the header's key commitment; this confirms the file
     * still begins with that header and writes only chunks that authenticate.
     * Any failure deletes the partial output, so a truncated or forged object
     * never lands as plaintext.
     */
    @PluginMethod
    public void decryptFile(PluginCall call) {
        execute(() -> transformZne1(call, false));
    }

    private void transformZne1(PluginCall call, boolean encrypt) {
        String operation = encrypt ? "encryption" : "decryption";
        Zne1FileCipher cipher;
        try {
            cipher = new Zne1FileCipher(decodeBase64(call, "header"), decodeBase64(call, "objectKey"),
                decodeBase64(call, "aad"));
        } catch (IllegalArgumentException error) {
            call.reject("Invalid Cloud " + operation + " request.",
                encrypt ? "INVALID_ENCRYPT_REQUEST" : "INVALID_DECRYPT_REQUEST");
            return;
        }
        Uri written = null;
        try {
            Uri target = vaultFileUri(call.getString("destination"), Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Uri source = vaultFileUri(call.getString("source"), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (source.equals(target)) throw new IOException("Cloud " + operation + " source and destination must differ.");
            JSObject result = new JSObject();
            try (InputStream input = openVaultFile(source.toString())) {
                written = target;
                try (OutputStream output = "content".equals(target.getScheme())
                         ? getContext().getContentResolver().openOutputStream(target, "wt")
                         : new FileOutputStream(target.getPath())) {
                    if (output == null) throw new IOException("Cloud " + operation + " destination is unavailable.");
                    if (encrypt) {
                        Zne1FileCipher.Sealed sealed = cipher.encrypt(input, output);
                        result.put("byteLength", sealed.byteLength);
                        result.put("sha256", sealed.sha256);
                        result.put("plaintextByteLength", sealed.plaintextByteLength);
                        result.put("plaintextSha256", sealed.plaintextSha256);
                    } else {
                        Zne1FileCipher.Opened opened = cipher.decrypt(input, output);
                        result.put("byteLength", opened.byteLength);
                        result.put("sha256", opened.sha256);
                    }
                }
            }
            call.resolve(result);
        } catch (Exception error) {
            discard(written);
            if (error instanceof Zne1FileCipher.Zne1Exception) {
                call.reject(error.getMessage(), ((Zne1FileCipher.Zne1Exception) error).code, error);
            } else {
                call.reject("Cloud file " + operation + " failed.",
                    encrypt ? "CLOUD_FILE_ENCRYPT_FAILED" : "CLOUD_FILE_DECRYPT_FAILED", error);
            }
        }
    }

    private static byte[] decodeBase64(PluginCall call, String name) {
        String value = call.getString(name);
        if (value == null) throw new IllegalArgumentException("Missing " + name + ".");
        return Base64.decode(value, Base64.DEFAULT);
    }

    /** Remove a failed write's partial output, so part of a file can never pass for all of it. */
    private void discard(Uri target) {
        if (target == null) return;
        if ("file".equals(target.getScheme())) {
            new File(target.getPath()).delete();
            return;
        }
        try {
            DocumentsContract.deleteDocument(getContext().getContentResolver(), target);
        } catch (Exception ignored) {
            // The bridge deletes its staging document too; the rejection
            // already marks this output as incomplete.
        }
    }

    @PluginMethod
    public void put(PluginCall call) {
        execute(() -> upload(call));
    }

    private void upload(PluginCall call) {
        HttpURLConnection connection = null;
        UploadStallWatchdog watchdog = null;
        try {
            String value = call.getString("url");
            String base64 = call.getString("base64");
            String sourceUri = call.getString("uri");
            String expectedHash = call.getString("sha256");
            Integer expectedBytes = call.getInt("byteLength");
            JSObject headers = call.getObject("headers", new JSObject());
            if (value == null || (base64 == null && sourceUri == null) || expectedBytes == null || expectedBytes < 0
                || (sourceUri != null && (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}")))) {
                call.reject("Invalid direct-upload request.", "INVALID_DIRECT_UPLOAD_REQUEST");
                return;
            }

            URL url = new URL(value);
            validateUrl(url);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(STALL_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(expectedBytes);
            applyHeaders(connection, headers);

            watchdog = new UploadStallWatchdog(connection, STALL_TIMEOUT_MS);
            try (
                InputStream decoded = sourceUri != null ? openVaultFile(sourceUri) : new Base64InputStream(
                    new ByteArrayInputStream(base64.getBytes(StandardCharsets.US_ASCII)), Base64.DEFAULT);
                OutputStream output = watchdog.watch(connection.getOutputStream())
            ) {
                if (expectedHash != null) {
                    CloudFileStream.copyVerified(decoded, output, expectedBytes, expectedHash);
                } else {
                    long written = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    while ((count = decoded.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        written += count;
                    }
                    if (written != expectedBytes) throw new IOException("Decoded upload size changed before transmission.");
                    output.flush();
                }
            }
            // The body is out; the read timeout bounds the wait for the answer.
            watchdog.close();

            int status = connection.getResponseCode();
            drain(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            JSObject result = new JSObject();
            result.put("status", status);
            call.resolve(result);
        } catch (Exception error) {
            call.reject("Cloud object upload failed.", "DIRECT_UPLOAD_FAILED", error);
        } finally {
            if (watchdog != null) watchdog.close();
            if (connection != null) connection.disconnect();
        }
    }

    private InputStream openVaultFile(String value) throws IOException {
        Uri uri = vaultFileUri(value, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if ("content".equals(uri.getScheme())) {
            InputStream input = getContext().getContentResolver().openInputStream(uri);
            if (input == null) throw new IOException("Vault file is unavailable.");
            return input;
        }
        return new FileInputStream(uri.getPath());
    }

    private Uri vaultFileUri(String value, int access) throws IOException {
        if (value == null) throw new IOException("Missing vault file.");
        Uri uri = Uri.parse(value);
        if ("content".equals(uri.getScheme())) {
            if (getContext().checkUriPermission(uri, Process.myPid(), Process.myUid(), access)
                != PackageManager.PERMISSION_GRANTED) throw new IOException("Vault file access is not granted.");
            return uri;
        }
        if (!"file".equals(uri.getScheme()) || uri.getPath() == null ||
            (uri.getAuthority() != null && !uri.getAuthority().isEmpty())) throw new IOException("Invalid vault file URI.");
        File file = new File(uri.getPath()).getCanonicalFile();
        if (!insideVaults(file, getContext().getFilesDir()) && !insideVaults(file, getContext().getExternalFilesDir(null))) {
            throw new IOException("File is outside the local vault storage.");
        }
        return Uri.fromFile(file);
    }

    private static boolean insideVaults(File file, File storage) throws IOException {
        return storage != null && file.getPath().startsWith(new File(storage, "ZenNotes").getCanonicalPath() + File.separator);
    }

    private static void validateUrl(URL url) {
        String protocol = url.getProtocol();
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
        boolean loopback = host.equals("localhost") || host.equals("::1") || host.startsWith("127.");
        if ((!protocol.equals("https") && !(protocol.equals("http") && loopback)) || url.getUserInfo() != null) {
            throw new IllegalArgumentException("Unsafe direct-upload URL.");
        }
    }

    private static void applyHeaders(HttpURLConnection connection, JSObject headers) throws Exception {
        Iterator<String> names = headers.keys();
        while (names.hasNext()) {
            String name = names.next();
            String value = headers.getString(name);
            if (value == null || containsNewline(name) || containsNewline(value)) {
                throw new IllegalArgumentException("Invalid direct-upload header.");
            }
            if (name.equalsIgnoreCase("Authorization")) {
                throw new IllegalArgumentException("Authorization is not allowed on signed object uploads.");
            }
            connection.setRequestProperty(name, value);
        }
    }

    private static boolean containsNewline(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static void drain(InputStream input) {
        if (input == null) return;
        byte[] buffer = new byte[4 * 1024];
        try (InputStream stream = input) {
            while (stream.read(buffer) != -1) {
                // Object-storage responses are intentionally discarded.
            }
        } catch (Exception ignored) {
            // The HTTP status is authoritative; a response-body read failure
            // must not turn a successfully persisted object into a retry.
        }
    }
}
