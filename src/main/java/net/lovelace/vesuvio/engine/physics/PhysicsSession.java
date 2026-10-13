package net.lovelace.vesuvio.engine.physics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Per-player diagnostic only. No dependency on violations, commands, or the punishment pipeline. */
public final class PhysicsSession {
    private static final int MAX_STATES = 4;
    private static final double MATCH_TOLERANCE = 0.03;
    private final MovementSimulator simulator = new MovementSimulator();
    private List<MovementSimulator.State> states = List.of();
    private MotionVector previousPosition;
    private boolean previousGround;
    private long previousNanos;
    private String previousWorld;
    private MovementSimulator.Parameters previousParameters;
    private float yaw;
    private boolean hasYaw;
    private long compared, mismatched, skipped;
    private double maximumResidual;
    private Report report = new Report("OFF", Double.NaN, 0, 0, 0, 0, 0, "", null, null);

    public record Packet(MotionVector position, boolean grounded, boolean hasRotation, float yaw, long receivedNanos) {}
    public record Report(String status, double residual, double maximumResidual, long compared, long mismatched,
                         long skipped, int candidates, String input, MotionVector predicted, MotionVector observed) {}
    private record Candidate(MovementSimulator.Step step, double residual) {}

    public synchronized Report report() { return report; }

    /** Keeps aggregate telemetry but never keeps motion across an unknown interval. */
    public synchronized void reset(String reason) {
        clearHistory();
        hasYaw = false;
        report = new Report(reason, Double.NaN, maximumResidual, compared, mismatched, skipped, 0, "", null, null);
    }

    public synchronized void observe(Packet packet, PhysicsFrame frame, long resetEpoch) {
        if (packet.hasRotation() && Float.isFinite(packet.yaw())) { yaw = packet.yaw(); hasYaw = true; }
        if (packet.position() == null) { skip("NO_POSITION"); return; }
        if (frame == null || !frame.usable()) { skip(frame == null ? "NO_WORLD" : frame.unavailable()); return; }
        long age = packet.receivedNanos() - frame.capturedNanos();
        if (age < 0 || age > 150_000_000L || frame.resetEpoch() != resetEpoch) { skip("STALE_WORLD"); return; }
        if (!hasYaw) { skip("NO_ROTATION"); return; }
        if (!frame.collisions().knownBounds().contains(CollisionBox.body(packet.position(), frame.parameters().width(), frame.parameters().height()))) {
            skip("OUTSIDE_SNAPSHOT"); return;
        }
        if (previousPosition == null || !frame.world().equals(previousWorld) || !frame.parameters().equals(previousParameters)) {
            seed(packet, frame, "WARMUP"); return;
        }
        long elapsed = packet.receivedNanos() - previousNanos;
        if (elapsed < 25_000_000L || elapsed > 80_000_000L) { seed(packet, frame, "TIMING_UNCERTAIN"); return; }
        if (states.isEmpty()) {
            MotionVector delta = packet.position().subtract(previousPosition);
            double friction = previousGround ? frame.parameters().slipperiness() * 0.91 : 0.91;
            double vy = ((packet.grounded() ? 0 : delta.y()) - frame.parameters().gravity()) * 0.98;
            states = List.of(new MovementSimulator.State(packet.position(), new MotionVector(delta.x() * friction, vy, delta.z() * friction), packet.grounded()));
            remember(packet, frame);
            publishSkip("WARMUP");
            return;
        }

        List<Candidate> candidates = new ArrayList<>(MAX_STATES * 72);
        boolean incomplete = false;
        for (var state : states) {
            for (int forward = -1; forward <= 1; forward++) for (int strafe = -1; strafe <= 1; strafe++) {
                for (int mode = 0; mode < 8; mode++) {
                    boolean sprint = (mode & 1) != 0, jump = (mode & 2) != 0, slow = (mode & 4) != 0;
                    if (jump && !state.grounded()) continue;
                    if (sprint && (forward <= 0 || slow)) continue;
                    var input = new MovementSimulator.Input(forward, strafe, yaw, sprint, jump, slow);
                    var step = simulator.step(state, frame.parameters(), input, frame.collisions());
                    if (step.isEmpty()) { incomplete = true; continue; }
                    candidates.add(new Candidate(step.get(), step.get().state().position().distance(packet.position())));
                }
            }
        }
        // If an unmodelled route leaves the captured region, a remaining route cannot prove failure.
        if (incomplete || candidates.isEmpty()) { seed(packet, frame, "INCOMPLETE_GEOMETRY"); return; }
        candidates.sort(Comparator.comparingDouble(Candidate::residual));
        Candidate best = candidates.getFirst();
        double residual = best.residual();
        compared++;
        maximumResidual = Math.max(maximumResidual, residual);
        boolean mismatch = residual > MATCH_TOLERANCE;
        if (mismatch) mismatched++;
        report = new Report(mismatch ? "MISMATCH" : "MATCH", residual, maximumResidual, compared, mismatched, skipped,
                candidates.size(), net.lovelace.vesuvio.i18n.Russian.input(best.step().input()), best.step().state().position(), packet.position());
        if (mismatch) {
            clearHistory(); // Do not absorb repeated impossible acceleration into the carried state.
        } else {
            states = candidates.stream().filter(c -> c.residual() <= MATCH_TOLERANCE)
                    .map(c -> new MovementSimulator.State(packet.position(), c.step().state().velocity(), c.step().state().grounded()))
                    .distinct().limit(MAX_STATES).toList();
            remember(packet, frame);
        }
    }

    private void seed(Packet packet, PhysicsFrame frame, String reason) {
        clearHistory(); remember(packet, frame); publishSkip(reason);
    }
    private void remember(Packet packet, PhysicsFrame frame) {
        previousPosition = packet.position(); previousNanos = packet.receivedNanos(); previousGround = packet.grounded();
        previousWorld = frame.world(); previousParameters = frame.parameters();
    }
    private void clearHistory() {
        states = List.of(); previousPosition = null; previousWorld = null; previousParameters = null; previousNanos = 0;
    }
    private void skip(String reason) { clearHistory(); publishSkip(reason); }
    private void publishSkip(String reason) {
        skipped++;
        report = new Report(reason, Double.NaN, maximumResidual, compared, mismatched, skipped, 0, "", null, null);
    }
}
