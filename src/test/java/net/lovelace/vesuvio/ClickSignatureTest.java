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
}
