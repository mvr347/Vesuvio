package net.lovelace.vesuvio.integration.essential;

import java.util.Set;

/** Immutable state published on main and read by packet/virtual threads. No Bukkit objects. */
public record MechanicsState(Set<String> states, long transitionUntilNanos) {
    public static final MechanicsState NONE = new MechanicsState(Set.of(), 0);

    public MechanicsState {
        states = Set.copyOf(states);
    }

    public boolean transition(long now) {
        return transitionUntilNanos != 0 && now - transitionUntilNanos < 0;
    }

    public boolean movement(long now) {
        return transition(now) || (states.size() > (states.contains("CARRYING") ? 1 : 0));
    }

    public boolean combat(long now) {
        return transition(now)
                || (states.size()
                        > (states.contains("CARRYING") ? 1 : 0)
                                + (states.contains("REVIVING") ? 1 : 0));
    }

    public boolean hitbox(long now) {
        return transition(now)
                || states.contains("SITTING")
                || states.contains("LYING")
                || states.contains("RIDING")
                || states.contains("DOWNED");
    }
}
