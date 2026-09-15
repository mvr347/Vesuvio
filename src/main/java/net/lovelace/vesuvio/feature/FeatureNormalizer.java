package net.lovelace.vesuvio.feature;

/**
 * Fixed-range feature normalization ("RobustScaler"-style: {@code (x - center) / scale}) for the
 * click and aim feature layouts.
 *
 * <h2>Why fixed ranges instead of running statistics</h2>
 * A per-player or per-server running mean/std would itself be attacker-controlled (a cheat that
 * plays "normal" for a while first would slowly widen its own normalization window and blunt
 * detection against itself later) and would need extra synchronized state shared across the
 * virtual threads that call these extractors. The center/scale constants below are instead fixed,
 * hand-picked estimates of the typical human range for each feature (documented per-index), which
 * is the "fixed reasonable ranges" option - simpler, thread-safe with zero extra state, and not
 * something a client can influence by how it plays.
 *
 * <h2>Why {@link OnlineClassifier} behaviour does not change at the moment this lands</h2>
 * Normalizing a linear model's inputs is an exact reparameterization, not an approximation:
 * {@code bias + Σ w_i·x_i == (bias + Σ w_i·center_i) + Σ (w_i·scale_i)·((x_i-center_i)/scale_i)}.
 * {@link OnlineClassifier} applies exactly that transform to its existing prior weights/bias on
 * construction, so the very first prediction it makes is numerically identical to the un-normalized
 * model. The only thing that changes is what SGD does <em>after</em> that: gradient steps are no
 * longer dominated by whichever raw feature happens to have the largest magnitude (e.g. mean click
 * delay in milliseconds versus a 0..1 ratio), which is the actual point of normalizing before online
 * learning.
 *
 * Author: Lovelace
 */
public final class FeatureNormalizer {

    private FeatureNormalizer() {}

    public enum Domain { CLICK, AIM }

    // ClickFeatureExtractor's 16-feature layout: meanMs, stdMs, skewness, kurtosis, dupRatio,
    // entropy, peakCps, consecutiveRatio, lowDelayRatio, highDelayRatio, meanAccelMs,
    // microPauseRatio, autocorrelation, outlierRatio, avgCps, temporalDeltaMs.
    // Indices 16-19 are the outlier-resistant distribution shape added later: IQR (ms),
    // median/mean ratio, longest-run fraction, lag-2 autocorrelation.
    private static final float[] CLICK_CENTER = {
            120f, 25f, 0f, 0f, 0.08f, 3.0f, 9.0f, 0.05f, 0.05f, 0.05f, 15f, 0.15f, 0f, 0.03f, 7.5f, 0f,
            30f, 1.0f, 0.1f, 0f
    };
    private static final float[] CLICK_SCALE = {
            60f, 25f, 1f, 2f, 0.15f, 1.2f, 5.0f, 0.12f, 0.12f, 0.10f, 15f, 0.15f, 0.3f, 0.08f, 3.5f, 15f,
            25f, 0.2f, 0.15f, 0.3f
    };

    // AimFeatureExtractor only ever populates indices 0-7 (meanYaw, meanPitch, yawStd, pitchStd,
    // snapRatio, zeroRatio, jerk, gcdConsistency); 8-15 are always zero padding, kept neutral here.
    // Indices 8-15 are the target-relative features: mean |yaw error|, mean |pitch error|, error
    // std-dev, target-speed/error correlation, mean camera speed, sub-1° fraction, signed mean
    // yaw error, sample fill. They are zero when no tracking data exists.
    private static final float[] AIM_CENTER = {
            8f, 5f, 6f, 4f, 0.05f, 0.05f, 3f, 0.5f,
            3f, 2f, 2.5f, 0.3f, 3f, 0.35f, 0f, 0.5f
    };
    private static final float[] AIM_SCALE = {
            8f, 5f, 6f, 4f, 0.10f, 0.10f, 3f, 0.3f,
            3f, 2f, 2.5f, 0.4f, 3f, 0.3f, 2f, 0.4f
    };

    public static float[] center(Domain domain) {
        return domain == Domain.AIM ? AIM_CENTER : CLICK_CENTER;
    }

    public static float[] scale(Domain domain) {
        return domain == Domain.AIM ? AIM_SCALE : CLICK_SCALE;
    }

    /** Returns a new array; does not mutate {@code raw}. */
    public static float[] normalize(float[] raw, Domain domain) {
        float[] center = center(domain);
        float[] scale = scale(domain);
        int n = Math.min(raw.length, center.length);
        float[] out = new float[raw.length];
        for (int i = 0; i < n; i++) {
            float s = scale[i] == 0f ? 1f : scale[i];
            out[i] = (raw[i] - center[i]) / s;
        }
        for (int i = n; i < raw.length; i++) {
            out[i] = raw[i];
        }
        return out;
    }
}
