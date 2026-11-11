package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RotationRingTest {

    private static final long MS = 1_000_000L;

    @Test
    void firstRotationAfterAttackWithin150msIsFound() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        long attack = 1_000 * MS;
        d.recordRotationSample(10f, 5f, attack - 20 * MS, 1f);
        d.recordRotationSample(40f, 6f, attack + 30 * MS, 30f); // the correction sent in the same client tick
        d.recordRotationSample(70f, 6f, attack + 80 * MS, 30f);
        var r = d.firstRotationAfter(attack, 150 * MS);
        assertTrue(r.present());
        assertEquals(40f, r.yaw(), 1e-6);
    }

    @Test
    void rotationOutsideTheWindowIsIgnored() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        long attack = 1_000 * MS;
        d.recordRotationSample(40f, 6f, attack + 400 * MS, 30f);
        assertFalse(d.firstRotationAfter(attack, 150 * MS).present());
    }

    @Test
    void lastSignificantRotationOnlyLooksBeforeTheAttack() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        long attack = 1_000 * MS;
        d.recordRotationSample(10f, 5f, attack - 300 * MS, 12f);   // significant, before
        d.recordRotationSample(11f, 5f, attack - 100 * MS, 0.2f);  // jitter, before
        d.recordRotationSample(50f, 5f, attack + 20 * MS, 39f);    // significant, but after the attack packet
        assertEquals(attack - 300 * MS, d.lastSignificantRotationBefore(attack, 2.0));
        assertEquals(0L, d.lastSignificantRotationBefore(attack - 400 * MS, 2.0));
    }

    @Test
    void ringKeepsTheNewestSamples() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        for (int i = 0; i < 100; i++) d.recordRotationSample(i, 0f, i * 10 * MS, 5f);
        assertTrue(d.firstRotationAfter(98 * 10 * MS, 150 * MS).present());
        assertFalse(d.firstRotationAfter(5 * 10 * MS, 20 * MS).present(), "old samples were overwritten");
    }
}
