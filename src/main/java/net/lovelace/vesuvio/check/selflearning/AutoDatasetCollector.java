package net.lovelace.vesuvio.check.selflearning;

import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.feature.ClickFeatureExtractor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * (b) immediately used to train the in-process OnlineClassifier (pure-Java SGD logistic
 * regression, see OnlineClassifier) - so the self-learning layer keeps adapting to this
 * specific server's playerbase and current cheat landscape without any manual step or external
 * ONNX export/import cycle.
 *
 * Author: Lovelace
 */
public final class AutoDatasetCollector {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-AutoLearn");
    private static final long LEGIT_COOLDOWN_MS = 60_000L; // hard floor, config interval also applies

    private final ConfigManager config;
    private final DatasetManager datasetManager;
    private final OnlineClassifier classifier;

    private final ConcurrentHashMap<UUID, Long> lastLegitCollection = new ConcurrentHashMap<>();

    public AutoDatasetCollector(ConfigManager config, DatasetManager datasetManager, OnlineClassifier classifier) {
        this.config = config;
        this.datasetManager = datasetManager;
        this.classifier = classifier;
    }

    /**
     * Call the moment a player's punishment crosses a "ban" rule. Captures their current click
     * feature vector as a confirmed-cheat sample.
     */
    public void collectCheatSample(Player player, UserData data) {
        if (!config.isAutoCollectionEnabled() || !config.isAutoCollectCheatOnBan()) return;
        if (data.getClickBuffer().getCount() < 16) return; // not enough signal to be a useful sample

        float[] features = ClickFeatureExtractor.extract(data.getClickBuffer(), data.getEarlyCombatMean()).clone();

        datasetManager.addSample(new DatasetManager.LabeledSample(
                player.getUniqueId(), player.getName(), features, 1,
                System.currentTimeMillis(), "AutoCollector(ban)"
        ));
        classifier.train(features, 1);

        LOGGER.info(String.format("[Vesuvio AutoLearn] Auto-collected CHEAT sample for %s on ban (classifier now trained on %d samples)",
                player.getName(), classifier.getTrainedSamplesCount()));
    }

    /**
     * Periodic sweep (called every tick-scheduled interval from Vesuvio.java) over all online
     * players: harvests a LEGIT sample from anyone who currently looks clearly trustworthy.
     */
    public void sweepLegitSamples(UserDataManager userDataManager) {
        if (!config.isAutoCollectionEnabled()) return;

        double minTrust = config.getAutoCollectLegitMinTrust();
        double maxRisk = config.getAutoCollectLegitMaxRisk();
        long intervalMs = Math.max(LEGIT_COOLDOWN_MS, config.getAutoCollectLegitIntervalMinutes() * 60_000L);
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UserData data = userDataManager.get(player.getUniqueId());
            if (data == null) continue;

            if (data.getTrustScore() < minTrust || data.getRiskIndex() > maxRisk || data.getVl() > 0.0) continue;
            if (data.getClickBuffer().getCount() < 16) continue;

            Long last = lastLegitCollection.get(player.getUniqueId());
            if (last != null && (now - last) < intervalMs) continue;

            float[] features = ClickFeatureExtractor.extract(data.getClickBuffer(), data.getEarlyCombatMean()).clone();

            datasetManager.addSample(new DatasetManager.LabeledSample(
                    player.getUniqueId(), player.getName(), features, 0, now, "AutoCollector(trusted)"
            ));
            classifier.train(features, 0);
            lastLegitCollection.put(player.getUniqueId(), now);
        }
    }

    public void forgetPlayer(UUID uuid) {
        lastLegitCollection.remove(uuid);
    }
}
