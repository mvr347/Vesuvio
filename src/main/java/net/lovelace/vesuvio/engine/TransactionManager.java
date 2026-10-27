package net.lovelace.vesuvio.engine;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transaction-based latency measurement and event acknowledgement.
 *
 * <h2>Why not {@code Player#getPing()}</h2>
 * Bukkit's ping is derived from the KeepAlive packet, which the vanilla client answers roughly
 * once per second and which a cheat client can answer late on purpose. Every check that scales
 * its tolerance by "how laggy is this player" is therefore trivially manipulated: stall KeepAlive,
 * get a permanently inflated lag allowance, and walk through the loosened thresholds.
 *
 * <p>Transactions do not have that problem. The server sends a Ping (1.17+) or Window Confirmation
 * (legacy) with an id the client must echo back <em>immediately</em>, before it processes any
 * further packets. The round-trip is measured against a real, unspoofable ordering point: a client
 * that delays the Pong also delays every movement and combat packet behind it, which is itself
 * detectable rather than free.
 *
 * <h2>What this buys the checks</h2>
 * <ul>
 *   <li><b>Real latency</b> for the lag-tolerance multiplier, sampled every tick instead of once
 *       a second, so a genuine spike is seen while it is happening rather than a second later.</li>
 *   <li><b>Ordered acknowledgement.</b> {@link #onServerEvent(UUID)} stamps a server-side action
 *       (a knockback, a teleport) with the id of the next transaction. When that id comes back the
 *       server knows the client has definitely seen the event, so a check can wait for the
 *       acknowledgement instead of guessing a fixed grace period. This is precisely the
 *       correlation whose absence lets players null knockback on threshold-based anticheats.</li>
 *   <li><b>Stall detection.</b> A client sitting on unanswered transactions far longer than its
 *       own measured latency is either lagging catastrophically or deliberately withholding
 *       acknowledgements to buy itself blind time; {@link #getPendingCount(UUID)} exposes that.</li>
 * </ul>
 *
 * Author: Lovelace
 */
// Not final: the latency accessors are overridden in tests to stand in for a live packet stream,
// which is what lets the Blink check's "stall versus lag switch" discrimination be tested at all.
public class TransactionManager {

    /**
     * Ping ids are negative so they cannot collide with the ids other plugins (or the server's own
     * inventory code) use. The counter walks downwards from a fixed start.
     */
    private static final int ID_START = -1;
    private static final int ID_MIN = -32000;

    /** Never let the pending queue grow without bound if a client stops answering entirely. */
    private static final int MAX_PENDING = 40;

    /** Weight of each new sample in the exponential moving average of the round-trip time. */
    private static final double EMA_ALPHA = 0.20;

    private final java.util.function.LongSupplier clock;

    public TransactionManager() {
        this(System::nanoTime);
    }

    TransactionManager(java.util.function.LongSupplier clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    private final Map<UUID, PlayerTransactions> players = new ConcurrentHashMap<>();

    /** Called on join; also lazily created on first use. */
    public void register(UUID uuid) {
        players.computeIfAbsent(uuid, u -> new PlayerTransactions());
    }

    public void remove(UUID uuid) {
        players.remove(uuid);
    }

    public void clear() {
        players.clear();
    }

    /**
     * Sends a transaction to the player and records the send time. Called once per tick from the
     * main thread. Returns the id sent, or 0 if nothing was sent.
     */
    public int tick(Player player) {
        if (player == null || !player.isOnline()) return 0;
        PlayerTransactions state = players.computeIfAbsent(player.getUniqueId(), u -> new PlayerTransactions());

        int id = begin(state);

        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null) {
                forget(state, id);
                return 0;
            }
            // 1.17 replaced Window Confirmation with the dedicated Ping/Pong pair. Older clients
            // (including 1.8 players coming through ViaVersion) still need the legacy packet.
            if (user.getClientVersion().isNewerThanOrEquals(
                    com.github.retrooper.packetevents.protocol.player.ClientVersion.V_1_17)) {
                user.sendPacketSilently(new WrapperPlayServerPing(id));
            } else {
                // Legacy transactions carry a short action id on window 0.
                user.sendPacketSilently(new WrapperPlayServerWindowConfirmation(0, (short) id, false));
            }
        } catch (Throwable t) {
            // Nothing went out, so nothing is owed. Leaving the entry queued would make an
            // undeliverable packet look like a client withholding acknowledgements, which costs
            // that player their lag tolerance for a failure that was ours.
            forget(state, id);
            return 0;
        }
        return id;
    }

    // Shared production path; package access allows deterministic network-clock regressions.
    int begin(UUID uuid) {
        return begin(players.computeIfAbsent(uuid, u -> new PlayerTransactions()));
    }

    private int begin(PlayerTransactions state) {
        int id;
        synchronized (state) {
            if (state.pending.size() >= MAX_PENDING) {
                // The client is not answering. Drop the oldest so the queue stays bounded, and
                // leave the recorded latency alone - a stalled client should not get to keep
                // lowering its own measured ping by having us forget the outstanding requests.
                state.pending.pollFirst();
            }
            id = state.nextId();
            state.pending.addLast(new Pending(id, clock.getAsLong(), state.eventSequence));
        }
        return id;
    }

    /** Removes a transaction we queued but could not actually send. */
    private void forget(PlayerTransactions state, int id) {
        synchronized (state) {
            state.pending.removeIf(p -> p.id == id);
        }
    }

    /**
     * Called from the packet listener when the client echoes a transaction back.
     *
     * @return true if the id was one of ours (so the packet should be swallowed rather than
     *         forwarded to the rest of the server, which never asked for it)
     */
    public boolean onResponse(UUID uuid, int id) {
        PlayerTransactions state = players.get(uuid);
        if (state == null) return false;

        long now = clock.getAsLong();
        synchronized (state) {
            // Ids are only ever ours if they are in our negative range.
            if (id > ID_START || id < ID_MIN) return false;

            // Other plugins may also use negative ids. Verify ownership before draining:
            // a foreign id must not erase all pending RTT/velocity acknowledgements.
            if (state.pending.stream().noneMatch(p -> p.id == id)) return false;
            Pending match = null;
            // The client answers in order, so the match is almost always the head. Draining
            // everything up to and including the match also clears responses we somehow missed.
            while (!state.pending.isEmpty()) {
                Pending p = state.pending.pollFirst();
                if (p.id == id) {
                    match = p;
                    break;
                }
            }
            if (match == null) return false;

            double rttMs = (now - match.sentNanos) / 1_000_000.0;
            state.lastRttMs = rttMs;
            state.emaRttMs = state.emaRttMs < 0 ? rttMs : state.emaRttMs + EMA_ALPHA * (rttMs - state.emaRttMs);
            state.acknowledgedSequence = match.eventSequence;
            state.lastResponseNanos = now;
            state.responses.add(now, rttMs);
            // Packet accounting (see BlinkCheck): the sequence counts answered transactions, and
            // the send time is the SERVER's, so the span between two acks is a quantity the client
            // cannot influence at all - not by lagging, not by jitter, not by holding packets.
            state.ackSequence++;
            state.lastAckSentNanos = match.sentNanos;
        }
        return true;
    }

    /**
     * Marks that the server just did something to this player (knockback, teleport, ...). The
     * returned sequence number is acknowledged once the client answers the next transaction, at
     * which point {@link #isAcknowledged(UUID, long)} turns true.
     */
    public long onServerEvent(UUID uuid) {
        PlayerTransactions state = players.computeIfAbsent(uuid, u -> new PlayerTransactions());
        synchronized (state) {
            return ++state.eventSequence;
        }
    }

    /** True once the client has provably received everything up to the given sequence number. */
    public boolean isAcknowledged(UUID uuid, long sequence) {
        PlayerTransactions state = players.get(uuid);
        if (state == null) return true;
        synchronized (state) {
            return state.acknowledgedSequence >= sequence;
        }
    }

    /**
     * Smoothed transaction round-trip in milliseconds, or -1 when no sample exists yet (the caller
     * should fall back to {@code Player#getPing()} for the first second after a join).
     */
    public double getTransactionPing(UUID uuid) {
        PlayerTransactions state = players.get(uuid);
        if (state == null) return -1;
        synchronized (state) {
            return state.emaRttMs;
        }
    }

    /** Most recent single round-trip sample, or -1 when none exists. Used for spike detection. */
    public double getLastPing(UUID uuid) {
        PlayerTransactions state = players.get(uuid);
        if (state == null) return -1;
        synchronized (state) {
            return state.lastRttMs;
        }
    }

    /**
     * How many transactions this client has answered, ever. Monotonic.
     *
     * <p>This is the clock the Blink packet accounting runs on. Its value on its own means nothing;
     * what matters is that it advances once per answered transaction, and that the answer travels
     * on the same ordered TCP stream as the player's movement packets. Two movement packets that
     * arrive without this number changing between them were sent by the client without it having
     * received a new transaction - so the client cannot have advanced its own clock between them.
     */
    public long getAckSequence(UUID uuid) {
        PlayerTransactions state = players.get(uuid);
        if (state == null) return 0L;
        synchronized (state) {
            return state.ackSequence;
        }
    }

    /**
     * Milliseconds since the server SENT the transaction this client most recently answered, or -1
     * when it has answered none.
     *
     * <p>Deliberately measured from the send, not the response: it bounds how much real time the
     * client can possibly have experienced since that point, and a client cannot inflate a
     * timestamp the server wrote down before the packet ever left.
     */
    public double getMsSinceLastAckSent(UUID uuid) {
        PlayerTransactions state = players.get(uuid);
        if (state == null) return -1;
        synchronized (state) {
            if (state.lastAckSentNanos == 0L) return -1;
            return (clock.getAsLong() - state.lastAckSentNanos) / 1_000_000.0;
        }
    }

    /** Number of transactions the client has not answered yet. */
    public int getPendingCount(UUID uuid) {
        PlayerTransactions state = players.get(uuid);
        if (state == null) return 0;
        synchronized (state) {
            return state.pending.size();
        }
    }

    /**
     * Milliseconds since the oldest unanswered transaction was sent, or 0 when nothing is pending.
     * A value far above the player's own {@link #getTransactionPing} is the signature of a client
     * withholding acknowledgements rather than one that is merely on a slow connection.
     */
    public double getOldestPendingAgeMs(UUID uuid) {
        PlayerTransactions state = players.get(uuid);
        if (state == null) return 0;
        synchronized (state) {
            Pending head = state.pending.peekFirst();
            if (head == null) return 0;
            return (clock.getAsLong() - head.sentNanos) / 1_000_000.0;
        }
    }

    /**
     * Evidence that probes actually flowed throughout a movement gap. A stale low EMA or a recovery
     * flush is not proof of a healthy connection during that gap. Zero RTT is valid.
     */
    public boolean hasHealthyResponsesDuring(UUID uuid, long from, long to, double maxRttMs) {
        PlayerTransactions state = players.get(uuid);
        if (state == null) return false;
        synchronized (state) {
            return state.responses.covers(from, to, maxRttMs);
        }
    }

    /** Fixed primitive ring: ~4 KiB per connected player, no retained packets or player objects. */
    private static final class ResponseWindow {
        private final long[] times = new long[256];
        private final double[] rtts = new double[256];
        private int next, size;

        void add(long time, double rtt) {
            times[next] = time;
            rtts[next] = rtt;
            next = (next + 1) % times.length;
            size = Math.min(size + 1, times.length);
        }

        boolean covers(long from, long to, double maxRttMs) {
            if (to <= from || !Double.isFinite(maxRttMs) || maxRttMs <= 0) return false;
            long maxGap = (long) (Math.max(250, Math.min(750, maxRttMs)) * 1_000_000);
            long previous = from;
            int count = 0;
            for (int i = 0; i < size; i++) {
                int index = (next - size + i + times.length) % times.length;
                long time = times[index];
                if (time < from || time > to) continue;
                double rtt = rtts[index];
                if (!Double.isFinite(rtt) || rtt < 0 || rtt >= maxRttMs) continue;
                if (time - previous > maxGap) return false;
                previous = time;
                count++;
            }
            return count >= 3 && to - previous <= maxGap;
        }
    }

    private record Pending(int id, long sentNanos, long eventSequence) {}

    private static final class PlayerTransactions {
        final ResponseWindow responses = new ResponseWindow();
        final Deque<Pending> pending = new ArrayDeque<>();
        int lastId = ID_START + 1; // nextId() pre-decrements, so the first id handed out is ID_START
        double lastRttMs = -1;
        double emaRttMs = -1;
        long lastResponseNanos = 0;
        long eventSequence = 0;
        long acknowledgedSequence = 0;
        long ackSequence = 0;
        long lastAckSentNanos = 0L;

        int nextId() {
            lastId--;
            if (lastId < ID_MIN) lastId = ID_START;
            return lastId;
        }
    }
}
