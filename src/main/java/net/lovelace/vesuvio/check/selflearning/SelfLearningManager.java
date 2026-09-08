package net.lovelace.vesuvio.check.selflearning;

import net.lovelace.vesuvio.config.ConfigManager;

import java.nio.file.Path;

/**
 * Layer 3: Coordinates Active Learning, Anomaly Memory, Online Classifier,
 * Automatic Dataset Collection, and Persistent Dataset storage.
 *
 * Author: Lovelace
 */
public final class SelfLearningManager {

    private final AnomalyMemory anomalyMemory = new AnomalyMemory();
    private final ActiveLearning activeLearning = new ActiveLearning();
    private final OnlineClassifier onlineClassifier = new OnlineClassifier(16);
    private final DatasetManager datasetManager;
    private final AutoDatasetCollector autoDatasetCollector;

    public SelfLearningManager(Path pluginFolder, ConfigManager config) {
        this.datasetManager = new DatasetManager(pluginFolder.resolve("datasets"));
        this.datasetManager.setMaxSize(config.getMaxDatasetSize());
        this.autoDatasetCollector = new AutoDatasetCollector(config, datasetManager, onlineClassifier);
    }

    public AutoDatasetCollector getAutoDatasetCollector() {
        return autoDatasetCollector;
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
