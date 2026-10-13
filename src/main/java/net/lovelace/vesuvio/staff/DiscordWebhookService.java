package net.lovelace.vesuvio.staff;

import net.lovelace.vesuvio.i18n.Russian;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dispatches Rich Embed detection cards to staff Discord channels via Webhooks.
 * Runs completely asynchronously on virtual threads.
 *
 * Author: Lovelace
 */
public final class DiscordWebhookService {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-Discord");

    private final ConfigManager config;
    private final Executor virtualExecutor;
    private final HttpClient httpClient;

    public DiscordWebhookService(ConfigManager config, Executor virtualExecutor) {
        this.config = config;
        this.virtualExecutor = virtualExecutor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void dispatchAlertAsync(Player player, UserData data, CheckResult result) {
        if (!config.isDiscordEnabled()) return;

        String webhookUrl = config.getDiscordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank() || !webhookUrl.startsWith("https://discord.com/api/webhooks/")) {
            return;
        }

        // Only send if Индекс риска or Confidence exceeds threshold
        if (data.getRiskIndex() < config.getDiscordMinRisk() && result.confidence() < 0.85) {
            return;
        }

        net.lovelace.vesuvio.engine.AsyncTasks.run(() -> {
            try {
                String payload = buildJsonPayload(player, data, result);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(6))
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    LOGGER.warning("[Vesuvio] Discord отклонил оповещение: HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Не удалось отправить оповещение в Discord", e);
            }
        }, virtualExecutor).exceptionally(failure -> {
            LOGGER.log(Level.FINE, "Оповещение Discord пропущено: очередь занята или остановлена", failure);
            return null;
        });
    }

    private String buildJsonPayload(Player player, UserData data, CheckResult result) {
        int embedColor = 0xFF4500; // Lava Orange
        if (data.getRiskIndex() >= 80.0) {
            embedColor = 0xFF2A55; // Lava Red
        }

        String avatarUrl = "https://mc-heads.net/avatar/" + player.getUniqueId() + "/100.png";

        return String.format(Locale.US, """
            {
              "username": "Античит Vesuvio 26.2",
              "avatar_url": "https://i.imgur.com/8QZ8GqQ.png",
              "embeds": [
                {
                  "title": "🌋 Обнаружено нарушение: %s",
                  "color": %d,
                  "thumbnail": { "url": "%s" },
                  "fields": [
                    { "name": "Проверка", "value": "`%s`", "inline": true },
                    { "name": "Индекс риска", "value": "`%.0f / 100`", "inline": true },
                    { "name": "Уровень доверия", "value": "`%.0f / 100`", "inline": true },
                    { "name": "Баллы нарушения", "value": "`+%.1f` (Всего: `%.0f`)", "inline": true },
                    { "name": "Уверенность модели", "value": "`%.1f%%`", "inline": true },
                    { "name": "Клиент", "value": "`%s`", "inline": true },
                    { "name": "Диагностика", "value": "%s", "inline": false }
                  ],
                  "footer": { "text": "Vesuvio 26.2 • Панель управления" },
                  "timestamp": "%s"
                }
              ]
            }
            """,
                escapeJson(player.getName()),
                embedColor,
                avatarUrl,
                escapeJson(Russian.label(result.checkName())),
                data.getRiskIndex(),
                data.getTrustScore(),
                result.vl(),
                data.getVl(),
                result.confidence() * 100,
                escapeJson(data.getClientBrand()),
                escapeJson(result.explanation()),
                java.time.Instant.now().toString()
        );
    }

    private String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
