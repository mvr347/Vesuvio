package net.lovelace.vesuvio.check.selflearning;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages labeled feature samples collected via Active Learning and trusted sessions.
 * Supports CSV/JSON export and import.
 *
 * Author: Lovelace
 */
public final class DatasetManager {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-Dataset");

    public record LabeledSample(
            UUID playerUuid,
            String playerName,
            float[] features,
            int label, // 1 = cheat, 0 = legit
            long timestamp,
            String reviewer
    ) {}

    private final Queue<LabeledSample> dataset = new ConcurrentLinkedQueue<>();
    private final Path dataDirectory;

    public DatasetManager(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create dataset directory", e);
        }
    }

    public void addSample(LabeledSample sample) {
        dataset.add(sample);
    }

    public int getDatasetSize() {
        return dataset.size();
    }

    public Collection<LabeledSample> getSamples() {
        return Collections.unmodifiableCollection(dataset);
    }

    /**
     * Exports dataset to a CSV file.
     */
    public boolean exportToCsv(Path destination) {
        try (BufferedWriter writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
            // Write CSV header
            StringBuilder sb = new StringBuilder("uuid,playerName,label,timestamp,reviewer");
            for (int i = 0; i < 16; i++) {
                sb.append(",f").append(i);
            }
            writer.write(sb.toString());
            writer.newLine();

            for (LabeledSample s : dataset) {
                StringBuilder line = new StringBuilder();
                line.append(s.playerUuid()).append(",")
                    .append(s.playerName()).append(",")
                    .append(s.label()).append(",")
                    .append(s.timestamp()).append(",")
                    .append(s.reviewer());

                for (float f : s.features()) {
                    line.append(",").append(String.format(Locale.US, "%.6f", f));
                }
                writer.write(line.toString());
                writer.newLine();
            }
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to export dataset to CSV: " + destination, e);
            return false;
        }
    }

    /**
     * Imports samples from CSV file.
     */
    public int importFromCsv(Path source) {
        if (!Files.exists(source)) return 0;
        int imported = 0;

        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String header = reader.readLine(); // Skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 21) continue;

                UUID uuid = UUID.fromString(parts[0]);
                String name = parts[1];
                int label = Integer.parseInt(parts[2]);
                long time = Long.parseLong(parts[3]);
                String reviewer = parts[4];

                float[] features = new float[16];
                for (int i = 0; i < 16; i++) {
                    features[i] = Float.parseFloat(parts[5 + i]);
                }

                dataset.add(new LabeledSample(uuid, name, features, label, time, reviewer));
                imported++;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to import dataset from CSV: " + source, e);
        }

        return imported;
    }
}
