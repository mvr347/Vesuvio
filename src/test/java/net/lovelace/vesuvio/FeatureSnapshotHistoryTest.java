package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.FeatureSnapshotHistory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A confirmed label used to record exactly one feature window - whatever happened to be in the
 * buffer at the instant the ban fired. These tests cover the rolling history that lets a label
 * cover several windows of the same session instead.
 */
public class FeatureSnapshotHistoryTest {

    private static float[] vec(float marker) {
        float[] f = new float[20];
        f[0] = marker;
        return f;
    }

    @Test
    public void testCaptureIsThrottledToTheConfiguredInterval() {
        FeatureSnapshotHistory history = new FeatureSnapshotHistory();
        long t = 1_000_000L;

        assertTrue(history.capture(t, 45_000L, vec(1f), null), "the first capture always lands");
        assertFalse(history.capture(t + 1_000L, 45_000L, vec(2f), null),
                "a capture one second later must be rejected - otherwise every sweep tick snapshots");
        assertTrue(history.capture(t + 45_000L, 45_000L, vec(3f), null),
                "a capture past the interval is accepted again");

        assertEquals(2, history.recent(t + 45_000L, 10 * 60_000L, 8).size());
    }

    @Test
    public void testShouldCaptureMatchesWhatCaptureWouldDo() {
        // The collector calls shouldCapture() to skip the feature extraction when the throttle has
        // not elapsed; if the two disagreed, it would either extract for nothing or skip captures.
        FeatureSnapshotHistory history = new FeatureSnapshotHistory();
        long t = 5_000_000L;

        assertTrue(history.shouldCapture(t, 45_000L));
        history.capture(t, 45_000L, vec(1f), null);
        assertFalse(history.shouldCapture(t + 44_999L, 45_000L));
        assertTrue(history.shouldCapture(t + 45_000L, 45_000L));
    }

    @Test
    public void testRecentReturnsNewestFirstAndRespectsTheLimit() {
        FeatureSnapshotHistory history = new FeatureSnapshotHistory();
        for (int i = 0; i < 6; i++) {
            history.capture(i * 60_000L, 45_000L, vec(i), null);
        }

        List<FeatureSnapshotHistory.Snapshot> recent = history.recent(5 * 60_000L, 10 * 60_000L, 3);
        assertEquals(3, recent.size());
        assertEquals(5f, recent.get(0).click()[0], 1e-6, "newest window first");
        assertEquals(4f, recent.get(1).click()[0], 1e-6);
        assertEquals(3f, recent.get(2).click()[0], 1e-6);
    }

    @Test
    public void testWindowsOlderThanTheAgeLimitAreDropped() {
        // A label says what the player was doing around the time it fired. A window from an hour
        // earlier may describe a completely different session and must not inherit that label.
        FeatureSnapshotHistory history = new FeatureSnapshotHistory();
        history.capture(0L, 45_000L, vec(1f), null);
        history.capture(60 * 60_000L, 45_000L, vec(2f), null);

        List<FeatureSnapshotHistory.Snapshot> recent = history.recent(60 * 60_000L, 10 * 60_000L, 8);
        assertEquals(1, recent.size(), "the hour-old window must not be harvested");
        assertEquals(2f, recent.get(0).click()[0], 1e-6);
    }

    @Test
    public void testRingEvictsOldestAndClearWipesEverything() {
        FeatureSnapshotHistory history = new FeatureSnapshotHistory();
        for (int i = 0; i < 20; i++) {
            history.capture(i * 60_000L, 45_000L, vec(i), null);
        }
        List<FeatureSnapshotHistory.Snapshot> recent = history.recent(19 * 60_000L, 24 * 60_000L, 32);
        assertEquals(8, recent.size(), "the ring is bounded - memory per online player stays fixed");
        assertEquals(19f, recent.get(0).click()[0], 1e-6);

        history.clear();
        assertTrue(history.recent(19 * 60_000L, 24 * 60_000L, 32).isEmpty(),
                "a harvested session must not be re-emitted by a second punishment");
    }

    @Test
    public void testAnEmptySnapshotIsNotRecorded() {
        FeatureSnapshotHistory history = new FeatureSnapshotHistory();
        assertFalse(history.capture(1_000L, 45_000L, null, null));
        assertTrue(history.recent(1_000L, 60_000L, 8).isEmpty());
    }
}
