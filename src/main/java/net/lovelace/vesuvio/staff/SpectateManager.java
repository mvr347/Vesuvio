package net.lovelace.vesuvio.staff;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advanced Mechanic 4.1: Live Spectate + Real-Time Overlay.
 * Allows staff to seamlessly track suspects with real-time biometric metrics
 * streamed to ActionBar and BossBar overlays every 2 ticks.
 *
 * Author: Lovelace
 */
public final class SpectateManager {

    private final UserDataManager userDataManager;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private final Map<UUID, UUID> activeSpectators = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> activeBossBars = new ConcurrentHashMap<>();
    private final Map<UUID, GameMode> originalGameModes = new ConcurrentHashMap<>();

    public SpectateManager(UserDataManager userDataManager) {
        this.userDataManager = userDataManager;
    }

    public void startSpectating(Player staff, Player target) {
        originalGameModes.put(staff.getUniqueId(), staff.getGameMode());
        activeSpectators.put(staff.getUniqueId(), target.getUniqueId());

        staff.setGameMode(GameMode.SPECTATOR);
        staff.teleport(target.getLocation());

        BossBar bossBar = BossBar.bossBar(
                Component.text("Vesuvio Live Telemetry: " + target.getName()),
                0.0f,
                BossBar.Color.RED,
                BossBar.Overlay.PROGRESS
        );
        staff.showBossBar(bossBar);
        activeBossBars.put(staff.getUniqueId(), bossBar);

        staff.sendMessage(mm.deserialize("<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <green>Начато наблюдение за игроком <white>" 
                + target.getName() + "</white>. Введите <yellow>/vesuvio spectate</yellow>, чтобы выйти.</green>"));
    }

    public void stopSpectating(Player staff) {
        UUID staffId = staff.getUniqueId();
        activeSpectators.remove(staffId);

        BossBar bossBar = activeBossBars.remove(staffId);
        if (bossBar != null) {
            staff.hideBossBar(bossBar);
        }

        GameMode gm = originalGameModes.remove(staffId);
        if (gm != null) {
            staff.setGameMode(gm);
        }

        staff.sendMessage(mm.deserialize("<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <gray>Сессия наблюдения завершена.</gray>"));
    }

    public boolean isSpectating(UUID staffId) {
        return activeSpectators.containsKey(staffId);
    }

    /**
     * Called every 2 ticks to refresh the live metrics overlay for all active staff spectators.
     */
    public void tickOverlay() {
        if (activeSpectators.isEmpty()) return;

        for (Map.Entry<UUID, UUID> entry : activeSpectators.entrySet()) {
            Player staff = Bukkit.getPlayer(entry.getKey());
            Player target = Bukkit.getPlayer(entry.getValue());

            if (staff == null || !staff.isOnline()) {
                activeSpectators.remove(entry.getKey());
                continue;
            }

            if (target == null || !target.isOnline()) {
                stopSpectating(staff);
                staff.sendMessage(mm.deserialize("<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <red>Отслеживаемый игрок отключился от сервера.</red>"));
                continue;
            }

            UserData targetData = userDataManager.get(target.getUniqueId());
            if (targetData == null) continue;

            double cps = targetData.getLastCalculatedCPS();
            double vl = targetData.getVl();
            double mlProb = targetData.getLastMLProbability();
            double risk = targetData.getRiskIndex();
            double trust = targetData.getTrustScore();
            String brand = targetData.getClientBrand();

            // 1. ActionBar update in Russian
            String actionMsg = String.format(Locale.US,
                    "<red><b>КПС:</b> <yellow>%.1f</yellow></red> <dark_gray>|</dark_gray> "
                    + "<gold><b>VL:</b> <yellow>%.0f</yellow></gold> <dark_gray>|</dark_gray> "
                    + "<aqua><b>Нейросеть:</b> <yellow>%.0f%%</yellow></aqua> <dark_gray>|</dark_gray> "
                    + "<dark_red><b>Риск:</b> <yellow>%.0f</yellow></dark_red> <dark_gray>|</dark_gray> "
                    + "<green><b>Доверие:</b> <yellow>%.0f</yellow></green> <dark_gray>|</dark_gray> "
                    + "<light_purple><b>Клиент:</b> <white>%s</white></light_purple>",
                    cps, vl, mlProb * 100, risk, trust, brand);
            staff.sendActionBar(mm.deserialize(actionMsg));

            // 2. BossBar update in Russian
            BossBar bar = activeBossBars.get(staff.getUniqueId());
            if (bar != null) {
                float progress = (float) Math.max(0.0, Math.min(1.0, risk / 100.0));
                bar.progress(progress);
                bar.name(mm.deserialize(String.format(Locale.US,
                        "<red><b>%s</b></red> <dark_gray>»</dark_gray> <gray>Угроза: <red>%.0f%%</red> | VL: <gold>%.0f</gold> | Проверка: <yellow>%s</yellow></gray>",
                        target.getName(), risk, vl, targetData.getLastTriggeredCheck())));
            }
        }
    }

    public void cleanup() {
        for (UUID staffId : activeSpectators.keySet()) {
            Player staff = Bukkit.getPlayer(staffId);
            if (staff != null) {
                stopSpectating(staff);
            }
        }
        activeSpectators.clear();
        activeBossBars.clear();
        originalGameModes.clear();
    }
}
