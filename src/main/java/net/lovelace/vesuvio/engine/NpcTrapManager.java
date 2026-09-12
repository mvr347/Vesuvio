package net.lovelace.vesuvio.engine;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.lovelace.vesuvio.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake-NPC trap for KillAura's most exploitable pattern: automatic nearest-entity targeting.
 *
 * <h2>Why this is close to unfalsifiable</h2>
 * A packet-level entity is spawned only for the suspect player, positioned somewhere a human
 * paying attention to their own screen has no reason to look at or click - directly behind them,
 * out of their forward view. A legitimate player never sees it as a threat, is never aiming near
 * it, and has nothing to click. A KillAura or MobAura module that auto-selects "the nearest valid
 * entity" instead of what the player is actually looking at has no such restraint: it attacks
 * anything in range indiscriminately, this trap included. Landing a hit on an entity that exists
 * for exactly one player, was never rendered as anything the player would reasonably target, and
 * was placed specifically to be undetectable by normal play is about as close to definitive proof
 * as packet-level analysis gets.
 *
 * <h2>Why it is gated behind existing suspicion</h2>
 * Spawning and tracking a fake entity per player has a real (if small) cost, and false "why is
 * there a random zombie behind me" confusion for legitimate players is worth avoiding. This is
 * only offered to players {@link net.lovelace.vesuvio.data.UserData#isSuspect} already flags as
 * high-risk from other combat checks - it exists to convert an accumulating suspicion into a
 * conclusive answer, not to surveil every player.
 *
 * Author: Lovelace
 */
public final class NpcTrapManager {

    /**
     * Entity IDs must not collide with anything the server itself ever assigns. Real entity IDs on
     * any server that has been running for a normal lifetime are nowhere near this range, so a
     * fixed high starting point with a simple increment is sufficient without needing to query
     * live world state for "IDs currently in use".
     */
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(2_000_000_000);

    private record TrapEntry(int entityId, long spawnedAtMillis) {}

    private final Plugin plugin;
    private final ConfigManager config;
    private final Map<UUID, TrapEntry> activeTraps = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTrapMillis = new ConcurrentHashMap<>();

    public NpcTrapManager(Plugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Spawns a trap for this player if none is currently active and the per-player cooldown has
     * elapsed. Safe to call on every suspicious action - the cooldown and the "already active"
     * check make it a cheap no-op the rest of the time.
     */
    public void maybeSpawnTrap(Player player) {
        if (!config.isNpcTrapEnabled()) return;

        UUID uuid = player.getUniqueId();
        if (activeTraps.containsKey(uuid)) return;

        long now = System.currentTimeMillis();
        Long lastTrap = lastTrapMillis.get(uuid);
        long cooldownMs = config.getNpcTrapCooldownSeconds() * 1000L;
        if (lastTrap != null && (now - lastTrap) < cooldownMs) return;

        spawnTrap(player, now);
    }

    private void spawnTrap(Player player, long now) {
        int entityId = NEXT_ENTITY_ID.getAndIncrement();
        Location eye = player.getEyeLocation();

        // Directly behind the player's current facing, at roughly chest height - within melee
        // range of an auto-targeting aura, but nowhere a player looking at their own screen would
        // be aiming or expecting anything to be.
        Vector behind = eye.getDirection().setY(0);
        if (behind.lengthSquared() < 1e-6) behind = new Vector(0, 0, 1);
        behind.normalize().multiply(-1.4);
        Location trapLoc = eye.clone().add(behind).subtract(0, 0.3, 0);

        var spawnPacket = new WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(UUID.randomUUID()),
                EntityTypes.ZOMBIE,
                new Vector3d(trapLoc.getX(), trapLoc.getY(), trapLoc.getZ()),
                0f, 0f, 0f,
                0,
                Optional.empty()
        );

        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user == null) return;
        user.sendPacketSilently(spawnPacket);

        UUID uuid = player.getUniqueId();
        activeTraps.put(uuid, new TrapEntry(entityId, now));
        lastTrapMillis.put(uuid, now);

        long lifetimeTicks = 20L * config.getNpcTrapLifetimeSeconds();
        Bukkit.getScheduler().runTaskLater(plugin, () -> despawnTrap(uuid), lifetimeTicks);
    }

    /**
     * Whether {@code entityId} is the currently active trap for this player - the whole point of
     * the mechanism: an attack packet naming this ID could only have come from targeting something
     * that does not exist for any other player and was never in the suspect's actual field of
     * view.
     */
    public boolean isTrapEntity(UUID playerUuid, int entityId) {
        TrapEntry entry = activeTraps.get(playerUuid);
        return entry != null && entry.entityId() == entityId;
    }

    public void despawnTrap(UUID uuid) {
        TrapEntry entry = activeTraps.remove(uuid);
        if (entry == null) return;

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user != null) {
            user.sendPacketSilently(new WrapperPlayServerDestroyEntities(entry.entityId()));
        }
    }

    /** Call from PlayerQuitEvent so a departed player's trap state cannot leak. */
    public void forgetPlayer(UUID uuid) {
        activeTraps.remove(uuid);
        lastTrapMillis.remove(uuid);
    }
}
