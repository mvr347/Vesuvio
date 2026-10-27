package net.lovelace.vesuvio;

import static org.junit.jupiter.api.Assertions.*;

import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.integration.essential.MechanicsState;

import org.junit.jupiter.api.Test;

import java.util.*;

class MechanicsStateTest {
    @Test
    void carrierAndHelperKeepUnrelatedChecks() {
        var carrier = new MechanicsState(Set.of("CARRYING"), 0);
        assertFalse(carrier.movement(1));
        assertFalse(carrier.combat(1));
        assertFalse(carrier.hitbox(1));
        var helper = new MechanicsState(Set.of("REVIVING"), 0);
        assertTrue(helper.movement(1));
        assertFalse(helper.combat(1));
        assertFalse(helper.hitbox(1));
    }

    @Test
    void everyForcedPoseControlsGeometry() {
        for (String state : Set.of("SITTING", "LYING", "RIDING", "DOWNED")) {
            var pose = new MechanicsState(Set.of(state), 0);
            assertTrue(pose.movement(1));
            assertTrue(pose.combat(1));
            assertTrue(pose.hitbox(1));
        }
    }

    @Test
    void transitionExpiresAndPollingCannotExtendIt() {
        var data = new UserData(UUID.randomUUID(), "Traveler");
        data.addVl(10);
        data.setLastPosition(1, 2, 3, false);
        data.updateMechanics(Set.of("LYING"), 100, 50, false);
        assertFalse(data.hasLastPosition());
        assertEquals(10, data.getVl());
        long reset = data.getMovementResetNanos();
        data.updateMechanics(Set.of("LYING"), 120, 50, false);
        assertEquals(reset, data.getMovementResetNanos());
        assertEquals(150, data.mechanics().transitionUntilNanos());
        data.updateMechanics(Set.of(), 200, 50, false);
        assertTrue(data.mechanics().movement(249));
        assertFalse(data.mechanics().movement(250));
        assertFalse(data.mechanics().combat(250));
        assertEquals(10, data.getVl());
    }

    @Test
    void immutableStateAndPulseInvalidatesQueuedEpoch() {
        var mutable = new HashSet<>(Set.of("DOWNED"));
        var state = new MechanicsState(mutable, 0);
        mutable.clear();
        assertTrue(state.movement(1));
        assertThrows(UnsupportedOperationException.class, () -> state.states().clear());
        var data = new UserData(UUID.randomUUID(), "SkinChange");
        long epoch = data.getMovementResetNanos();
        data.updateMechanics(Set.of(), 100, 50, true);
        assertNotEquals(epoch, data.getMovementResetNanos());
        assertFalse(data.mechanics().movement(150));
    }
}
