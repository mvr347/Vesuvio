package net.lovelace.vesuvio.staff;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advanced Mechanic 4.3: Smart Alerts Service.
 * Delivers informative, human-readable MiniMessage detection alerts
 * explaining exactly which features triggered the flag.
 * Includes hover feature breakdown and clickable interactive actions.
 *
 * Author: Lovelace
 */
public final class SmartAlertService {

    private final ConfigManager config;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Set<UUID> disabledAlerts = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public SmartAlertService(ConfigManager config) {
        this.config = config;
    }

    public boolean toggleAlerts(UUID staffUuid) {
        if (disabledAlerts.contains(staffUuid)) {
            disabledAlerts.remove(staffUuid);
            return true; // now enabled
        } else {
            disabledAlerts.add(staffUuid);
            return false; // now disabled
        }
    }

    public boolean hasAlertsEnabled(UUID staffUuid) {
        return !disabledAlerts.contains(staffUuid);
    }

    public void broadcastAlert(Player player, UserData data, CheckResult result) {
        if (config.isSilent(result.checkName())) {
            // Check is configured in silent mode - record metrics without chat broadcast
            return;
        }

        // Build feature hover tooltip in Russian
        StringBuilder hover = new StringBuilder("<gold><b>Диагностика нарушения</b></gold><newline>");
        hover.append("<gray>Проверка:</gray> <white>").append(result.checkName()).append("</white><newline>");
        hover.append("<gray>Уверенность:</gray> <yellow>").append(String.format(Locale.US, "%.1f%%", result.confidence() * 100)).append("</yellow><newline>");
        hover.append("<gray>Причина:</gray> <white>").append(result.explanation()).append("</white><newline>");
        hover.append("<dark_gray>--------------------</dark_gray><newline>");

        for (Map.Entry<String, Object> entry : result.details().entrySet()) {
            hover.append("<gray>").append(entry.getKey()).append(":</gray> <yellow>").append(entry.getValue()).append("</yellow><newline>");
        }
        hover.append("<dark_gray>--------------------</dark_gray><newline>");
        hover.append("<gray>Клиент:</gray> <aqua>").append(data.getClientBrand()).append("</aqua><newline>");
        hover.append("<gray>Доверие:</gray> <green>").append(String.format(Locale.US, "%.1f", data.getTrustScore())).append("</green><newline>");
        hover.append("<gray>Индекс риска:</gray> <red>").append(String.format(Locale.US, "%.1f", data.getRiskIndex())).append("</red>");

        // Construct MiniMessage formatted alert with staff interactive training buttons
        String alertMsg = String.format(Locale.US,
                "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> "
                + "<click:run_command:'/vesuvio spectate %s'><hover:show_text:'%s'>"
                + "<white><bold>%s</bold></white> <gray>провалил</gray> <gold>%s</gold> "
                + "<dark_gray>(</dark_gray><yellow>%s</yellow><dark_gray>)</dark_gray> "
                + "<red><bold>[VL: %.0f]</bold></red> "
                + "<dark_red><bold>[Риск: %.0f]</bold></dark_red></hover></click> "
                + "<dark_gray>|</dark_gray> "
                + "<aqua>[<click:run_command:'/vesuvio spectate %s'><hover:show_text:'<aqua>Следить в реальном времени</aqua>'>👁</click>]</aqua> "
                + "<green>[<click:run_command:'/vesuvio learn %s legit'><hover:show_text:'<green><b>Обучить модель: ЧИСТО</b><newline><gray>Игрок чист. Адаптировать веса нейросети</gray></green>'>✔ Чисто</click>]</green> "
                + "<red>[<click:run_command:'/vesuvio learn %s cheat'><hover:show_text:'<red><b>Обучить модель: ЧИТ</b><newline><gray>Подтвердить нарушение и натренировать модель</gray></red>'>✖ Чит</click>]</red>",
                player.getName(),
                hover.toString().replace("'", "\\'"),
                player.getName(),
                result.checkName(),
                result.explanation(),
                data.getVl(),
                data.getRiskIndex(),
                player.getName(),
                player.getName(),
                player.getName()
        );

        Component component = mm.deserialize(alertMsg);

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if ((staff.hasPermission("vesuvio.alerts") || staff.isOp()) && hasAlertsEnabled(staff.getUniqueId())) {
                staff.sendMessage(component);
            }
        }
    }
}
