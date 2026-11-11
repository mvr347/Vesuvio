package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.DecayingEvidence;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.listener.WorldInteractionListener;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldEvidenceTest {

    @Test
    void aSingleEventIsNeverAVerdict() {
        DecayingEvidence e = new DecayingEvidence();
        assertFalse(WorldInteractionListener.accumulate(e, 2));
        assertTrue(WorldInteractionListener.accumulate(e, 2), "the second event inside the window flags");
        assertFalse(WorldInteractionListener.accumulate(e, 2), "and the accumulator restarted");
    }

    @Test
    void threeEventsAreNeededWhenThreeAreRequired() {
        DecayingEvidence e = new DecayingEvidence();
        assertFalse(WorldInteractionListener.accumulate(e, 3));
        assertFalse(WorldInteractionListener.accumulate(e, 3));
        assertTrue(WorldInteractionListener.accumulate(e, 3));
    }

    @Test
    void ownCancelledBlockActionOpensADesyncWindowThatScalesWithRtt() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        assertFalse(d.inOwnBlockDesyncWindow(40.0), "nothing cancelled yet");
        d.markOwnBlockDesync();
        assertTrue(d.inOwnBlockDesyncWindow(40.0));
        assertTrue(d.inOwnBlockDesyncWindow(-1.0), "unknown RTT counts as 0 plus the 500 ms");
    }

    @Test
    void vehicleDeltaYReturnsThePreviousTickOnlyWhenItIsRecent() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        assertTrue(Double.isNaN(d.swapVehicleDeltaY(0.4, 1_000)));
        assertEquals(0.4, d.swapVehicleDeltaY(0.3, 1_050), 1e-9);
        assertTrue(Double.isNaN(d.swapVehicleDeltaY(0.2, 5_000)), "a stale previous value is not a trend");
    }
}
