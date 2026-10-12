package md.zennotes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The ZNE1 chunk loop for end-to-end encrypted Cloud vaults. It writes and
 * accepts exactly the bytes of encryptZne1Stream and decryptZne1Stream in the
 * shared cloud-e2ee module, and refuses truncated, reordered or forged chunks
 * with the same codes; docs/specs/cloud-e2ee.md in the desktop repo is
 * normative, and the shared vectors in the unit-test resources pin the bytes.
 *
 * JavaScript derives the object key, builds the 74-byte header, checks the key
 * commitment and computes the AAD. This class receives only those three, never
 * a parent key, and holds two chunks at a time, so a 200 MB attachment never
 * crosses the WebView bridge or sits in memory whole.
 */
final class Zne1FileCipher {
    static final int HEADER_BYTES = 74;
    static final int CHUNK_BYTES = 65_536;
    static final int TAG_BYTES = 16;
    static final int SEALED_CHUNK_BYTES = CHUNK_BYTES + TAG_BYTES;
    static final String MALFORMED = "E2EE_MALFORMED";
    static final String TAMPERED = "E2EE_TAMPERED";

    private static final byte[] MAGIC = { 0x5a, 0x4e, 0x45, 0x31 };
    private static final byte ALGORITHM_AES_256_GCM = 0x01;
    private static final int KEY_BYTES = 32;
    private static final int AAD_BYTES = 32;

    /** A refusal the bridge reports by its code: malformed data, or data that failed authentication. */
    static final class Zne1Exception extends IOException {
        final String code;

        Zne1Exception(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** The ciphertext the upload declares, and the plaintext the item metadata records. */
    static final class Sealed {
        final long byteLength;
        final String sha256;
        final long plaintextByteLength;
        final String plaintextSha256;

        Sealed(long byteLength, String sha256, long plaintextByteLength, String plaintextSha256) {
            this.byteLength = byteLength;
            this.sha256 = sha256;
            this.plaintextByteLength = plaintextByteLength;
            this.plaintextSha256 = plaintextSha256;
        }
    }

    static final class Opened {
        final long byteLength;
        final String sha256;

        Opened(long byteLength, String sha256) {
            this.byteLength = byteLength;
            this.sha256 = sha256;
        }
    }

    private final byte[] header;
    private final SecretKeySpec key;
    private final byte[] aad;

    Zne1FileCipher(byte[] header, byte[] objectKey, byte[] aad) {
        // Another algorithm byte is another format with its own chunking, which
        // this loop must never run.
        if (header == null || header.length != HEADER_BYTES
            || !Arrays.equals(Arrays.copyOf(header, MAGIC.length), MAGIC) || header[4] != ALGORITHM_AES_256_GCM) {
            throw new IllegalArgumentException("Expected a 74-byte ZNE1 header for algorithm 1.");
        }
        if (objectKey == null || objectKey.length != KEY_BYTES) {
            throw new IllegalArgumentException("ZNE1 object keys are 32 bytes.");
        }
        if (aad == null || aad.length != AAD_BYTES) throw new IllegalArgumentException("ZNE1 AAD is 32 bytes.");
        this.header = header.clone();
        this.key = new SecretKeySpec(objectKey, "AES");
        this.aad = aad.clone();
    }

    /** Writes the header, then one sealed chunk per 65536 plaintext bytes; an empty input seals one empty chunk. */
    Sealed encrypt(InputStream plaintext, OutputStream ciphertext) throws IOException {
        Cipher cipher = newCipher();
        MessageDigest plainDigest = CloudFileStream.digest();
        MessageDigest sealedDigest = CloudFileStream.digest();
        byte[] chunk = new byte[CHUNK_BYTES];
        byte[] following = new byte[CHUNK_BYTES];
        byte[] sealed = new byte[SEALED_CHUNK_BYTES];
        ciphertext.write(header);
        sealedDigest.update(header);
        long plainLength = 0;
        long sealedLength = HEADER_BYTES;
        int size = fill(plaintext, chunk);
        for (long index = 0; ; index++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Cloud encryption cancelled.");
            // A full chunk is the last one only when nothing follows it, so the
            // next one is read before this one is sealed.
            int next = size == CHUNK_BYTES ? fill(plaintext, following) : 0;
            boolean last = next == 0;
            int count = crypt(cipher, Cipher.ENCRYPT_MODE, chunk, size, sealed, index, last);
            plainDigest.update(chunk, 0, size);
            sealedDigest.update(sealed, 0, count);
            ciphertext.write(sealed, 0, count);
            plainLength += size;
            sealedLength += count;
            if (last) break;
            byte[] done = chunk;
            chunk = following;
            following = done;
            size = next;
        }
        ciphertext.flush();
        return new Sealed(sealedLength, CloudFileStream.hex(sealedDigest.digest()),
            plainLength, CloudFileStream.hex(plainDigest.digest()));
    }

    /**
     * Writes the plaintext of each chunk once it authenticates, so the output
     * only ever holds a verified prefix. A failure says nothing about how much
     * was written; the caller discards the output.
     */
    Opened decrypt(InputStream ciphertext, OutputStream plaintext) throws IOException {
        byte[] found = new byte[HEADER_BYTES];
        if (fill(ciphertext, found) < HEADER_BYTES) {
            throw new Zne1Exception(MALFORMED, "The encrypted data is shorter than a ZNE1 header.");
        }
        if (!Arrays.equals(found, header)) {
            // JavaScript checked this header's commitment; any other object is
            // not the one it unlocked.
            throw new Zne1Exception(TAMPERED, "The encrypted data does not begin with the header that was checked.");
        }
        Cipher cipher = newCipher();
        MessageDigest digest = CloudFileStream.digest();
        byte[] chunk = new byte[SEALED_CHUNK_BYTES];
        byte[] following = new byte[SEALED_CHUNK_BYTES];
        byte[] opened = new byte[SEALED_CHUNK_BYTES];
        long length = 0;
        int size = fill(ciphertext, chunk);
        for (long index = 0; ; index++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Cloud decryption cancelled.");
            // The chunk that ends the input is the last one, so each full chunk
            // waits for the next read before it is opened.
            int next = size == SEALED_CHUNK_BYTES ? fill(ciphertext, following) : 0;
            boolean last = next == 0;
            if (last && size < TAG_BYTES) {
                throw new Zne1Exception(TAMPERED, "The encrypted data was changed or is incomplete.");
            }
            if (last && size == TAG_BYTES && index > 0) {
                // Authentication alone would accept an empty last chunk sealed
                // after a full one; the format never writes one, so its length
                // is refused here.
                throw new Zne1Exception(MALFORMED, "A sealed ZNE1 chunk of " + size + " bytes is not allowed here.");
            }
            int count = crypt(cipher, Cipher.DECRYPT_MODE, chunk, size, opened, index, last);
            digest.update(opened, 0, count);
            plaintext.write(opened, 0, count);
            length += count;
            if (last) break;
            byte[] done = chunk;
            chunk = following;
            following = done;
            size = next;
        }
        plaintext.flush();
        return new Opened(length, CloudFileStream.hex(digest.digest()));
    }

    /** One chunk under a fresh init: every chunk has its own nonce, and all share the AAD. */
    private int crypt(Cipher cipher, int mode, byte[] input, int length, byte[] output, long index, boolean last)
        throws IOException {
        try {
            cipher.init(mode, key, new GCMParameterSpec(TAG_BYTES * 8, nonce(index, last)));
            cipher.updateAAD(aad);
            return cipher.doFinal(input, 0, length, output, 0);
        } catch (BadPaddingException error) {
            // A failed tag is an AEADBadTagException; catching its parent class
            // covers providers that throw the parent directly.
            throw new Zne1Exception(TAMPERED, "The encrypted data was changed or is incomplete.");
        } catch (GeneralSecurityException error) {
            throw new IOException("AES-GCM failed.", error);
        }
    }

    /** The chunk index as an 11-byte big-endian integer, then 0x01 for the last chunk or 0x00. */
    private static byte[] nonce(long index, boolean last) {
        byte[] nonce = new byte[12];
        for (int position = 10; position >= 0; position--) {
            nonce[position] = (byte) index;
            index >>>= 8;
        }
        nonce[11] = last ? (byte) 1 : (byte) 0;
        return nonce;
    }

    private static Cipher newCipher() throws IOException {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException error) {
            throw new IOException("AES-GCM is unavailable.", error);
        }
    }

    /** Reads until the buffer is full or the input ends; a stream may return any amount per read. */
    private static int fill(InputStream input, byte[] buffer) throws IOException {
        int filled = 0;
        while (filled < buffer.length) {
            int count = input.read(buffer, filled, buffer.length - filled);
            if (count == -1) break;
            filled += count;
        }
        return filled;
    }
}
