package net.lovelace.vesuvio;

import net.lovelace.vesuvio.engine.StickyWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StickyWindowTest {

    @Test
    void stateStaysOnForTwentyTicksAfterItEnds() {
        StickyWindow w = new StickyWindow(20);
        assertFalse(w.update(0, false));
        assertTrue(w.update(1, true));
        assertTrue(w.update(2, false));
        assertTrue(w.update(21, false), "20 ticks after the last active tick");
        assertFalse(w.update(22, false), "released after that");
    }

    @Test
    void reactivationRestartsTheWindow() {
        StickyWindow w = new StickyWindow(20);
        w.update(1, true);
        assertTrue(w.update(15, true));
        assertTrue(w.update(35, false));
        assertFalse(w.update(36, false));
    }

    @Test
    void rollingMaximumKeepsTheBestValueForTwentyTicks() {
        StickyWindow w = new StickyWindow(20);
        assertEquals(0.13, w.max(1, 0.13), 1e-9);
        // The Speed effect is gone on the server: the attribute drops, the client has not noticed yet.
        assertEquals(0.13, w.max(2, 0.1), 1e-9);
        assertEquals(0.13, w.max(20, 0.1), 1e-9);
        assertEquals(0.1, w.max(21, 0.1), 1e-9, "expired after 20 ticks");
    }
}
