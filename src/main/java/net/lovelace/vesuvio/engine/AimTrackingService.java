package net.lovelace.vesuvio.engine;

import net.lovelace.vesuvio.check.statistical.KillauraAngleCheck;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main-thread, once-per-tick capture of each fighting player's aim relative to the entity they are
 * actually attacking, into {@link net.lovelace.vesuvio.data.AimTrackingBuffer}.
 *
 * <p>Deliberately cheap: it only touches players who are in combat and have a live target, and per
 * player does one eye-location read plus a few vector operations. Entities are held weakly and
 * revalidated every tick, so a despawned or unloaded target is dropped rather than pinned in
 * memory by this tracker.
 *
 * Author: Lovelace
 */
public final class AimTrackingService {

    private final UserDataManager userDataManager;
    private final Map<UUID, WeakReference<Entity>> targets = new ConcurrentHashMap<>();

    public AimTrackingService(UserDataManager userDataManager) {
        this.userDataManager = userDataManager;
    }

    /** Records which entity a player just attacked, so the per-tick capture knows what to track. */
    public void noteTarget(Player attacker, Entity target) {
        if (attacker == null || target == null) return;
        targets.put(attacker.getUniqueId(), new WeakReference<>(target));
    }

    public void forgetPlayer(UUID uuid) {
        targets.remove(uuid);
    }

    /** Called once per tick on the main thread. */
    public void tick(Iterable<? extends Player> onlinePlayers) {
        long now = System.nanoTime();
        for (Player player : onlinePlayers) {
            UserData data = userDataManager.get(player.getUniqueId());
            if (data == null || !data.isInCombat()) continue;

            WeakReference<Entity> ref = targets.get(player.getUniqueId());
            if (ref == null) continue;
            Entity target = ref.get();
            if (target == null) {
                targets.remove(player.getUniqueId());
                continue;
            }
            if (!target.isValid() || !target.getWorld().equals(player.getWorld())) continue;

            Location eye = player.getEyeLocation();
            Vector toTarget = target.getBoundingBox().getCenter().subtract(eye.toVector());
            // Inside the target's own body the required angle is meaningless (it swings wildly for
            // sub-block movement), which would fabricate "target manoeuvres" that never happened.
            if (toTarget.lengthSquared() < 0.25) continue;

            float requiredYaw = KillauraAngleCheck.requiredYaw(toTarget.getX(), toTarget.getZ());
            float requiredPitch = KillauraAngleCheck.requiredPitch(toTarget.getX(), toTarget.getY(), toTarget.getZ());

            data.getAimTrackingBuffer().record(requiredYaw, requiredPitch, eye.getYaw(), eye.getPitch(), now);
        }
    }
}
