package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.OrderedTaskQueue;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PacketOrderAndTeleportTest {
    @Test void swingMayArriveAfterAttackAndMustNotBeOverwrittenByTheNextSwing() {
        var data = new UserData(UUID.randomUUID(), "Fighter");
        long attack = 1_000_000_000L;
        assertFalse(data.hasSwingNear(attack));
        data.setLastSwingNanos(attack + 20_000_000L);
        data.setLastSwingNanos(attack + 200_000_000L);
        assertTrue(data.hasSwingNear(attack));
        assertFalse(data.hasSwingNear(attack + 600_000_000L));
    }
    @Test void checksRemainOrderedEvenWhenFirstWorkerIsDelayed() throws Exception {
        var queue = new OrderedTaskQueue();
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(100);
        List<Integer> observed = new CopyOnWriteArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                int sequence = i;
                queue.submit(executor, () -> {
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException ex) { throw new RuntimeException(ex); }
                    observed.add(sequence);
                    finished.countDown();
                });
            }
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
        assertEquals(java.util.stream.IntStream.range(0, 100).boxed().toList(), observed);
    }

    @Test void teleportDropsOldMotionButKeepsViolationHistory() {
        var data = new UserData(UUID.randomUUID(), "Traveler");
        data.setLastPosition(1000, 100, 1000, false);
        data.setPrevHorizontalSpeed(5);
        data.setSpeedPredictionDebt(2);
        data.setTimerBalanceMs(1000);
        data.incrementAirTicks();
        data.addVl(10);
        long reset = data.getMovementResetNanos();
        data.expectTeleport(41);
        assertTrue(data.hasRecentTeleport());
        assertFalse(data.hasLastPosition());
        assertEquals(0, data.getAirTicks());
        assertEquals(0, data.getSpeedPredictionDebt());
        assertEquals(0, data.getTimerBalanceMs());
        assertEquals(10, data.getVl());
        assertNotEquals(reset, data.getMovementResetNanos());
        assertFalse(data.acknowledgeTeleport(40), "An unrelated confirmation cannot extend grace");
        assertTrue(data.acknowledgeTeleport(41));
        assertFalse(data.acknowledgeTeleport(41), "A replayed confirmation cannot extend grace");
    }

    @Test void eachAttackRetainsItsOwnPositionAndRotation() {
        var data = new UserData(UUID.randomUUID(), "Fighter");
        data.setInitialRotation(true);
        data.setLastYaw(45);
        data.noteReportedClientState(true, 10, 64, 20, true, 45, 0, true);
        var first = data.captureAttack(100);
        data.setLastYaw(-90);
        data.noteReportedClientState(true, 11, 64, 20, true, -90, 0, true);
        var second = data.captureAttack(200);
        assertEquals(45, first.yaw());
        assertEquals(10, first.x());
        assertEquals(-90, second.yaw());
        assertEquals(11, second.x());
        data.recordTeleport();
        assertFalse(data.captureAttack(300).positionKnown());
    }
}
