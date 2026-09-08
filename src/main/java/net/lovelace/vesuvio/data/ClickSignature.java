package net.lovelace.vesuvio.data;

/**
 * Click signature fingerprinting utilities.
 * Handles vector comparison (Hamming distance, Cosine similarity)
 * across 64-byte (8-long) biometric click signatures.
 *
 * Author: Lovelace
 */
public final class ClickSignature {

    private ClickSignature() {}

    /**
     * Calculates bitwise normalized Hamming similarity in range [0.0, 1.0].
     * 1.0 means identical bit patterns, 0.0 means completely opposite.
     */
    public static float hammingSimilarity(long[] sigA, long[] sigB) {
        if (sigA == null || sigB == null || sigA.length != 8 || sigB.length != 8) {
            return 0f;
        }

        int totalBits = 8 * 64; // 512 bits
        int matchingBits = 0;

        for (int i = 0; i < 8; i++) {
            long xor = sigA[i] ^ sigB[i];
            int diffBits = Long.bitCount(xor);
            matchingBits += (64 - diffBits);
        }

        return (float) matchingBits / totalBits;
    }

    /**
     * Calculates cosine similarity of the numeric signature vector in range [0.0, 1.0].
     */
    public static double cosineSimilarity(long[] sigA, long[] sigB) {
        if (sigA == null || sigB == null || sigA.length != 8 || sigB.length != 8) {
            return 0.0;
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < 8; i++) {
            double a = (double) sigA[i];
            double b = (double) sigB[i];
            dot += a * b;
            normA += a * a;
            normB += b * b;
        }

        if (normA <= 1e-9 || normB <= 1e-9) {
            return 0.0;
        }

        double sim = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return Math.max(0.0, Math.min(1.0, (sim + 1.0) / 2.0));
    }

    /**
     * Converts an 8-long signature into a human-readable hex fingerprint string.
     */
    public static String toHexString(long[] sig) {
        if (sig == null) return "null";
        StringBuilder sb = new StringBuilder(64);
        for (long l : sig) {
            sb.append(String.format("%016x", l));
        }
        return sb.toString();
    }
}
