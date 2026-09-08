package net.lovelace.vesuvio.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Advanced Mechanic 3.3: Client Brand & Soft Mod Detection.
 * Intercepts incoming brand channels ("minecraft:brand", "MC|Brand") and inspects signatures.
 * Soft-mods and suspicious brands adjust player Risk Index and Trust Score silently without bans.
 *
 * Author: Lovelace
 */
public final class BrandPacketListener extends PacketListenerAbstract {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-Brand");

    private final UserDataManager userDataManager;
    private final ConfigManager config;

    public BrandPacketListener(UserDataManager userDataManager, ConfigManager config) {
        super(PacketListenerPriority.LOWEST);
        this.userDataManager = userDataManager;
        this.config = config;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            Player player = (Player) event.getPlayer();
            if (player == null) return;

            WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);
            String channel = packet.getChannelName();

            if ("minecraft:brand".equalsIgnoreCase(channel) || "MC|Brand".equalsIgnoreCase(channel)) {
                byte[] data = packet.getData();
                if (data != null && data.length > 0) {
                    String rawBrand = parseBrandString(data);
                    processBrand(player, rawBrand);
                }
            }
        }
    }

    public void processBrand(Player player, String brand) {
        if (brand == null || brand.isBlank()) return;

        // Sanitize before storing: brand is fully attacker-controlled and later gets interpolated
        // into MiniMessage templates for staff alerts (SmartAlertService) - strip anything that
        // could break out of quoting or inject MiniMessage tags/click-actions.
        brand = brand.replaceAll("[<>'\\\\]", "");
        if (brand.length() > 64) {
            brand = brand.substring(0, 64);
        }
        if (brand.isBlank()) return;

        UserData userData = userDataManager.getOrCreate(player);
        userData.setClientBrand(brand);

        if (!config.isBrandCheckEnabled()) return;

        String lower = brand.toLowerCase();

        // Check against known cheat brands
        for (String suspicious : config.getSuspiciousBrands()) {
            if (lower.contains(suspicious.toLowerCase())) {
                userData.adjustTrust(-40.0);
                userData.adjustRisk(35.0);
                LOGGER.warning(String.format("[Vesuvio] Flagged suspicious client brand '%s' for player %s (Trust: %.0f, Risk: %.0f)",
                        brand, player.getName(), userData.getTrustScore(), userData.getRiskIndex()));
                return;
            }
        }

        // Beneficial trust adjustment for recognized legitimate clients
        if (lower.contains("lunar") || lower.contains("badlion") || lower.contains("feather")) {
            userData.adjustTrust(5.0);
            userData.adjustRisk(-5.0);
        }
    }

    private String parseBrandString(byte[] data) {
        try {
            // In Minecraft protocol, first byte(s) may be a VarInt length
            int offset = 0;
            if (data.length > 1 && data[0] > 0 && data[0] < data.length) {
                offset = 1;
            }
            return new String(data, offset, data.length - offset, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return new String(data, StandardCharsets.UTF_8).trim();
        }
    }
}
