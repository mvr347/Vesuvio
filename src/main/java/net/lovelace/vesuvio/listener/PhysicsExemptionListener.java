package net.lovelace.vesuvio.listener;

import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.player.PlayerRiptideEvent;

import java.util.List;

/**
 * Grants the shared "recent velocity" exemption ({@link UserData#recordVelocity()}, already
 * respected by Speed/Fly/Phase/StepUp via {@link UserData#hasRecentVelocity()}) for two vanilla
 * physics events that legitimately move a player outside their own input: a Trident's Riptide
 * enchantment, and a piston shoving or retracting a block near them.
 *
 * <p>Neither currently produces anything the movement checks recognise as a server-applied
 * impulse - Riptide's dash and a piston's shove are not delivered the way knockback is (see
 * {@code TransactionPacketListener}, which captures the outbound EntityVelocity packet) - so
 * without this a rain-riptide dash reads as unexplained excess speed, and standing next to an
 * active piston reads as a block-clip (Phase) or an impossible step (StepUp).
 *
 * Author: Lovelace
 */
public final class PhysicsExemptionListener implements Listener {

    private final UserDataManager userDataManager;
    private final ConfigManager config;

    public PhysicsExemptionListener(UserDataManager userDataManager, ConfigManager config) {
        this.userDataManager = userDataManager;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRiptide(PlayerRiptideEvent event) {
        if (!config.isRiptideExemptionEnabled()) return;
        UserData data = userDataManager.get(event.getPlayer().getUniqueId());
        if (data != null) data.recordVelocity();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!config.isPistonExemptionEnabled()) return;
        exemptNearbyPlayers(event.getBlock(), event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!config.isPistonExemptionEnabled()) return;
        exemptNearbyPlayers(event.getBlock(), event.getBlocks(), event.getDirection());
    }

    /**
     * A player standing where a piston head lands, or overlapping any block it pushes/pulls, gets
     * shoved by vanilla exactly like an entity caught in the way - that shove is what would
     * otherwise read as a Phase clip or a StepUp/Speed excess for the next tick or two.
     */
    private void exemptNearbyPlayers(Block piston, List<Block> movedBlocks, BlockFace direction) {
        World world = piston.getWorld();
        double radius = config.getPistonExemptionRadius();
        double radiusSq = radius * radius;

        Location pistonHead = piston.getRelative(direction).getLocation().add(0.5, 0.5, 0.5);

        for (Player player : world.getPlayers()) {
            UserData data = userDataManager.get(player.getUniqueId());
            if (data == null) continue;

            Location playerLoc = player.getLocation();
            boolean nearPistonHead = playerLoc.distanceSquared(pistonHead) <= radiusSq;
            boolean nearMovedBlock = !nearPistonHead && movedBlocks.stream().anyMatch(b ->
                    playerLoc.distanceSquared(b.getLocation().add(0.5, 0.5, 0.5)) <= radiusSq);

            if (nearPistonHead || nearMovedBlock) {
                data.recordVelocity();
            }
        }
    }
}
