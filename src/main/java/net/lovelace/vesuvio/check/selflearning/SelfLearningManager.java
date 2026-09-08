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
    // Separate classifier for aim features - different feature semantics (AimFeatureExtractor
    // layout) and different priors (OnlineClassifier.AIM_PRIORS) than the click classifier, so
    // they must not share weights.
    private final OnlineClassifier aimClassifier = new OnlineClassifier(16, OnlineClassifier.AIM_PRIORS, -2.0);
    private final DatasetManager datasetManager;
    private final AutoDatasetCollector autoDatasetCollector;

    public SelfLearningManager(Path pluginFolder, ConfigManager config) {
        this.datasetManager = new DatasetManager(pluginFolder.resolve("datasets"));
        this.datasetManager.setMaxSize(config.getMaxDatasetSize());
        this.autoDatasetCollector = new AutoDatasetCollector(config, datasetManager, onlineClassifier, aimClassifier);
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

    public OnlineClassifier getAimClassifier() {
        return aimClassifier;
    }

    public DatasetManager getDatasetManager() {
        return datasetManager;
    }

    /** Flushes and closes the persistent dataset writer. Call from plugin onDisable. */
    public void close() {
        datasetManager.close();
    }
}
