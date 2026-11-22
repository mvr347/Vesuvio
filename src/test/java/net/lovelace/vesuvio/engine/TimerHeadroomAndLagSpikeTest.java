package net.lovelace.vesuvio.engine;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.movement.InvMoveCheck;
import net.lovelace.vesuvio.check.movement.TimerCheck;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TimerHeadroomAndLagSpikeTest {

    private static final long MS = 1_000_000L;

    private static EnvironmentSnapshot openChestSnapshot() {
        long nowMs = System.currentTimeMillis();
        return new EnvironmentSnapshot(
                nowMs, true, 0, 64, 0,
                false, false, false, false, false,
                true, false, false, false, 0f,
                false, false, false, false,
                false, true, false, Material.GRASS_BLOCK,
                false, false, false, false,
                false, false, false, false, false, -1,
                0, 0, 0.1, 0.42,
                true, "CHEST", 0.6, false, false, nowMs, false);
    }

    @Test
    void timerHeadroomPreservesAckGapAfterBurstPongDrain() {
        AtomicLong now = new AtomicLong(10_000 * MS);
        TransactionManager transactions = new TransactionManager(now::get);
        UUID id = UUID.randomUUID();
        UserData data = new UserData(id, "Miha");
        TimerCheck timerCheck = new TimerCheck();

        // Warm up at steady 20 tps / 40 ms RTT
        for (int i = 0; i < 15; i++) {
            int tx = transactions.begin(id);
            now.addAndGet(40 * MS);
            assertTrue(transactions.onResponse(id, tx));
            assertFalse(timerCheck.check(data, 1.0, now.get(), transactions.getTimerHeadroomMs(id)).isFlag());
            now.addAndGet(10 * MS);
        }

        // Simulate a 650 ms TCP stall where server kept sending pings, then Pongs + 13 bunched
        // movement packets arrive in the same burst (Netty processes Pongs before movement checks run).
        int[] stalledIds = new int[13];
        for (int i = 0; i < stalledIds.length; i++) {
            stalledIds[i] = transactions.begin(id);
            now.addAndGet(50 * MS);
        }
        // Client replies to all queued transactions at once
        for (int tx : stalledIds) {
            assertTrue(transactions.onResponse(id, tx));
        }

        // getMsSinceLastAckSent() is now ~50 ms, but getTimerHeadroomMs() preserves the 650+ ms stall gap!
        assertTrue(transactions.getTimerHeadroomMs(id) >= 600.0,
                "Expected preserved stall headroom, got " + transactions.getTimerHeadroomMs(id));

        for (int i = 0; i < stalledIds.length; i++) {
            now.addAndGet(1 * MS);
            CheckResult res = timerCheck.check(data, 1.0, now.get(), transactions.getTimerHeadroomMs(id));
            assertFalse(res.isFlag(), "Bunched packet " + i + " after stall must not flag: " + res.explanation());
        }
    }

    @Test
    void invMoveWaitsForOpenWindowAcknowledgementAndFlagsOncePastGrace() throws Exception {
        AtomicLong now = new AtomicLong(10_000 * MS);
        TransactionManager transactions = new TransactionManager(now::get);
        UUID id = UUID.randomUUID();
        UserData data = new UserData(id, "ChestMover");
        InvMoveCheck check = new InvMoveCheck();

        // Steady low RTT first
        int warmTx = transactions.begin(id);
        now.addAndGet(30 * MS);
        assertTrue(transactions.onResponse(id, warmTx));

        long openSeq = transactions.onServerEvent(id);
        int openTx = transactions.begin(id);
        data.markContainerOpened(openSeq);

        // Snapshot shows container open on server immediately, before client received OPEN_WINDOW
        data.setEnvironment(openChestSnapshot());
        for (int i = 0; i < 15; i++) {
            assertFalse(check.check(id, data, 0.29, 0.0, 0.0, transactions).isFlag(),
                    "Must not flag while OPEN_WINDOW is unacknowledged (tick " + i + ")");
        }

        // Client acknowledges OPEN_WINDOW
        now.addAndGet(40 * MS);
        assertTrue(transactions.onResponse(id, openTx));
        assertTrue(transactions.isAcknowledged(id, openSeq));

        // First call records ack timestamp and enters grace period
        assertFalse(check.check(id, data, 0.29, 0.0, 0.0, transactions).isFlag());

        // Move past grace period (400 ms) so ackMillis is well in the past
        data.markContainerOpenAcknowledged(System.currentTimeMillis() - 1000L);
        data.setContainerOpenSinceMillis(System.currentTimeMillis() - 1000L);

        boolean flagged = false;
        for (int i = 0; i < 6 && !flagged; i++) {
            flagged = check.check(id, data, 0.29, 0.0, 0.0, transactions).isFlag();
        }
        assertTrue(flagged, "Sustained movement after OPEN_WINDOW acknowledgement and grace must flag");
    }

    @Test
    void lagCompensatorDetectsSingleSampleNetworkSpikeFromLastPing() {
        AtomicLong now = new AtomicLong(10_000 * MS);
        TransactionManager transactions = new TransactionManager(now::get);
        UUID id = UUID.randomUUID();

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("lag-compensation.max-ping-spike-ms", 120);
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConfig" -> yaml;
                    case "saveDefaultConfig", "reloadConfig" -> null;
                    default -> 0;
                });
        ConfigManager config = new ConfigManager(plugin);
        LagCompensator compensator = new LagCompensator(config, transactions);

        Player player = (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getPing" -> 30;
                    default -> null;
                });

        // Establish 20 ms baseline over multiple samples
        for (int i = 0; i < 10; i++) {
            int tx = transactions.begin(id);
            now.addAndGet(20 * MS);
            transactions.onResponse(id, tx);
            assertFalse(compensator.isNetworkSpike(player));
        }

        // Single 200 ms latency jump: EMA rises only slowly (20 -> 56 ms), so EMA-based check missed it,
        // whereas getLastPing() (200 ms vs ~20 ms baseline) catches the spike immediately.
        int spikeTx = transactions.begin(id);
        now.addAndGet(200 * MS);
        transactions.onResponse(id, spikeTx);
        assertTrue(compensator.isNetworkSpike(player),
                "Single-sample 200 ms ping jump must register as a network spike");
    }
}
