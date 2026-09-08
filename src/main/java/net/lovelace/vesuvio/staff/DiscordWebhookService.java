package net.lovelace.vesuvio.staff;

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

        // Only send if Risk Index or Confidence exceeds threshold
        if (data.getRiskIndex() < config.getDiscordMinRisk() && result.confidence() < 0.85) {
            return;
        }

        virtualExecutor.execute(() -> {
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
                    LOGGER.warning("[Vesuvio] Discord Webhook rejected alert: HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to send Discord webhook alert", e);
            }
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
              "username": "Vesuvio 26.2 AntiCheat",
              "avatar_url": "https://i.imgur.com/8QZ8GqQ.png",
              "embeds": [
                {
                  "title": "🌋 Detection Alert: %s",
                  "color": %d,
                  "thumbnail": { "url": "%s" },
                  "fields": [
                    { "name": "Check", "value": "`%s`", "inline": true },
                    { "name": "Risk Index", "value": "`%.0f / 100`", "inline": true },
                    { "name": "Trust Score", "value": "`%.0f / 100`", "inline": true },
                    { "name": "VL Added", "value": "`+%.1f` (Total: `%.0f`)", "inline": true },
                    { "name": "ML Confidence", "value": "`%.1f%%`", "inline": true },
                    { "name": "Client Brand", "value": "`%s`", "inline": true },
                    { "name": "Diagnostics", "value": "%s", "inline": false }
                  ],
                  "footer": { "text": "Vesuvio 26.2 • Operations Console" },
                  "timestamp": "%s"
                }
              ]
            }
            """,
                escapeJson(player.getName()),
                embedColor,
                avatarUrl,
                escapeJson(result.checkName()),
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
