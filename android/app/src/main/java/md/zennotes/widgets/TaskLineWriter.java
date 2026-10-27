package md.zennotes.widgets;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Writes a Calendar widget tick into the note itself, with the app closed.
 *
 * It never parses a task: app-core sent both forms of the line (`raw`
 * unticked, `toggled` ticked, from its own toggleTaskAtIndex), and this
 * swaps one for the other only when the line at that number is exactly the
 * one expected. Anything else (the note moved on, a different line there,
 * a folder it cannot reach) answers false and the tap waits in the log for
 * the app, which finds the task again or leaves it alone.
 *
 * Line numbers count the parser's lines: core splits on "\n" after turning
 * "\r\n" into "\n", so a CRLF line compares without its "\r" and keeps it.
 * Notes are overwritten in place, as the vault writes them: a temporary
 * file beside the note would show up in the app's listings.
 */
final class TaskLineWriter {
    private TaskLineWriter() {}

    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    /** The line now holds `wanted` (written, or it already did). */
    static boolean swap(Context context, String root, String relPath, int line, String expected, String wanted) {
        if (root == null || relPath == null || line < 0 || expected == null || wanted == null) return false;
        if (!safe(relPath)) return false;
        synchronized (LOCK) {
            try {
                Target target = Target.resolve(context, root, relPath);
                if (target == null) return false;
                String text = target.read();
                if (text == null) return false;
                int start = 0;
                for (int i = 0; i < line; i++) {
                    int next = text.indexOf('\n', start);
                    if (next < 0) return false;
                    start = next + 1;
                }
                int end = text.indexOf('\n', start);
                if (end < 0) end = text.length();
                String current = text.substring(start, end);
                String ending = "";
                if (current.endsWith("\r")) {
                    current = current.substring(0, current.length() - 1);
                    ending = "\r";
                }
                String bom = "";
                if (line == 0 && current.startsWith("﻿")) {
                    bom = "﻿";
                    current = current.substring(1);
                }
                if (current.equals(wanted)) return true;
                if (!current.equals(expected)) return false;
                target.write(text.substring(0, start) + bom + wanted + ending + text.substring(end));
                return true;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }
    }

    /** Vault-relative, forward slashes, no `..` segment (SafFsPlugin.clean's rule). */
    static boolean safe(String rel) {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains("\\") || rel.indexOf('\0') >= 0) return false;
        for (String segment : rel.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    private abstract static class Target {
        abstract InputStream open() throws IOException;

        abstract OutputStream create() throws IOException;

        String read() throws IOException {
            try (InputStream in = open()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(chunk)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) return null;
                    buffer.write(chunk, 0, n);
                }
                // A note that is not valid UTF-8 would come back with every bad
                // byte replaced; leave it for the app rather than rewrite it so.
                try {
                    return StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(buffer.toByteArray()))
                            .toString();
                } catch (CharacterCodingException e) {
                    return null;
                }
            }
        }

        void write(String text) throws IOException {
            try (OutputStream out = create()) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
            }
        }

        static Target resolve(Context context, String root, String rel) {
            if (root.startsWith("file:")) {
                String base = Uri.parse(root).getPath();
                if (base == null) return null;
                File file = new File(base, rel);
                return file.isFile() ? new FileTarget(file) : null;
            }
            if (root.startsWith("content:")) {
                ContentResolver resolver = context.getContentResolver();
                Uri tree = Uri.parse(root);
                Uri doc = findDocument(resolver, tree, rel);
                return doc == null ? null : new DocumentTarget(resolver, doc);
            }
            return null;
        }

        /** Walk a picked folder by name, one listing per segment (a
         *  provider's document ids are opaque, so no id is built by hand). */
        private static Uri findDocument(ContentResolver resolver, Uri tree, String rel) {
            String docId = DocumentsContract.getTreeDocumentId(tree);
            String[] segments = rel.split("/");
            for (int s = 0; s < segments.length; s++) {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
                String found = null;
                boolean dir = false;
                try (Cursor c = resolver.query(children, new String[] {
                        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE
                }, null, null, null)) {
                    if (c == null) return null;
                    while (c.moveToNext()) {
                        if (segments[s].equals(c.getString(1))) {
                            found = c.getString(0);
                            dir = Document.MIME_TYPE_DIR.equals(c.getString(2));
                            break;
                        }
                    }
                }
                if (found == null) return null;
                boolean last = s == segments.length - 1;
                if (last == dir) return null;
                docId = found;
            }
            return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        }
    }

    private static final class FileTarget extends Target {
        private final File file;

        FileTarget(File file) {
            this.file = file;
        }

        @Override
        InputStream open() throws IOException {
            return new FileInputStream(file);
        }

        @Override
        OutputStream create() throws IOException {
            return new FileOutputStream(file, false);
        }
    }

    private static final class DocumentTarget extends Target {
        private final ContentResolver resolver;
        private final Uri doc;

        DocumentTarget(ContentResolver resolver, Uri doc) {
            this.resolver = resolver;
            this.doc = doc;
        }

        @Override
        InputStream open() throws IOException {
            InputStream in = resolver.openInputStream(doc);
            if (in == null) throw new IOException("No stream for " + doc);
            return in;
        }

        @Override
        OutputStream create() throws IOException {
            // "rwt" truncates; plain "w" can leave a stale tail (SafFsPlugin).
            OutputStream out = resolver.openOutputStream(doc, "rwt");
            if (out == null) throw new IOException("No stream for " + doc);
            return out;
        }
    }
}
