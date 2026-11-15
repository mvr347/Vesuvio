package net.lovelace.vesuvio;

import static org.junit.jupiter.api.Assertions.*;
import io.papermc.paper.event.entity.EntityLungeEvent;
import java.lang.reflect.Proxy;
import java.util.UUID;
import net.lovelace.vesuvio.check.movement.*;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.listener.PhysicsExemptionListener;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class NativeLungeExemptionTest {
    private final UUID id = UUID.randomUUID();
    private final YamlConfiguration yaml = new YamlConfiguration();
    private final UserDataManager users = new UserDataManager();
    private final Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch(method.getName()) {
                case "getUniqueId" -> id;
                case "getGameMode" -> GameMode.CREATIVE;
                default -> throw new AssertionError("Must not read hunger/inventory: " + method.getName());
            });

    private ConfigManager config() {
        var plugin = (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch(method.getName()) {
                    case "getConfig" -> yaml;
                    case "saveDefaultConfig", "reloadConfig" -> null;
                    default -> throw new AssertionError(method.getName());
                });
        return new ConfigManager(plugin);
    }

    @Test void nativeLungeGrantsExemptionWithoutAnyExhaustionEvent() {
        var data = users.getOrCreate(id, "CreativePlayer");
        assertEquals(GameMode.CREATIVE, player.getGameMode());
        assertFalse(data.hasRecentVelocity());
        new PhysicsExemptionListener(users, config()).onLunge(new EntityLungeEvent(player, 1));
        assertTrue(data.hasRecentVelocity());
        data.setEnvironment(TestSnapshots.builder().insideSolidBlock(true).build());
        assertFalse(new SpeedCheck().check(data, 3.0, 0, 50, 1).isFlag());
        assertFalse(new FlyCheck().check(data, 3, 1.5, 0, false).isFlag());
        assertFalse(new StepUpCheck().check(data, 1.5, true).isFlag());
        assertFalse(new PhaseCheck().check(data, 3, 0).isFlag());
    }

    @Test void cancelledOrZeroPowerLungeDoesNotGrantExemption() {
        var data = users.getOrCreate(id, "Player");
        var listener = new PhysicsExemptionListener(users, config());
        var cancelled = new EntityLungeEvent(player, 1);
        cancelled.setCancelled(true);
        listener.onLunge(cancelled);
        assertFalse(data.hasRecentVelocity());
        listener.onLunge(new EntityLungeEvent(player, 0));
        assertFalse(data.hasRecentVelocity());
    }

    @Test void configurationCanDisableTheNativeExemption() {
        yaml.set("mechanics.movement.lunge-exemption.enabled", false);
        var data = users.getOrCreate(id, "Player");
        new PhysicsExemptionListener(users, config()).onLunge(new EntityLungeEvent(player, 1));
        assertFalse(data.hasRecentVelocity());
    }

    @Test void lateEventDoesNotResurrectDisconnectedUser() {
        new PhysicsExemptionListener(users, config()).onLunge(new EntityLungeEvent(player, 1));
        assertNull(users.get(id));
    }

    @Test void missingLegacyConfigUsesEnabledDefault() {
        assertTrue(config().isLungeExemptionEnabled());
    }
}
