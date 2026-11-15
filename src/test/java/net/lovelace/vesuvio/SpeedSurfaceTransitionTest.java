package net.lovelace.vesuvio;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.lovelace.vesuvio.check.movement.SpeedCheck;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class SpeedSurfaceTransitionTest {
    @Test void sprintJumpingFromBlueIceToStoneDoesNotFlag() {
        var check = new SpeedCheck();
        var data = new UserData(UUID.randomUUID(), "Vanilla");
        double velocity = 0, y = 0, vy = 0;
        boolean grounded = true;
        for (int tick = 0; tick < 400; tick++) {
            boolean ice = tick < 300;
            double slip = ice ? 0.989 : 0.6;
            data.setEnvironment(TestSnapshots.builder().blockBelow(ice ? Material.BLUE_ICE : Material.STONE)
                    .movementSpeed(0.13).build());
            boolean jump = grounded && tick > 10;
            if (jump) { velocity += 0.2; vy = 0.42; }
            double displacement = velocity + (grounded ? 0.13 * 0.98 * 0.216 / Math.pow(slip, 3) : 0.026 * 0.98);
            velocity = displacement * (grounded ? slip * 0.91 : 0.91);
            double dy = 0;
            if (!grounded || jump) {
                double next = Math.min(0.2, y + vy);
                dy = next - y;
                y = next;
                vy = (vy - 0.08) * 0.98;
                if (y >= 0.2 - 1e-9 && vy > 0) vy = 0;
                if (y <= 0) { dy -= y; y = 0; grounded = true; vy = 0; }
                else grounded = false;
            }
            if (grounded) data.resetAirTicks(); else data.incrementAirTicks();
            var result = check.check(data, displacement, 0, dy, grounded, 50, 1);
            assertFalse(result.isFlag(), "tick=" + tick + " " + result);
        }
    }
}
