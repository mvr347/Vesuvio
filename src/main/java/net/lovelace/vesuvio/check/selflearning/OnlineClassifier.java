package net.lovelace.vesuvio.check.selflearning;

import net.lovelace.vesuvio.feature.FeatureNormalizer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure Java Online Machine Learning Classifier (Logistic Regression via SGD).
 * Learns continuously on the server without native libraries or external dependencies.
 *
 * <p>Internally normalizes features via {@link FeatureNormalizer} before scoring/training. See
 * that class's javadoc for why this is an exact reparameterization of the model rather than a
 * behavioural change - the very first prediction after construction is numerically identical to
 * the un-normalized model; only the conditioning of subsequent SGD updates improves.
 *
 * Author: Lovelace
 */
public final class OnlineClassifier {

    private final int featureCount;
    private final double[] weights;
    private double bias;

    private double learningRate = 0.05;
    private double l2Regularization = 0.001;
    /**
     * Learning-rate decay: effective rate = learningRate / (1 + trainedSamples * lrDecayFactor).
     * A mature model (thousands of confirmed samples) should nudge its weights more gently than a
     * fresh one still finding its footing - this keeps early convergence fast (matches the existing
     * ~25-iteration convergence tests) while damping oscillation once the model has seen a lot of
     * data, which is the "slightly more aggressive schedule / forgetting" behaviour asked for
     * without needing a fixed-window sample buffer.
     */
    private double lrDecayFactor = 0.0006;

    private final FeatureNormalizer.Domain domain;
    private final AtomicLong trainedSamples = new AtomicLong(0);

    /**
     * Click-domain classifier (default priors match ClickFeatureExtractor's 16-feature layout).
     * Kept for backward compatibility - equivalent to {@code OnlineClassifier(featureCount, CLICK_PRIORS, -2.0)}.
     */
    public OnlineClassifier(int featureCount) {
        this(featureCount, featureCount >= 16 ? CLICK_PRIORS : null, -2.0);
    }

    /**
     * Domain-agnostic constructor: pass a feature-count-sized prior weight array tailored to the
     * feature extractor this instance will score (e.g. AimFeatureExtractor's layout instead of
     * ClickFeatureExtractor's). Priors are just a warm start - SGD training overwrites them.
     * Assumes the click feature layout for normalization purposes unless {@code priorWeights} is
     * exactly {@link #AIM_PRIORS} (see the 3-arg overload below to be explicit instead).
     *
     * @param priorWeights initial weights, or null to start from all-zero weights
     * @param priorBias    initial bias (sigmoid intercept)
     */
    public OnlineClassifier(int featureCount, double[] priorWeights, double priorBias) {
        this(featureCount, priorWeights, priorBias, priorWeights == AIM_PRIORS ? FeatureNormalizer.Domain.AIM : FeatureNormalizer.Domain.CLICK);
    }

    public OnlineClassifier(int featureCount, double[] priorWeights, double priorBias, FeatureNormalizer.Domain domain) {
        this.featureCount = featureCount;
        this.domain = domain;
        double[] rawWeights = new double[featureCount];
        if (priorWeights != null) {
            System.arraycopy(priorWeights, 0, rawWeights, 0, Math.min(priorWeights.length, featureCount));
        }

        // Reparameterize the raw-scale priors into normalized-feature space so this model's first
        // prediction is identical to what it would have been without normalization (see class
        // javadoc / FeatureNormalizer javadoc for the derivation).
        float[] center = FeatureNormalizer.center(domain);
        float[] scale = FeatureNormalizer.scale(domain);
        this.weights = new double[featureCount];
        double biasAdjustment = 0.0;
        for (int i = 0; i < featureCount; i++) {
            double s = i < scale.length ? scale[i] : 1.0;
            double c = i < center.length ? center[i] : 0.0;
            if (s == 0.0) s = 1.0;
            this.weights[i] = rawWeights[i] * s;
            biasAdjustment += rawWeights[i] * c;
        }
        this.bias = priorBias + biasAdjustment;
    }

    // Domain priors for ClickFeatureExtractor's 16-feature layout (positive = more suspect):
    // low variance, high duplicate ratio, low entropy, many consecutive identical, high CPS.
    private static final double[] CLICK_PRIORS = {
            -0.05, -0.40, 0.0, 0.0, 2.50, -0.80, 0.0, 2.20, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.35, 0.0
    };

    // Domain priors for AimFeatureExtractor's layout (indices 0-7 populated; 8-15 unused/zero):
    // meanYaw, meanPitch, varYaw, varPitch, snapRatio, zeroRatio, jerk, gcdConsistency.
    // High snapRatio/zeroRatio (snap-then-freeze) and low gcdConsistency (smooth trig aim,
    // fails vanilla mouse quantization) push toward "suspect".
    public static final double[] AIM_PRIORS = {
            0.0, 0.0, 0.0, 0.0, 1.80, 1.20, 0.0, -1.00, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
    };

    /**
     * Calculates probability that features represent an unfair advantage.
     *
     * @return Probability between 0.0 and 1.0
     */
    public synchronized double predict(float[] features) {
        if (features == null || features.length < featureCount) return 0.0;
        float[] normalized = FeatureNormalizer.normalize(features, domain);

        double z = bias;
        for (int i = 0; i < featureCount; i++) {
            z += weights[i] * normalized[i];
        }

        // Numerical stability check for sigmoid
        if (z < -30.0) return 0.0;
        if (z > 30.0) return 1.0;

        return 1.0 / (1.0 + Math.exp(-z));
    }

    /**
     * Updates model weights using a single labeled sample (online SGD).
     *
     * @param features Normalized feature array
     * @param label    1 for cheat, 0 for legitimate
     */
    public synchronized void train(float[] features, int label) {
        if (features == null || features.length < featureCount) return;
        float[] normalized = FeatureNormalizer.normalize(features, domain);

        double prediction = predict(features);
        double error = (double) label - prediction; // gradient of cross-entropy

        double effectiveLearningRate = learningRate / (1.0 + trainedSamples.get() * lrDecayFactor);

        // Update bias
        bias += effectiveLearningRate * error;

        // Update weights with L2 weight decay
        for (int i = 0; i < featureCount; i++) {
            double grad = error * normalized[i] - l2Regularization * weights[i];
            weights[i] += effectiveLearningRate * grad;
        }

        trainedSamples.incrementAndGet();
    }

    public synchronized double[] getWeights() {
        return weights.clone();
    }

    public synchronized double getBias() {
        return bias;
    }

    public long getTrainedSamplesCount() {
        return trainedSamples.get();
    }

    public void setLearningRate(double learningRate) {
        this.learningRate = learningRate;
    }

    public void setL2Regularization(double l2Regularization) {
        this.l2Regularization = l2Regularization;
    }

    public void setLrDecayFactor(double lrDecayFactor) {
        this.lrDecayFactor = Math.max(0.0, lrDecayFactor);
    }
}
