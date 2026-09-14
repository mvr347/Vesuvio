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

    /**
     * Compares two sets of raw click intervals (nanoseconds) by quantile shape rather than the
     * compact rolling signature above. Ghost/macro clients frequently inject artificial long
     * pauses specifically to defeat mean/std-based comparisons while their tight inner clicking
     * rhythm stays identical - the median and interquartile spread are far more resistant to a few
     * injected outliers than a mean ever is, which is exactly the property {@code calculateIQR} in
     * {@code ClickFeatureExtractor} already relies on for a single window; this extends the same
     * idea to a two-window (e.g. early-vs-late combat) comparison.
     *
     * @return similarity in [0.0, 1.0], or 0.0 if either window has too few samples to be meaningful
     */
    public static double quantileSimilarity(long[] intervalsA, int countA, long[] intervalsB, int countB) {
        if (countA < 8 || countB < 8) return 0.0;

        double[] qa = quantilesMs(intervalsA, countA);
        double[] qb = quantilesMs(intervalsB, countB);

        double diffSum = 0.0;
        double scaleSum = 0.0;
        for (int i = 0; i < qa.length; i++) {
            diffSum += Math.abs(qa[i] - qb[i]);
            scaleSum += Math.max(qa[i], qb[i]);
        }
        if (scaleSum < 1e-6) return 1.0; // both windows are ~0ms apart everywhere - identical
        double relativeDiff = diffSum / scaleSum;
        return Math.max(0.0, 1.0 - relativeDiff);
    }

    /** p10/p25/median/p75/p90 of the given interval window, in milliseconds. */
    private static double[] quantilesMs(long[] intervals, int count) {
        long[] copy = new long[count];
        System.arraycopy(intervals, 0, copy, 0, count);
        java.util.Arrays.sort(copy);
        double[] out = new double[5];
        double[] fractions = {0.10, 0.25, 0.50, 0.75, 0.90};
        for (int i = 0; i < fractions.length; i++) {
            int idx = (int) Math.min(count - 1, Math.round(fractions[i] * (count - 1)));
            out[i] = copy[idx] / 1_000_000.0;
        }
        return out;
    }

    /**
     * Longest run of consecutive intervals within {@code toleranceMs} of one another - a robotic
     * click source (autoclicker, macro) tends to produce one dominant run far longer than a human's
     * naturally drifting rhythm ever sustains, independent of the mean/variance-based checks
     * elsewhere.
     */
    public static int longestRunMs(long[] intervals, int count, double toleranceMs) {
        if (count < 2) return count;
        long toleranceNanos = (long) (toleranceMs * 1_000_000.0);
        int longest = 1;
        int current = 1;
        for (int i = 1; i < count; i++) {
            if (Math.abs(intervals[i] - intervals[i - 1]) <= toleranceNanos) {
                current++;
                if (current > longest) longest = current;
            } else {
                current = 1;
            }
        }
        return longest;
    }

    /**
     * Parses a 128-hex-char string (produced by {@link #toHexString}) back into an 8-long
     * signature vector. Used to round-trip stored ban fingerprints from the database.
     *
     * @return the parsed signature, or null if the input isn't a well-formed 128-char hex string
     */
    public static long[] fromHexString(String hex) {
        if (hex == null || hex.length() != 128) return null;
        long[] sig = new long[8];
        try {
            for (int i = 0; i < 8; i++) {
                sig[i] = Long.parseUnsignedLong(hex.substring(i * 16, i * 16 + 16), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return sig;
    }
}
