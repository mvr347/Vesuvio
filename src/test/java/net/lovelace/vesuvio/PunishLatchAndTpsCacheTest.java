package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.TpsCache;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PunishLatchAndTpsCacheTest {
    @Test void latchFiresOncePerSessionEvenUnderContention() {
        var data = new UserData(UUID.randomUUID(), "Cheater");
        var wins = new AtomicInteger();
        IntStream.range(0, 64).parallel().forEach(i -> { if (data.tryLatchPunished()) wins.incrementAndGet(); });
        assertEquals(1, wins.get());
        assertTrue(data.isPunished());
    }

    @Test void resetAndNewSessionStartUnlatched() {
        var data = new UserData(UUID.randomUUID(), "Cheater");
        assertTrue(data.tryLatchPunished());
        assertFalse(data.tryLatchPunished());
        data.resetPunishedLatch();
        assertTrue(data.tryLatchPunished());
        assertTrue(new UserData(data.getUuid(), "Cheater").tryLatchPunished());
    }

    @Test void tpsCacheIgnoresGarbageAndClampsToTwenty() {
        TpsCache.update(new double[]{18.5});
        assertEquals(18.5, TpsCache.get());
        TpsCache.update(new double[]{Double.NaN});
        TpsCache.update(new double[]{0});
        TpsCache.update(new double[0]);
        assertEquals(18.5, TpsCache.get());
        TpsCache.update(new double[]{20.7});
        assertEquals(20.0, TpsCache.get());
    }
}
