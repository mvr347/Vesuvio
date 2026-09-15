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
    /** Matches ClickFeatureExtractor.FEATURE_COUNT - the widest vector any domain persists. */
    private static final int FEATURE_COUNT = 20;

    /**
     * Where a label came from. This is not cosmetic: it decides whether a sample may be trained on
     * at all.
     *
     * <p>{@link #BAN} and {@link #TRUSTED} are <em>self-confirming</em> - the ban was decided by
     * the same pipeline whose models the sample would train, and "trusted" means precisely "the
     * pipeline has not flagged them". Training on those alone teaches the model to reproduce its
     * own current opinion, including its mistakes: a legitimate play style that the heuristics
     * mis-flag gets banned, is recorded as a cheat, and the model then learns to be more certain
     * about the same error.
     *
     * <p>{@link #TRAP} and {@link #STAFF} are the only sources carrying information the pipeline
     * did not already have - a packet-level trap entity a legitimate client cannot see, and a
     * human verdict. The training script treats them accordingly (see scripts/train_models.py).
     */
    public enum LabelSource {
        /** Auto-captured when the pipeline's own punishment threshold fired. Self-confirming. */
        BAN,
        /** Auto-captured from a high-Trust/low-Risk session. Self-confirming. */
        TRUSTED,
        /** Staff verdict via Active Learning or the web panel. External ground truth. */
        STAFF,
        /** Hit a per-player packet trap entity invisible to a legitimate client. Conclusive. */
        TRAP,
        /** Imported from an external CSV, or predating the source column. */
        UNKNOWN
    }

    /**
     * Conditions the sample was captured under. Deliberately NOT part of the feature vector fed to
     * the models: ping and TPS are properties of the connection and the server, not of the player's
     * behaviour, and training on them directly teaches "high-ping players are cheaters" - exactly
     * the bias that makes an anticheat unusable for a distant playerbase.
     *
     * <p>They are recorded so the training script and any offline analysis can <em>slice</em> by
     * them: if precision collapses above 200ms ping, or every false positive lands during a TPS
     * dip, that is visible in the report instead of being an unexplained regression. The client
     * brand serves the same purpose for a mod-pack-specific input pattern.
     */
    public record SampleContext(int pingMs, double tps, String clientBrand) {
        public static final SampleContext UNKNOWN = new SampleContext(-1, -1.0, "");

        /** Strips anything that would break a CSV row or a header-driven column index. */
        public String safeBrand() {
            if (clientBrand == null || clientBrand.isBlank()) return "";
            String cleaned = clientBrand.replaceAll("[^A-Za-z0-9_.:-]", "_");
            return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
        }
    }

    public record LabeledSample(
            UUID playerUuid,
            String playerName,
            float[] features,
            int label, // 1 = cheat, 0 = legit
            long timestamp,
            String reviewer,
            String domain, // "click" or "aim" - which feature extractor/classifier this sample belongs to
            LabelSource source,
            SampleContext context
    ) {
        /** Legacy 6-arg constructor, defaults to the click domain (all pre-existing call sites). */
        public LabeledSample(UUID playerUuid, String playerName, float[] features, int label, long timestamp, String reviewer) {
            this(playerUuid, playerName, features, label, timestamp, reviewer, "click", LabelSource.UNKNOWN, SampleContext.UNKNOWN);
        }

        /** Legacy 7-arg constructor, from before label provenance was tracked. */
        public LabeledSample(UUID playerUuid, String playerName, float[] features, int label, long timestamp,
                             String reviewer, String domain) {
            this(playerUuid, playerName, features, label, timestamp, reviewer, domain, LabelSource.UNKNOWN, SampleContext.UNKNOWN);
        }

        /** Legacy 8-arg constructor, from before capture context was recorded. */
        public LabeledSample(UUID playerUuid, String playerName, float[] features, int label, long timestamp,
                             String reviewer, String domain, LabelSource source) {
            this(playerUuid, playerName, features, label, timestamp, reviewer, domain, source, SampleContext.UNKNOWN);
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
        sb.append(",domain,source,pingMs,tps,clientBrand");
        return sb.toString();
    }

    private String csvLine(LabeledSample s) {
        StringBuilder line = new StringBuilder();
        line.append(s.playerUuid()).append(",")
            .append(s.playerName()).append(",")
            .append(s.label()).append(",")
            .append(s.timestamp()).append(",")
            .append(s.reviewer());
        // Always emit exactly FEATURE_COUNT columns, padding a narrower vector with zeros: the
        // aim domain is narrower than the click domain, and both share this file. Writing a short
        // row would shift the domain/source columns out of position for that row and make it
        // unreadable - which is exactly what the header-driven reader would then skip.
        float[] f = s.features();
        for (int i = 0; i < FEATURE_COUNT; i++) {
            line.append(",").append(String.format(Locale.US, "%.6f", i < f.length ? f[i] : 0f));
        }
        line.append(",").append(s.domain());
        line.append(",").append(s.source() == null ? LabelSource.UNKNOWN : s.source());
        SampleContext ctx = s.context() == null ? SampleContext.UNKNOWN : s.context();
        line.append(",").append(ctx.pingMs());
        line.append(",").append(String.format(Locale.US, "%.2f", ctx.tps()));
        line.append(",").append(ctx.safeBrand());
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
            String header = reader.readLine();
            // How many feature columns this particular file carries is read from its own header
            // rather than assumed: the feature set grows over time (features are only appended),
            // so a file written before the current FEATURE_COUNT is perfectly valid and its rows
            // must be zero-padded rather than misread - the column after the features is the
            // domain, and reading it as a float would silently drop the whole row.
            int fileFeatureCount = countFeatureColumns(header);
            if (fileFeatureCount <= 0) {
                LOGGER.warning("[Vesuvio] Dataset file has no recognisable feature columns: " + source);
                return 0;
            }
            int domainIdx = 5 + fileFeatureCount;
            int sourceIdx = domainIdx + 1;
            int usable = Math.min(fileFeatureCount, FEATURE_COUNT);

            String line;
            while ((line = reader.readLine()) != null) {
                // -1 keeps trailing empty fields. The client brand is the last column and is very
                // often empty (the player never sent a brand packet), and the default split drops
                // every trailing empty - which silently shortened those rows past the context
                // columns and made their ping/TPS unreadable.
                String[] parts = line.split(",", -1);
                if (parts.length < domainIdx) continue;

                UUID uuid = UUID.fromString(parts[0]);
                String name = parts[1];
                int label = Integer.parseInt(parts[2]);
                long time = Long.parseLong(parts[3]);
                String reviewer = parts[4];

                float[] features = new float[FEATURE_COUNT];
                for (int i = 0; i < usable; i++) {
                    features[i] = Float.parseFloat(parts[5 + i]);
                }
                String domain = (parts.length > domainIdx) ? parts[domainIdx] : "click";
                LabelSource labelSource = (parts.length > sourceIdx)
                        ? parseSource(parts[sourceIdx]) : inferLegacySource(reviewer);
                // Context columns were added after source; rows written before that simply lack
                // them and read back as UNKNOWN rather than shifting anything.
                SampleContext context = SampleContext.UNKNOWN;
                if (parts.length > sourceIdx + 3) {
                    context = new SampleContext(
                            parseIntOr(parts[sourceIdx + 1], -1),
                            parseDoubleOr(parts[sourceIdx + 2], -1.0),
                            parts[sourceIdx + 3]);
                }

                dataset.add(new LabeledSample(uuid, name, features, label, time, reviewer, domain, labelSource, context));
                imported++;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to import dataset from CSV: " + source, e);
        }

        return imported;
    }

    /** Number of {@code f<N>} columns declared by a CSV header, or -1 if it is unreadable. */
    private static int countFeatureColumns(String header) {
        if (header == null || header.isBlank()) return -1;
        int count = 0;
        for (String column : header.split(",")) {
            String trimmed = column.trim();
            if (trimmed.length() > 1 && trimmed.charAt(0) == 'f' && Character.isDigit(trimmed.charAt(1))) {
                count++;
            }
        }
        return count > 0 ? count : -1;
    }

    private static int parseIntOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double parseDoubleOr(String raw, double fallback) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static LabelSource parseSource(String raw) {
        if (raw == null || raw.isBlank()) return LabelSource.UNKNOWN;
        try {
            return LabelSource.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return LabelSource.UNKNOWN;
        }
    }

    /**
     * Recovers provenance for rows written before the source column existed, from the reviewer
     * string the collectors already stamped. Rows that are neither recognisable auto-collector
     * output stay UNKNOWN rather than being optimistically promoted to STAFF.
     */
    private static LabelSource inferLegacySource(String reviewer) {
        if (reviewer == null) return LabelSource.UNKNOWN;
        if (reviewer.startsWith("AutoCollector(ban)")) return LabelSource.BAN;
        if (reviewer.startsWith("AutoCollector(trusted)")) return LabelSource.TRUSTED;
        return LabelSource.UNKNOWN;
    }

    /** Counts labeled samples in memory matching a domain/label/source triple. */
    public int countSamples(String domain, int label, LabelSource source) {
        int count = 0;
        for (LabeledSample s : dataset) {
            if (domain.equals(s.domain()) && s.label() == label && s.source() == source) count++;
        }
        return count;
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
