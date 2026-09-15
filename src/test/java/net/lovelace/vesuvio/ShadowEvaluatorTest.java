package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.onnx.ShadowEvaluator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shadow mode's whole value is answering one question before a candidate model can punish anyone:
 * how much more (or less) would it flag? These tests check that the number an operator promotes on
 * actually says that.
 */
public class ShadowEvaluatorTest {

    @Test
    public void testFlagRateRatioExposesATriggerHappyCandidate() {
        ShadowEvaluator evaluator = new ShadowEvaluator();
        // 100 windows: the live model flags 5, the candidate flags 15 of the same ones.
        for (int i = 0; i < 100; i++) {
            double live = i < 5 ? 0.95 : 0.10;
            double shadow = i < 15 ? 0.95 : 0.10;
            evaluator.record("click_model", live, shadow, 0.85, 0.85);
        }

        ShadowEvaluator.Stats stats = evaluator.get("click_model");
        assertEquals(100, stats.samples());
        assertEquals(5, stats.liveFlags());
        assertEquals(15, stats.shadowFlags());
        assertEquals(3.0, stats.flagRateRatio(), 1e-9,
                "a candidate flagging three times as often must read as 3.00x, not as a good agreement score");
        assertEquals(10, stats.shadowOnlyFlags());
        assertEquals(0, stats.liveOnlyFlags());
        // Agreement alone would look reassuring here - 90% - which is exactly why the ratio is the
        // number the command leads with.
        assertEquals(0.90, stats.agreementRate(), 1e-9);
    }

    @Test
    public void testCandidateJudgedAtItsOwnThreshold() {
        // A candidate's operating point is its own, not the live model's: comparing a new model at
        // the old model's threshold measures the wrong thing entirely.
        ShadowEvaluator evaluator = new ShadowEvaluator();
        for (int i = 0; i < 10; i++) {
            evaluator.record("aim_model", 0.90, 0.70, 0.85, 0.65);
        }

        ShadowEvaluator.Stats stats = evaluator.get("aim_model");
        assertEquals(10, stats.liveFlags());
        assertEquals(10, stats.shadowFlags(), "0.70 clears the candidate's own 0.65 threshold");
        assertEquals(1.0, stats.agreementRate(), 1e-9);
        assertEquals(0.20, stats.meanAbsoluteDelta(), 1e-9);
    }

    @Test
    public void testClearForgetsAPromotedCandidate() {
        ShadowEvaluator evaluator = new ShadowEvaluator();
        evaluator.record("click_model", 0.9, 0.9, 0.85, 0.85);
        assertNotNull(evaluator.get("click_model"));

        evaluator.clear("click_model");
        assertNull(evaluator.get("click_model"),
                "stats from a promoted candidate would be compared against itself if kept");
        assertTrue(evaluator.describe("click_model").contains("no paired inferences"));
    }

    @Test
    public void testNanVerdictsAreIgnored() {
        // MLManager returns NaN when it cannot read a model's output shape and falls back to the
        // heuristic - counting that as a disagreement would make a healthy candidate look broken.
        ShadowEvaluator evaluator = new ShadowEvaluator();
        evaluator.record("click_model", Double.NaN, 0.9, 0.85, 0.85);
        evaluator.record("click_model", 0.9, Double.NaN, 0.85, 0.85);
        assertNull(evaluator.get("click_model"));
    }
}
