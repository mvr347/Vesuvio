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
            long timestamp,
            String domain, // "click" or "aim" - must follow the sample into DatasetManager
            DatasetManager.SampleContext context
    ) {
        public ReviewSample(String id, UUID uuid, String name, float[] features, double probability, long timestamp, String domain) {
            this(id,uuid,name,features,probability,timestamp,domain,DatasetManager.SampleContext.UNKNOWN);
        }
        /** Legacy 6-arg constructor, defaults to the click domain. */
        public ReviewSample(String id, UUID playerUuid, String playerName, float[] features,
                            double probability, long timestamp) {
            this(id, playerUuid, playerName, features, probability, timestamp, "click");
        }
    }

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
        requestReview(player, data, result, features, "click");
    }

    /**
     * @param domain which feature extractor produced {@code features} ("click"/"aim"). It has to
     *               be carried through the verdict into {@link DatasetManager}: a sample stored
     *               under the wrong domain trains the other domain's model on feature vectors
     *               whose columns mean something entirely different.
     */
    public void requestReview(Player player, UserData data, MLResult result, float[] features, String domain) {
        String sampleId = "S" + sampleCounter.incrementAndGet();
        ReviewSample sample = new ReviewSample(
                sampleId,
                player.getUniqueId(),
                player.getName(),
                features.clone(),
                result.probability(),
                System.currentTimeMillis(),
                domain,
                new DatasetManager.SampleContext(-1, -1, data.getClientBrand(), data.getLearningSessionId(), data.getLearningClientVersion())
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
            if (staff.hasPermission("vesuvio.review")) {
                staff.sendMessage(component);
            }
        }
    }

    /**
     * Submits a staff verdict for a sample.
     */
    /** Compatibility entry point for worker callers. Commands use the non-blocking variant. */
    public boolean submitVerdict(String id, String verdict, String reviewer,
        OnlineClassifier classifier, DatasetManager dataset, UserData userData) {
        if(!Set.of("cheat","legit").contains(verdict))throw new IllegalArgumentException("Некорректный вердикт");
        return submitVerdictAsync(id,verdict,reviewer,dataset).join();
    }

    public java.util.concurrent.CompletableFuture<Boolean> submitVerdictAsync(
        String sampleId,String verdict,String reviewer,DatasetManager dataset) {
        if (!Set.of("cheat", "legit").contains(verdict) || reviewer==null || reviewer.isBlank())
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Некорректный вердикт"));
        ReviewSample sample = pendingReviews.asMap().remove(sampleId);
        if(sample==null || System.currentTimeMillis()-sample.timestamp()>=600000)return java.util.concurrent.CompletableFuture.completedFuture(false);
        return dataset.submitSample(new DatasetManager.LabeledSample(sample.playerUuid(),sample.playerName(),sample.features(),
            "cheat".equals(verdict)?1:0,sample.timestamp(),reviewer,sample.domain(),DatasetManager.LabelSource.STAFF,sample.context()))
            .whenComplete((accepted,failure)-> {
                // Keep the original capture timestamp and expiry when retrying after a storage error.
                if((failure!=null || !Boolean.TRUE.equals(accepted)) && System.currentTimeMillis()-sample.timestamp()<600000)
                    pendingReviews.asMap().putIfAbsent(sampleId,sample);
            });
    }

    public Collection<ReviewSample> getPendingReviews() {
        return pendingReviews.asMap().values();
    }

    public ReviewSample getSample(String id) {
        return pendingReviews.getIfPresent(id);
    }
}
