package net.lovelace.vesuvio.check.onnx;

/**
 * Configuration options for an individual ONNX neural model.
 *
 * Author: Lovelace
 */
public record ModelConfig(
        String name,
        boolean enabled,
        String filePath,
        double threshold,
        double weight,
        String inputName
) {}
