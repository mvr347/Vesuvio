package net.lovelace.vesuvio;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import net.lovelace.vesuvio.api.VesuvioAPI;
import net.lovelace.vesuvio.api.VesuvioAPIImpl;
import net.lovelace.vesuvio.api.VesuvioProvider;
import net.lovelace.vesuvio.check.onnx.MLManager;
import net.lovelace.vesuvio.check.onnx.ModelConfig;
import net.lovelace.vesuvio.check.selflearning.SelfLearningManager;
import net.lovelace.vesuvio.command.VesuvioCommand;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.listener.PlayerLifecycleListener;
import net.lovelace.vesuvio.packet.AimPacketListener;
import net.lovelace.vesuvio.packet.BrandPacketListener;
import net.lovelace.vesuvio.packet.ClickPacketListener;
import net.lovelace.vesuvio.pipeline.CheckPipeline;
import net.lovelace.vesuvio.staff.SmartAlertService;
import net.lovelace.vesuvio.staff.SpectateManager;
import net.lovelace.vesuvio.storage.DatabaseManager;
import net.lovelace.vesuvio.web.ApiServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * Vesuvio 26.2 AntiCheat
 * Author: Lovelace
 *
 * Next-Generation Hybrid 3-Layer AntiCheat for Paper & Purpur 1.21.11 / 26.2.
 */
public final class Vesuvio extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private UserDataManager userDataManager;
    private net.lovelace.vesuvio.engine.TransactionManager transactionManager;
    private net.lovelace.vesuvio.engine.EnvironmentSnapshotService environmentSnapshotService;
    private net.lovelace.vesuvio.engine.AimTrackingService aimTrackingService;
    private MLManager mlManager;
    private SelfLearningManager selfLearningManager;
    private net.lovelace.vesuvio.check.onnx.ModelAutoTrainer modelAutoTrainer;
    private SmartAlertService alertService;
    private SpectateManager spectateManager;
    private net.lovelace.vesuvio.staff.DebugOverlayManager debugOverlayManager;
    private net.lovelace.vesuvio.config.PresetManager presetManager;
    private CheckPipeline checkPipeline;
    private ApiServer apiServer;
    private net.lovelace.vesuvio.diagnostics.DiagnosticService diagnostics;
    private ExecutorService virtualExecutor;
    private net.lovelace.vesuvio.integration.hunt.LoveHuntHook loveHuntHook;
    private net.lovelace.vesuvio.integration.papi.VesuvioExpansion papiExpansion;

    @Override
    public void onLoad() {
        // Initialize PacketEvents Spigot / Paper platform
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));

        // PacketEvents is shaded and relocated into net.lovelace.vesuvio.libs.packetevents, and its
        // own update checker's console message needs Adventure classes (net.kyori.adventure) that
        // are not part of that relocation - only Paper's own copy is on the classpath, under the
        // real net.kyori.adventure package. The checker's background thread throws
        // NoClassDefFoundError on every startup trying to build that message. It is harmless (a
        // background thread failing to print an update notice) but pure noise: this plugin ships
        // pinned dependency versions, so a PacketEvents update notice for server owners was never
        // actionable here anyway.
        PacketEvents.getAPI().getSettings().checkForUpdates(false);
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        long startMs = System.currentTimeMillis();

        // 1. PacketEvents initialization
        PacketEvents.getAPI().init();

        ClassLoader pluginClassLoader = getClass().getClassLoader();
        this.configManager = new ConfigManager(this);
        this.virtualExecutor = new net.lovelace.vesuvio.engine.ManagedExecutor(
                "Vesuvio-Worker-", configManager.useVirtualThreads(), configManager.getWorkerCapacity(),
                Math.max(2, Runtime.getRuntime().availableProcessors()), pluginClassLoader);

        // Preload core classes so virtual threads and Netty pipeline handlers never fail
        try {
            Class.forName("net.lovelace.vesuvio.engine.PingRecord", true, pluginClassLoader);
            Class.forName("net.lovelace.vesuvio.storage.ViolationRecord", true, pluginClassLoader);
            Class.forName("net.lovelace.vesuvio.storage.PunishmentRecord", true, pluginClassLoader);
            Class.forName("net.lovelace.vesuvio.storage.DatabaseManager$PlayerProfile", true, pluginClassLoader);
            Class.forName("net.lovelace.vesuvio.libs.packetevents.api.util.ExceptionUtil", true, pluginClassLoader);
            Class.forName("net.lovelace.vesuvio.libs.packetevents.api.protocol.player.TextureProperty", true, pluginClassLoader);
        } catch (ClassNotFoundException ignored) {}

        // 3. Configuration & Database
        this.presetManager = new net.lovelace.vesuvio.config.PresetManager(this, configManager);
        this.databaseManager = new DatabaseManager(configManager, getDataFolder().toPath());
        this.userDataManager = new UserDataManager();

        // 4. ML Manager & Model Registration
        this.mlManager = new MLManager(configManager.useVirtualThreads(), configManager.getInferenceParallelism());
        initModels();

        // 5. Self-Learning Layer
        this.selfLearningManager = new SelfLearningManager(getDataFolder().toPath(), configManager);

        // 5b. Fully-automatic ONNX retraining from the live dataset (see ModelAutoTrainer).
        // Extracted/started after models are registered below so it can hot-reload into an
        // MLManager that already knows about click_model/aim_model.
        this.modelAutoTrainer = new net.lovelace.vesuvio.check.onnx.ModelAutoTrainer(
                configManager,
                selfLearningManager.getDatasetManager(),
                mlManager,
                getDataFolder().toPath(),
                this::getResource
        );

        // 6. Staff Services, Live Overlay & Spartan Enhancements
        this.alertService = new SmartAlertService(configManager);
        this.spectateManager = new SpectateManager(userDataManager);
        this.debugOverlayManager = new net.lovelace.vesuvio.staff.DebugOverlayManager(userDataManager);
        // Transaction-based latency: the unspoofable ping source every check's lag tolerance is
        // scaled by, and the acknowledgement clock the Velocity check waits on.
        this.transactionManager = new net.lovelace.vesuvio.engine.TransactionManager();
        // Main-thread world/player snapshots, so the movement checks running on virtual threads
        // never touch the Bukkit API themselves.
        this.environmentSnapshotService = new net.lovelace.vesuvio.engine.EnvironmentSnapshotService(userDataManager);
        // Per-tick target-relative aim capture, feeding StrafeReversalCheck.
        this.aimTrackingService = new net.lovelace.vesuvio.engine.AimTrackingService(userDataManager);
        var lagCompensator = new net.lovelace.vesuvio.engine.LagCompensator(configManager, transactionManager);
        var waveManager = new net.lovelace.vesuvio.punishment.PunishmentWaveManager(this, configManager, databaseManager);
        var discordService = new net.lovelace.vesuvio.staff.DiscordWebhookService(configManager, virtualExecutor);
        var hitboxTracker = new net.lovelace.vesuvio.engine.HitboxHistoryTracker();
        var banEvasionManager = new net.lovelace.vesuvio.evasion.BanEvasionManager(databaseManager);
        var npcTrapManager = new net.lovelace.vesuvio.engine.NpcTrapManager(this, configManager);

        this.diagnostics = new net.lovelace.vesuvio.diagnostics.DiagnosticService(this, configManager, userDataManager, transactionManager);

        // 7. Check Pipeline
        this.checkPipeline = new CheckPipeline(
                this,
                configManager,
                mlManager,
                selfLearningManager,
                alertService,
                databaseManager,
                lagCompensator,
                waveManager,
                discordService,
                hitboxTracker,
                banEvasionManager,
                transactionManager,
                npcTrapManager,
                virtualExecutor,
                diagnostics
        );
        this.checkPipeline.setAimTrackingService(aimTrackingService);
        // Shadow mode (see ShadowEvaluator): mirrors every real ONNX inference to a staged
        // candidate model without letting it affect anything. Wired unconditionally - the pipeline
        // skips the mirror when no candidate is loaded, which is the case unless shadow mode is on
        // and a retrain has produced one.
        this.checkPipeline.setShadowEvaluator(modelAutoTrainer.getShadowEvaluator());

        // 8. Register Packet Listeners
        BrandPacketListener brandListener = new BrandPacketListener(userDataManager, configManager);
        PacketEvents.getAPI().getEventManager().registerListener(
                new ClickPacketListener(userDataManager, checkPipeline, virtualExecutor)
        );
        PacketEvents.getAPI().getEventManager().registerListener(
                new AimPacketListener(userDataManager, checkPipeline, virtualExecutor)
        );
        PacketEvents.getAPI().getEventManager().registerListener(
                new net.lovelace.vesuvio.packet.MovementPacketListener(userDataManager, checkPipeline, virtualExecutor)
        );
        PacketEvents.getAPI().getEventManager().registerListener(
                new net.lovelace.vesuvio.packet.TransactionPacketListener(userDataManager, transactionManager, configManager)
        );
        PacketEvents.getAPI().getEventManager().registerListener(brandListener);
        var clientWorldListener = new net.lovelace.vesuvio.packet.ClientWorldPacketListener(userDataManager, configManager);
        PacketEvents.getAPI().getEventManager().registerListener(clientWorldListener);

        // 9. Register Bukkit Events
        var worldInteractionListener = new net.lovelace.vesuvio.listener.WorldInteractionListener(userDataManager, checkPipeline, configManager);
        Bukkit.getPluginManager().registerEvents(
                new PlayerLifecycleListener(userDataManager, databaseManager, spectateManager, brandListener, lagCompensator, hitboxTracker, transactionManager, environmentSnapshotService, worldInteractionListener, selfLearningManager, banEvasionManager, checkPipeline, configManager, virtualExecutor),
                this
        );
        Bukkit.getPluginManager().registerEvents(diagnostics, this);
        Bukkit.getPluginManager().registerEvents(worldInteractionListener, this);
        Bukkit.getPluginManager().registerEvents(environmentSnapshotService, this);
        Bukkit.getPluginManager().registerEvents(
                new net.lovelace.vesuvio.listener.PhysicsExemptionListener(userDataManager, configManager),
                this
        );

        // 10. Register Commands
        VesuvioCommand cmdExecutor = new VesuvioCommand(
                this,
                configManager,
                userDataManager,
                databaseManager,
                mlManager,
                selfLearningManager,
                alertService,
                spectateManager,
                waveManager,
                presetManager,
                debugOverlayManager,
                modelAutoTrainer,
                diagnostics
        );
        var cmd = getCommand("vesuvio");
        if (cmd != null) {
            cmd.setExecutor(cmdExecutor);
            cmd.setTabCompleter(cmdExecutor);
        }

        // 11. Register Integrations & Public Java API
        this.loveHuntHook = new net.lovelace.vesuvio.integration.hunt.LoveHuntHook(this);
        waveManager.setLoveHuntHook(loveHuntHook);

        VesuvioAPIImpl apiImpl = new VesuvioAPIImpl(userDataManager, waveManager, configManager.getHighRiskThreshold(),
                databaseManager, mlManager, selfLearningManager, configManager);
        VesuvioProvider.register(apiImpl);
        try {
            getServer().getServicesManager().register(VesuvioAPI.class, apiImpl, this, org.bukkit.plugin.ServicePriority.Normal);
        } catch (Throwable ignored) {}

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.papiExpansion = new net.lovelace.vesuvio.integration.papi.VesuvioExpansion(this);
            papiExpansion.register();
            getLogger().info("Подключена интеграция с PlaceholderAPI (VesuvioExpansion зарегистрирован).");
        }

        // 12. Start Embedded Web Server & REST API
        this.apiServer = new ApiServer(
                this,
                configManager,
                userDataManager,
                mlManager,
                selfLearningManager,
                databaseManager
        );
        apiServer.start();

        // 13. Schedulers: Hitbox History Bounding Box Capture (1 tick = 50ms)
        Bukkit.getScheduler().runTaskTimer(this, hitboxTracker::recordTick, 1L, 1L);

        // Schedulers: main-thread environment snapshot + transaction ping (1 tick = 50ms).
        // These two must run on the main thread: the snapshot is the only place allowed to read
        // the world on behalf of the async movement checks, and the transaction is the clock they
        // measure latency against.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                net.lovelace.vesuvio.engine.TpsCache.update(Bukkit.getTPS());
            } catch (Throwable ignored) {}
            environmentSnapshotService.captureAll(Bukkit.getOnlinePlayers(), configManager.isAnticheatEnabled());
            if (configManager.isAnticheatEnabled()) aimTrackingService.tick(Bukkit.getOnlinePlayers());
            for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                transactionManager.tick(online);
                clientWorldListener.tick(online);
            }
        }, 1L, 1L);

        Bukkit.getScheduler().runTaskTimer(this, diagnostics::sample, 5L, 5L);

        // Schedulers: Live Spectate Overlay (every 2 ticks = 100ms)
        Bukkit.getScheduler().runTaskTimer(this, spectateManager::tickOverlay, 2L, 2L);

        // Schedulers: /vesuvio debug live telemetry overlay (every 2 ticks = 100ms)
        Bukkit.getScheduler().runTaskTimer(this, debugOverlayManager::tick, 2L, 2L);

        // Schedulers: Lava Wave Execution
        if (configManager.isWavePunishmentEnabled()) {
            long waveTicks = configManager.getWaveIntervalMinutes() * 60L * 20L;
            Bukkit.getScheduler().runTaskTimer(this, waveManager::executeWave, waveTicks, waveTicks);
        }

        // Schedulers: Passive VL Decay
        long decayTicks = (long) (configManager.getVlDecaySeconds() * 20.0);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            userDataManager.performDecay(configManager.getVlDecayAmount());
        }, decayTicks, decayTicks);

        // Schedulers: Automatic self-learning dataset collection (legit samples from trusted
        // players) - runs every minute, AutoDatasetCollector internally rate-limits per player
        // against the configured interval. Cheat samples are collected inline on ban instead.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (configManager.isAnticheatEnabled()) selfLearningManager.getAutoDatasetCollector().sweepLegitSamples(userDataManager);
        }, 20L * 60L, 20L * 60L);

        // Schedulers: fully-automatic ONNX retraining (extracts the bundled Python script and,
        // if enabled, schedules the periodic retrain cycle - see ModelAutoTrainer).
        modelAutoTrainer.start();

        long elapsed = System.currentTimeMillis() - startMs;
        getLogger().info(String.format("Vesuvio 26.2 (автор: Lovelace) запущен за %d мс. Трёхуровневая система проверок активна.", elapsed));
        getLogger().info("[Диагностика] Пороги проверки задержки движения: "
                + net.lovelace.vesuvio.check.movement.BlinkCheck.fromConfig(configManager).describeThresholds());
    }

    private void initModels() {
        Path modelsDir = getDataFolder().toPath().resolve("models");
        try {
            Files.createDirectories(modelsDir);
        } catch (Exception ignored) {}

        // Unpack bundled pre-trained ONNX models from JAR if not present
        extractModelResource("click_model.onnx", modelsDir);
        extractModelResource("aim_model.onnx", modelsDir);

        // Click Model
        mlManager.registerModel(new ModelConfig("click_model", true, "models/click_model.onnx", 0.85, 1.5, "float_input"));
        Path clickPath = modelsDir.resolve("click_model.onnx");
        if (Files.exists(clickPath)) {
            try {
                mlManager.loadModel("click_model", clickPath);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Не удалось загрузить click_model.onnx, используется резервная эвристика", e);
            }
        }

        // Aim Model
        mlManager.registerModel(new ModelConfig("aim_model", true, "models/aim_model.onnx", 0.82, 1.4, "float_input"));
        Path aimPath = modelsDir.resolve("aim_model.onnx");
        if (Files.exists(aimPath)) {
            try {
                mlManager.loadModel("aim_model", aimPath);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Не удалось загрузить aim_model.onnx, используется резервная эвристика", e);
            }
        }

        // Note: there is deliberately no "reach_model"/"scaffold_model" ONNX registration here.
        // Nobody has trained those models yet, and evaluateAsync() is only ever called with
        // "click_model"/"aim_model" by name - a registered-but-never-loaded entry would just be
        // a config option that silently does nothing. Reach and Scaffold are covered today by
        // StatisticalReachCheck and the Scaffold heuristic in WorldInteractionListener instead.
        // Drop real .onnx files into models/ and register them here (see click/aim above) once
        // trained models exist.
    }

    private void extractModelResource(String modelName, Path modelsDir) {
        Path target = modelsDir.resolve(modelName);
        if (!Files.exists(target)) {
            try (java.io.InputStream in = getResource("models/" + modelName)) {
                if (in != null) {
                    Files.copy(in, target);
                    getLogger().info("Извлечена предобученная модель ONNX: " + modelName);
                }
            } catch (Exception e) {
                getLogger().warning("Не удалось извлечь встроенную модель " + modelName + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Остановка Vesuvio 26.2...");
        if (configManager != null) configManager.setAnticheatEnabled(false);

        // Unregister Public API & Expansion
        if (papiExpansion != null) {
            try {
                papiExpansion.unregister();
            } catch (Throwable ignored) {}
        }
        VesuvioProvider.unregister();

        // Stop Web Server
        if (apiServer != null) {
            apiServer.close();
        }

        // Clean up spectating staff
        if (spectateManager != null) {
            spectateManager.cleanup();
        }
        if (debugOverlayManager != null) {
            debugOverlayManager.cleanup();
        }

        // Stop scheduled/manual training and terminate its owned subprocess.
        if (modelAutoTrainer != null) {
            modelAutoTrainer.stop();
        }

        // Players are not kicked before plugins disable, so their quit events never ran: persist
        // everyone synchronously while the pool is still open. Stopping the executor below also
        // drains any async quit saves already queued.
        if (databaseManager != null && userDataManager != null) {
            for (net.lovelace.vesuvio.data.UserData u : userDataManager.getAllUsers()) {
                try {
                    databaseManager.savePlayerSync(u.getUuid(), u.getUsername(), u.getTrustScore(),
                            u.getRiskIndex(), u.getClientBrand(), u.isManualSuspect());
                } catch (Throwable t) {
                    getLogger().warning("Не удалось сохранить игрока " + u.getUsername() + ": " + t.getMessage());
                }
            }
        }

        // Drain workers before closing the resources they use.
        net.lovelace.vesuvio.engine.ManagedExecutor.stop(virtualExecutor);
        if (mlManager != null) mlManager.stopTasks();
        if (diagnostics != null) diagnostics.close();

        // Flush and close the persistent dataset writer
        if (selfLearningManager != null) {
            selfLearningManager.close();
        }

        // Flush and close Database connection pool
        if (databaseManager != null) {
            databaseManager.close();
        }

        // Close ONNX runtime sessions
        if (mlManager != null) {
            mlManager.close();
        }

        // Drop per-player latency and snapshot state
        if (transactionManager != null) {
            transactionManager.clear();
        }
        if (environmentSnapshotService != null) {
            environmentSnapshotService.clear();
        }

        // Terminate PacketEvents
        try {
            PacketEvents.getAPI().terminate();
        } catch (Throwable ignored) {}

        getLogger().info("Vesuvio 26.2 остановлен.");
    }

    public net.lovelace.vesuvio.config.PresetManager getPresetManager() {
        return presetManager;
    }
}
