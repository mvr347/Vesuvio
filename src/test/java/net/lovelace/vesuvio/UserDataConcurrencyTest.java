package net.lovelace.vesuvio;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserDataConcurrencyTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedMechanicsPollingDoesNotWaitForPacketChecks(boolean controlled) throws Exception {
        var data = new UserData(UUID.randomUUID(), "Traveler");
        var states = controlled ? Set.of("LYING") : Set.<String>of();
        data.updateMechanics(states, 100, 50, false);
        var state = data.mechanics();
        long reset = data.getMovementResetNanos();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = run(() -> {
            synchronized (data) {
                held.countDown();
                await(release);
            }
        });
        try {
            assertTrue(held.await(2, TimeUnit.SECONDS));
            var polling = run(() -> data.updateMechanics(states, 120, 50, false));
            polling.done.get(2, TimeUnit.SECONDS);
            assertSame(state, data.mechanics(), "polling must not extend transition grace");
            assertEquals(reset, data.getMovementResetNanos());
        } finally {
            release.countDown();
            worker.done.get(2, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void teleportPacketsCannotHoldKinematicsWhileWaitingForPacketChecks(boolean confirmation) throws Exception {
        var data = new UserData(UUID.randomUUID(), "Traveler");
        if (confirmation) data.expectTeleport(41);
        data.addVl(10);
        var held = new CountDownLatch(1);
        var probe = new CountDownLatch(1);
        // The ordered packet worker owns UserData; Netty concurrently sends/acknowledges a teleport.
        var worker = run(() -> {
            synchronized (data) {
                held.countDown();
                await(probe);
                // This acquires the kinematic monitor, as a normal movement check does.
                data.noteReportedClientState(true, 10, 64, 20, true, 0, 0, true);
                data.setLastPosition(10, 64, 20, true);
                data.setInitialRotation(true);
                data.setLastSwingNanos(123);
            }
        });
        try {
            assertTrue(held.await(2, TimeUnit.SECONDS));
            var packet = run(() -> {
                if (confirmation) assertTrue(data.acknowledgeTeleport(41));
                else data.expectTeleport(41);
            });
            waitUntilBlocked(packet.thread);
            probe.countDown();
            worker.done.get(2, TimeUnit.SECONDS);
            packet.done.get(2, TimeUnit.SECONDS);
            assertFalse(data.hasLastPosition());
            assertFalse(data.hasInitialRotation());
            assertEquals(0, data.getLastSwingNanos());
            assertTrue(data.hasRecentTeleport());
            assertEquals(10, data.getVl(), "teleports must keep accumulated violations");
            assertFalse(data.acknowledgeTeleport(confirmation ? 41 : 40));
        } finally {
            probe.countDown();
        }
    }

    private static void waitUntilBlocked(Thread thread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (thread.getState() != Thread.State.BLOCKED && thread.isAlive()
                && System.nanoTime() - deadline < 0) LockSupport.parkNanos(100_000);
        assertEquals(Thread.State.BLOCKED, thread.getState(), "teleport must be waiting for the packet worker");
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "test latch was not released"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private record Work(Thread thread, CompletableFuture<Void> done) {}

    private static Work run(Runnable action) {
        var done = new CompletableFuture<Void>();
        // Daemon virtual threads let a failing lock regression time out instead of hanging the test JVM.
        var thread = Thread.ofVirtual().start(() -> {
            try { action.run(); done.complete(null); }
            catch (Throwable error) { done.completeExceptionally(error); }
        });
        return new Work(thread, done);
    }
}
