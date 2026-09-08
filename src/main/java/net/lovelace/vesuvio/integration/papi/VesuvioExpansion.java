package net.lovelace.vesuvio.integration.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.lovelace.vesuvio.Vesuvio;
import net.lovelace.vesuvio.api.VesuvioAPI;
import net.lovelace.vesuvio.api.VesuvioProvider;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * PlaceholderAPI expansion for Vesuvio.
 * Exposes real-time anti-cheat metrics to scoreboards, tabs, chat, and DeluxeMenus.
 *
 * Author: Lovelace
 */
public final class VesuvioExpansion extends PlaceholderExpansion {

    private final Vesuvio plugin;

    public VesuvioExpansion(Vesuvio plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "vesuvio";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Lovelace";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || !VesuvioProvider.isAvailable()) {
            return "";
        }

        VesuvioAPI api = VesuvioProvider.get();
        var uuid = player.getUniqueId();

        switch (params.toLowerCase(Locale.ROOT)) {
            case "trust":
            case "trust_score":
                return String.format(Locale.US, "%.1f", api.getTrustScore(uuid));
            case "risk":
            case "risk_score":
                return String.format(Locale.US, "%.1f", api.getRiskScore(uuid));
            case "vl":
            case "violation_level":
                return String.format(Locale.US, "%.1f", api.getViolationLevel(uuid));
            case "status":
                return api.getPlayerStatus(uuid);
            case "is_suspect":
                return String.valueOf(api.isSuspect(uuid));
            case "is_high_risk":
                return String.valueOf(api.isHighRisk(uuid));
            case "is_wave_queued":
                return String.valueOf(api.isQueuedForWave(uuid));
            case "brand":
            case "client_brand":
                return api.getClientBrand(uuid);
            case "risk_color": {
                double risk = api.getRiskScore(uuid);
                if (risk >= 75.0) return "&c";
                if (risk >= 40.0) return "&e";
                return "&a";
            }
            case "trust_color": {
                double trust = api.getTrustScore(uuid);
                if (trust >= 80.0) return "&a";
                if (trust >= 50.0) return "&e";
                return "&c";
            }
            default:
                return null;
        }
    }
}
