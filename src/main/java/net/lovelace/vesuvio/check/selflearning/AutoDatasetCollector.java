package net.lovelace.vesuvio.check.selflearning;

import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.feature.AimFeatureExtractor;
import net.lovelace.vesuvio.feature.ClickFeatureExtractor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

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

        boolean collected = false;
        if (data.getClickBuffer().getCount() >= 16) {
            float[] features = ClickFeatureExtractor.extract(data.getClickBuffer(), data.getEarlyCombatMean()).clone();
            datasetManager.addSample(new DatasetManager.LabeledSample(
                    player.getUniqueId(), player.getName(), features, 1,
                    System.currentTimeMillis(), "AutoCollector(ban)", "click"
            ));
            clickClassifier.train(features, 1);
            collected = true;
        }
        if (data.getAimBuffer().getCount() >= 16) {
            float[] aimFeatures = AimFeatureExtractor.extract(data.getAimBuffer()).clone();
            datasetManager.addSample(new DatasetManager.LabeledSample(
                    player.getUniqueId(), player.getName(), aimFeatures, 1,
                    System.currentTimeMillis(), "AutoCollector(ban)", "aim"
            ));
            aimClassifier.train(aimFeatures, 1);
            collected = true;
        }

        if (collected) {
            autoCheatCount.incrementAndGet();
            LOGGER.info(String.format(
                    "[Vesuvio AutoLearn] Auto-collected CHEAT sample(s) for %s on ban (click classifier: %d samples, aim classifier: %d samples)",
                    player.getName(), clickClassifier.getTrainedSamplesCount(), aimClassifier.getTrainedSamplesCount()));
        }
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
        int cheatSoFar = Math.max(1, autoCheatCount.get());
        if (autoLegitCount.get() >= cheatSoFar * LEGIT_TO_CHEAT_RATIO_CAP) {
            return;
        }

        double minTrust = config.getAutoCollectLegitMinTrust();
        double maxRisk = config.getAutoCollectLegitMaxRisk();
        long intervalMs = Math.max(LEGIT_COOLDOWN_MS, config.getAutoCollectLegitIntervalMinutes() * 60_000L);
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UserData data = userDataManager.get(player.getUniqueId());
            if (data == null) continue;

            if (data.getTrustScore() < minTrust || data.getRiskIndex() > maxRisk || data.getVl() > 0.0) continue;

            Long last = lastLegitCollection.get(player.getUniqueId());
            if (last != null && (now - last) < intervalMs) continue;

            boolean collected = false;
            if (data.getClickBuffer().getCount() >= 16) {
                float[] features = ClickFeatureExtractor.extract(data.getClickBuffer(), data.getEarlyCombatMean()).clone();
                datasetManager.addSample(new DatasetManager.LabeledSample(
                        player.getUniqueId(), player.getName(), features, 0, now, "AutoCollector(trusted)", "click"
                ));
                clickClassifier.train(features, 0);
                collected = true;
            }
            if (data.isInCombat() && data.getAimBuffer().getCount() >= 16) {
                float[] aimFeatures = AimFeatureExtractor.extract(data.getAimBuffer()).clone();
                datasetManager.addSample(new DatasetManager.LabeledSample(
                        player.getUniqueId(), player.getName(), aimFeatures, 0, now, "AutoCollector(trusted)", "aim"
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

    public void forgetPlayer(UUID uuid) {
        lastLegitCollection.remove(uuid);
    }
}
