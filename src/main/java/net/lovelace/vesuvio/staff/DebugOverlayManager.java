package net.lovelace.vesuvio.staff;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /vesuvio debug &lt;player&gt; - live ActionBar telemetry for tuning detection thresholds.
 * Shows the exact statistical values (CPS, StdDev, DupRatio, Entropy, airTicks, deltaY, onGround,
 * VL/Risk/Trust, last triggered check and buffer fill) that the checks are currently seeing,
 * so staff can confirm the pipeline is actually receiving and evaluating data in real time.
 *
 * Author: Lovelace
 */
public final class DebugOverlayManager {

    private final UserDataManager userDataManager;
    private final MiniMessage mm = MiniMessage.miniMessage();

    // watcher UUID -> target UUID
    private final Map<UUID, UUID> watchers = new ConcurrentHashMap<>();

    public DebugOverlayManager(UserDataManager userDataManager) {
        this.userDataManager = userDataManager;
    }

    /**
     * Toggles debug watching of a target for the given staff member.
     * @return true if debug overlay is now enabled, false if it was just disabled
     */
    public boolean toggle(Player staff, Player target) {
        UUID staffId = staff.getUniqueId();
        UUID currentTarget = watchers.get(staffId);
        if (currentTarget != null && currentTarget.equals(target.getUniqueId())) {
            watchers.remove(staffId);
            return false;
        }
        watchers.put(staffId, target.getUniqueId());
        return true;
    }

    public void stop(UUID staffId) {
        watchers.remove(staffId);
    }

    public void tick() {
        if (watchers.isEmpty()) return;

        for (Map.Entry<UUID, UUID> entry : watchers.entrySet()) {
            Player staff = Bukkit.getPlayer(entry.getKey());
            if (staff == null || !staff.isOnline()) {
                watchers.remove(entry.getKey());
                continue;
            }

            Player target = Bukkit.getPlayer(entry.getValue());
            if (target == null || !target.isOnline()) {
                continue;
            }

            UserData data = userDataManager.get(target.getUniqueId());
            if (data == null) continue;

            String msg = String.format(Locale.US,
                    "<yellow>DBG %s</yellow> <dark_gray>|</dark_gray> "
                    + "<white>CPS:%.1f Std:%.1f Dup:%.0f%% Ent:%.2f</white> <dark_gray>|</dark_gray> "
                    + "<aqua>Air:%d ΔY:%.2f Grnd:%s</aqua> <dark_gray>|</dark_gray> "
                    + "<gold>VL:%.0f</gold> <red>Risk:%.0f</red> <green>Trust:%.0f</green> <dark_gray>|</dark_gray> "
                    + "<blue>SL:%.0f%%</blue> <dark_gray>|</dark_gray> "
                    + "<light_purple>%s</light_purple> <dark_gray>|</dark_gray> "
                    + "<gray>Clk:%d/64 Aim:%d/64</gray>",
                    target.getName(),
                    data.getLastCalculatedCPS(), data.getLastStdDevMs(), data.getLastDupRatio() * 100, data.getLastEntropy(),
                    data.getAirTicks(), data.getLastDeltaY(), data.isLastOnGround() ? "Y" : "N",
                    data.getVl(), data.getRiskIndex(), data.getTrustScore(),
                    data.getLastSelfLearnProbability() * 100,
                    data.getLastTriggeredCheck(),
                    data.getClickBuffer().getCount(), data.getAimBuffer().getCount());

            staff.sendActionBar(mm.deserialize(msg));
        }
    }

    public void cleanup() {
        watchers.clear();
    }
}
