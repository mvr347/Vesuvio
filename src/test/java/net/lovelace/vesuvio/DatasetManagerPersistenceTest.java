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

    @Test
    public void testLabelProvenanceSurvivesARoundTrip() throws IOException {
        Path tempDir = Files.createTempDirectory("vesuvio-dataset-source-test");
        float[] features = new float[16];
        features[0] = 42.0f;

        try {
            DatasetManager first = new DatasetManager(tempDir);
            first.addSample(new DatasetManager.LabeledSample(
                    UUID.randomUUID(), "Trapped", features, 1, 1_700_000_000_000L, "NpcTrap", "click",
                    DatasetManager.LabelSource.TRAP));
            first.addSample(new DatasetManager.LabeledSample(
                    UUID.randomUUID(), "Reviewed", features, 1, 1_700_000_001_000L, "Admin", "click",
                    DatasetManager.LabelSource.STAFF));
            first.addSample(new DatasetManager.LabeledSample(
                    UUID.randomUUID(), "Banned", features, 1, 1_700_000_002_000L, "AutoCollector(ban)", "click",
                    DatasetManager.LabelSource.BAN));
            first.close();

            DatasetManager second = new DatasetManager(tempDir);
            // Provenance decides whether a label may be trained on at all, so it has to survive
            // the CSV round trip intact - not collapse to a default.
            assertEquals(1, second.countSamples("click", 1, DatasetManager.LabelSource.TRAP));
            assertEquals(1, second.countSamples("click", 1, DatasetManager.LabelSource.STAFF));
            assertEquals(1, second.countSamples("click", 1, DatasetManager.LabelSource.BAN));
            second.close();
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testLegacyRowsWithoutASourceColumnAreRecovered() throws IOException {
        Path tempDir = Files.createTempDirectory("vesuvio-dataset-legacy-test");
        float[] features = new float[16];

        try {
            // Rows written before the source column existed must not be silently promoted to a
            // trusted provenance - the collectors' reviewer string is the only evidence available.
            DatasetManager first = new DatasetManager(tempDir);
            first.addSample(new DatasetManager.LabeledSample(
                    UUID.randomUUID(), "OldBan", features, 1, 1_700_000_000_000L, "AutoCollector(ban)", "click"));
            first.addSample(new DatasetManager.LabeledSample(
                    UUID.randomUUID(), "OldTrusted", features, 0, 1_700_000_001_000L, "AutoCollector(trusted)", "click"));
            first.close();

            // Strip the source column back off, imitating a pre-upgrade file.
            Path csv = tempDir.resolve("auto_dataset.csv");
            var stripped = new java.util.ArrayList<String>();
            for (String line : Files.readAllLines(csv)) {
                int lastComma = line.lastIndexOf(',');
                stripped.add(lastComma < 0 ? line : line.substring(0, lastComma));
            }
            Files.write(csv, stripped);

            DatasetManager second = new DatasetManager(tempDir);
            assertEquals(1, second.countSamples("click", 1, DatasetManager.LabelSource.BAN));
            assertEquals(1, second.countSamples("click", 0, DatasetManager.LabelSource.TRUSTED));
            assertEquals(0, second.countSamples("click", 1, DatasetManager.LabelSource.STAFF));
            second.close();
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testNarrowerLegacyFileIsReadRatherThanDiscarded() throws IOException {
        Path tempDir = Files.createTempDirectory("vesuvio-dataset-width-test");
        try {
            // A file written before the feature set widened: 16 feature columns, then domain and
            // source. Demanding the current width would shift the domain column out of position
            // and silently discard every historical row, so the reader takes the width from the
            // file's own header and zero-pads the rest.
            StringBuilder header = new StringBuilder("uuid,playerName,label,timestamp,reviewer");
            for (int i = 0; i < 16; i++) header.append(",f").append(i);
            header.append(",domain,source");

            StringBuilder row = new StringBuilder(UUID.randomUUID() + ",Old,1,1700000000000,Admin");
            for (int i = 0; i < 16; i++) row.append(",").append(i == 0 ? "42.000000" : "0.000000");
            row.append(",click,STAFF");

            Files.write(tempDir.resolve("auto_dataset.csv"),
                    java.util.List.of(header.toString(), row.toString()));

            DatasetManager manager = new DatasetManager(tempDir);
            assertEquals(1, manager.getDatasetSize(), "a narrower legacy row must still load");
            assertEquals(1, manager.countSamples("click", 1, DatasetManager.LabelSource.STAFF),
                    "domain and source must be read from their real positions, not shifted");

            DatasetManager.LabeledSample sample = manager.getSamples().iterator().next();
            assertEquals(42.0f, sample.features()[0], 1e-4, "existing features must survive");
            assertEquals(0f, sample.features()[19], 1e-6, "missing trailing features must be zero-padded");
            manager.close();
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
