package net.lovelace.vesuvio.check.onnx;

import net.lovelace.vesuvio.check.selflearning.DatasetManager;
import net.lovelace.vesuvio.config.ConfigManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fully automatic ONNX model retraining. Ties together two things that already exist on their
 * own - the continuously-growing dataset (DatasetManager.getAutoPersistFile()) and the bundled
 * training script (resources/scripts/train_models.py) - so a server operator never has to run
 * /vesuvio dataset export or invoke Python by hand.
 *
 * On a configurable interval, this:
 *  1. Checks whether enough labeled samples exist per class/domain to bother training
 *     (config: min-samples-per-class). Skips silently (with a one-time info log) if not.
 *  2. Invokes the bundled Python script against the live dataset CSV, writing to a staging
 *     directory.
 *  3. On success, atomically replaces click_model.onnx/aim_model.onnx in models/ and hot-reloads
 *     them into MLManager - the running server picks up the retrained model with no restart.
 *
 * This deliberately never installs Python packages itself: if scikit-learn/skl2onnx aren't
 * present, the script exits non-zero with a clear message on stderr, which is logged along with
 * the exact `pip install` command to run. Modifying the host's Python environment without an
 * operator's explicit action is a bigger blast radius than this class should take on its own.
 *
 * Author: Lovelace
 */
public final class ModelAutoTrainer {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-AutoTrain");
    private static final String[] DOMAINS = {"click", "aim"};

    private final ConfigManager config;
    private final DatasetManager datasetManager;
    private final MLManager mlManager;
    private final Path pluginFolder;
    private final java.util.function.Function<String, InputStream> resourceLoader;

    private final Path scriptsDir;
    private final Path modelsDir;
    private final AtomicBoolean warnedMissingPython = new AtomicBoolean(false);
    private final AtomicBoolean trainingInProgress = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;

    public ModelAutoTrainer(ConfigManager config, DatasetManager datasetManager,
                             MLManager mlManager, Path pluginFolder, java.util.function.Function<String, InputStream> resourceLoader) {
        this.config = config;
        this.datasetManager = datasetManager;
        this.mlManager = mlManager;
        this.pluginFolder = pluginFolder;
        this.resourceLoader = resourceLoader;
        this.scriptsDir = pluginFolder.resolve("scripts");
        this.modelsDir = pluginFolder.resolve("models");
    }

    /**
     * Extracts the bundled training script + requirements file, then schedules the periodic
     * retrain cycle. Safe to call even if auto-retrain is disabled in config (extraction still
     * happens so an operator can run the script by hand; scheduling is skipped).
     */
    public void start() {
        extractScriptResources();

        if (!config.isAutoRetrainEnabled()) {
            LOGGER.info("[Vesuvio] Automatic ONNX retraining is disabled (layers.onnx.auto-retrain.enabled: false).");
            return;
        }

        long intervalHours = Math.max(1, config.getAutoRetrainIntervalHours());
        long initialDelayHours = Math.max(0, config.getAutoRetrainInitialDelayHours());

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Vesuvio-AutoTrain");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::runRetrainCycleSafely,
                initialDelayHours, intervalHours, TimeUnit.HOURS);

        LOGGER.info(String.format(Locale.US,
                "[Vesuvio] Automatic ONNX retraining scheduled: every %dh (first run in %dh).",
                intervalHours, initialDelayHours));
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** Triggers a retrain cycle immediately, off the calling thread. Used by /vesuvio retrain. */
    public void triggerNow() {
        Thread.ofVirtual().name("Vesuvio-AutoTrain-Manual").start(this::runRetrainCycleSafely);
    }

    private void runRetrainCycleSafely() {
        if (!trainingInProgress.compareAndSet(false, true)) {
            LOGGER.info("[Vesuvio] Skipping retrain cycle - a previous run is still in progress.");
            return;
        }
        try {
            runRetrainCycle();
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[Vesuvio] Unexpected error during automatic retrain cycle", t);
        } finally {
            trainingInProgress.set(false);
        }
    }

    private void runRetrainCycle() {
        int minSamples = Math.max(10, config.getAutoRetrainMinSamplesPerClass());

        List<String> readyDomains = new ArrayList<>();
        for (String domain : DOMAINS) {
            int cheat = datasetManager.countSamples(domain, 1);
            int legit = datasetManager.countSamples(domain, 0);
            if (cheat >= minSamples && legit >= minSamples) {
                readyDomains.add(domain);
            } else {
                LOGGER.info(String.format(Locale.US,
                        "[Vesuvio] Auto-retrain: not enough '%s' samples yet (cheat=%d, legit=%d, need >= %d each) - skipping this domain.",
                        domain, cheat, legit, minSamples));
            }
        }
        if (readyDomains.isEmpty()) {
            return;
        }

        String pythonExe = config.getAutoRetrainPythonExecutable();
        Path scriptPath = scriptsDir.resolve("train_models.py");
        if (!Files.exists(scriptPath)) {
            LOGGER.warning("[Vesuvio] Auto-retrain: training script missing at " + scriptPath + " - extraction may have failed.");
            return;
        }

        Path stagingDir = modelsDir.resolve("staging");
        try {
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Vesuvio] Auto-retrain: failed to create staging directory", e);
            return;
        }

        List<String> command = new ArrayList<>();
        command.add(pythonExe);
        command.add(scriptPath.toString());
        command.add("--dataset");
        command.add(datasetManager.getAutoPersistFile().toString());
        command.add("--output-dir");
        command.add(stagingDir.toString());
        command.add("--domains");
        command.add(String.join(",", readyDomains));

        LOGGER.info("[Vesuvio] Auto-retrain: starting training for domain(s) " + readyDomains + " ...");

        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.directory(pluginFolder.toFile());
            process = pb.start();
        } catch (IOException e) {
            if (warnedMissingPython.compareAndSet(false, true)) {
                LOGGER.warning("=============================================================================");
                LOGGER.warning("[Vesuvio] Auto-retrain: could not launch '" + pythonExe + "'. Automatic ONNX");
                LOGGER.warning("[Vesuvio] retraining needs Python 3 with scikit-learn + skl2onnx installed on");
                LOGGER.warning("[Vesuvio] this machine. Install it once with:");
                LOGGER.warning("[Vesuvio]   pip install -r " + scriptsDir.resolve("requirements.txt"));
                LOGGER.warning("[Vesuvio] Or set layers.onnx.auto-retrain.python-executable in config.yml if");
                LOGGER.warning("[Vesuvio] python3 isn't on PATH. The OnlineClassifier self-learning layer keeps");
                LOGGER.warning("[Vesuvio] working regardless - this only affects the ONNX neural models.");
                LOGGER.warning("=============================================================================");
            } else {
                LOGGER.warning("[Vesuvio] Auto-retrain: still can't launch '" + pythonExe + "' (see earlier warning for setup instructions).");
            }
            return;
        }

        List<String> outputLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLines.add(line);
            }
        } catch (IOException ignored) {}

        boolean finished;
        try {
            finished = process.waitFor(Math.max(1, config.getAutoRetrainTimeoutMinutes()), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            LOGGER.warning("[Vesuvio] Auto-retrain: interrupted while waiting for training script.");
            return;
        }

        if (!finished) {
            process.destroyForcibly();
            LOGGER.warning("[Vesuvio] Auto-retrain: training script timed out and was killed.");
            logScriptOutput(outputLines);
            return;
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            LOGGER.warning("[Vesuvio] Auto-retrain: training script exited with code " + exitCode + ".");
            logScriptOutput(outputLines);
            return;
        }

        LOGGER.info("[Vesuvio] Auto-retrain: training script completed successfully.");
        logScriptOutput(outputLines);

        for (String domain : readyDomains) {
            String modelFile = domain + "_model.onnx";
            Path staged = stagingDir.resolve(modelFile);
            if (!Files.exists(staged)) {
                LOGGER.warning("[Vesuvio] Auto-retrain: expected output " + staged + " was not produced, skipping hot-reload for " + domain + ".");
                continue;
            }
            Path live = modelsDir.resolve(modelFile);
            try {
                Files.createDirectories(modelsDir);
                Files.copy(staged, live, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "[Vesuvio] Auto-retrain: failed to install retrained model " + modelFile, e);
                continue;
            }

            String modelName = domain + "_model";
            mlManager.hotReload(modelName, live).thenAccept(success -> {
                if (success) {
                    LOGGER.info("[Vesuvio] Auto-retrain: hot-reloaded freshly retrained " + modelFile + ".");
                } else {
                    LOGGER.warning("[Vesuvio] Auto-retrain: hot-reload of " + modelFile + " reported failure - check earlier ML log lines.");
                }
            });
        }
    }

    private void logScriptOutput(List<String> lines) {
        if (lines.isEmpty()) return;
        int cap = 40;
        int shown = 0;
        for (String line : lines) {
            if (shown++ >= cap) {
                LOGGER.info(String.format("[Vesuvio] Auto-retrain: ... (%d more line(s) omitted)", lines.size() - cap));
                break;
            }
            LOGGER.info("[Vesuvio][train_models.py] " + line);
        }
    }

    private void extractScriptResources() {
        try {
            Files.createDirectories(scriptsDir);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Vesuvio] Failed to create scripts directory", e);
            return;
        }
        extractResource("scripts/train_models.py", scriptsDir.resolve("train_models.py"));
        extractResource("scripts/requirements.txt", scriptsDir.resolve("requirements.txt"));
    }

    private void extractResource(String resourcePath, Path target) {
        try (InputStream in = resourceLoader.apply(resourcePath)) {
            if (in == null) {
                LOGGER.warning("[Vesuvio] Bundled resource not found in jar: " + resourcePath);
                return;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Vesuvio] Failed to extract bundled resource " + resourcePath, e);
        }
    }
}
