package net.lovelace.vesuvio;

import net.lovelace.vesuvio.integration.essential.LoveEssentialHook;
import net.lovelace.vesuvio.integration.essential.MechanicsState;
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
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.lovelace.vesuvio.check.movement.BlinkCheck;
import net.lovelace.vesuvio.check.onnx.ModelAutoTrainer;
import net.lovelace.vesuvio.config.PresetManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.diagnostics.DiagnosticService;
import net.lovelace.vesuvio.engine.AimTrackingService;
import net.lovelace.vesuvio.engine.EnvironmentSnapshotService;
import net.lovelace.vesuvio.engine.HitboxHistoryTracker;
import net.lovelace.vesuvio.engine.LagCompensator;
import net.lovelace.vesuvio.engine.ManagedExecutor;
import net.lovelace.vesuvio.engine.NpcTrapManager;
import net.lovelace.vesuvio.engine.ServerHitch;
import net.lovelace.vesuvio.engine.StateJanitor;
import net.lovelace.vesuvio.engine.TpsCache;
import net.lovelace.vesuvio.engine.TransactionManager;
import net.lovelace.vesuvio.evasion.BanEvasionManager;
import net.lovelace.vesuvio.integration.hunt.LoveHuntHook;
import net.lovelace.vesuvio.integration.papi.VesuvioExpansion;
import net.lovelace.vesuvio.listener.PhysicsExemptionListener;
import net.lovelace.vesuvio.listener.WorldInteractionListener;
import net.lovelace.vesuvio.packet.ClientWorldPacketListener;
import net.lovelace.vesuvio.packet.MovementPacketListener;
import net.lovelace.vesuvio.packet.TransactionPacketListener;
import net.lovelace.vesuvio.punishment.PunishmentWaveManager;
import net.lovelace.vesuvio.staff.DebugOverlayManager;
import net.lovelace.vesuvio.staff.DiscordWebhookService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

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
    private TransactionManager transactionManager;
    private EnvironmentSnapshotService environmentSnapshotService;
    private LoveEssentialHook loveEssentialHook;
    private AimTrackingService aimTrackingService;
    private MLManager mlManager;
    private SelfLearningManager selfLearningManager;
    private ModelAutoTrainer modelAutoTrainer;
    private SmartAlertService alertService;
    private SpectateManager spectateManager;
    private DiscordWebhookService discordWebhookService;
    private DebugOverlayManager debugOverlayManager;
    private PresetManager presetManager;
    private CheckPipeline checkPipeline;
    private DiagnosticService diagnostics;
    private ExecutorService virtualExecutor;
    private LoveHuntHook loveHuntHook;
    private VesuvioExpansion papiExpansion;
    private LagCompensator lagCompensator;
    private PunishmentWaveManager waveManager;
    private HitboxHistoryTracker hitboxTracker;
    private BanEvasionManager banEvasionManager;
    private NpcTrapManager npcTrapManager;
    private ClientWorldPacketListener clientWorldListener;
    private WorldInteractionListener worldInteractionListener;
    private BrandPacketListener brandListener;

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

        initStorage();
        initDetection();
        registerListeners();
        registerCommandsAndIntegrations();
        startTasks();

        long elapsed = System.currentTimeMillis() - startMs;
        getLogger().info(String.format("Vesuvio 26.2 (автор: Lovelace) запущен за %d мс. Трёхуровневая система проверок активна.", elapsed));
        getLogger().info("[Диагностика] Пороги проверки задержки движения: "
                + BlinkCheck.fromConfig(configManager).describeThresholds());
    }

    /** Configuration, worker pool, presets, database and the per-player data registry. */
    private void initStorage() {
        ClassLoader pluginClassLoader = getClass().getClassLoader();
        this.configManager = new ConfigManager(this);
        this.virtualExecutor = new ManagedExecutor(
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
        this.presetManager = new PresetManager(this, configManager);
        this.databaseManager = new DatabaseManager(configManager, getDataFolder().toPath());
        this.userDataManager = new UserDataManager();
    }

    /** ML models, self-learning, staff services, latency and snapshot services, and the check pipeline. */
    private void initDetection() {
        // 4. ML Manager & Model Registration
        this.mlManager = new MLManager(configManager.useVirtualThreads(), configManager.getInferenceParallelism());
        initModels();

        // 5. Self-Learning Layer
        this.selfLearningManager = new SelfLearningManager(getDataFolder().toPath(), configManager);
        this.mlManager.bindTrainingRevision(selfLearningManager.getDatasetManager()::trainingRevision);

        // 5b. Fully-automatic ONNX retraining from the live dataset (see ModelAutoTrainer).
        // Extracted/started after models are registered below so it can hot-reload into an
        // MLManager that already knows about click_model/aim_model.
        this.modelAutoTrainer = new ModelAutoTrainer(
                configManager,
                selfLearningManager.getDatasetManager(),
                mlManager,
                getDataFolder().toPath(),
                this::getResource
        );

        // 6. Staff Services, Live Overlay & Spartan Enhancements
        this.alertService = new SmartAlertService(configManager);
        this.spectateManager = new SpectateManager(userDataManager);
        this.debugOverlayManager = new DebugOverlayManager(userDataManager);
        // Transaction-based latency: the unspoofable ping source every check's lag tolerance is
        // scaled by, and the acknowledgement clock the Velocity check waits on.
        this.transactionManager = new TransactionManager();
        // Main-thread world/player snapshots, so the movement checks running on virtual threads
        // never touch the Bukkit API themselves.
        this.environmentSnapshotService = new EnvironmentSnapshotService(userDataManager);
        // Per-tick target-relative aim capture, feeding StrafeReversalCheck.
        this.loveEssentialHook =
                new LoveEssentialHook(
                        this, userDataManager, transactionManager, configManager);
        loveEssentialHook.start();
        environmentSnapshotService.mechanics(loveEssentialHook);
        // Per-tick target-relative aim capture, feeding StrafeReversalCheck.
        this.aimTrackingService = new AimTrackingService(userDataManager);
        this.lagCompensator = new LagCompensator(configManager, transactionManager);
        this.waveManager = new PunishmentWaveManager(this, configManager, databaseManager);
        waveManager.start();
        this.discordWebhookService = new DiscordWebhookService(configManager, virtualExecutor);
        this.hitboxTracker = new HitboxHistoryTracker();
        loveEssentialHook.invalidateGeometry(hitboxTracker::remove);
        this.banEvasionManager = new BanEvasionManager(databaseManager);
        this.npcTrapManager = new NpcTrapManager(this, configManager);

        this.diagnostics = new DiagnosticService(this, configManager, userDataManager, transactionManager);

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
                discordWebhookService,
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
    }

    /** PacketEvents and Bukkit listeners. */
    private void registerListeners() {
        // 8. Register Packet Listeners
        this.brandListener = new BrandPacketListener(userDataManager, configManager);
        PacketEvents.getAPI().getEventManager().registerListener(
                new ClickPacketListener(userDataManager, checkPipeline, virtualExecutor)
        );
        PacketEvents.getAPI().getEventManager().registerListener(
                new AimPacketListener(userDataManager, checkPipeline, virtualExecutor)
        );
        PacketEvents.getAPI().getEventManager().registerListener(
                new MovementPacketListener(userDataManager, checkPipeline, virtualExecutor)
        );
        PacketEvents.getAPI().getEventManager().registerListener(
                new TransactionPacketListener(userDataManager, transactionManager, configManager)
        );
        PacketEvents.getAPI().getEventManager().registerListener(brandListener);
        this.clientWorldListener = new ClientWorldPacketListener(userDataManager, configManager);
        PacketEvents.getAPI().getEventManager().registerListener(clientWorldListener);

        // 9. Register Bukkit Events
        this.worldInteractionListener = new WorldInteractionListener(userDataManager, checkPipeline, configManager);
        Bukkit.getPluginManager().registerEvents(
                new PlayerLifecycleListener(userDataManager, databaseManager, spectateManager, brandListener, lagCompensator, hitboxTracker, transactionManager, environmentSnapshotService, worldInteractionListener, selfLearningManager, banEvasionManager, checkPipeline, configManager, virtualExecutor),
                this
        );
        Bukkit.getPluginManager().registerEvents(diagnostics, this);
        Bukkit.getPluginManager().registerEvents(worldInteractionListener, this);
        checkPipeline.targetMechanics(
                id -> {
                    var data = userDataManager.get(id);
                    return data == null
                            ? MechanicsState.NONE
                            : data.mechanics();
                });
        Bukkit.getPluginManager().registerEvents(environmentSnapshotService, this);
        Bukkit.getPluginManager().registerEvents(
                new PhysicsExemptionListener(userDataManager, configManager),
                this
        );
    }

    /** The /vesuvio command, the public API and optional integrations. */
    private void registerCommandsAndIntegrations() {
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
        this.loveHuntHook = new LoveHuntHook(this);
        waveManager.setLoveHuntHook(loveHuntHook);

        VesuvioAPIImpl apiImpl = new VesuvioAPIImpl(userDataManager, waveManager, configManager.getHighRiskThreshold(),
                databaseManager, mlManager, selfLearningManager, configManager);
        apiImpl.configureManagement(virtualExecutor,getDataFolder().toPath().resolve("models"));
        VesuvioProvider.register(apiImpl);
        try {
            getServer().getServicesManager().register(VesuvioAPI.class, apiImpl, this, ServicePriority.Normal);
        } catch (Throwable ignored) {}

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.papiExpansion = new VesuvioExpansion(this);
            papiExpansion.register();
            getLogger().info("Подключена интеграция с PlaceholderAPI (VesuvioExpansion зарегистрирован).");
        }
    }

    /** Every scheduler: janitor, per-tick capture, decay, waves, dataset collection, retraining. */
    private void startTasks() {
        // State janitor (every 30 s, main thread): per-player state of everybody who is offline is dropped. See StateJanitor.
        final var janitor = new StateJanitor(
                () -> {
                    Set<UUID> ids = new HashSet<>();
                    for (Player p : Bukkit.getOnlinePlayers()) ids.add(p.getUniqueId());
                    return ids;
                },
                List.of(
                        userDataManager::retainOnline,
                        lagCompensator::retainOnline,
                        transactionManager::retainOnline,
                        hitboxTracker::retainOnline,
                        environmentSnapshotService::retainOnline,
                        worldInteractionListener::retainOnline,
                        aimTrackingService::retainOnline,
                        npcTrapManager::retainOnline,
                        ids -> { if (selfLearningManager != null) selfLearningManager.getAutoDatasetCollector().retainOnline(ids); }));
        Bukkit.getScheduler().runTaskTimer(this, janitor, 600L, 600L);

        // 13. Schedulers: Hitbox History Bounding Box Capture (1 tick = 50ms)
        Bukkit.getScheduler().runTaskTimer(this, hitboxTracker::recordTick, 1L, 1L);

        // Schedulers: main-thread environment snapshot + transaction ping (1 tick = 50ms).
        // These two must run on the main thread: the snapshot is the only place allowed to read
        // the world on behalf of the async movement checks, and the transaction is the clock they
        // measure latency against.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                TpsCache.update(Bukkit.getTPS());
            } catch (Throwable ignored) {}
            ServerHitch.tick(System.currentTimeMillis());
            environmentSnapshotService.captureAll(Bukkit.getOnlinePlayers(), configManager.isAnticheatEnabled());
            if (configManager.isAnticheatEnabled()) aimTrackingService.tick(Bukkit.getOnlinePlayers());
            for (Player online : Bukkit.getOnlinePlayers()) {
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
            userDataManager.performDecay(configManager.getVlDecayAmount(),
                    (long) (configManager.getRiskCleanSeconds() * 1000.0), configManager.getRiskDecayPerMinute(),
                    configManager.getRiskFloor(), configManager.getTrustDecayCleanHours());
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
            try (InputStream in = getResource("models/" + modelName)) {
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
        if (loveEssentialHook != null) loveEssentialHook.close();

        // Unregister Public API & Expansion
        if (papiExpansion != null) {
            try {
                papiExpansion.unregister();
            } catch (Throwable ignored) {}
        }
        VesuvioProvider.unregister();

        if (discordWebhookService != null) discordWebhookService.shutdown();

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
            for (UserData u : userDataManager.getAllUsers()) {
                try {
                    databaseManager.savePlayerSync(u.getUuid(), u.getUsername(), u.getTrustScore(),
                            u.getRiskIndex(), u.getClientBrand(), u.isManualSuspect());
                } catch (Throwable t) {
                    getLogger().warning("Не удалось сохранить игрока " + u.getUsername() + ": " + t.getMessage());
                }
            }
        }

        // Drain workers before closing the resources they use.
        ManagedExecutor.stop(virtualExecutor);
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

    public PresetManager getPresetManager() {
        return presetManager;
    }
}
