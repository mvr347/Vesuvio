package net.lovelace.vesuvio.check.selflearning;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lovelace.vesuvio.check.onnx.MLResult;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Advanced Mechanic 2.1: Active Learning.
 * Detects borderline ML probability verdicts (0.55 - 0.78) and asks staff
 * to review with interactive MiniMessage buttons.
 * Reviewed samples immediately update the online classifier and training dataset.
 *
 * Author: Lovelace
 */
public final class ActiveLearning {

    public record ReviewSample(
            String id,
            UUID playerUuid,
            String playerName,
            float[] features,
            double probability,
            long timestamp
    ) {}

    private final AtomicInteger sampleCounter = new AtomicInteger(100);
    private final Cache<String, ReviewSample> pendingReviews = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    private final MiniMessage mm = MiniMessage.miniMessage();

    /**
     * Creates a pending sample and alerts staff with interactive buttons.
     */
    public void requestReview(Player player, UserData data, MLResult result, float[] features) {
        String sampleId = "S" + sampleCounter.incrementAndGet();
        ReviewSample sample = new ReviewSample(
                sampleId,
                player.getUniqueId(),
                player.getName(),
                features.clone(),
                result.probability(),
                System.currentTimeMillis()
        );
        pendingReviews.put(sampleId, sample);

        // Build interactive MiniMessage alert
        float dupRatio = features.length >= 5 ? features[4] : 0f;
        float stdDev = features.length >= 2 ? features[1] : 0f;
        float cps = features.length >= 15 ? features[14] : 0f;

        String messageFormat = "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> "
                + "<gray>Требуется вердикт персонала для <white><bold>%s</bold></white> "
                + "(<yellow>Нейросеть: %.1f%%</yellow>, <yellow>КПС: %.1f</yellow>, <yellow>Разброс: %.1fms</yellow>, <yellow>Дубли: %.0f%%</yellow>) "
                + "<dark_gray>[Образец #%s]</dark_gray></gray><newline>"
                + "<gray>Вердикт: </gray>"
                + "<green><bold>[<click:run_command:/vesuvio review %s legit><hover:show_text:'<green>Пометить как ЧИСТУЮ игру (Легит)</green>'>✔ ЧИСТО</click></bold>]</green> "
                + "<dark_gray> | </dark_gray>"
                + "<red><bold>[<click:run_command:/vesuvio review %s cheat><hover:show_text:'<red>Пометить как использование читов</red>'>✖ ЧИТ</click></bold>]</red> "
                + "<dark_gray> | </dark_gray>"
                + "<aqua>[<click:run_command:/vesuvio spectate %s><hover:show_text:'<aqua>Следить за игроком с оверлеем</aqua>'>👁 СПЕКТАТИТЬ</click>]</aqua>";

        String formatted = String.format(Locale.US, messageFormat,
                player.getName(),
                result.probability() * 100,
                cps,
                stdDev,
                dupRatio * 100,
                sampleId,
                sampleId,
                sampleId,
                player.getName()
        );

        Component component = mm.deserialize(formatted);

        // Broadcast to all online staff with permission
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("vesuvio.review") || staff.isOp()) {
                staff.sendMessage(component);
            }
        }
    }

    /**
     * Submits a staff verdict for a sample.
     */
    public boolean submitVerdict(String sampleId, String verdict, String reviewer,
                                  OnlineClassifier classifier, DatasetManager dataset, UserData userData) {
        ReviewSample sample = pendingReviews.getIfPresent(sampleId);
        if (sample == null) return false;

        pendingReviews.invalidate(sampleId);

        int label = "cheat".equalsIgnoreCase(verdict) ? 1 : 0;

        // 1. Train online classifier
        classifier.train(sample.features(), label);

        // 2. Persist to training dataset
        dataset.addSample(new DatasetManager.LabeledSample(
                sample.playerUuid(),
                sample.playerName(),
                sample.features(),
                label,
                System.currentTimeMillis(),
                reviewer
        ));

        // 3. Adjust player's Trust/Risk if online
        if (userData != null) {
            if (label == 1) {
                userData.adjustRisk(25.0);
                userData.adjustTrust(-20.0);
                userData.addVl(10.0);
            } else {
                userData.adjustTrust(15.0);
                userData.adjustRisk(-15.0);
            }
        }

        return true;
    }

    public Collection<ReviewSample> getPendingReviews() {
        return pendingReviews.asMap().values();
    }

    public ReviewSample getSample(String id) {
        return pendingReviews.getIfPresent(id);
    }
}
