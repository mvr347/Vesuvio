package net.lovelace.vesuvio;

import net.lovelace.vesuvio.engine.HitboxHistoryTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ReachRaycastTest {

    @Test
    public void testBoxSnapshotDistanceCalculation() {
        // Target bounding box from [0, 0, 0] to [1, 2, 1]
        HitboxHistoryTracker.BoxSnapshot box = new HitboxHistoryTracker.BoxSnapshot(
                0.0, 0.0, 0.0,
                1.0, 2.0, 1.0,
                System.nanoTime()
        );

        // Point inside box -> distance 0
        assertEquals(0.0, box.distanceTo(0.5, 1.0, 0.5), 0.001);

        // Point offset by 3.0 along X: (4.0, 1.0, 0.5) -> dx = 4.0 - 1.0 = 3.0
        assertEquals(3.0, box.distanceTo(4.0, 1.0, 0.5), 0.001);

        // Point offset along X and Z: (4.0, 1.0, 5.0) -> dx = 3.0, dz = 4.0 -> distance = 5.0
        assertEquals(5.0, box.distanceTo(4.0, 1.0, 5.0), 0.001);

        // Typical Minecraft reach distance: Eye at (0.5, 1.62, -3.05), Box at [0, 0, 0] to [1, 2, 0.6]
        // dz = 0.0 - (-3.05) = 3.05
        HitboxHistoryTracker.BoxSnapshot target = new HitboxHistoryTracker.BoxSnapshot(
                0.0, 0.0, 0.0,
                0.6, 1.8, 0.6,
                System.nanoTime()
        );
        double dist = target.distanceTo(0.3, 1.62, -2.5);
        assertEquals(2.5, dist, 0.001);
    }
}
