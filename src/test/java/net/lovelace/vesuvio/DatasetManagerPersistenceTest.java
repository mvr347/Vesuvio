package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.selflearning.DatasetManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DatasetManager now persists every sample to disk as it arrives (not just on manual export),
 * so an external process (the auto-retrain Python script) can read live data, and a restart
 * never loses collected samples. This verifies both halves of that contract.
 */
public class DatasetManagerPersistenceTest {

    @Test
    public void testSamplesArePersistedAndReloadedAcrossInstances() throws IOException {
        Path tempDir = Files.createTempDirectory("vesuvio-dataset-test");

        float[] clickFeatures = new float[16];
        clickFeatures[0] = 50.0f;
        float[] aimFeatures = new float[16];
        aimFeatures[4] = 0.9f;

        try {
            DatasetManager first = new DatasetManager(tempDir);
            first.addSample(new DatasetManager.LabeledSample(
                    UUID.randomUUID(), "Cheater1", clickFeatures, 1, 1_700_000_000_000L, "AutoCollector(ban)", "click"));
            first.addSample(new DatasetManager.LabeledSample(
                    UUID.randomUUID(), "LegitPlayer", aimFeatures, 0, 1_700_000_001_000L, "AutoCollector(trusted)", "aim"));

            assertEquals(2, first.getDatasetSize());
            assertTrue(Files.exists(first.getAutoPersistFile()), "auto_dataset.csv should exist after addSample");
            first.close();

            // A brand-new instance pointed at the same directory must reload what was persisted.
            DatasetManager second = new DatasetManager(tempDir);
            assertEquals(2, second.getDatasetSize());
            assertEquals(1, second.countSamples("click", 1));
            assertEquals(1, second.countSamples("aim", 0));
            second.close();
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        }
    }
}
