package net.lovelace.vesuvio.config;

/** Runtime-only switches. Generations invalidate work queued before an operator's change. */
public final class RuntimeControls {
    private record State(boolean detection, boolean punishments, long detectionEpoch, long actionEpoch) {}
    private volatile State state = new State(true, true, 0, 0);

    public boolean detectionEnabled() { return state.detection(); }
    public boolean punishmentsEnabled() { return state.punishments(); }
    public boolean mayPunish() { State s = state; return s.detection() && s.punishments(); }
    public long detectionEpoch() { return state.detectionEpoch(); }
    public long actionEpoch() { return state.actionEpoch(); }
    public boolean acceptsDetection(long epoch) {
        State s = state;
        return s.detection() && s.detectionEpoch() == epoch;
    }
    public boolean acceptsAction(long epoch) {
        State s = state;
        return s.detection() && s.punishments() && s.actionEpoch() == epoch;
    }
    public synchronized boolean setDetection(boolean enabled) {
        State s = state;
        if (s.detection() != enabled) {
            state = new State(enabled, s.punishments(), s.detectionEpoch() + 1, s.actionEpoch() + 1);
        }
        return enabled;
    }
    public synchronized boolean setPunishments(boolean enabled) {
        State s = state;
        if (s.punishments() != enabled) {
            state = new State(s.detection(), enabled, s.detectionEpoch(), s.actionEpoch() + 1);
        }
        return enabled;
    }
}
