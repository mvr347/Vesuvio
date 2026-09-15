package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.AimTrackingBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the geometry the strafe-reversal check is built on. The check itself needs a live
 * ConfigManager (Bukkit) to run, so these tests pin down the buffer maths and the reversal/response
 * detection that the verdict depends on - that is where a mistake would silently make the check
 * either blind or trigger-happy.
 */
class StrafeReversalTest {

    @Test
    void wrapDegreesTakesTheShortWayAroundTheCircle() {
        assertEquals(10f, AimTrackingBuffer.wrapDegrees(10f, 0f), 1e-4);
        assertEquals(-10f, AimTrackingBuffer.wrapDegrees(0f, 10f), 1e-4);
        // 350 -> 10 is a +20 turn, not -340.
        assertEquals(20f, AimTrackingBuffer.wrapDegrees(10f, 350f), 1e-4);
        assertEquals(-20f, AimTrackingBuffer.wrapDegrees(350f, 10f), 1e-4);
        assertEquals(180f, AimTrackingBuffer.wrapDegrees(180f, 0f), 1e-4);
    }

    @Test
    void bufferKeepsChronologicalOrderAcrossWraparound() {
        AimTrackingBuffer buffer = new AimTrackingBuffer();
        // Overfill so the ring wraps and the oldest entries are evicted.
        int total = AimTrackingBuffer.SIZE + 40;
        for (int i = 0; i < total; i++) {
            buffer.record(i, 0f, i * 2, 0f, i);
        }

        assertEquals(AimTrackingBuffer.SIZE, buffer.getCount());

        float[] reqYaw = new float[AimTrackingBuffer.SIZE];
        float[] actYaw = new float[AimTrackingBuffer.SIZE];
        float[] reqPitch = new float[AimTrackingBuffer.SIZE];
        float[] actPitch = new float[AimTrackingBuffer.SIZE];
        long[] stamps = new long[AimTrackingBuffer.SIZE];
        int n = buffer.copy(reqYaw, actYaw, reqPitch, actPitch, stamps);

        assertEquals(AimTrackingBuffer.SIZE, n);
        // Oldest retained entry is total - SIZE, and entries must come back in order.
        assertEquals(total - AimTrackingBuffer.SIZE, stamps[0]);
        assertEquals(total - 1, stamps[n - 1]);
        for (int i = 1; i < n; i++) {
            assertTrue(stamps[i] > stamps[i - 1], "entries must be chronological");
            assertEquals(stamps[i], (long) reqYaw[i], "required yaw must stay aligned with its timestamp");
            assertEquals(stamps[i] * 2, (long) actYaw[i], "actual yaw must stay aligned with its timestamp");
        }
    }

    // ------------------------------------------------------------------
    // The discriminator itself, expressed directly against the same maths the check runs.
    // ------------------------------------------------------------------

    /** Ticks until the camera picks up the target's new direction; -1 if it never does. */
    private static int responseTicks(float[] actualYaw, int eventIndex, int n,
                                      float targetVelAfter, int maxResponseTicks, double minCameraSpeed) {
        float wantedSign = Math.signum(targetVelAfter);
        int limit = Math.min(n - 1, eventIndex + maxResponseTicks);
        for (int j = eventIndex; j <= limit; j++) {
            float cameraVel = AimTrackingBuffer.wrapDegrees(actualYaw[j], actualYaw[j - 1]);
            if (Math.abs(cameraVel) >= minCameraSpeed && Math.signum(cameraVel) == wantedSign) {
                return j - eventIndex;
            }
        }
        return -1;
    }

    @Test
    void humanLagsBehindATargetReversal() {
        // Target strafes right, then reverses at index 10. A human keeps going the old way for
        // ~4 ticks (200ms of sensorimotor delay) before the correction lands.
        int n = 24;
        float[] actualYaw = new float[n];
        float yaw = 0f;
        for (int i = 0; i < n; i++) {
            actualYaw[i] = yaw;
            boolean afterReactionLanded = i >= 14;
            yaw += afterReactionLanded ? -3f : 3f;
        }

        int response = responseTicks(actualYaw, 10, n, -3f, 10, 0.75);
        assertTrue(response >= 3,
                "a human must not answer a reversal faster than sensorimotor delay allows, was " + response);
    }

    @Test
    void aimbotAnswersAReversalImmediately() {
        // Same reversal at index 10, but the camera flips direction in the same tick - it acted on
        // information a human could not have had yet.
        int n = 24;
        float[] actualYaw = new float[n];
        float yaw = 0f;
        for (int i = 0; i < n; i++) {
            actualYaw[i] = yaw;
            yaw += (i >= 10) ? -3f : 3f;
        }

        int response = responseTicks(actualYaw, 10, n, -3f, 10, 0.75);
        assertTrue(response >= 0 && response <= 1,
                "an angle recomputed server-side answers within a tick, was " + response);
    }

    @Test
    void cameraThatNeverFollowsIsNotJudged() {
        // The player simply stopped tracking (target left, they disengaged). That is neither
        // evidence for nor against a cheat, and must not be scored either way.
        int n = 24;
        float[] actualYaw = new float[n];
        for (int i = 0; i < n; i++) {
            actualYaw[i] = 0f; // camera frozen
        }

        assertEquals(-1, responseTicks(actualYaw, 10, n, -3f, 10, 0.75));
    }
}
