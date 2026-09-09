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
import java.util.concurrent.Executors;
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
    private MLManager mlManager;
    private SelfLearningManager selfLearningManager;
    private net.lovelace.vesuvio.check.onnx.ModelAutoTrainer modelAutoTrainer;
    private SmartAlertService alertService;
    private SpectateManager spectateManager;
    private net.lovelace.vesuvio.staff.DebugOverlayManager debugOverlayManager;
    private net.lovelace.vesuvio.config.PresetManager presetManager;
    private CheckPipeline checkPipeline;
    private ApiServer apiServer;
    private ExecutorService virtualExecutor;
    private net.lovelace.vesuvio.integration.hunt.LoveHuntHook loveHuntHook;
    private net.lovelace.vesuvio.integration.papi.VesuvioExpansion papiExpansion;

    @Override
    public void onLoad() {
        // Initialize PacketEvents Spigot / Paper platform
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        long startMs = System.currentTimeMillis();

        // 1. PacketEvents initialization
        PacketEvents.getAPI().init();

        // 2. Virtual Threads Executor with PluginClassLoader awareness (Java 21+)
        ClassLoader pluginClassLoader = getClass().getClassLoader();
        Thread.Builder.OfVirtual virtualBuilder = Thread.ofVirtual().name("Vesuvio-Worker-", 0);
        this.virtualExecutor = Executors.newThreadPerTaskExecutor(task -> {
            Thread t = virtualBuilder.unstarted(task);
            t.setContextClassLoader(pluginClassLoader);
            return t;
        });

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
        this.configManager = new ConfigManager(this);
        this.presetManager = new net.lovelace.vesuvio.config.PresetManager(this, configManager);
        this.databaseManager = new DatabaseManager(configManager, getDataFolder().toPath());
        this.userDataManager = new UserDataManager();

        // 4. ML Manager & Model Registration
        this.mlManager = new MLManager();
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
        var lagCompensator = new net.lovelace.vesuvio.engine.LagCompensator(configManager, transactionManager);
        var waveManager = new net.lovelace.vesuvio.punishment.PunishmentWaveManager(this, configManager, databaseManager);
        var discordService = new net.lovelace.vesuvio.staff.DiscordWebhookService(configManager, virtualExecutor);
        var hitboxTracker = new net.lovelace.vesuvio.engine.HitboxHistoryTracker();
        var banEvasionManager = new net.lovelace.vesuvio.evasion.BanEvasionManager(databaseManager);

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
                transactionManager
        );

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
                new net.lovelace.vesuvio.packet.TransactionPacketListener(userDataManager, transactionManager)
        );
        PacketEvents.getAPI().getEventManager().registerListener(brandListener);

        // 9. Register Bukkit Events
        var worldInteractionListener = new net.lovelace.vesuvio.listener.WorldInteractionListener(userDataManager, checkPipeline, configManager);
        Bukkit.getPluginManager().registerEvents(
                new PlayerLifecycleListener(userDataManager, databaseManager, spectateManager, brandListener, lagCompensator, hitboxTracker, transactionManager, environmentSnapshotService, worldInteractionListener, selfLearningManager, banEvasionManager, checkPipeline, configManager),
                this
        );
        Bukkit.getPluginManager().registerEvents(worldInteractionListener, this);

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
                modelAutoTrainer
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
            environmentSnapshotService.captureAll(Bukkit.getOnlinePlayers());
            for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                transactionManager.tick(online);
            }
        }, 1L, 1L);

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
            selfLearningManager.getAutoDatasetCollector().sweepLegitSamples(userDataManager);
        }, 20L * 60L, 20L * 60L);

        // Schedulers: fully-automatic ONNX retraining (extracts the bundled Python script and,
        // if enabled, schedules the periodic retrain cycle - see ModelAutoTrainer).
        modelAutoTrainer.start();

        long elapsed = System.currentTimeMillis() - startMs;
        getLogger().info(String.format("Vesuvio 26.2 (Author: Lovelace) initialized in %dms. Hybrid 3-Layer Engine Active.", elapsed));
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
                getLogger().log(Level.WARNING, "Failed to load click_model.onnx, using neural fallback", e);
            }
        }

        // Aim Model
        mlManager.registerModel(new ModelConfig("aim_model", true, "models/aim_model.onnx", 0.82, 1.4, "float_input"));
        Path aimPath = modelsDir.resolve("aim_model.onnx");
        if (Files.exists(aimPath)) {
            try {
                mlManager.loadModel("aim_model", aimPath);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to load aim_model.onnx, using neural fallback", e);
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
                    getLogger().info("Extracted pre-trained ONNX model: " + modelName);
                }
            } catch (Exception e) {
                getLogger().warning("Could not extract default model " + modelName + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Shutting down Vesuvio 26.2...");

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

        // Stop the automatic retrain scheduler (does not interrupt an in-flight training run's
        // subprocess timeout handling, just stops scheduling new ones)
        if (modelAutoTrainer != null) {
            modelAutoTrainer.stop();
        }

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

        // Shutdown Virtual Threads Executor
        if (virtualExecutor != null) {
            virtualExecutor.shutdown();
        }

        getLogger().info("Vesuvio 26.2 shutdown complete.");
    }

    public net.lovelace.vesuvio.config.PresetManager getPresetManager() {
        return presetManager;
    }
}
