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

        int id;
        synchronized (state) {
            if (state.pending.size() >= MAX_PENDING) {
                // The client is not answering. Drop the oldest so the queue stays bounded, and
                // leave the recorded latency alone - a stalled client should not get to keep
                // lowering its own measured ping by having us forget the outstanding requests.
                state.pending.pollFirst();
            }
            id = state.nextId();
            state.pending.addLast(new Pending(id, System.nanoTime(), state.eventSequence));
        }

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

        long now = System.nanoTime();
        synchronized (state) {
            // Ids are only ever ours if they are in our negative range.
            if (id > ID_START || id < ID_MIN) return false;

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
            return (System.nanoTime() - head.sentNanos) / 1_000_000.0;
        }
    }

    private record Pending(int id, long sentNanos, long eventSequence) {}

    private static final class PlayerTransactions {
        final Deque<Pending> pending = new ArrayDeque<>();
        int lastId = ID_START + 1; // nextId() pre-decrements, so the first id handed out is ID_START
        double lastRttMs = -1;
        double emaRttMs = -1;
        long lastResponseNanos = 0;
        long eventSequence = 0;
        long acknowledgedSequence = 0;

        int nextId() {
            lastId--;
            if (lastId < ID_MIN) lastId = ID_START;
            return lastId;
        }
    }
}
