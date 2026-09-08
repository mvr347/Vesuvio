package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.selflearning.AnomalyMemory;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class AnomalyMemoryTest {

    @Test
    public void testBorderlineObservationAndRepeatScore() {
        AnomalyMemory memory = new AnomalyMemory();
        UUID player = UUID.randomUUID();

        long[] borderlineSig = new long[]{0xAAAAAAAAAAAAAAAAL, 100L, 200L, 300L, 400L, 500L, 600L, 700L};

        // 1. Observe non-borderline pass (confidence 0.1) -> should not record
        memory.observe(player, borderlineSig, new CheckResult(false, 0.10, 0.0, "Click", "OK", Collections.emptyMap()));
        assertEquals(0.0f, memory.getRepeatScore(player, borderlineSig));

        // 2. Observe borderline check (confidence 0.65)
        memory.observe(player, borderlineSig, new CheckResult(true, 0.65, 1.0, "Click", "Borderline", Collections.emptyMap()));

        // 3. Current pattern matches the recorded anomaly
        float repeatScore = memory.getRepeatScore(player, borderlineSig);
        assertEquals(1.0f, repeatScore, 0.01f, "Repeat score should be 1.0 when matching single anomaly");

        // 4. Current pattern completely different (all bits inverted)
        long[] differentSig = new long[8];
        for (int i = 0; i < 8; i++) {
            differentSig[i] = ~borderlineSig[i];
        }
        float lowRepeat = memory.getRepeatScore(player, differentSig);
        assertEquals(0.0f, lowRepeat, 0.01f);
    }
}
