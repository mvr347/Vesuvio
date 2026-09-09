package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Game-clock acceleration (Timer) detection via a drift balance.
 *
 * <h2>Why not packets-per-second</h2>
 * The previous implementation counted movement packets in a one-second window and flagged above a
 * fixed count. That has two structural problems. It cannot see <em>sporadic</em> timer - a client
 * running at 1.4x for 300ms and then normally for the rest of the second still lands under the
 * per-second cap - and the buffer needed to absorb TCP burst-flushes after a lag spike (packets
 * queued during the stall arrive together) has to be so generous that a steady low-ratio timer
 * fits underneath it. Short bursts are exactly how modern timer modules are used: enough to win a
 * combo or clear a gap, not enough to show up in a per-second average.
 *
 * <h2>The balance</h2>
 * Every movement packet represents one client tick, which the client is only entitled to send once
 * per 50ms of real time. So each packet credits +50ms and the real elapsed time since the previous
 * packet debits whatever actually passed:
 *
 * <pre>balance += 50ms - elapsedSincePreviousPacket</pre>
 *
 * A vanilla client hovers around zero forever, because over any window it sends exactly as many
 * ticks as wall-clock time allows. A timer at ratio {@code r} gains {@code 50 * (1 - 1/r)} ms per
 * packet and climbs without bound - and it climbs during the burst, so a 300ms burst is caught on
 * its own merit rather than being averaged away.
 *
 * <p>The balance floor is clamped ({@link #BALANCE_FLOOR_MS}). Without it, a player who lagged or
 * idled would bank an unbounded negative balance and could then run timer for as long as they had
 * previously stalled without the balance ever crossing zero - which is the same "stall to buy
 * blind time" trick that transaction stalling exploits elsewhere. A few ticks of headroom absorbs
 * genuine jitter and post-lag catch-up flushes; anything beyond that is not credit worth keeping.
 *
 * Author: Lovelace
 */
public final class TimerCheck {

    /** Milliseconds of client simulation one movement packet is entitled to claim. */
    private static final double TICK_MS = 50.0;

    /**
     * How much negative balance a player may bank. Five ticks is enough to swallow the burst that
     * follows an ordinary network hiccup without letting a long stall become a long free pass.
     */
    private static final double BALANCE_FLOOR_MS = -250.0;

    /** Balance at which the drift stops being explainable as jitter. */
    private static final double FLAG_BALANCE_MS = 320.0;

    /** Consecutive readings above the threshold before a flag is raised. */
    private static final int REQUIRED_STREAK = 2;

    /** Smoothing weight for the reported packet-interval average. */
    private static final double INTERVAL_EMA_ALPHA = 0.10;

    /**
     * Gaps longer than this are a genuine stall (chunk load, server hitch, reconnect), not client
     * ticks. Crediting them would let the balance swing wildly, so the sample is discarded.
     */
    private static final double MAX_SANE_GAP_MS = 1000.0;

    /**
     * @param lagTolerance multiplier from the lag compensator; scales the flag threshold so a
     *                     genuinely lagging server or connection does not manufacture drift
     */
    public CheckResult check(UserData data, double lagTolerance) {
        return check(data, lagTolerance, System.nanoTime());
    }

    /**
     * Clock-injectable form. The balance is defined purely in terms of packet arrival times, so
     * supplying them explicitly is what makes the drift behaviour testable without real sleeps.
     */
    public CheckResult check(UserData data, double lagTolerance, long nowNanos) {
        if (data == null) return CheckResult.pass("Timer");

        long now = nowNanos;
        long last = data.getTimerLastPacketNanos();
        data.setTimerLastPacketNanos(now);

        if (last == 0L) {
            // First packet of the session - no interval to measure yet.
            return CheckResult.pass("Timer");
        }

        double elapsedMs = (now - last) / 1_000_000.0;
        if (elapsedMs > MAX_SANE_GAP_MS) {
            // A real stall. Reset rather than credit or debit: neither side of the balance learned
            // anything trustworthy from an interval the server itself may have caused.
            data.setTimerBalanceMs(0.0);
            data.decrementTimerViolationStreak();
            return CheckResult.pass("Timer");
        }

        double balance = data.getTimerBalanceMs() + (TICK_MS - elapsedMs);
        if (balance < BALANCE_FLOOR_MS) balance = BALANCE_FLOOR_MS;
        data.setTimerBalanceMs(balance);

        // Smoothed packet interval, kept purely so a flag can report a meaningful speed ratio.
        double ema = data.getTimerIntervalEmaMs();
        ema = ema < 0 ? elapsedMs : ema + INTERVAL_EMA_ALPHA * (elapsedMs - ema);
        data.setTimerIntervalEmaMs(ema);

        double threshold = FLAG_BALANCE_MS * Math.max(1.0, lagTolerance);

        if (balance > threshold) {
            data.incrementTimerViolationStreak();
            if (data.getTimerViolationStreak() >= REQUIRED_STREAK) {
                // Estimated speed ratio: a vanilla client averages a 50ms packet interval, so the
                // ratio of that to the observed average is directly "how many times real-time the
                // client is simulating at". The balance says the drift is real; this says how big.
                double ratio = TICK_MS / Math.max(1.0, ema);
                double over = balance - threshold;
                double confidence = Math.min(0.99, 0.78 + over / 1500.0);
                double vl = Math.min(8.0, 1.5 + over / 200.0);

                // Consume the balance so one sustained timer session produces a steady trickle of
                // flags rather than one flag per packet forever after crossing the line.
                data.setTimerBalanceMs(threshold * 0.5);

                Map<String, Object> details = new HashMap<>();
                details.put("balanceMs", balance);
                details.put("thresholdMs", threshold);
                details.put("estimatedRatio", ratio);
                details.put("streak", data.getTimerViolationStreak());

                return CheckResult.flag("Timer", confidence, vl,
                        String.format(Locale.US,
                                "Accelerated game clock (drift: +%.0fms over %.0fms allowance, ~%.2fx speed)",
                                balance, threshold, ratio),
                        details);
            }
        } else if (balance < threshold * 0.5) {
            data.decrementTimerViolationStreak();
        }

        return CheckResult.pass("Timer");
    }
}
