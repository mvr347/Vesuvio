package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.selflearning.OnlineClassifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OnlineClassifierTest {

    @Test
    public void testOnlineLearningConvergence() {
        OnlineClassifier classifier = new OnlineClassifier(16);

        // Feature vector 1: extreme autoclicker (low delay, 0 std dev, 100% dup, 20 CPS)
        float[] cheatFeatures = new float[16];
        cheatFeatures[0] = 50.0f; // mean delay 50ms
        cheatFeatures[1] = 0.5f;  // std dev 0.5ms
        cheatFeatures[4] = 0.95f; // duplicate ratio 95%
        cheatFeatures[5] = 0.8f;  // entropy
        cheatFeatures[7] = 0.8f;  // consecutive identical
        cheatFeatures[14] = 20.0f;// 20 CPS

        // Feature vector 2: legit human (high delay, 30ms std dev, 5% dup, 7 CPS)
        float[] legitFeatures = new float[16];
        legitFeatures[0] = 140.0f;
        legitFeatures[1] = 32.0f;
        legitFeatures[4] = 0.05f;
        legitFeatures[5] = 2.8f;
        legitFeatures[7] = 0.0f;
        legitFeatures[14] = 7.1f;

        // Train 25 iterations with SGD
        for (int i = 0; i < 25; i++) {
            classifier.train(cheatFeatures, 1);
            classifier.train(legitFeatures, 0);
        }

        double cheatProb = classifier.predict(cheatFeatures);
        double legitProb = classifier.predict(legitFeatures);

        assertTrue(cheatProb > 0.80, "Cheat probability should converge high (> 0.80), was: " + cheatProb);
        assertTrue(legitProb < 0.20, "Legit probability should converge low (< 0.20), was: " + legitProb);
        assertTrue(classifier.getTrainedSamplesCount() == 50);
    }
}
