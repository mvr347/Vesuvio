package net.lovelace.vesuvio.check.onnx;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Live, no-consequence evaluation of a candidate model against the model currently in production.
 *
 * <p>Why: until now a retrained model had exactly two fates - it passed an offline quality gate and
 * went straight to production, or it failed and was thrown away. Both are guesses about live
 * behaviour made from a held-out slice of the server's own historical dataset. That dataset is
 * collected by the current pipeline, so it systematically under-represents whatever the current
 * pipeline is blind to; a model can look excellent on it and still flag three times as many people
 * the moment it meets real traffic.
 *
 * <p>Shadow mode closes that gap: the candidate runs on exactly the same feature vectors as the
 * live model, at the same moments, and its verdicts are recorded and never acted on. After a few
 * hours an operator can see what actually changes - how often the two disagree, whether the
 * candidate flags more or fewer players, and by how much its probabilities move - and promote it
 * on evidence rather than on a number from an offline split.
 *
 * <p>All counters are lock-free adders: this is written from ONNX completion callbacks on many
 * threads at once, and the numbers are diagnostic, so exact cross-counter consistency at any given
 * instant is not worth a lock.
 *
 * Author: Lovelace
 */
public final class ShadowEvaluator {

    /** Suffix that distinguishes a shadow session from its live counterpart in MLManager. */
    public static final String SHADOW_SUFFIX = "#shadow";

    public static final class Stats {
        private final AtomicLong samples = new AtomicLong();
        private final AtomicLong agreements = new AtomicLong();
        private final AtomicLong liveFlags = new AtomicLong();
        private final AtomicLong shadowFlags = new AtomicLong();
        private final AtomicLong shadowOnlyFlags = new AtomicLong();
        private final AtomicLong liveOnlyFlags = new AtomicLong();
        private final DoubleAdder absoluteDelta = new DoubleAdder();
        private final long startedAtMillis = System.currentTimeMillis();

        public long samples() { return samples.get(); }
        public long liveFlags() { return liveFlags.get(); }
        public long shadowFlags() { return shadowFlags.get(); }
        public long shadowOnlyFlags() { return shadowOnlyFlags.get(); }
        public long liveOnlyFlags() { return liveOnlyFlags.get(); }
        public long startedAtMillis() { return startedAtMillis; }

        public double agreementRate() {
            long n = samples.get();
            return n == 0 ? 0.0 : (double) agreements.get() / n;
        }

        public double meanAbsoluteDelta() {
            long n = samples.get();
            return n == 0 ? 0.0 : absoluteDelta.sum() / n;
        }

        /**
         * How the candidate's flag volume compares to the live model's: 1.0 means identical,
         * 2.0 means it would flag twice as often. This is the number that decides whether a
         * promotion is safe, because it translates directly into staff workload and false
         * positives.
         */
        public double flagRateRatio() {
            long live = liveFlags.get();
            if (live == 0) return shadowFlags.get() == 0 ? 1.0 : Double.POSITIVE_INFINITY;
            return (double) shadowFlags.get() / live;
        }
    }

    private final Map<String, Stats> statsByModel = new ConcurrentHashMap<>();

    /**
     * Records one paired verdict.
     *
     * @param liveThreshold   the threshold the live model is judged at
     * @param shadowThreshold the threshold the candidate would be judged at - usually the training
     *                        script's suggested operating point, which is exactly what needs
     *                        testing before it is written into config.yml
     */
    public void record(String modelName, double liveProbability, double shadowProbability,
                       double liveThreshold, double shadowThreshold) {
        if (Double.isNaN(liveProbability) || Double.isNaN(shadowProbability)) return;

        Stats stats = statsByModel.computeIfAbsent(modelName, k -> new Stats());
        boolean liveFlag = liveProbability >= liveThreshold;
        boolean shadowFlag = shadowProbability >= shadowThreshold;

        stats.samples.incrementAndGet();
        if (liveFlag == shadowFlag) stats.agreements.incrementAndGet();
        if (liveFlag) stats.liveFlags.incrementAndGet();
        if (shadowFlag) stats.shadowFlags.incrementAndGet();
        if (shadowFlag && !liveFlag) stats.shadowOnlyFlags.incrementAndGet();
        if (liveFlag && !shadowFlag) stats.liveOnlyFlags.incrementAndGet();
        stats.absoluteDelta.add(Math.abs(shadowProbability - liveProbability));
    }

    public Stats get(String modelName) {
        return statsByModel.get(modelName);
    }

    public Map<String, Stats> all() {
        return Map.copyOf(statsByModel);
    }

    /** Called when a candidate is promoted or discarded - its comparison no longer means anything. */
    public void clear(String modelName) {
        statsByModel.remove(modelName);
    }

    /** One human-readable line per shadowed model, for {@code /vesuvio model shadow}. */
    public String describe(String modelName) {
        Stats stats = statsByModel.get(modelName);
        if (stats == null || stats.samples() == 0) {
            return modelName + ": no paired inferences recorded yet";
        }
        long minutes = Math.max(1L, (System.currentTimeMillis() - stats.startedAtMillis()) / 60_000L);
        return String.format(Locale.US,
                "%s: %d paired inferences over %dm | agreement %.1f%% | live flags %d, candidate flags %d "
                        + "(ratio %.2fx) | candidate-only %d, live-only %d | mean |Δp| %.3f",
                modelName, stats.samples(), minutes, stats.agreementRate() * 100,
                stats.liveFlags(), stats.shadowFlags(), stats.flagRateRatio(),
                stats.shadowOnlyFlags(), stats.liveOnlyFlags(), stats.meanAbsoluteDelta());
    }
}
