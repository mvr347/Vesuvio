package net.lovelace.vesuvio.data;

/**
 * A time-decaying evidence accumulator, used in place of a consecutive-hit streak.
 *
 * <p>Why this exists: every "N suspicious events in a row" counter has the same structural weakness
 * against a randomized/humanized cheat - behaving humanly one event out of five resets the counter
 * to zero and the check can never fire, no matter how overwhelming the other four events were.
 * A cheat author does not have to defeat the detection logic at all, only its bookkeeping.
 *
 * <p>An accumulator inverts that: a suspicious event adds weight, a clean event refunds only part
 * of it, and unused weight fades on its own so evidence from a fight twenty minutes ago never
 * contributes to a verdict now. A player who is 80% suspicious still crosses the bar; a player who
 * is occasionally, honestly odd never does, because the decay outpaces the accumulation.
 *
 * <p>Decay is exponential with a configurable half-life and is applied lazily (on read/write from
 * the checking thread) rather than on a scheduler, so an idle player costs nothing at all - which
 * matters because there is one of these per sub-check per online player.
 *
 * <p>All methods are synchronized on the instance: the aim checks run on virtual threads dispatched
 * off the netty pipeline, so two events for the same player can genuinely overlap.
 *
 * Author: Lovelace
 */
public final class DecayingEvidence {

    private double score;
    private int events;
    private long lastUpdateNanos;

    /** Applies elapsed decay and returns the current weight, without recording an event. */
    public synchronized double current(long nowNanos, double halfLifeMs) {
        decay(nowNanos, halfLifeMs);
        return score;
    }

    /**
     * Records a suspicious event and returns the resulting weight.
     *
     * @param weight how much this event is worth (1.0 keeps the accumulator on the same scale as
     *               the streak counter it replaces, so existing threshold values stay meaningful)
     */
    public synchronized double reward(double weight, long nowNanos, double halfLifeMs) {
        decay(nowNanos, halfLifeMs);
        score += Math.max(0.0, weight);
        events++;
        return score;
    }

    /**
     * Records a clean event, refunding part of the accumulated weight. Deliberately a partial
     * refund rather than a reset - that asymmetry is the whole point of the accumulator.
     */
    public synchronized double relieve(double weight, long nowNanos, double halfLifeMs) {
        decay(nowNanos, halfLifeMs);
        score = Math.max(0.0, score - Math.max(0.0, weight));
        events++;
        return score;
    }

    /**
     * The weight as of the last decay application, without touching the clock. Intended for
     * read-only display surfaces (API snapshot, debug overlay) that must not mutate check state.
     */
    public synchronized double peek() {
        return score;
    }

    /** Total events (suspicious and clean) observed since the last reset or full fade-out. */
    public synchronized int events() {
        return events;
    }

    /** Clears the accumulator - called after a flag fires, so one fight cannot flag twice for free. */
    public synchronized void reset() {
        score = 0.0;
        events = 0;
        lastUpdateNanos = 0L;
    }

    private void decay(long nowNanos, double halfLifeMs) {
        if (lastUpdateNanos == 0L) {
            lastUpdateNanos = nowNanos;
            return;
        }
        long elapsedNanos = nowNanos - lastUpdateNanos;
        lastUpdateNanos = nowNanos;
        if (elapsedNanos <= 0L || halfLifeMs <= 0.0 || score <= 0.0) return;

        double halfLives = (elapsedNanos / 1_000_000.0) / halfLifeMs;
        // Past ~32 half-lives the result is indistinguishable from zero and Math.pow is pure waste;
        // this is also the path a player who simply stopped fighting takes.
        if (halfLives > 32.0) {
            score = 0.0;
            events = 0;
            return;
        }
        score *= Math.pow(0.5, halfLives);
        if (score < 1e-3) {
            score = 0.0;
            events = 0;
        }
    }
}
