package net.lovelace.vesuvio.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.*;

/**
 * Strongly-typed access to Vesuvio configuration settings.
 *
 * Author: Lovelace
 */
public final class ConfigManager {

    public static final String DEFAULT_WEB_BEARER_TOKEN = "vesuvio-secret-token-change-me";

    private final Plugin plugin;
    private FileConfiguration config;

    public record PunishmentRule(int vlThreshold, String action, String command) {}

    private final List<PunishmentRule> punishmentRules = new ArrayList<>();
    private final Set<String> suspiciousBrands = new HashSet<>();
    private final Map<String, Boolean> silentChecks = new HashMap<>();

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        punishmentRules.clear();
        var punishmentsSection = config.getConfigurationSection("punishments.thresholds");
        if (punishmentsSection != null) {
            for (String key : punishmentsSection.getKeys(false)) {
                try {
                    int vl = Integer.parseInt(key);
                    String action = punishmentsSection.getString(key + ".action", "log");
                    String cmd = punishmentsSection.getString(key + ".command", "");
                    punishmentRules.add(new PunishmentRule(vl, action, cmd));
                } catch (NumberFormatException ignored) {}
            }
            // Descending: evaluatePunishments() walks this list and fires the FIRST rule whose
            // threshold is met (with a non-blank command), then stops. With an ascending sort
            // that was always the lowest-severity rule (e.g. "kick" at VL 60), so a player who
            // blew straight past the "ban" threshold (VL 100) would only ever get kicked forever
            // - VL is never auto-reset after a punishment fires, so the loop hit the same low
            // threshold again next flag. Descending order makes the loop find the HIGHEST
            // (most severe) satisfied threshold instead, which is the correct escalation semantics.
            punishmentRules.sort(Comparator.comparingInt(PunishmentRule::vlThreshold).reversed());
        }

        suspiciousBrands.clear();
        suspiciousBrands.addAll(config.getStringList("mechanics.client-brand.suspicious-brands"));

        silentChecks.clear();
        var silentSection = config.getConfigurationSection("mechanics.silent-checks");
        if (silentSection != null) {
            for (String key : silentSection.getKeys(false)) {
                silentChecks.put(normalizeCheckName(key), silentSection.getBoolean(key, false));
            }
        }
    }

    /**
     * Normalizes a check name for silent-checks lookup: lowercase with separators stripped, so
     * a readable hyphenated config key (e.g. "click-statistical") matches the actual check name
     * used at runtime (CheckResult.flag's checkName, e.g. "ClickStatistical" has no separator at
     * all). Without this, isSilent() previously never matched any hyphenated config key.
     */
    private static String normalizeCheckName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public String getPrefix() {
        return config.getString("settings.prefix", "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> ");
    }

    public boolean isDebug() {
        return config.getBoolean("settings.debug", false);
    }

    // Layer 1
    public boolean isStatisticalEnabled() {
        return config.getBoolean("layers.statistical.enabled", true);
    }

    public double getStatisticalWeight() {
        return config.getDouble("layers.statistical.weight", 1.0);
    }

    public double getStatisticalCutoff() {
        return config.getDouble("layers.statistical.certainty-cutoff", 0.92);
    }

    // Layer 2
    public boolean isOnnxEnabled() {
        return config.getBoolean("layers.onnx.enabled", true);
    }

    public double getOnnxWeight() {
        return config.getDouble("layers.onnx.weight", 1.5);
    }

    public double getOnnxDefaultThreshold() {
        return config.getDouble("layers.onnx.default-threshold", 0.85);
    }

    public boolean isAutoRetrainEnabled() {
        return config.getBoolean("layers.onnx.auto-retrain.enabled", true);
    }

    public int getAutoRetrainIntervalHours() {
        return config.getInt("layers.onnx.auto-retrain.interval-hours", 24);
    }

    public int getAutoRetrainInitialDelayHours() {
        return config.getInt("layers.onnx.auto-retrain.initial-delay-hours", 2);
    }

    public int getAutoRetrainMinSamplesPerClass() {
        return config.getInt("layers.onnx.auto-retrain.min-samples-per-class", 150);
    }

    public int getAutoRetrainTimeoutMinutes() {
        return config.getInt("layers.onnx.auto-retrain.timeout-minutes", 15);
    }

    public String getAutoRetrainPythonExecutable() {
        return config.getString("layers.onnx.auto-retrain.python-executable", "python3");
    }

    // Layer 3
    public boolean isSelfLearningEnabled() {
        return config.getBoolean("layers.self-learning.enabled", true);
    }

    public double getSelfLearningWeight() {
        return config.getDouble("layers.self-learning.weight", 1.2);
    }

    public double getActiveLearningMinProb() {
        return config.getDouble("layers.self-learning.active-learning.min-probability", 0.55);
    }

    public double getActiveLearningMaxProb() {
        return config.getDouble("layers.self-learning.active-learning.max-probability", 0.78);
    }

    public float getAnomalySimilarityThreshold() {
        return (float) config.getDouble("layers.self-learning.anomaly-memory.similarity-threshold", 0.78);
    }

    public double getAnomalyRiskBoost() {
        return config.getDouble("layers.self-learning.anomaly-memory.risk-boost-factor", 1.75);
    }

    public int getOnlineClassifierMinTrainedSamples() {
        return config.getInt("layers.self-learning.online-classifier.min-trained-samples-to-flag", 40);
    }

    public double getOnlineClassifierFlagThreshold() {
        return config.getDouble("layers.self-learning.online-classifier.flag-threshold", 0.90);
    }

    public double getOnlineClassifierSilentRiskThreshold() {
        return config.getDouble("layers.self-learning.online-classifier.silent-risk-threshold", 0.65);
    }

    public boolean isAutoCollectionEnabled() {
        return config.getBoolean("layers.self-learning.auto-collection.enabled", true);
    }

    public double getAutoCollectLegitMinTrust() {
        return config.getDouble("layers.self-learning.auto-collection.legit-min-trust", 85.0);
    }

    public double getAutoCollectLegitMaxRisk() {
        return config.getDouble("layers.self-learning.auto-collection.legit-max-risk", 15.0);
    }

    public int getAutoCollectLegitIntervalMinutes() {
        return config.getInt("layers.self-learning.auto-collection.legit-interval-minutes", 15);
    }

    public boolean isAutoCollectCheatOnBan() {
        return config.getBoolean("layers.self-learning.auto-collection.cheat-on-ban", true);
    }

    public int getMaxDatasetSize() {
        return config.getInt("layers.self-learning.auto-collection.max-dataset-size", 50000);
    }

    // Mechanics
    public boolean isFingerprintingEnabled() {
        return config.getBoolean("mechanics.click-fingerprinting.enabled", true);
    }

    public double getFingerprintDeviationThreshold() {
        return config.getDouble("mechanics.click-fingerprinting.max-deviation", 0.65);
    }

    public double getFingerprintRiskPenalty() {
        return config.getDouble("mechanics.click-fingerprinting.risk-penalty", 25.0);
    }

    public boolean isTemporalConsistencyEnabled() {
        return config.getBoolean("mechanics.temporal-consistency.enabled", true);
    }

    public boolean isLagCompensationEnabled() {
        return config.getBoolean("mechanics.lag-compensation.enabled", true);
    }

    public double getMinServerTps() {
        return config.getDouble("mechanics.lag-compensation.min-tps", 18.0);
    }

    public int getMaxPingSpikeMs() {
        return config.getInt("mechanics.lag-compensation.max-ping-spike-ms", 120);
    }

    public boolean isGcdAimEnabled() {
        return config.getBoolean("mechanics.gcd-aim.enabled", true);
    }

    public double getGcdAimMinRotation() {
        // Default matches GCDAimCheck's tuned MIN_ROTATION - lower catches smaller/subtler
        // aimbot rotations, at the cost of a bit more analysis noise on tiny mouse movements.
        return config.getDouble("mechanics.gcd-aim.min-rotation", 0.3);
    }

    public boolean isBadPacketsEnabled() {
        return config.getBoolean("mechanics.bad-packets.enabled", true);
    }

    public boolean isBadPacketsNoSwing() {
        return config.getBoolean("mechanics.bad-packets.no-swing", true);
    }

    public boolean isBadPacketsPitchBounds() {
        return config.getBoolean("mechanics.bad-packets.pitch-bounds", true);
    }

    public boolean isBadPacketsInventoryAttack() {
        return config.getBoolean("mechanics.bad-packets.inventory-attack", true);
    }

    public boolean isReachEnabled() {
        return config.getBoolean("mechanics.reach.enabled", true);
    }

    public double getMaxReach() {
        return config.getDouble("mechanics.reach.max-reach", 3.05);
    }

    public boolean isFlyEnabled() {
        return config.getBoolean("mechanics.movement.fly.enabled", true);
    }

    public boolean isSpeedEnabled() {
        return config.getBoolean("mechanics.movement.speed.enabled", true);
    }

    public boolean isNoFallEnabled() {
        return config.getBoolean("mechanics.movement.nofall.enabled", true);
    }

    public boolean isTimerEnabled() {
        return config.getBoolean("mechanics.movement.timer.enabled", true);
    }

    public boolean isStepEnabled() {
        return config.getBoolean("mechanics.movement.step.enabled", true);
    }

    public boolean isInvMoveEnabled() {
        return config.getBoolean("mechanics.movement.invmove.enabled", true);
    }

    public boolean isPhaseEnabled() {
        return config.getBoolean("mechanics.movement.phase.enabled", true);
    }

    /**
     * Disabled by default (2026-09-12): the "movement stream silent, transactions healthy"
     * signature this check looks for is also produced by legitimate client-side stalls it cannot
     * distinguish itself from - a GC pause, window focus loss throttling FPS (and, with it, the
     * client's own tick/packet rate), or opening an inventory/chat/F3 screen - since movement
     * packets are only produced by the client's main game loop while transactions are answered by
     * the Netty thread almost independently of it. Vesuvio also has no setback/position-correction
     * path (see the teleport-exemption branch in CheckPipeline#processMovement and TimerCheck's own
     * gap reset), so a real blink's "silence then one big catch-up jump" is exempted rather than
     * flagged by Speed/Phase/Timer either - this check was the only thing actually looking at the
     * silence itself. Left configurable rather than removed so it can be re-enabled if the false
     * positive causes above are ever more precisely excluded.
     */
    public boolean isBlinkEnabled() {
        return config.getBoolean("mechanics.movement.blink.enabled", false);
    }

    public boolean isVelocityEnabled() {
        return config.getBoolean("mechanics.movement.velocity.enabled", true);
    }

    public boolean isElytraEnabled() {
        return config.getBoolean("mechanics.movement.elytra.enabled", true);
    }

    public boolean isRiptideExemptionEnabled() {
        return config.getBoolean("mechanics.movement.riptide-exemption.enabled", true);
    }

    public boolean isPistonExemptionEnabled() {
        return config.getBoolean("mechanics.movement.piston-exemption.enabled", true);
    }

    public double getPistonExemptionRadius() {
        return config.getDouble("mechanics.movement.piston-exemption.radius", 1.5);
    }

    /**
     * Whether the latency compensator should prefer transaction round-trip times over Bukkit's
     * KeepAlive-derived ping. Configurable only so an operator can fall back if a proxy or
     * protocol-rewriting plugin interferes with the transaction packets.
     */
    public boolean isTransactionLatencyEnabled() {
        return config.getBoolean("mechanics.latency.transactions.enabled", true);
    }

    /** Unanswered-transaction age (ms) past which a client is treated as withholding acks. */
    public double getTransactionStallMs() {
        return config.getDouble("mechanics.latency.transactions.stall-ms", 1200.0);
    }

    public boolean isBanEvasionEnabled() {
        return config.getBoolean("mechanics.ban-evasion.enabled", true);
    }

    public boolean isBanEvasionIpCheckEnabled() {
        return config.getBoolean("mechanics.ban-evasion.ip-check", true);
    }

    public boolean isBanEvasionSignatureCheckEnabled() {
        return config.getBoolean("mechanics.ban-evasion.signature-check", true);
    }

    public float getBanEvasionSignatureThreshold() {
        return (float) config.getDouble("mechanics.ban-evasion.signature-threshold", 0.86);
    }

    public int getBanEvasionSignatureScanLimit() {
        return config.getInt("mechanics.ban-evasion.signature-scan-limit", 1000);
    }

    public double getBanEvasionIpMatchRisk() {
        return config.getDouble("mechanics.ban-evasion.ip-match-risk", 45.0);
    }

    public double getBanEvasionSignatureMatchRisk() {
        return config.getDouble("mechanics.ban-evasion.signature-match-risk", 25.0);
    }

    public boolean isKillauraAngleEnabled() {
        return config.getBoolean("mechanics.combat.angle.enabled", true);
    }

    public boolean isAutoCriticalsEnabled() {
        return config.getBoolean("mechanics.combat.autocriticals.enabled", true);
    }

    public boolean isBackTrackEnabled() {
        return config.getBoolean("mechanics.combat.backtrack.enabled", true);
    }

    public boolean isMoveDirectionEnabled() {
        return config.getBoolean("mechanics.combat.movedirection.enabled", true);
    }

    public boolean isAirPlaceEnabled() {
        return config.getBoolean("mechanics.world.airplace.enabled", true);
    }

    public boolean isScaffoldEnabled() {
        return config.getBoolean("mechanics.world.scaffold.enabled", true);
    }

    public boolean isFastBreakEnabled() {
        return config.getBoolean("mechanics.world.fastbreak.enabled", true);
    }

    public boolean isVehicleFlyEnabled() {
        return config.getBoolean("mechanics.world.vehiclefly.enabled", true);
    }

    public boolean isVehicleClipEnabled() {
        return config.getBoolean("mechanics.world.vehicleclip.enabled", true);
    }

    public boolean isXrayEnabled() {
        return config.getBoolean("mechanics.world.xray.enabled", true);
    }

    public boolean isTowerEnabled() {
        return config.getBoolean("mechanics.world.tower.enabled", true);
    }

    public boolean isFastPlaceEnabled() {
        return config.getBoolean("mechanics.world.fastplace.enabled", true);
    }

    public boolean isBlockReachEnabled() {
        return config.getBoolean("mechanics.world.blockreach.enabled", true);
    }

    public boolean isGhostHandEnabled() {
        return config.getBoolean("mechanics.world.ghosthand.enabled", true);
    }

    public boolean isFastEatEnabled() {
        return config.getBoolean("mechanics.item.fasteat.enabled", true);
    }

    public boolean isFastBowEnabled() {
        return config.getBoolean("mechanics.item.fastbow.enabled", true);
    }

    public boolean isWavePunishmentEnabled() {
        return config.getBoolean("punishments.wave.enabled", true);
    }

    public int getWaveIntervalMinutes() {
        return config.getInt("punishments.wave.interval-minutes", 60);
    }

    public boolean isWaveBroadcastEnabled() {
        return config.getBoolean("punishments.wave.broadcast-announcement", true);
    }

    public boolean isDiscordEnabled() {
        return config.getBoolean("staff.discord.enabled", false);
    }

    public String getDiscordWebhookUrl() {
        return config.getString("staff.discord.webhook-url", "");
    }

    public double getDiscordMinRisk() {
        return config.getDouble("staff.discord.min-risk-to-send", 70.0);
    }

    public boolean isSilent(String checkName) {
        if (checkName == null) return false;
        return silentChecks.getOrDefault(normalizeCheckName(checkName), false);
    }

    public boolean isBrandCheckEnabled() {
        return config.getBoolean("mechanics.client-brand.enabled", true);
    }

    public Set<String> getSuspiciousBrands() {
        return Collections.unmodifiableSet(suspiciousBrands);
    }

    // Scoring
    public double getInitialTrust() {
        return config.getDouble("scoring.trust.initial", 50.0);
    }

    public double getHighRiskThreshold() {
        return config.getDouble("scoring.risk.high-risk-threshold", 75.0);
    }

    public double getVlDecayAmount() {
        return config.getDouble("scoring.vl.decay-amount", 1.0);
    }

    public double getVlDecaySeconds() {
        return config.getDouble("scoring.vl.decay-seconds", 2.0);
    }

    // Web
    public boolean isWebEnabled() {
        return config.getBoolean("web.enabled", true);
    }

    public String getWebHost() {
        return config.getString("web.host", "0.0.0.0");
    }

    public int getWebPort() {
        return config.getInt("web.port", 8085);
    }

    public String getWebBearerToken() {
        return config.getString("web.bearer-token", DEFAULT_WEB_BEARER_TOKEN);
    }

    // Database
    public String getDatabaseType() {
        return config.getString("database.type", "sqlite");
    }

    public String getSqliteFileName() {
        return config.getString("database.sqlite.file", "vesuvio.db");
    }

    public String getPostgresHost() {
        return config.getString("database.postgresql.host", "localhost");
    }

    public int getPostgresPort() {
        return config.getInt("database.postgresql.port", 5432);
    }

    public String getPostgresDatabase() {
        return config.getString("database.postgresql.database", "vesuvio");
    }

    public String getPostgresUser() {
        return config.getString("database.postgresql.username", "postgres");
    }

    public String getPostgresPassword() {
        return config.getString("database.postgresql.password", "password");
    }

    public int getDatabasePoolSize() {
        return config.getInt("database.pool.maximum-pool-size", 8);
    }

    public List<PunishmentRule> getPunishmentRules() {
        return Collections.unmodifiableList(punishmentRules);
    }
}
