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
    private final ShadowEvaluator shadowEvaluator = new ShadowEvaluator();

    private ScheduledExecutorService scheduler;
    private final java.util.concurrent.ExecutorService trainingExecutor;
    private volatile boolean stopped;
    private volatile Process activeProcess;

    public ModelAutoTrainer(ConfigManager config, DatasetManager datasetManager,
                             MLManager mlManager, Path pluginFolder, java.util.function.Function<String, InputStream> resourceLoader) {
        this.config = config;
        this.trainingExecutor = new net.lovelace.vesuvio.engine.ManagedExecutor(
                "Vesuvio-AutoTrain-", config.useVirtualThreads(), 1, 1, getClass().getClassLoader());
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
            LOGGER.info("[Vesuvio] Автоматическое обучение ONNX отключено (layers.onnx.auto-retrain.enabled: false).");
            return;
        }

        long intervalHours = Math.max(1, config.getAutoRetrainIntervalHours());
        long initialDelayHours = Math.max(0, config.getAutoRetrainInitialDelayHours());

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Vesuvio-AutoTrain");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::triggerNow,
                initialDelayHours, intervalHours, TimeUnit.HOURS);

        LOGGER.info(String.format(Locale.US,
                "[Vesuvio] Автоматическое обучение ONNX: каждые %d ч (первый запуск через %d ч).",
                intervalHours, initialDelayHours));
    }

    public void stop() {
        stopped = true;
        if (scheduler != null) scheduler.shutdownNow();
        Process process = activeProcess;
        if (process != null) process.destroyForcibly();
        trainingExecutor.shutdownNow();
        net.lovelace.vesuvio.engine.ManagedExecutor.stop(trainingExecutor);
    }

    /** One owned task for both manual and scheduled training, with no waiting training queue. */
    public void triggerNow() {
        if (stopped) return;
        try { trainingExecutor.execute(this::runRetrainCycleSafely); }
        catch (java.util.concurrent.RejectedExecutionException busy) {
            if (!stopped) LOGGER.info("[Vesuvio] Обучение уже выполняется, повторный запуск пропущен.");
        }
    }

    private void runRetrainCycleSafely() {
        if (stopped || !config.isAnticheatEnabled()) return;
        if (!trainingInProgress.compareAndSet(false, true)) {
            LOGGER.info("[Vesuvio] Обучение пропущено — предыдущий запуск ещё выполняется.");
            return;
        }
        try {
            runRetrainCycle();
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[Vesuvio] Непредвиденная ошибка автоматического обучения", t);
        } finally {
            Process process = activeProcess;
            activeProcess = null;
            if (process != null && process.isAlive()) process.destroyForcibly();
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
                        "[Vesuvio] Автообучение: недостаточно образцов «%s» (чит: %d, честная игра: %d, нужно не менее %d каждого типа) — пропуск.",
                        domain, cheat, legit, minSamples));
            }
        }
        if (readyDomains.isEmpty()) {
            return;
        }

        String pythonExe = config.getAutoRetrainPythonExecutable();
        Path scriptPath = scriptsDir.resolve("train_models.py");
        if (!Files.exists(scriptPath)) {
            LOGGER.warning("[Vesuvio] Автообучение: скрипт отсутствует по пути " + scriptPath + " — возможно, не удалось извлечь ресурс.");
            return;
        }

        Path stagingDir = modelsDir.resolve("staging");
        try {
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Vesuvio] Автообучение: не удалось создать временный каталог", e);
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
        command.add("--model-type");
        command.add(config.getAutoRetrainModelType());
        command.add("--max-fpr");
        command.add(String.valueOf(config.getAutoRetrainMaxFpr()));
        command.add("--half-life-days");
        command.add(String.valueOf(config.getAutoRetrainSampleHalfLifeDays()));
        if (config.isAutoRetrainCalibrationEnabled()) {
            command.add("--calibrate");
        }

        LOGGER.info("[Vesuvio] Автообучение: запуск для типов " + readyDomains + " ...");

        Path trainingLog = stagingDir.resolve("training-output.log");
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(trainingLog.toFile());
            pb.directory(pluginFolder.toFile());
            process = pb.start();
            activeProcess = process;
            if (stopped) { process.destroyForcibly(); return; }
        } catch (IOException e) {
            if (warnedMissingPython.compareAndSet(false, true)) {
                LOGGER.warning("=============================================================================");
                LOGGER.warning("[Vesuvio] Автообучение: не удалось запустить «" + pythonExe + "». Для автоматического");
                LOGGER.warning("[Vesuvio] обучения ONNX необходим Python 3 с библиотеками scikit-learn и skl2onnx");
                LOGGER.warning("[Vesuvio] на этой машине. Установите зависимости командой:");
                LOGGER.warning("[Vesuvio]   pip install -r " + scriptsDir.resolve("requirements.txt"));
                LOGGER.warning("[Vesuvio] Или укажите layers.onnx.auto-retrain.python-executable в config.yml,");
                LOGGER.warning("[Vesuvio] если python3 отсутствует в PATH. Встроенный самообучающийся классификатор");
                LOGGER.warning("[Vesuvio] продолжает работать — это влияет только на модели ONNX.");
                LOGGER.warning("=============================================================================");
            } else {
                LOGGER.warning("[Vesuvio] Автообучение: по-прежнему не удаётся запустить «" + pythonExe + "» (инструкции установки приведены в предыдущем предупреждении).");
            }
            return;
        }

        boolean finished;
        try {
            finished = process.waitFor(Math.max(1, config.getAutoRetrainTimeoutMinutes()), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            LOGGER.warning("[Vesuvio] Автообучение: ожидание скрипта прервано.");
            return;
        }

        List<String> outputLines = new ArrayList<>();
        // Read after waitFor: a silent child cannot block timeout handling in readLine().
        try (var lines = Files.lines(trainingLog, StandardCharsets.UTF_8)) {
            lines.limit(256).forEach(outputLines::add);
        } catch (IOException ignored) {}
        if (stopped) return;

        if (!finished) {
            process.destroyForcibly();
            LOGGER.warning("[Vesuvio] Автообучение: время выполнения скрипта истекло, процесс остановлен.");
            logScriptOutput(outputLines);
            return;
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            LOGGER.warning("[Vesuvio] Автообучение: скрипт завершился с кодом " + exitCode + ".");
            logScriptOutput(outputLines);
            return;
        }

        LOGGER.info("[Vesuvio] Автообучение: скрипт успешно завершён.");
        logScriptOutput(outputLines);

        for (String domain : readyDomains) {
            String modelFile = domain + "_model.onnx";
            Path staged = stagingDir.resolve(modelFile);
            if (!Files.exists(staged)) {
                LOGGER.warning("[Vesuvio] Автообучение: ожидаемый файл " + staged + " не создан, пропущена перезагрузка " + domain + ".");
                continue;
            }

            if (!passesQualityGate(domain, stagingDir)) {
                continue;
            }

            if (config.isShadowModeEnabled()) {
                installAsShadowCandidate(domain, staged, stagingDir);
                continue;
            }

            Path live = modelsDir.resolve(modelFile);
            try {
                Files.createDirectories(modelsDir);
                // Keep the outgoing model so a bad retrain can be undone without waiting for the
                // next cycle - see /vesuvio model rollback.
                if (Files.exists(live)) {
                    Files.copy(live, modelsDir.resolve(domain + "_model.onnx.previous"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                Files.copy(staged, live, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "[Vesuvio] Автообучение: не удалось установить обученную модель " + modelFile, e);
                continue;
            }

            String modelName = domain + "_model";
            mlManager.hotReload(modelName, live).thenAccept(success -> {
                if (success) {
                    LOGGER.info("[Vesuvio] Автообучение: перезагружена обученная модель " + modelFile + ".");
                } else {
                    LOGGER.warning("[Vesuvio] Автообучение: перезагрузка " + modelFile + " завершилась ошибкой — проверьте предыдущие записи журнала моделей.");
                }
            });
        }
    }

    /**
     * Installs a gate-passing model as a shadow candidate instead of publishing it.
     *
     * <p>The offline report says the candidate is good on a held-out slice of the server's own
     * historical dataset - but that dataset was collected by the current pipeline, so it
     * systematically under-represents whatever the current pipeline is blind to. Shadow mode buys
     * the missing evidence cheaply: the candidate scores the same live feature vectors as the
     * production model, its verdicts are recorded and never acted on, and an operator promotes it
     * once {@code /vesuvio model shadow} shows what it would actually have done.
     */
    private void installAsShadowCandidate(String domain, Path staged, Path stagingDir) {
        String modelName = domain + "_model";
        String shadowName = modelName + ShadowEvaluator.SHADOW_SUFFIX;
        Path candidate = modelsDir.resolve(domain + "_model.onnx.candidate");

        try {
            Files.createDirectories(modelsDir);
            Files.copy(staged, candidate, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Vesuvio] Автообучение: не удалось подготовить теневого кандидата для " + domain, e);
            return;
        }

        // The candidate is judged at the threshold its own report recommends, not the live model's:
        // thresholds are model-specific, and comparing a new model at the old model's operating
        // point measures the wrong thing entirely.
        double threshold = candidateThreshold(domain, stagingDir);
        shadowEvaluator.clear(modelName);
        mlManager.registerModel(new ModelConfig(shadowName, true, candidate.toString(),
                threshold, config.getOnnxWeight(), "float_input"));
        mlManager.hotReload(shadowName, candidate).thenAccept(success -> {
            if (success) {
                LOGGER.info(String.format(Locale.US,
                        "[Vesuvio] Автообучение: «%s» прошёл проверку качества и работает в ТЕНЕВОМ РЕЖИМЕ с порогом "
                                + "%.3f — оценивает текущие данные, не влияя на решения. Выполните «/vesuvio model shadow» "
                                + "через несколько часов, затем «/vesuvio model promote %s» для публикации.",
                        domain, threshold, domain));
            } else {
                LOGGER.warning("[Vesuvio] Автообучение: теневой кандидат для " + domain + " не загружен.");
            }
        });
    }

    /** The operating threshold the candidate's own report recommends, or the configured default. */
    private double candidateThreshold(String domain, Path stagingDir) {
        Path reportPath = stagingDir.resolve(domain + "_report.json");
        if (Files.exists(reportPath)) {
            try {
                double suggested = readJsonNumber(Files.readString(reportPath, StandardCharsets.UTF_8),
                        "suggested_threshold");
                if (!Double.isNaN(suggested) && suggested > 0.0 && suggested < 1.0) return suggested;
            } catch (IOException ignored) {
                // Falls through to the configured default - a missing report is already reported
                // by the quality gate, which runs before this.
            }
        }
        return config.getOnnxDefaultThreshold();
    }

    /**
     * Publishes the shadow candidate for a domain as the live model.
     *
     * @return a human-readable result, suitable for sending straight back to the operator
     */
    public String promoteCandidate(String domain) {
        Path candidate = modelsDir.resolve(domain + "_model.onnx.candidate");
        if (!Files.exists(candidate)) {
            return "Нет теневого кандидата для «" + domain + "».";
        }

        String modelName = domain + "_model";
        Path live = modelsDir.resolve(domain + "_model.onnx");
        try {
            if (Files.exists(live)) {
                Files.copy(live, modelsDir.resolve(domain + "_model.onnx.previous"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(candidate, live, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(candidate);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Vesuvio] Не удалось опубликовать теневого кандидата для " + domain, e);
            return "Не удалось опубликовать кандидата «" + domain + "» — подробности в консоли.";
        }

        String summary = shadowEvaluator.describe(modelName);
        mlManager.unloadModel(modelName + ShadowEvaluator.SHADOW_SUFFIX);
        shadowEvaluator.clear(modelName);
        mlManager.hotReload(modelName, live);

        LOGGER.info("[Vesuvio] Теневой кандидат опубликован для " + domain + " (" + summary + ")");
        return "Опубликован кандидат «" + domain + "». Предыдущая модель сохранена как "
                + domain + "_model.onnx.previous. Наблюдение: " + summary;
    }

    /** Throws away the shadow candidate for a domain without publishing it. */
    public String discardCandidate(String domain) {
        Path candidate = modelsDir.resolve(domain + "_model.onnx.candidate");
        String modelName = domain + "_model";
        mlManager.unloadModel(modelName + ShadowEvaluator.SHADOW_SUFFIX);
        shadowEvaluator.clear(modelName);
        try {
            boolean existed = Files.deleteIfExists(candidate);
            return existed ? "Удалён теневой кандидат «" + domain + "»."
                    : "Нет теневого кандидата для «" + domain + "».";
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Vesuvio] Не удалось удалить теневого кандидата для " + domain, e);
            return "Не удалось удалить файл кандидата «" + domain + "» — подробности в консоли.";
        }
    }

    /** Live-vs-candidate comparison counters, written from the ONNX callbacks in CheckPipeline. */
    public ShadowEvaluator getShadowEvaluator() {
        return shadowEvaluator;
    }

    /** The domains this trainer knows about, for command completion and status output. */
    public static String[] domains() {
        return DOMAINS.clone();
    }

    /**
     * Refuses to publish a retrained model that the training script could not validate, or that
     * validated badly.
     *
     * <p>Previously every successful script run was copied into place and hot-reloaded on the
     * spot, so a model trained on a handful of near-duplicate windows went straight to production
     * with nothing watching. The script now writes a JSON report next to the model; a model that
     * is unvalidated (too few distinct players to hold any out) or below the configured
     * precision floor at the false-positive budget stays in staging, where an operator can still
     * inspect it.
     */
    private boolean passesQualityGate(String domain, Path stagingDir) {
        if (!config.isAutoRetrainQualityGateEnabled()) {
            return true;
        }

        Path reportPath = stagingDir.resolve(domain + "_report.json");
        if (!Files.exists(reportPath)) {
            LOGGER.warning("[Vesuvio] Автообучение: нет отчёта оценки для «" + domain
                    + "» — модель без измерений не публикуется. Возможно, встроенный скрипт обучения устарел.");
            return false;
        }

        String json;
        try {
            json = Files.readString(reportPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Vesuvio] Автообучение: не удалось прочитать отчёт оценки для " + domain, e);
            return false;
        }

        if (!readJsonBoolean(json, "validated")) {
            LOGGER.warning("[Vesuvio] Автообучение: модель «" + domain + "» НЕ ПРОШЛА ВАЛИДАЦИЮ (слишком мало разных "
                    + "игроков для отдельной проверочной выборки) — публикация отменена. Нужны образцы от большего числа игроков.");
            return false;
        }

        double precision = readJsonNumber(json, "precision_at_max_fpr");
        double recall = readJsonNumber(json, "recall_at_max_fpr");
        double minPrecision = config.getAutoRetrainMinPrecision();

        if (Double.isNaN(precision) || precision < minPrecision) {
            LOGGER.warning(String.format(Locale.US,
                    "[Vesuvio] Автообучение: модель «%s» получила точность %.3f (полнота %.3f) при заданном "
                            + "бюджете ложных срабатываний, ниже минимума %.3f — публикация отменена.",
                    domain, precision, recall, minPrecision));
            return false;
        }

        // Calibration gate. Every threshold an operator sets in config.yml is a probability, so a
        // model whose 0.9 does not actually mean "9 in 10 such windows are cheats" silently
        // redefines every one of those settings. A badly miscalibrated model can still have a fine
        // PR-AUC - ranking and calibration are different properties - which is exactly why this is
        // checked separately rather than assumed from the precision figure above.
        double calibrationError = readJsonNumber(json, "ece");
        double maxCalibrationError = config.getAutoRetrainMaxCalibrationError();
        if (!Double.isNaN(calibrationError) && maxCalibrationError > 0 && calibrationError > maxCalibrationError) {
            LOGGER.warning(String.format(Locale.US,
                    "[Vesuvio] Автообучение: модель «%s» плохо откалибрована (ошибка калибровки %.3f > %.3f) "
                            + "— публикация отменена, поскольку пороги вероятности в config.yml потеряли бы смысл. "
                            + "Обычно помогает включение layers.onnx.auto-retrain.calibrate.",
                    domain, calibrationError, maxCalibrationError));
            return false;
        }

        double suggested = readJsonNumber(json, "suggested_threshold");
        if (!Double.isNaN(suggested)) {
            // Surfaced rather than applied: the live threshold is an operator's setting, and
            // silently rewriting config.yml from a background task would be a surprising thing for
            // a plugin to do. Logged so the recommendation is actually actionable.
            LOGGER.info(String.format(Locale.US,
                    "[Vesuvio] Автообучение: модель «%s» достигает заданного бюджета ложных срабатываний при "
                            + "пороге %.3f — для его использования задайте layers.onnx.models.%s.threshold.",
                    domain, suggested, domain + "_model"));
        }

        LOGGER.info(String.format(Locale.US,
                "[Vesuvio] Автообучение: модель «%s» прошла проверку качества (точность %.3f, полнота %.3f, "
                        + "ошибка калибровки: %s).",
                domain, precision, recall,
                Double.isNaN(calibrationError) ? "n/a" : String.format(Locale.US, "%.3f", calibrationError)));
        return true;
    }

    /**
     * Minimal field reader for the flat, machine-generated report the training script writes.
     * Deliberately not a JSON dependency: the document is one level deep and written by code in
     * this same repository, so a full parser would be more surface area than the task needs.
     */
    private static double readJsonNumber(String json, String field) {
        String needle = "\"" + field + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return Double.NaN;
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) return Double.NaN;

        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        while (i < json.length() && "-+.eE0123456789".indexOf(json.charAt(i)) >= 0) i++;
        if (start == i) return Double.NaN;
        try {
            return Double.parseDouble(json.substring(start, i));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static boolean readJsonBoolean(String json, String field) {
        String needle = "\"" + field + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return false;
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) return false;
        return json.regionMatches(true, skipWhitespace(json, colon + 1), "true", 0, 4);
    }

    private static int skipWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private void logScriptOutput(List<String> lines) {
        if (lines.isEmpty()) return;
        int cap = 40;
        int shown = 0;
        for (String line : lines) {
            if (shown++ >= cap) {
                LOGGER.info(String.format("[Vesuvio] Автообучение: ... (пропущено ещё %d строк)", lines.size() - cap));
                break;
            }
            LOGGER.info("[Vesuvio][train_models.py] " + line);
        }
    }

    private void extractScriptResources() {
        try {
            Files.createDirectories(scriptsDir);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Vesuvio] Не удалось создать каталог скриптов", e);
            return;
        }
        extractResource("scripts/train_models.py", scriptsDir.resolve("train_models.py"));
        extractResource("scripts/requirements.txt", scriptsDir.resolve("requirements.txt"));
    }

    private void extractResource(String resourcePath, Path target) {
        try (InputStream in = resourceLoader.apply(resourcePath)) {
            if (in == null) {
                LOGGER.warning("[Vesuvio] Встроенный ресурс не найден в JAR: " + resourcePath);
                return;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Vesuvio] Не удалось извлечь встроенный ресурс " + resourcePath, e);
        }
    }
}
