package net.lovelace.vesuvio.integration.hunt;

import net.lovelace.vesuvio.Vesuvio;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Dynamic bridge to LoveHunt for the "Lava Bounty" server event.
 * Places a public server bounty on suspects queued for Lava Wave mass ban.
 * Uses reflection and ServicesManager so there is zero compile-time hard dependency.
 *
 * Author: Lovelace
 */
public final class LoveHuntHook {

    private final Vesuvio plugin;
    private boolean enabled;
    private Material rewardMaterial = Material.DIAMOND;
    private int rewardAmount = 5;

    public LoveHuntHook(Vesuvio plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("integrations.lovehunt.lava-bounty", true);
        String matName = plugin.getConfig().getString("integrations.lovehunt.reward-item", "DIAMOND");
        try {
            this.rewardMaterial = Material.valueOf(matName.toUpperCase());
        } catch (Exception e) {
            this.rewardMaterial = Material.DIAMOND;
        }
        this.rewardAmount = plugin.getConfig().getInt("integrations.lovehunt.reward-amount", 5);
    }

    public boolean isAvailable() {
        return enabled && Bukkit.getPluginManager().isPluginEnabled("LoveHunt");
    }

    /**
     * Places a server bounty on a cheater caught by Vesuvio before the Lava Wave erupts.
     */
    public void createLavaBounty(UUID suspectUuid, String suspectName, String reason) {
        if (!isAvailable()) return;

        OfflinePlayer target = Bukkit.getOfflinePlayer(suspectUuid);

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Class<?> apiClass = Class.forName("me.lovelace.loveHunt.api.LoveHuntAPI");
                var registration = Bukkit.getServicesManager().getRegistration(apiClass);
                if (registration == null) return;
                Object apiInstance = registration.getProvider();
                if (apiInstance == null) return;

                // Check if bounty already exists on target
                Method getActiveBountyMethod = apiClass.getMethod("getActiveBountyOn", UUID.class);
                Object existing = getActiveBountyMethod.invoke(apiInstance, suspectUuid);
                if (existing != null) return;

                // Construct RewardItem(Material, int, ItemStack, String)
                Class<?> rewardItemClass = Class.forName("me.lovelace.loveHunt.model.RewardItem");
                Constructor<?> ctor = rewardItemClass.getConstructor(Material.class, int.class, org.bukkit.inventory.ItemStack.class, String.class);
                Object rewardItem = ctor.newInstance(rewardMaterial, rewardAmount, null, "§6Награда за софтера (Lava Bounty)");

                // createServerBounty(OfflinePlayer, RewardItem)
                Method createBountyMethod = apiClass.getMethod("createServerBounty", OfflinePlayer.class, rewardItemClass);
                createBountyMethod.invoke(apiInstance, target, rewardItem);

                plugin.getLogger().info("Lava Bounty: Объявлена охота в LoveHunt на нарушителя " + suspectName);

                if (plugin.getConfig().getBoolean("integrations.lovehunt.broadcast", true)) {
                    Bukkit.broadcast(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                            "<newline><gradient:#ff4500:#ff8c00><b>🌋 [Lava Bounty] Объявлена серверная охота!</b></gradient><newline>"
                            + "<gray>Античит зафиксировал софт у <red><b>" + suspectName + "</b></red>. "
                            + "Успейте ликвидировать нарушителя до волны банов и заберите <gold>" + rewardAmount + "x " + rewardMaterial.name() + "</gold>!</gray><newline>"
                    ));
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Ошибка создания Lava Bounty в LoveHunt: " + t.getMessage());
            }
        });
    }
}
