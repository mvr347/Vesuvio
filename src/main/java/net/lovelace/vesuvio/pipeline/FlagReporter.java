package net.lovelace.vesuvio.pipeline;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.staff.SmartAlertService;
import net.lovelace.vesuvio.storage.DatabaseManager;
import org.bukkit.entity.Player;
import java.util.Locale;
import java.util.logging.Logger;
import net.lovelace.vesuvio.diagnostics.DiagnosticService;
import net.lovelace.vesuvio.i18n.Russian;
import net.lovelace.vesuvio.punishment.PunishmentService;
import net.lovelace.vesuvio.staff.DiscordWebhookService;
import net.lovelace.vesuvio.storage.ViolationRecord;

/**
 * The single place where a flag becomes VL, risk, an alert, a database record and a punishment check.
 * Extracted from CheckPipeline unchanged.
 */
public final class FlagReporter {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-Pipeline");
    private final ConfigManager config;
    private final SmartAlertService alertService;
    private final DatabaseManager databaseManager;
    private final DiscordWebhookService discordService;
    private final DiagnosticService diagnostics;
    private final PunishmentService punishmentService;

    FlagReporter(ConfigManager config, SmartAlertService alertService, DatabaseManager databaseManager, DiscordWebhookService discordService, DiagnosticService diagnostics, PunishmentService punishmentService) {
        this.config = config;
        this.alertService = alertService;
        this.databaseManager = databaseManager;
        this.discordService = discordService;
        this.diagnostics = diagnostics;
        this.punishmentService = punishmentService;
    }

    public boolean report(Player player, UserData data, CheckResult result, double vl, double risk) {
        return reportInternal(player, data, result, vl, risk, false);
    }

    /** Same as {@link #report}, for checks that re-evaluate one sliding window on every packet. */
    public boolean reportWindowed(Player player, UserData data, CheckResult result, double vl, double risk) {
        return reportInternal(player, data, result, vl, risk, true);
    }

    private boolean reportInternal(Player player, UserData data, CheckResult result, double vl, double risk, boolean windowed) {
        if (!config.isAnticheatEnabled() || data.hasRecentTeleport()) {
            diagnostics.flag(data, result, !config.isAnticheatEnabled() ? "отклонено: проверки выключены"
                    : "отклонено: защита после телепортации");
            return false;
        }
        if (windowed && !data.tryReflag(result.checkName(), System.currentTimeMillis(), config.getStatisticalReflagIntervalMs())) {
            diagnostics.flag(data, result, "отклонено: повторный флаг оконной проверки");
            return false;
        }
        if (!config.isExcludedFromPunishment(result.checkName())) {
            data.addVl(vl);
            data.adjustRisk(risk);
        }
        data.setLastTriggeredCheck(result.checkName());
        handleFlag(player, data, result);
        return true;
    }

    public void handleFlag(Player player, UserData data, CheckResult result) {
        boolean disabled = !config.isAnticheatEnabled();
        boolean teleportGrace = data.hasRecentTeleport();
        diagnostics.flag(data, result, disabled ? "отклонено: проверки выключены"
                : teleportGrace ? "отклонено: защита после телепортации" : "принято");
        if (disabled || teleportGrace) return;
        // 0. Verbose console diagnostics (settings.debug: true) - dumps the exact feature values
        // that triggered the flag, so server owners can tune thresholds without guessing.
        if (config.isDebug()) {
            LOGGER.info(String.format(Locale.US,
                    "[FLAG] %s | проверка=%s уверенность=%.2f добавлено=%.2f -> нарушения=%.1f риск=%.1f доверие=%.1f | %s | подробности=%s",
                    player.getName(), result.checkName(), result.confidence(), result.vl(),
                    data.getVl(), data.getRiskIndex(), data.getTrustScore(),
                    result.explanation(), Russian.details(result.details())));
        }

        // 1-2. Staff chat and Discord: at most one alert per check and player per staff.smart-alerts.min-interval-ms. The database
        // record below is written for every flag, so the journal stays complete.
        if (data.tryAlert(result.checkName(), System.currentTimeMillis(), config.getAlertMinIntervalMs())) {
            // Broadcast smart MiniMessage alert to staff
            alertService.broadcastAlert(player, data, result);

            // Dispatch Discord Webhook rich embed asynchronously
            if (discordService != null) {
                discordService.dispatchAlertAsync(player, data, result);
            }
        }

        // 3. Queue asynchronous database violation record
        databaseManager.logViolationAsync(new ViolationRecord(
                player.getUniqueId(),
                player.getName(),
                result.checkName(),
                data.getVl(),
                result.confidence(),
                result.explanation(),
                result.details().toString(),
                System.currentTimeMillis()
        ));

        // 4. Evaluate punishment thresholds
        punishmentService.evaluate(player, data);
    }
}
