package net.lovelace.vesuvio.check.selflearning;

import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.FeatureSnapshotHistory;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.feature.AimFeatureExtractor;
import net.lovelace.vesuvio.feature.ClickFeatureExtractor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Fully automatic dataset collection and online training - no staff interaction required.
 *
 * Two label sources, both considered "ground truth" strong enough to train on directly:
 *  - CHEAT: a player's click/aim features are captured automatically the moment they cross the
 *    "ban" punishment threshold - by then the pipeline (statistical + ONNX + streak checks) is
 *    already confident, so this sample is a safe positive.
 *  - LEGIT: periodically sampled from players with high Trust, low Risk and zero current VL -
 *    a good proxy for "definitely not cheating right now".
 *
 * Every collected sample is (a) persisted into DatasetManager for later export/analysis and
 * (b) immediately used to train the matching in-process OnlineClassifier (click or aim; pure-Java
 * SGD logistic regression, see OnlineClassifier) - so the self-learning layer keeps adapting to
 * this specific server's playerbase and current cheat landscape without any manual step or
 * external ONNX export/import cycle.
 *
 * Class balance: bans are naturally much rarer than "currently trustworthy" sessions, so without
 * a guard the auto-collected dataset would skew overwhelmingly legit over time and the
 * classifier would learn "almost everything is legit" rather than a useful boundary. legitToCheatRatioCap
 * throttles further automatic LEGIT collection once legit samples outnumber cheat samples by
 * more than that ratio - CHEAT collection is never throttled.
 *
 * Author: Lovelace
 */
public final class AutoDatasetCollector {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-AutoLearn");
    private static final long LEGIT_COOLDOWN_MS = 60_000L; // hard floor, config interval also applies
    private static final double LEGIT_TO_CHEAT_RATIO_CAP = 3.0;

    /**
     * Legit samples collected unconditionally before the ratio cap starts applying at all.
     *
     * <p>Without this floor the cap made the dataset unusable on any server that had not banned
     * anyone yet: with zero cheat samples the old {@code max(1, cheatCount) * 3} evaluated to
     * three, so automatic legit collection stopped after three samples - far below the
     * {@code min-samples-per-class} needed to train anything, meaning retraining could never fire.
     * The floor is scaled off that same setting so the two cannot drift apart.
     */
    private int legitBootstrapFloor() {
        return Math.max(500, config.getAutoRetrainMinSamplesPerClass() * 3);
    }

    private final ConfigManager config;
    private final DatasetManager datasetManager;
    private final OnlineClassifier clickClassifier;
    private final OnlineClassifier aimClassifier;

    private final ConcurrentHashMap<UUID, Long> lastLegitCollection = new ConcurrentHashMap<>();
    private final AtomicInteger autoCheatCount = new AtomicInteger(0);
    private final AtomicInteger autoLegitCount = new AtomicInteger(0);

    public AutoDatasetCollector(ConfigManager config, DatasetManager datasetManager,
                                 OnlineClassifier clickClassifier, OnlineClassifier aimClassifier) {
        this.config = config;
        this.datasetManager = datasetManager;
        this.clickClassifier = clickClassifier;
        this.aimClassifier = aimClassifier;
    }

    /**
     * Call the moment a player's punishment crosses a "ban" rule. Captures their current
     * click and (if available) aim feature vectors as confirmed-cheat samples.
     */
    public void collectCheatSample(Player player, UserData data) {
        if (!config.isAutoCollectionEnabled() || !config.isAutoCollectCheatOnBan()) return;

        long now = System.currentTimeMillis();
        DatasetManager.SampleContext context = contextOf(player, data);
        boolean collected = false;

        if (data.getClickBuffer().getCount() >= 16) {
            float[] features = ClickFeatureExtractor.extract(data.getClickBuffer(), data.getEarlyCombatMean()).clone();
            datasetManager.addSample(new DatasetManager.LabeledSample(
                    player.getUniqueId(), player.getName(), features, 1,
                    now, "AutoCollector(ban)", "click", DatasetManager.LabelSource.BAN, context
            ));
            clickClassifier.train(features, 1);
            collected = true;
        }
        if (data.getAimBuffer().getCount() >= 16) {
            float[] aimFeatures = AimFeatureExtractor.extract(data.getAimBuffer()).clone();
            datasetManager.addSample(new DatasetManager.LabeledSample(
                    player.getUniqueId(), player.getName(), aimFeatures, 1,
                    now, "AutoCollector(ban)", "aim", DatasetManager.LabelSource.BAN, context
            ));
            aimClassifier.train(aimFeatures, 1);
            collected = true;
        }

        int historySamples = harvestHistory(player, data, 1, DatasetManager.LabelSource.BAN, "AutoCollector(ban)", context, now);

        if (collected || historySamples > 0) {
            autoCheatCount.incrementAndGet();
            LOGGER.info(String.format(
                    "[Vesuvio AutoLearn] Auto-collected CHEAT sample(s) for %s on ban, plus %d earlier session window(s) "
                            + "(click classifier: %d samples, aim classifier: %d samples)",
                    player.getName(), historySamples,
                    clickClassifier.getTrainedSamplesCount(), aimClassifier.getTrainedSamplesCount()));
        }
    }

    /**
     * Call when a player hit a per-player packet trap entity (see engine.NpcTrapManager).
     *
     * <p>This is the only cheat label in the system that is <em>not</em> self-confirming. A ban
     * label records that the pipeline's own thresholds fired, so training on it mostly teaches the
     * models to reproduce their current opinion - including its mistakes. A trap hit is decided by
     * something the pipeline's statistics had no part in: a legitimate client never renders the
     * entity and cannot swing at it, so the label carries information the models did not already
     * have. Collected unconditionally, and never throttled.
     */
    public void collectTrapSample(Player player, UserData data) {
        if (!config.isAutoCollectionEnabled()) return;

        long now = System.currentTimeMillis();
        DatasetManager.SampleContext context = contextOf(player, data);
        boolean collected = false;

        if (data.getClickBuffer().getCount() >= 16) {
            float[] features = ClickFeatureExtractor.extract(data.getClickBuffer(), data.getEarlyCombatMean()).clone();
            datasetManager.addSample(new DatasetManager.LabeledSample(
                    player.getUniqueId(), player.getName(), features, 1,
                    now, "NpcTrap", "click", DatasetManager.LabelSource.TRAP, context
            ));
            clickClassifier.train(features, 1);
            collected = true;
        }
        if (data.getAimBuffer().getCount() >= 16) {
            float[] aimFeatures = AimFeatureExtractor.extract(data.getAimBuffer()).clone();
            datasetManager.addSample(new DatasetManager.LabeledSample(
                    player.getUniqueId(), player.getName(), aimFeatures, 1,
                    now, "NpcTrap", "aim", DatasetManager.LabelSource.TRAP, context
            ));
            aimClassifier.train(aimFeatures, 1);
            collected = true;
        }

        // A trap hit is the strongest label the system can produce, so it is also the label most
        // worth spending several windows on rather than one.
        int historySamples = harvestHistory(player, data, 1, DatasetManager.LabelSource.TRAP, "NpcTrap", context, now);

        if (collected || historySamples > 0) {
            autoCheatCount.incrementAndGet();
            LOGGER.info(String.format(
                    "[Vesuvio AutoLearn] Auto-collected conclusive TRAP sample(s) for %s, plus %d earlier session window(s)",
                    player.getName(), historySamples));
        }
    }

    /**
     * Emits the player's buffered earlier feature windows under the same confirmed label.
     *
     * <p>Every window is stamped with the same player UUID, which is what keeps this honest: the
     * training script groups by player when it splits train/test, so extra windows from one
     * session can never appear on both sides of that split and inflate the reported metrics.
     * They add variety within the session, not apparent volume.
     *
     * @return how many extra samples were recorded
     */
    private int harvestHistory(Player player, UserData data, int label, DatasetManager.LabelSource source,
                               String reviewer, DatasetManager.SampleContext context, long now) {
        if (!config.isAutoCollectHistoryEnabled()) return 0;

        long maxAgeMs = Math.max(0L, config.getAutoCollectHistoryMaxAgeMinutes() * 60_000L);
        int limit = Math.max(0, config.getAutoCollectHistoryMaxSamples());
        if (limit == 0 || maxAgeMs == 0L) return 0;

        List<FeatureSnapshotHistory.Snapshot> snapshots = data.getFeatureHistory().recent(now, maxAgeMs, limit);
        int recorded = 0;
        for (FeatureSnapshotHistory.Snapshot snapshot : snapshots) {
            long ageSeconds = Math.max(0L, (now - snapshot.timestampMillis()) / 1000L);
            String stamp = reviewer + "@t-" + ageSeconds + "s";

            if (snapshot.click() != null) {
                datasetManager.addSample(new DatasetManager.LabeledSample(
                        player.getUniqueId(), player.getName(), snapshot.click(), label,
                        snapshot.timestampMillis(), stamp, "click", source, context));
                clickClassifier.train(snapshot.click(), label);
                recorded++;
            }
            if (snapshot.aim() != null) {
                datasetManager.addSample(new DatasetManager.LabeledSample(
                        player.getUniqueId(), player.getName(), snapshot.aim(), label,
                        snapshot.timestampMillis(), stamp, "aim", source, context));
                aimClassifier.train(snapshot.aim(), label);
                recorded++;
            }
        }
        // Harvested once - a second punishment in the same session must not re-emit the same rows.
        data.getFeatureHistory().clear();
        return recorded;
    }

    /**
     * Capture conditions for a sample. Ping comes from the transaction-measured value where the
     * pipeline has one (it cannot be spoofed the way Player#getPing() can), TPS from the server.
     */
    private DatasetManager.SampleContext contextOf(Player player, UserData data) {
        double tps;
        try {
            double[] recent = Bukkit.getServer().getTPS();
            tps = recent.length > 0 ? recent[0] : -1.0;
        } catch (Throwable t) {
            // Not all server implementations expose getTPS(); context is diagnostic, never required.
            tps = -1.0;
        }
        int ping = Math.max(-1, player.getPing());
        String brand = data.getClientBrand();
        return new DatasetManager.SampleContext(ping, tps, brand == null ? "" : brand);
    }

    /**
     * Periodic sweep (called every tick-scheduled interval from Vesuvio.java) over all online
     * players: harvests a LEGIT sample from anyone who currently looks clearly trustworthy.
     */
    public void sweepLegitSamples(UserDataManager userDataManager) {
        if (!config.isAutoCollectionEnabled()) return;

        // Once auto-collected legit samples heavily outnumber cheat samples, stop adding more -
        // cheat collection (from bans) is unthrottled, so the ratio naturally recovers as real
        // cheaters get caught, rather than the dataset drifting further toward "everyone is legit".
        //
        // Counts come from the dataset itself rather than in-memory counters: the CSV survives
        // restarts (and is reloaded on startup) while the counters do not, so counters made the
        // cap behave differently on every reboot. The bootstrap floor keeps a server with no bans
        // yet from freezing its dataset at a handful of samples - see legitBootstrapFloor().
        int cheatSamples = datasetManager.countSamples("click", 1) + datasetManager.countSamples("aim", 1);
        int legitSamples = datasetManager.countSamples("click", 0) + datasetManager.countSamples("aim", 0);
        if (legitSamples >= legitBootstrapFloor() && legitSamples >= cheatSamples * LEGIT_TO_CHEAT_RATIO_CAP) {
            return;
        }

        double minTrust = config.getAutoCollectLegitMinTrust();
        double maxRisk = config.getAutoCollectLegitMaxRisk();
        long intervalMs = Math.max(LEGIT_COOLDOWN_MS, config.getAutoCollectLegitIntervalMinutes() * 60_000L);
        long now = System.currentTimeMillis();

        long historyIntervalMs = Math.max(10_000L, config.getAutoCollectHistoryIntervalSeconds() * 1000L);

        for (Player player : Bukkit.getOnlinePlayers()) {
            UserData data = userDataManager.get(player.getUniqueId());
            if (data == null) continue;

            // Rolling window capture runs for EVERY player, before the trust filters below: by the
            // time someone is banned it is far too late to start remembering what their session
            // looked like, and the whole point of the history is to have windows from before the
            // moment the pipeline made up its mind.
            captureHistory(player, data, now, historyIntervalMs);

            if (data.getTrustScore() < minTrust || data.getRiskIndex() > maxRisk || data.getVl() > 0.0) continue;

            Long last = lastLegitCollection.get(player.getUniqueId());
            if (last != null && (now - last) < intervalMs) continue;

            DatasetManager.SampleContext context = contextOf(player, data);
            boolean collected = false;
            if (data.getClickBuffer().getCount() >= 16) {
                float[] features = ClickFeatureExtractor.extract(data.getClickBuffer(), data.getEarlyCombatMean()).clone();
                datasetManager.addSample(new DatasetManager.LabeledSample(
                        player.getUniqueId(), player.getName(), features, 0, now, "AutoCollector(trusted)", "click",
                        DatasetManager.LabelSource.TRUSTED, context
                ));
                clickClassifier.train(features, 0);
                collected = true;
            }
            if (data.isInCombat() && data.getAimBuffer().getCount() >= 16) {
                float[] aimFeatures = AimFeatureExtractor.extract(data.getAimBuffer()).clone();
                datasetManager.addSample(new DatasetManager.LabeledSample(
                        player.getUniqueId(), player.getName(), aimFeatures, 0, now, "AutoCollector(trusted)", "aim",
                        DatasetManager.LabelSource.TRUSTED, context
                ));
                aimClassifier.train(aimFeatures, 0);
                collected = true;
            }

            if (collected) {
                autoLegitCount.incrementAndGet();
                lastLegitCollection.put(player.getUniqueId(), now);
            }
        }
    }

    /**
     * Snapshots the player's current feature vectors into their rolling history, throttled to the
     * configured interval. Cheap: two feature extractions over buffers the pipeline already
     * maintains, at most once every {@code history-interval-seconds} per player, and only for
     * players whose buffers actually hold enough samples to extract anything meaningful.
     */
    private void captureHistory(Player player, UserData data, long now, long intervalMs) {
        if (!config.isAutoCollectHistoryEnabled()) return;

        boolean hasClick = data.getClickBuffer().getCount() >= 16;
        boolean hasAim = data.getAimBuffer().getCount() >= 16;
        if (!hasClick && !hasAim) return;

        // Extraction is only worth doing once the throttle has actually elapsed, so ask the
        // history first with null vectors... which it would reject. Instead the history itself
        // owns the throttle and we pay the extraction cost only when it will be accepted.
        if (!data.getFeatureHistory().shouldCapture(now, intervalMs)) return;

        float[] click = hasClick
                ? ClickFeatureExtractor.extract(data.getClickBuffer(), data.getEarlyCombatMean()).clone() : null;
        float[] aim = hasAim ? AimFeatureExtractor.extract(data.getAimBuffer()).clone() : null;
        data.getFeatureHistory().capture(now, intervalMs, click, aim);
    }

    public void forgetPlayer(UUID uuid) {
        lastLegitCollection.remove(uuid);
    }
}
