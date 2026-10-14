package net.lovelace.vesuvio;

import net.lovelace.vesuvio.config.RuntimeControls;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeControlsTest {
    @Test void pausedWaveDoesNotReachTheServerOrScheduleCommands() {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        var plugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{org.bukkit.plugin.Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConfig" -> yaml;
                    case "saveDefaultConfig", "reloadConfig" -> null;
                    default -> throw new AssertionError("Unexpected plugin access: " + method.getName());
                });
        var config = new net.lovelace.vesuvio.config.ConfigManager(plugin);
        var wave = new net.lovelace.vesuvio.punishment.PunishmentWaveManager(plugin, config, null);
        var id = java.util.UUID.randomUUID();
        wave.queuePunishment(id, "Player", "ban Player", "test");
        assertEquals(1, wave.getQueueSize());
        config.setPunishmentEnabled(false);
        assertEquals(0, wave.executeWave());
        wave.queuePunishment(java.util.UUID.randomUUID(), "Other", "ban Other", "test");
        assertEquals(1, wave.getQueueSize());
        config.reload();
        assertFalse(config.isPunishmentEnabled(), "Reload must preserve observation mode");
        config.setPunishmentEnabled(true);
        config.setAnticheatEnabled(false);
        assertEquals(0, wave.executeWave());
    }

    @Test void observationModeKeepsChecksAndInvalidatesQueuedPunishments() {
        var controls = new RuntimeControls();
        long detection = controls.detectionEpoch(), action = controls.actionEpoch();
        controls.setPunishments(false);
        assertTrue(controls.acceptsDetection(detection));
        assertFalse(controls.mayPunish());
        assertFalse(controls.acceptsAction(action));
        controls.setPunishments(true);
        assertTrue(controls.mayPunish());
        assertFalse(controls.acceptsAction(action), "Old kicks/bans must not fire after re-enabling");
    }

    @Test void fullDisableCancelsBothChecksAndActionsWithoutChangingObservationPreference() {
        var controls = new RuntimeControls();
        long detection = controls.detectionEpoch();
        controls.setPunishments(false);
        controls.setDetection(false);
        assertFalse(controls.acceptsDetection(detection));
        controls.setDetection(true);
        assertFalse(controls.acceptsDetection(detection));
        assertFalse(controls.mayPunish());
        assertTrue(controls.acceptsDetection(controls.detectionEpoch()));
    }

    @Test void explicitOnIsIdempotent() {
        var controls = new RuntimeControls();
        long action = controls.actionEpoch();
        controls.setDetection(true);
        controls.setPunishments(true);
        assertTrue(controls.acceptsAction(action));
    }
}
