package md.zennotes;

import static org.junit.Assert.*;

import org.junit.Test;

public class SafFsPathTest {
    @Test
    public void keepsNamesWithDotsInsideThem() {
        String note = "quick/This is a note for capturing something that comes up... a brain dump..md";
        assertEquals(note, SafFsPlugin.clean(note));
        String sidecar = ".zennotes/note-metadata/" + note + ".metadata.json";
        assertEquals(sidecar, SafFsPlugin.clean(sidecar));
        assertEquals("inbox/..hidden/v2..final.md", SafFsPlugin.clean("inbox/..hidden/v2..final.md"));
        assertEquals("inbox/...", SafFsPlugin.clean("/inbox/.../"));
    }

    @Test
    public void refusesASegmentThatWalksOut() {
        for (String path : new String[] { "..", "../outside.md", "inbox/../../outside.md", "inbox/..", "/../x" }) {
            try {
                SafFsPlugin.clean(path);
                fail("Expected an escape to be refused: " + path);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().startsWith("Path escapes the vault"));
            }
        }
    }

    @Test
    public void treatsAMissingPathAsTheRoot() {
        assertEquals("", SafFsPlugin.clean(null));
        assertEquals("", SafFsPlugin.clean("/"));
    }
}
