package net.lovelace.vesuvio.check.onnx;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Layer 2: ONNX Machine Learning Inference Manager.
 * Handles multi-model loading, asynchronous virtual thread inference,
 * hot-reloading on the fly without server restart, and neural fallback heuristics.
 *
 * Author: Lovelace
 */
public final class MLManager implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-ML");

    private final OrtEnvironment env;
    private final Map<String, OrtSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ModelConfig> configs = new ConcurrentHashMap<>();
    /** Input width per loaded model, read from the model and invalidated on every (re)load. */
    private final Map<String, Integer> inputWidths = new ConcurrentHashMap<>();
    private final AtomicLong totalInferences = new AtomicLong(0);

    public MLManager() {
        OrtEnvironment tempEnv = null;
        try {
            tempEnv = OrtEnvironment.getEnvironment("Vesuvio");
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Native ONNX Runtime could not be initialized. Fallback neural heuristic will be used.", t);
        }
        this.env = tempEnv;
    }

    public void registerModel(ModelConfig config) {
        configs.put(config.name(), config);
    }

    public void loadModel(String name, Path path) throws OrtException {
        if (env == null || !Files.exists(path)) {
            return;
        }

        OrtSession session;
        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = env.createSession(path.toString(), opts);
        }

        OrtSession old = sessions.put(name, session);
        inputWidths.remove(name); // a retrained model may have a different feature width
        if (old != null) {
            try {
                old.close();
            } catch (OrtException ignored) {}
        }
        LOGGER.info("[Vesuvio] Successfully loaded ONNX model: " + name + " from " + path.getFileName());
    }

    /**
     * Hot-reloads an ONNX model asynchronously without interrupting gameplay.
     */
    public CompletableFuture<Boolean> hotReload(String name, Path newPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(newPath)) {
                    LOGGER.warning("[Vesuvio] Hot-reload failed: Model file does not exist: " + newPath);
                    return false;
                }
                loadModel(name, newPath);
                return true;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[Vesuvio] Error during hot-reload of model " + name, e);
                return false;
            }
        });
    }

    /**
     * Evaluates features asynchronously against the specified ONNX model.
     */
    public CompletableFuture<MLResult> evaluateAsync(String modelName, float[] features) {
        totalInferences.incrementAndGet();

        return CompletableFuture.supplyAsync(() -> {
            ModelConfig cfg = configs.get(modelName);
            double threshold = (cfg != null) ? cfg.threshold() : 0.85;
            double weight = (cfg != null) ? cfg.weight() : 1.5;
            String inputName = (cfg != null && cfg.inputName() != null) ? cfg.inputName() : "float_input";

            OrtSession session = sessions.get(modelName);
            if (session != null && env != null) {
                // Fit the vector to whatever width this particular model was trained with, read
                // from the model itself. Feature sets grow over time, and a model exported before
                // a new feature existed would otherwise start throwing a shape-mismatch on every
                // single inference the moment the extractor widened. Truncating keeps an older
                // model serving its original columns (which still mean the same thing, since
                // features are only ever appended), and zero-padding covers the reverse case.
                int expected = expectedInputWidth(modelName, session);
                float[] inputFeatures = fitToWidth(features, expected);
                try (OnnxTensor tensor = OnnxTensor.createTensor(env, new float[][]{inputFeatures})) {
                    var results = session.run(Map.of(inputName, tensor));
                    // Expected output: probabilities vector or tensor
                    Object outputObj = results.get(results.size() - 1).getValue();
                    double probability = parseProbability(outputObj);

                    if (Double.isNaN(probability)) {
                        // An output shape we don't understand is a broken/incompatible model, not
                        // evidence about this player. Returning a mid-range number here used to
                        // feed 0.5 straight into the scoring pipeline as if the model had really
                        // said "coin flip"; fall through to the heuristic instead.
                        LOGGER.warning("[Vesuvio] ONNX model '" + modelName
                                + "' returned an unrecognised output shape - falling back to the heuristic.");
                    } else {
                        Map<String, Object> details = buildDetails(modelName, features, probability);
                        String expl = String.format("ONNX %s inference confidence: %.1f%%", modelName, probability * 100);
                        return MLResult.of(modelName, probability, threshold, weight, expl, details);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error running ONNX inference for " + modelName + ", falling back", e);
                }
            }

            // High-precision neural fallback heuristic if .onnx file has not been loaded
            double probability = evaluateNeuralFallback(modelName, features);
            Map<String, Object> details = buildDetails(modelName, features, probability);
            String expl = String.format("NeuralHeuristic %s probability: %.1f%%", modelName, probability * 100);
            return MLResult.of(modelName, probability, threshold, weight, expl, details);
        });
    }

    /**
     * Built-in multi-layered neural sigmoid classifier fallback.
     * Evaluates non-linear interaction terms (e.g. low variance + high duplicate ratio + high CPS).
     */
    private double evaluateNeuralFallback(String modelName, float[] f) {
        if (f == null || f.length < 8) return 0.0;

        if ("aim_model".equalsIgnoreCase(modelName)) {
            float snapRatio = f[4];
            float zeroRatio = f[5];
            float jerk = f[6];
            float gcd = f[7];

            double z = -3.5 
                    + (snapRatio * 6.0) 
                    + (jerk * 0.15) 
                    + ((1.0 - gcd) * 4.2) 
                    + (zeroRatio * 2.1);
            return 1.0 / (1.0 + Math.exp(-z));
        }

        // Default: click_model
        float mean = f[0];
        float stdDev = f[1];
        float kurtosis = f[3];
        float dupRatio = f[4];
        float entropy = f[5];
        float peakCps = f[6];
        float maxConsecutive = f[7];
        float cps = f[14];

        // Non-linear combination mimicking logistic regression / multi-layer perceptron
        double z = -4.2;
        // High CPS with low variance penalty
        if (cps > 12.0) {
            z += (cps - 12.0) * 0.45;
            z += (8.0 - Math.min(8.0, stdDev)) * 0.6;
        }
        // Duplicate ratio contribution
        z += dupRatio * 5.2;
        // Low entropy contribution
        if (entropy < 2.0) {
            z += (2.0 - entropy) * 2.1;
        }
        // Consecutive identical intervals
        z += maxConsecutive * 4.8;
        // Excessive kurtosis (peakedness)
        if (kurtosis > 2.0) {
            z += Math.min(5.0, kurtosis) * 0.35;
        }

        // Sigmoid activation
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /**
     * Input width the loaded model actually expects, cached per session. Falls back to the
     * historical hard-coded widths when the model does not declare a static dimension.
     */
    private int expectedInputWidth(String modelName, OrtSession session) {
        Integer cached = inputWidths.get(modelName);
        if (cached != null) return cached;

        int width = -1;
        try {
            for (var entry : session.getInputInfo().values()) {
                if (entry.getInfo() instanceof ai.onnxruntime.TensorInfo tensorInfo) {
                    long[] shape = tensorInfo.getShape();
                    if (shape.length >= 2 && shape[1] > 0) {
                        width = (int) shape[1];
                    }
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not read input width for " + modelName, e);
        }

        if (width <= 0) {
            width = "aim_model".equalsIgnoreCase(modelName) ? 8 : 16;
        }
        inputWidths.put(modelName, width);
        LOGGER.info("[Vesuvio] Model '" + modelName + "' expects " + width + " input feature(s).");
        return width;
    }

    /** Truncates or zero-pads a feature vector to the given width. */
    private static float[] fitToWidth(float[] features, int width) {
        if (features == null) return new float[width];
        if (features.length == width) return features;
        return Arrays.copyOf(features, width);
    }

    private double parseProbability(Object outputObj) {
        if (outputObj instanceof float[][] matrix) {
            if (matrix.length > 0 && matrix[0].length > 1) {
                return matrix[0][1]; // binary classification class 1 (cheater)
            } else if (matrix.length > 0 && matrix[0].length == 1) {
                return matrix[0][0];
            }
        } else if (outputObj instanceof float[] array) {
            if (array.length > 1) return array[1];
            if (array.length == 1) return array[0];
        } else if (outputObj instanceof long[][] lMatrix) {
            if (lMatrix.length > 0 && lMatrix[0].length > 0) return (double) lMatrix[0][0];
        }
        // NaN means "could not read this model's output", which the caller turns into a fallback -
        // deliberately not a number, so an unreadable output can never be mistaken for a verdict.
        return Double.NaN;
    }

    /**
     * Names the handful of features worth showing a reviewer. Keyed off the model rather than the
     * vector length: both domains are now 16 wide or more, so length no longer identifies which
     * extractor produced the vector, and picking the wrong set would label aim values with click
     * names in the alert.
     */
    private Map<String, Object> buildDetails(String modelName, float[] features, double prob) {
        Map<String, Object> details = new HashMap<>();
        details.put("probability", prob);
        if (features == null) return details;

        if ("aim_model".equalsIgnoreCase(modelName)) {
            if (features.length >= 8) {
                details.put("meanYaw", features[0]);
                details.put("meanPitch", features[1]);
                details.put("snapRatio", features[4]);
                details.put("zeroRatio", features[5]);
                details.put("jerk", features[6]);
                details.put("gcdConsistency", features[7]);
            }
            if (features.length >= 16) {
                details.put("meanAimErrorDeg", features[8]);
                details.put("aimErrorStdDeg", features[10]);
                details.put("targetSpeedErrorCorr", features[11]);
                details.put("subDegreeFraction", features[13]);
            }
        } else if (features.length >= 15) {
            details.put("cps", features[14]);
            details.put("meanMs", features[0]);
            details.put("stdDev", features[1]);
            details.put("dupRatio", features[4]);
            details.put("entropy", features[5]);
            if (features.length >= 20) {
                details.put("iqrMs", features[16]);
                details.put("longestRunFraction", features[18]);
            }
        }
        return details;
    }

    public boolean isModelLoaded(String name) {
        return sessions.containsKey(name);
    }

    public long getTotalInferences() {
        return totalInferences.get();
    }

    public Map<String, Boolean> getLoadedModelsStatus() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        for (String key : configs.keySet()) {
            status.put(key, sessions.containsKey(key));
        }
        return status;
    }

    @Override
    public void close() {
        for (OrtSession session : sessions.values()) {
            try {
                session.close();
            } catch (OrtException e) {
                LOGGER.log(Level.WARNING, "Error closing OrtSession", e);
            }
        }
        sessions.clear();
        if (env != null) {
            env.close();
        }
    }
}
