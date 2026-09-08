package net.lovelace.vesuvio.check.onnx;

import java.util.Collections;
import java.util.Map;

/**
 * Encapsulates the inference outcome of an ONNX neural model.
 *
 * Author: Lovelace
 */
public record MLResult(
        double probability,
        boolean isFlag,
        double vl,
        String modelName,
        String explanation,
        Map<String, Object> details
) {
    public static MLResult empty(String modelName) {
        return new MLResult(0.0, false, 0.0, modelName, "No evaluation", Collections.emptyMap());
    }

    public static MLResult of(String modelName, double probability, double threshold, double weight, String explanation, Map<String, Object> details) {
        boolean flag = probability >= threshold;
        double vl = flag ? (probability * weight * 2.0) : 0.0;
        return new MLResult(probability, flag, vl, modelName, explanation, details != null ? details : Collections.emptyMap());
    }
}
