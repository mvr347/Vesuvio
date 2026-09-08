package net.lovelace.vesuvio.check.selflearning;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages labeled feature samples collected via Active Learning and trusted sessions.
 *
 * Every sample is (a) kept in memory (bounded, FIFO-evicted) for fast access by
 * /vesuvio dataset stats and the web panel, and (b) appended to a persistent CSV file on disk
 * as it arrives - not just on a manual /vesuvio dataset export. This is what lets an external
 * process (see resources/scripts/train_models.py, invoked automatically by
 * net.lovelace.vesuvio.check.onnx.ModelAutoTrainer) retrain real ONNX models from live data
 * without any manual export step, and means a server restart never loses collected samples.
 *
 * Author: Lovelace
 */
public final class DatasetManager {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-Dataset");
    private static final int FEATURE_COUNT = 16;

    public record LabeledSample(
            UUID playerUuid,
            String playerName,
            float[] features,
            int label, // 1 = cheat, 0 = legit
            long timestamp,
            String reviewer,
            String domain // "click" or "aim" - which feature extractor/classifier this sample belongs to
    ) {
        /** Legacy 6-arg constructor, defaults to the click domain (all pre-existing call sites). */
        public LabeledSample(UUID playerUuid, String playerName, float[] features, int label, long timestamp, String reviewer) {
            this(playerUuid, playerName, features, label, timestamp, reviewer, "click");
        }
    }

    // Bounded to avoid unbounded memory growth once auto-collection is continuously feeding
    // samples in (see AutoDatasetCollector) - oldest samples are evicted from the in-memory
    // view first (FIFO). The on-disk CSV is append-only and never trimmed here; ModelAutoTrainer
    // and the training script are responsible for their own retention if that ever matters.
    private volatile int maxSize = 50_000;

    private final Queue<LabeledSample> dataset = new ConcurrentLinkedQueue<>();
    private final Path dataDirectory;
    private final Path autoPersistFile;
    private final Object writeLock = new Object();
    private BufferedWriter persistWriter;

    public DatasetManager(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.autoPersistFile = dataDirectory.resolve("auto_dataset.csv");
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create dataset directory", e);
        }

        // Preload whatever was persisted across the last restart so the in-memory view (and
        // /vesuvio dataset stats) reflect real history, not just this session.
        if (Files.exists(autoPersistFile)) {
            int loaded = importFromCsv(autoPersistFile);
            LOGGER.info(String.format("[Vesuvio] Reloaded %d persisted training sample(s) from %s", loaded, autoPersistFile.getFileName()));
        }

        try {
            persistWriter = Files.newBufferedWriter(autoPersistFile,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (Files.size(autoPersistFile) == 0) {
                persistWriter.write(csvHeader());
                persistWriter.newLine();
                persistWriter.flush();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to open persistent dataset file for append: " + autoPersistFile, e);
            persistWriter = null;
        }
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = Math.max(1000, maxSize);
    }

    public void addSample(LabeledSample sample) {
        dataset.add(sample);
        while (dataset.size() > maxSize) {
            dataset.poll();
        }

        if (persistWriter != null) {
            synchronized (writeLock) {
                try {
                    persistWriter.write(csvLine(sample));
                    persistWriter.newLine();
                    persistWriter.flush();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to persist training sample to disk", e);
                }
            }
        }
    }

    public int getDatasetSize() {
        return dataset.size();
    }

    /** Path to the continuously-updated on-disk dataset, for external tooling (e.g. the auto-retrain script). */
    public Path getAutoPersistFile() {
        return autoPersistFile;
    }

    public Collection<LabeledSample> getSamples() {
        return Collections.unmodifiableCollection(dataset);
    }

    /**
     * Counts labeled samples in memory matching a domain/label pair - used to decide whether
     * there's enough data yet to attempt a retrain (see ModelAutoTrainer).
     */
    public int countSamples(String domain, int label) {
        int count = 0;
        for (LabeledSample s : dataset) {
            if (domain.equals(s.domain()) && s.label() == label) count++;
        }
        return count;
    }

    private String csvHeader() {
        StringBuilder sb = new StringBuilder("uuid,playerName,label,timestamp,reviewer");
        for (int i = 0; i < FEATURE_COUNT; i++) {
            sb.append(",f").append(i);
        }
        sb.append(",domain");
        return sb.toString();
    }

    private String csvLine(LabeledSample s) {
        StringBuilder line = new StringBuilder();
        line.append(s.playerUuid()).append(",")
            .append(s.playerName()).append(",")
            .append(s.label()).append(",")
            .append(s.timestamp()).append(",")
            .append(s.reviewer());
        for (float f : s.features()) {
            line.append(",").append(String.format(Locale.US, "%.6f", f));
        }
        line.append(",").append(s.domain());
        return line.toString();
    }

    /**
     * Exports the in-memory dataset to an arbitrary CSV file (manual /vesuvio dataset export).
     * Independent from the always-on persistence to {@link #getAutoPersistFile()}.
     */
    public boolean exportToCsv(Path destination) {
        try (BufferedWriter writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
            writer.write(csvHeader());
            writer.newLine();
            for (LabeledSample s : dataset) {
                writer.write(csvLine(s));
                writer.newLine();
            }
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to export dataset to CSV: " + destination, e);
            return false;
        }
    }

    /**
     * Imports samples from a CSV file into the in-memory view (does NOT re-append them to the
     * persistent file - used for both manual /vesuvio dataset import and reloading the
     * persistent file itself on startup).
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

                float[] features = new float[FEATURE_COUNT];
                for (int i = 0; i < FEATURE_COUNT; i++) {
                    features[i] = Float.parseFloat(parts[5 + i]);
                }
                // Older exports (before the domain column existed) have exactly 21 columns and
                // default to "click"; newer exports carry domain as column 22.
                String domain = (parts.length >= 22) ? parts[21] : "click";

                dataset.add(new LabeledSample(uuid, name, features, label, time, reviewer, domain));
                imported++;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to import dataset from CSV: " + source, e);
        }

        return imported;
    }

    /** Flushes and closes the persistent-append writer. Call from plugin onDisable. */
    public void close() {
        synchronized (writeLock) {
            if (persistWriter != null) {
                try {
                    persistWriter.flush();
                    persistWriter.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
