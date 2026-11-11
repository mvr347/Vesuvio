package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.RateWindow;
import net.lovelace.vesuvio.engine.ServerHitch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateWindowTest {

    @Test
    void countsEventsInsideTheWindowOnly() {
        RateWindow w = new RateWindow(2000);
        assertEquals(1, w.record(0, 0));
        assertEquals(2, w.record(500, 0));
        assertEquals(3, w.record(1900, 0));
        // The first two have left the window by now.
        assertEquals(2, w.record(2600, 0));
    }

    @Test
    void aBurstProcessedInOneTickAfterAHangIsDiscardedNotCounted() {
        RateWindow w = new RateWindow(2000);
        // 35 placements the client made normally over 3.5 s, all processed at once after a 3 s server hang.
        long now = 10_000;
        long hitchAt = 9_999;
        int last = 0;
        for (int i = 0; i < 35; i++) last = w.record(now, hitchAt);
        // A hang inside the window clears it on every record, so a count above the threshold can never build up from the burst.
        assertTrue(last <= 1);
    }

    @Test
    void realFastPlaceWithoutAHangAccumulates() {
        RateWindow w = new RateWindow(2000);
        int last = 0;
        for (int i = 0; i < 40; i++) last = w.record(1000 + i * 20L, 0);
        assertEquals(40, last);
    }

    @Test
    void hitchOlderThanTheWindowDoesNotClear() {
        RateWindow w = new RateWindow(2000);
        w.record(10_000, 0);
        assertEquals(2, w.record(10_100, 5_000));
    }

    @Test
    void serverHitchIsDetectedFromATickGap() {
        ServerHitch.reset();
        ServerHitch.tick(1_000);
        ServerHitch.tick(1_050);
        assertEquals(0L, ServerHitch.lastHitchMillis());
        ServerHitch.tick(1_900); // an 850 ms gap
        assertEquals(1_900L, ServerHitch.lastHitchMillis());
        ServerHitch.reset();
    }
}
