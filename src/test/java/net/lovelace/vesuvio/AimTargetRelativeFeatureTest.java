package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.AimRingBuffer;
import net.lovelace.vesuvio.data.AimTrackingBuffer;
import net.lovelace.vesuvio.feature.AimFeatureExtractor;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Features 8-15 describe the camera's relationship to the target rather than the camera alone.
 * The separation they are meant to provide is the one a humanized module cannot fake by adding
 * noise: a human's aiming error grows when the target moves fast (the hand lags the picture),
 * whereas a recomputed angle keeps the error flat no matter what the target does.
 */
class AimTargetRelativeFeatureTest {

    private static AimRingBuffer filledRotationBuffer() {
        AimRingBuffer buffer = new AimRingBuffer();
        Random rng = new Random(3);
        float yaw = 0f, pitch = 0f;
        for (int i = 0; i < AimRingBuffer.SIZE + 2; i++) {
            yaw += (float) (rng.nextGaussian() * 2.0);
            pitch += (float) (rng.nextGaussian() * 0.8);
            buffer.addRotation(yaw, pitch, i * 50_000_000L);
        }
        return buffer;
    }

    @Test
    void trackingSlotsStayZeroWithoutTrackingData() {
        float[] f = AimFeatureExtractor.extract(filledRotationBuffer(), null);
        for (int i = 8; i < 16; i++) {
            assertEquals(0f, f[i], 1e-6, "slot " + i + " must stay zero without tracking data");
        }
    }

    @Test
    void humanErrorGrowsWithTargetSpeedButAimbotErrorDoesNot() {
        // Target sweeps back and forth at a varying rate.
        int ticks = 100;
        float[] targetYaw = new float[ticks];
        float t = 0f;
        for (int i = 0; i < ticks; i++) {
            // Speed alternates between slow and fast stretches.
            float speed = (i / 10) % 2 == 0 ? 0.6f : 6.0f;
            t += speed;
            targetYaw[i] = t;
        }

        AimTrackingBuffer human = new AimTrackingBuffer();
        AimTrackingBuffer bot = new AimTrackingBuffer();
        Random rng = new Random(11);

        int lagTicks = 4; // ~200ms of sensorimotor delay
        for (int i = 0; i < ticks; i++) {
            // Human: camera sits where the target was a few ticks ago, so error tracks speed.
            float humanYaw = targetYaw[Math.max(0, i - lagTicks)] + (float) (rng.nextGaussian() * 0.3);
            human.record(targetYaw[i], 0f, humanYaw, 0f, i * 50_000_000L);

            // Bot: small constant offset regardless of how fast the target is moving.
            float botYaw = targetYaw[i] + (float) (rng.nextGaussian() * 0.3);
            bot.record(targetYaw[i], 0f, botYaw, 0f, i * 50_000_000L);
        }

        AimRingBuffer rotation = filledRotationBuffer();
        float[] humanF = AimFeatureExtractor.extract(rotation, human).clone();
        float[] botF = AimFeatureExtractor.extract(rotation, bot).clone();

        // Mean aiming error: the bot is far tighter.
        assertTrue(botF[8] < humanF[8],
                "bot mean error (" + botF[8] + ") should be below human (" + humanF[8] + ")");

        // The discriminator that survives humanization: correlation between target speed and
        // error. Clearly positive for the human, near zero for the recomputed angle.
        assertTrue(humanF[11] > 0.3f,
                "human error must correlate with target speed, was " + humanF[11]);
        assertTrue(Math.abs(botF[11]) < humanF[11],
                "bot correlation (" + botF[11] + ") must be weaker than human (" + humanF[11] + ")");

        // Fraction of ticks spent inside 1° of the target.
        assertTrue(botF[13] > humanF[13],
                "bot should sit sub-degree far more often than a human");
    }
}
