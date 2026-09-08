package net.lovelace.vesuvio.check.selflearning;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure Java Online Machine Learning Classifier (Logistic Regression via SGD).
 * Learns continuously on the server without native libraries or external dependencies.
 *
 * Author: Lovelace
 */
public final class OnlineClassifier {

    private final int featureCount;
    private final double[] weights;
    private double bias = -2.0;

    private double learningRate = 0.05;
    private double l2Regularization = 0.001;

    private final AtomicLong trainedSamples = new AtomicLong(0);

    public OnlineClassifier(int featureCount) {
        this.featureCount = featureCount;
        this.weights = new double[featureCount];
        // Initialize weights with domain priors (positive for low variance, high dup ratio, high CPS)
        if (featureCount >= 16) {
            weights[0] = -0.05; // mean delay (shorter = more suspect)
            weights[1] = -0.40; // std dev (lower = more suspect)
            weights[4] = 2.50;  // duplicate ratio
            weights[5] = -0.80; // entropy (lower = more suspect)
            weights[7] = 2.20;  // consecutive identical
            weights[14] = 0.35; // CPS (higher = more suspect)
        }
    }

    /**
     * Calculates probability that features represent an unfair advantage.
     *
     * @return Probability between 0.0 and 1.0
     */
    public synchronized double predict(float[] features) {
        if (features == null || features.length < featureCount) return 0.0;

        double z = bias;
        for (int i = 0; i < featureCount; i++) {
            z += weights[i] * features[i];
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

        double prediction = predict(features);
        double error = (double) label - prediction; // gradient of cross-entropy

        // Update bias
        bias += learningRate * error;

        // Update weights with L2 weight decay
        for (int i = 0; i < featureCount; i++) {
            double grad = error * features[i] - l2Regularization * weights[i];
            weights[i] += learningRate * grad;
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
}
