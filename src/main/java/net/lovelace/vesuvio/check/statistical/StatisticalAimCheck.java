package net.lovelace.vesuvio.check.statistical;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.feature.AimFeatureExtractor;

import java.util.HashMap;
import java.util.Map;

/**
 * Layer 1: Statistical aim and rotation dynamics analysis.
 * Catches snap aimbots, Silent Aim rotations, and robotic linear tracking.
 *
 * Author: Lovelace
 */
public final class StatisticalAimCheck {

    public CheckResult check(UserData data) {
        // Only evaluate aim heuristics during combat interactions
        if (!data.isInCombat() || data.getAimBuffer().getCount() < 16) {
            return CheckResult.pass("AimStatistical");
        }

        float[] f = AimFeatureExtractor.extract(data.getAimBuffer());
        float meanYaw = f[0];
        float meanPitch = f[1];
        float varYaw = f[2];
        float varPitch = f[3];
        float snapRatio = f[4];
        float zeroRatio = f[5];
        float jerk = f[6];

        Map<String, Object> details = new HashMap<>();
        details.put("meanYaw", meanYaw);
        details.put("snapRatio", snapRatio);
        details.put("zeroRatio", zeroRatio);
        details.put("jerk", jerk);

        double sensitivity = data.getSensitivityMultiplier();

        // 1. Extreme Snap Aim / Silent Aim (high snap ratio coupled with extreme jerk)
        if (snapRatio > (0.25 / sensitivity) && jerk > 20.0f && meanYaw > 6.0f) {
            return CheckResult.flag("AimStatistical", 0.90, 2.0,
                    String.format("Violent snap rotations (SnapRatio: %.0f%%, Jerk: %.1f)", snapRatio * 100, jerk), details);
        }

        // 2. Perfectly Linear Machine Aim (completely robotic tracking with near-zero human jerk)
        if (jerk < (0.04f * sensitivity) && meanYaw > 5.0f && zeroRatio < 0.05f) {
            return CheckResult.flag("AimStatistical", 0.88, 1.8,
                    String.format("Robotic constant angular tracking (Jerk: %.3f, MeanYaw: %.1f°)", jerk, meanYaw), details);
        }

        // 3. Snap-and-Freeze (SnapLock): a hard snap onto the target followed by near-total
        // stillness for most of the window - the signature of a basic/free KillAura that jumps
        // its view onto a target once and then does not micro-adjust the way a human tracking
        // a moving mob constantly does.
        if (snapRatio > (0.04 / sensitivity) && zeroRatio > (0.45 / sensitivity) && meanYaw > 2.5f) {
            return CheckResult.flag("AimStatistical", 0.86, 1.9,
                    String.format("Snap-then-freeze rotation pattern (SnapRatio: %.0f%%, ZeroRatio: %.0f%%)",
                            snapRatio * 100, zeroRatio * 100), details);
        }

        return CheckResult.pass("AimStatistical");
    }
}
