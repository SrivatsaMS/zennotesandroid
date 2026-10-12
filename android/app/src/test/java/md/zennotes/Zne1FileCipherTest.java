package md.zennotes;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Runs the shared ZNE1 vectors (copied from the desktop repo's docs/specs) through the native chunk loop. */
public class Zne1FileCipherTest {
    private static final int HEADER = Zne1FileCipher.HEADER_BYTES;
    private static final int CHUNK = Zne1FileCipher.CHUNK_BYTES;
    private static final int SEALED = Zne1FileCipher.SEALED_CHUNK_BYTES;
    private static final String MALFORMED = Zne1FileCipher.MALFORMED;
    private static final String TAMPERED = Zne1FileCipher.TAMPERED;

    @Test
    public void sealsEveryVectorToItsExactBytesAndOpensItAgain() throws Exception {
        Set<Integer> lengths = new HashSet<>();
        for (JSONObject vector : vectors()) {
            int length = vector.getInt("plaintext_length");
            lengths.add(length);
            String label = "purpose " + vector.getInt("purpose") + ", " + length + " bytes";
            byte[] plaintext = pattern(length);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Zne1FileCipher.Sealed sealed = cipher(vector).encrypt(trickle(plaintext, CHUNK), output);
            byte[] ciphertext = output.toByteArray();
            assertEquals(label, vector.getLong("ciphertext_length"), ciphertext.length);
            assertEquals(label, vector.getString("ciphertext_sha256"), hash(ciphertext));
            if (vector.has("ciphertext_hex")) assertEquals(label, vector.getString("ciphertext_hex"), hex(ciphertext));
            assertEquals(label, ciphertext.length, sealed.byteLength);
            assertEquals(label, hash(ciphertext), sealed.sha256);
            assertEquals(label, length, sealed.plaintextByteLength);
            assertEquals(label, hash(plaintext), sealed.plaintextSha256);

            output = new ByteArrayOutputStream();
            Zne1FileCipher.Opened opened = cipher(vector).decrypt(trickle(ciphertext, SEALED), output);
            assertArrayEquals(label, plaintext, output.toByteArray());
            assertEquals(label, length, opened.byteLength);
            assertEquals(label, hash(plaintext), opened.sha256);
        }
        assertTrue("The vectors cover the sizes the spec names",
            lengths.containsAll(Arrays.asList(0, 1, 65_535, 65_536, 65_537, 200_000, 256)));
    }

    @Test
    public void refusesAChangedByteInTheHeaderAnyChunkOrAnyTag() throws Exception {
        JSONObject vector = vector(200_000);
        byte[] sealed = seal(vector);
        for (int offset = 0; offset < HEADER; offset++) assertRefused(TAMPERED, vector, flip(sealed, offset), 0);
        for (int chunk = 0; HEADER + chunk * SEALED < sealed.length; chunk++) {
            int start = HEADER + chunk * SEALED;
            int end = Math.min(start + SEALED, sealed.length);
            assertRefused(TAMPERED, vector, flip(sealed, start), chunk * CHUNK);
            assertRefused(TAMPERED, vector, flip(sealed, end - 1), chunk * CHUNK);
        }
    }

    @Test
    public void refusesInputThatEndsEarlyOrIsShorterThanAHeader() throws Exception {
        JSONObject vector = vector(200_000);
        byte[] sealed = seal(vector);
        for (int chunks = 1; chunks <= 3; chunks++) {
            // Ends on a chunk boundary: the chunk that now ends the input was not sealed as the last one.
            assertRefused(TAMPERED, vector, Arrays.copyOf(sealed, HEADER + chunks * SEALED), (chunks - 1) * CHUNK);
        }
        assertRefused(TAMPERED, vector, Arrays.copyOf(sealed, sealed.length - 5), 3 * CHUNK);
        assertRefused(TAMPERED, vector, Arrays.copyOf(sealed, HEADER + SEALED + 3), CHUNK);
        assertRefused(TAMPERED, vector, Arrays.copyOf(sealed, HEADER), 0);
        assertRefused(MALFORMED, vector, Arrays.copyOf(sealed, 40), 0);
    }

    @Test
    public void refusesSwappedAndAppendedChunks() throws Exception {
        JSONObject vector = vector(200_000);
        byte[] sealed = seal(vector);
        byte[] swapped = sealed.clone();
        System.arraycopy(sealed, HEADER + SEALED, swapped, HEADER, SEALED);
        System.arraycopy(sealed, HEADER, swapped, HEADER + SEALED, SEALED);
        assertRefused(TAMPERED, vector, swapped, 0);
        assertRefused(TAMPERED, vector, concat(sealed, new byte[1]), 3 * CHUNK);
        assertRefused(TAMPERED, vector, concat(sealed, Arrays.copyOfRange(sealed, HEADER, HEADER + SEALED)), 3 * CHUNK);

        // One full chunk that is also the last: anything after it means it was not.
        JSONObject single = vector(CHUNK);
        byte[] whole = seal(single);
        assertRefused(TAMPERED, single, concat(whole, new byte[Zne1FileCipher.TAG_BYTES]), 0);
        assertRefused(TAMPERED, single, concat(whole, Arrays.copyOfRange(whole, HEADER, whole.length)), 0);
    }

    @Test
    public void refusesAnEmptyLastChunkAfterAFullOneByItsLength() throws Exception {
        // The format seals 65536 bytes as one final chunk. Sealed instead as a
        // full chunk plus an empty final chunk, every chunk still
        // authenticates, so only the length rule refuses the object.
        JSONObject vector = vector(CHUNK);
        byte[] header = unhex(vector.getString("header_hex"));
        byte[] key = unhex(vector.getString("object_key_hex"));
        byte[] aad = unhex(vector.getString("aad_hex"));
        byte[] plaintext = pattern(CHUNK);
        assertArrayEquals("The crafted chunks follow the spec", seal(vector), concat(header, gcm(key, aad, 0, true, plaintext)));
        byte[] notLast = gcm(key, aad, 0, false, plaintext);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        cipher(vector).decrypt(new ByteArrayInputStream(concat(header, notLast, gcm(key, aad, 1, true, new byte[] { 7 }))), output);
        assertEquals(CHUNK + 1, output.size());

        assertRefused(MALFORMED, vector, concat(header, notLast, gcm(key, aad, 1, true, new byte[0])), CHUNK);
    }

    @Test
    public void refusesAnotherObjectsHeaderAndTheWrongKeyOrContext() throws Exception {
        JSONObject content = vector(1);
        JSONObject metadata = vectorsFile().getJSONObject("metadata_object");
        byte[] sealed = seal(content);
        byte[] header = unhex(content.getString("header_hex"));
        byte[] key = unhex(content.getString("object_key_hex"));
        byte[] aad = unhex(content.getString("aad_hex"));
        assertRefused(TAMPERED, new Zne1FileCipher(unhex(metadata.getString("header_hex")), key, aad), sealed, 0);
        assertRefused(TAMPERED, new Zne1FileCipher(header, flip(key, 0), aad), sealed, 0);
        assertRefused(TAMPERED, new Zne1FileCipher(header, key, unhex(metadata.getString("aad_hex"))), sealed, 0);
        assertRefused(TAMPERED, new Zne1FileCipher(header, key, flip(aad, 31)), sealed, 0);
    }

    @Test
    public void refusesParametersThatDoNotDescribeAZne1Object() throws Exception {
        JSONObject vector = vector(0);
        byte[] header = unhex(vector.getString("header_hex"));
        byte[] key = unhex(vector.getString("object_key_hex"));
        byte[] aad = unhex(vector.getString("aad_hex"));
        byte[] nextAlgorithm = header.clone();
        nextAlgorithm[4] = 0x02;
        assertThrows(IllegalArgumentException.class, () -> new Zne1FileCipher(null, key, aad));
        assertThrows(IllegalArgumentException.class, () -> new Zne1FileCipher(Arrays.copyOf(header, HEADER - 1), key, aad));
        assertThrows(IllegalArgumentException.class, () -> new Zne1FileCipher(flip(header, 0), key, aad));
        assertThrows(IllegalArgumentException.class, () -> new Zne1FileCipher(nextAlgorithm, key, aad));
        assertThrows(IllegalArgumentException.class, () -> new Zne1FileCipher(header, Arrays.copyOf(key, 16), aad));
        assertThrows(IllegalArgumentException.class, () -> new Zne1FileCipher(header, key, Arrays.copyOf(aad, 31)));
    }

    /** Checks the refusal's code, and that only the chunks before the failure reached the output. */
    private static void assertRefused(String code, Zne1FileCipher cipher, byte[] data, int written) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Zne1FileCipher.Zne1Exception error = assertThrows(Zne1FileCipher.Zne1Exception.class,
            () -> cipher.decrypt(new ByteArrayInputStream(data), output));
        assertEquals(error.getMessage(), code, error.code);
        assertArrayEquals(pattern(written), output.toByteArray());
    }

    private static void assertRefused(String code, JSONObject vector, byte[] data, int written) throws Exception {
        assertRefused(code, cipher(vector), data, written);
    }

    /**
     * Serves at most 7919 bytes per read, so chunks straddle reads the way a
     * document provider delivers them, and fails any read larger than allowed.
     */
    private static InputStream trickle(byte[] bytes, int largestRead) {
        return new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] target, int offset, int length) {
                assertTrue("Native reads must stay bounded", length <= largestRead);
                return super.read(target, offset, Math.min(length, 7919));
            }
        };
    }

    private static JSONObject vectorsFile() throws Exception {
        try (InputStream input = Zne1FileCipherTest.class.getClassLoader().getResourceAsStream("cloud-e2ee-vectors.json")) {
            assertNotNull("The shared vectors belong in src/test/resources", input);
            return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Every content object, then the item-metadata object. */
    private static List<JSONObject> vectors() throws Exception {
        JSONObject file = vectorsFile();
        JSONArray objects = file.getJSONArray("objects");
        List<JSONObject> vectors = new ArrayList<>();
        for (int index = 0; index < objects.length(); index++) vectors.add(objects.getJSONObject(index));
        vectors.add(file.getJSONObject("metadata_object"));
        return vectors;
    }

    private static JSONObject vector(int plaintextLength) throws Exception {
        for (JSONObject vector : vectors()) {
            if (vector.getInt("purpose") == 1 && vector.getInt("plaintext_length") == plaintextLength) return vector;
        }
        throw new AssertionError("No content vector of " + plaintextLength + " bytes");
    }

    private static Zne1FileCipher cipher(JSONObject vector) throws Exception {
        return new Zne1FileCipher(unhex(vector.getString("header_hex")), unhex(vector.getString("object_key_hex")),
            unhex(vector.getString("aad_hex")));
    }

    private static byte[] seal(JSONObject vector) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        cipher(vector).encrypt(new ByteArrayInputStream(pattern(vector.getInt("plaintext_length"))), output);
        return output.toByteArray();
    }

    /** Plaintext bytes for the vectors: byte i is i mod 251. */
    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) bytes[index] = (byte) (index % 251);
        return bytes;
    }

    /** One chunk sealed by javax.crypto directly, with a nonce built here from the spec. */
    private static byte[] gcm(byte[] key, byte[] aad, int index, boolean last, byte[] chunk) throws Exception {
        byte[] nonce = new byte[12];
        for (int position = 10; position >= 7; position--) nonce[position] = (byte) (index >>> (8 * (10 - position)));
        nonce[11] = (byte) (last ? 1 : 0);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(chunk);
    }

    private static byte[] flip(byte[] bytes, int offset) {
        byte[] copy = bytes.clone();
        copy[offset] ^= 0x01;
        return copy;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : parts) joined.write(part, 0, part.length);
        return joined.toByteArray();
    }

    private static byte[] unhex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) hex.append(String.format("%02x", value));
        return hex.toString();
    }

    private static String hash(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
