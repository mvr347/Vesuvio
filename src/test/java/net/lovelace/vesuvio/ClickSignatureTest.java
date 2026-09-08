package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.ClickSignature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ClickSignatureTest {

    @Test
    public void testIdenticalSignatureSimilarity() {
        long[] sigA = new long[]{0x123456789ABCDEF0L, 100L, 200L, 300L, 400L, 500L, 600L, 700L};
        long[] sigB = sigA.clone();

        float ham = ClickSignature.hammingSimilarity(sigA, sigB);
        assertEquals(1.0f, ham, 0.0001f);

        double cos = ClickSignature.cosineSimilarity(sigA, sigB);
        assertEquals(1.0, cos, 0.0001);
    }

    @Test
    public void testDissimilarSignatures() {
        long[] sigA = new long[]{0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L};
        long[] sigB = new long[]{-1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L}; // all 1s in bits

        float ham = ClickSignature.hammingSimilarity(sigA, sigB);
        assertEquals(0.0f, ham, 0.0001f);
    }

    @Test
    public void testHexRoundTrip() {
        // Used by BanEvasionManager to persist/reload a click signature from the database.
        long[] original = new long[]{0x123456789ABCDEF0L, -1L, 0L, 42L, Long.MIN_VALUE, Long.MAX_VALUE, 777L, -777L};

        String hex = ClickSignature.toHexString(original);
        assertEquals(128, hex.length());

        long[] roundTripped = ClickSignature.fromHexString(hex);
        assertArrayEquals(original, roundTripped);

        // Same signature -> perfect similarity after round-tripping through hex
        assertEquals(1.0f, ClickSignature.hammingSimilarity(original, roundTripped), 0.0001f);
    }

    @Test
    public void testFromHexStringRejectsMalformedInput() {
        assertNull(ClickSignature.fromHexString(null));
        assertNull(ClickSignature.fromHexString(""));
        assertNull(ClickSignature.fromHexString("not-hex-and-wrong-length"));
    }
}
