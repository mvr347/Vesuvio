package net.lovelace.vesuvio.check;

import java.util.Collections;
import java.util.Map;

/**
 * Encapsulates the verdict of any detection check or model.
 *
 * Author: Lovelace
 */
public record CheckResult(
        boolean flag,
        double confidence,
        double vl,
        String checkName,
        String explanation,
        Map<String, Object> details
) {
    public static CheckResult pass(String checkName) {
        return new CheckResult(false, 0.0, 0.0, checkName, "OK", Collections.emptyMap());
    }

    public static CheckResult flag(String checkName, double confidence, double vl, String explanation, Map<String, Object> details) {
        return new CheckResult(true, confidence, vl, checkName, explanation, details != null ? details : Collections.emptyMap());
    }

    public boolean isFlag() {
        return flag;
    }
}
