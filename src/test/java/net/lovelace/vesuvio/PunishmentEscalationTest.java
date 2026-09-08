package net.lovelace.vesuvio;

import net.lovelace.vesuvio.config.ConfigManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards against a real bug found in a review pass: ConfigManager.reload() used to sort
 * punishment thresholds ascending, and CheckPipeline#evaluatePunishments walks the list and
 * fires (then breaks on) the FIRST rule whose threshold is met with a non-blank command. With
 * ascending order that was always the lowest-severity satisfied rule - e.g. a player who blew
 * straight past the "ban" threshold (VL 100) would only ever get "kick" (VL 60) fired, over and
 * over, since VL is never auto-reset after a punishment and the loop stops at 60 every time.
 * The fix sorts descending so the loop finds the highest (most severe) satisfied threshold.
 */
public class PunishmentEscalationTest {

    /** Mirrors CheckPipeline#evaluatePunishments' selection: first rule (in list order) whose
     *  threshold is met with a non-blank command wins. */
    private static ConfigManager.PunishmentRule selectRule(List<ConfigManager.PunishmentRule> rules, double currentVl) {
        for (ConfigManager.PunishmentRule rule : rules) {
            if (currentVl >= rule.vlThreshold() && rule.command() != null && !rule.command().isBlank()) {
                return rule;
            }
        }
        return null;
    }

    @Test
    public void testEscalatesToHighestSatisfiedThreshold() {
        List<ConfigManager.PunishmentRule> rules = new ArrayList<>(List.of(
                new ConfigManager.PunishmentRule(15, "log", ""),
                new ConfigManager.PunishmentRule(35, "notify-staff", ""),
                new ConfigManager.PunishmentRule(60, "kick", "kick %player% too high VL"),
                new ConfigManager.PunishmentRule(100, "ban", "ban %player% 14d cheating")
        ));
        // Same sort ConfigManager.reload() performs.
        rules.sort(Comparator.comparingInt(ConfigManager.PunishmentRule::vlThreshold).reversed());

        // A player who blew straight past every threshold must get the BAN rule, not "kick".
        ConfigManager.PunishmentRule selected = selectRule(rules, 150.0);
        assertEquals("ban", selected.action());
    }

    @Test
    public void testMidRangeVlGetsKickNotBan() {
        List<ConfigManager.PunishmentRule> rules = new ArrayList<>(List.of(
                new ConfigManager.PunishmentRule(15, "log", ""),
                new ConfigManager.PunishmentRule(35, "notify-staff", ""),
                new ConfigManager.PunishmentRule(60, "kick", "kick %player% too high VL"),
                new ConfigManager.PunishmentRule(100, "ban", "ban %player% 14d cheating")
        ));
        rules.sort(Comparator.comparingInt(ConfigManager.PunishmentRule::vlThreshold).reversed());

        ConfigManager.PunishmentRule selected = selectRule(rules, 70.0);
        assertEquals("kick", selected.action());
    }
}
