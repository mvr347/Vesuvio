package net.lovelace.vesuvio.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Advanced Preset Management Engine for Vesuvio.
 * Supports hot-swapping pre-tuned profiles:
 * - balanced (Default Survival & Minigames)
 * - strict (Competitive / Ranked PvP / BedWars / Duels)
 * - lenient (Casual / High Ping / Crossplay)
 * - anarchy (Pure telemetry & logging, zero auto-punishments)
 *
 * Users can also save custom presets via '/vesuvio preset save <name>'.
 *
 * Author: Lovelace
 */
public final class PresetManager {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-Presets");
    private static final String[] DEFAULT_PRESETS = {"balanced", "strict", "lenient", "anarchy"};

    private final Plugin plugin;
    private final ConfigManager configManager;
    private final Path presetsDir;

    public PresetManager(Plugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.presetsDir = plugin.getDataFolder().toPath().resolve("presets");
        initPresets();
    }

    /**
     * Initializes the presets folder and copies default template files if missing.
     */
    public void initPresets() {
        try {
            if (!Files.exists(presetsDir)) {
                Files.createDirectories(presetsDir);
            }

            for (String preset : DEFAULT_PRESETS) {
                Path target = presetsDir.resolve(preset + ".yml");
                if (!Files.exists(target)) {
                    try (InputStream in = plugin.getResource("presets/" + preset + ".yml")) {
                        if (in != null) {
                            Files.copy(in, target);
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to initialize default presets: " + e.getMessage());
        }
    }

    /**
     * Returns a list of all installed preset names.
     */
    public List<String> getAvailablePresets() {
        List<String> list = new ArrayList<>();
        File dir = presetsDir.toFile();
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
            if (files != null) {
                for (File f : files) {
                    list.add(f.getName().substring(0, f.getName().length() - 4));
                }
            }
        }
        Collections.sort(list);
        return list;
    }

    /**
     * Applies a preset by merging its values into config.yml and reloading.
     */
    public boolean applyPreset(String presetName) {
        Path target = presetsDir.resolve(presetName.toLowerCase() + ".yml");
        if (!Files.exists(target)) {
            return false;
        }

        FileConfiguration presetConfig = YamlConfiguration.loadConfiguration(target.toFile());
        FileConfiguration mainConfig = plugin.getConfig();

        // Recursively merge preset keys into main configuration
        for (String key : presetConfig.getKeys(true)) {
            if (!presetConfig.isConfigurationSection(key)) {
                mainConfig.set(key, presetConfig.get(key));
            }
        }

        plugin.saveConfig();
        configManager.reload();
        return true;
    }

    /**
     * Saves the current active configuration as a new preset.
     */
    public boolean savePreset(String presetName) {
        Path target = presetsDir.resolve(presetName.toLowerCase() + ".yml");
        try {
            FileConfiguration mainConfig = plugin.getConfig();
            mainConfig.save(target.toFile());
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to save preset " + presetName, e);
            return false;
        }
    }

    /**
     * Retrieves the description or summary for a given preset.
     */
    public String getPresetDescription(String presetName) {
        Path target = presetsDir.resolve(presetName.toLowerCase() + ".yml");
        if (!Files.exists(target)) {
            return "Custom server preset";
        }
        FileConfiguration presetConfig = YamlConfiguration.loadConfiguration(target.toFile());
        return presetConfig.getString("description", "Vesuvio configuration preset");
    }
}
