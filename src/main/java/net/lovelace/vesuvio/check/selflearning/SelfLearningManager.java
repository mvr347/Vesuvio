package net.lovelace.vesuvio.check.selflearning;

import java.nio.file.Path;

/**
 * Layer 3: Coordinates Active Learning, Anomaly Memory, Online Classifier,
 * and Persistent Dataset storage.
 *
 * Author: Lovelace
 */
public final class SelfLearningManager {

    private final AnomalyMemory anomalyMemory = new AnomalyMemory();
    private final ActiveLearning activeLearning = new ActiveLearning();
    private final OnlineClassifier onlineClassifier = new OnlineClassifier(16);
    private final DatasetManager datasetManager;

    public SelfLearningManager(Path pluginFolder) {
        this.datasetManager = new DatasetManager(pluginFolder.resolve("datasets"));
    }

    public AnomalyMemory getAnomalyMemory() {
        return anomalyMemory;
    }

    public ActiveLearning getActiveLearning() {
        return activeLearning;
    }

    public OnlineClassifier getOnlineClassifier() {
        return onlineClassifier;
    }

    public DatasetManager getDatasetManager() {
        return datasetManager;
    }
}
