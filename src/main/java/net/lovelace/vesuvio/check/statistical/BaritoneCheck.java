package net.lovelace.vesuvio.check.statistical;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.entity.Player;

/**
 * Compatibility entry point for the retired heading-alignment heuristic.
 *
 * <p>Vanilla forward input rotates motion with the player's yaw, so holding W without moving the
 * mouse satisfies perfect look/movement alignment indefinitely. It is not independent evidence of a
 * pathfinder. Do not raise VL from this signal. A future detector needs validated independent
 * evidence; changing the tolerance or requiring more straight-line ticks cannot fix this flaw.
 */
public final class BaritoneCheck {
  public CheckResult check(Player player, UserData data, double deltaX, double deltaZ) {
    if (data != null) data.resetBaritoneStreak();
    return CheckResult.pass("Baritone");
  }
}
