package net.lovelace.vesuvio.i18n;

import java.util.Locale;
import java.util.ResourceBundle;
import net.lovelace.vesuvio.engine.physics.MovementSimulator;

/** Presentation only: protocol, config, database and API identifiers remain stable. */
public final class Russian {
    private static final ResourceBundle LABELS = ResourceBundle.getBundle("messages", Locale.forLanguageTag("ru"));
    private Russian() {}
    public static String label(String key) {
        if (key == null || key.isBlank()) return "—";
        return LABELS.containsKey(key) ? LABELS.getString(key) : readable(key);
    }

    /**
     * A key without a translation must not reach staff as a raw identifier ("stepHeight", "BLOCKS_IN_WINDOW"): camelCase,
     * snake_case and kebab-case are split into lowercase words. Anything that is not a plain identifier (a sentence, a number,
     * a value that merely went through label()) is returned as it is.
     */
    static String readable(String key) {
        if (!key.matches("[A-Za-z][A-Za-z0-9_\\-]*")) return key;
        String spaced = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').replace('-', ' ');
        // A name that is already a single word keeps its case (a check name such as "Fly" or a material).
        return spaced.indexOf(' ') < 0 ? key : spaced.toLowerCase(Locale.ROOT);
    }
    public static String value(Object value) {
        if (value instanceof Boolean bool) return bool ? "да" : "нет";
        return value == null ? "—" : label(value.toString());
    }
    public static String details(java.util.Map<String, Object> values) {
        return values.entrySet().stream().map(e -> label(e.getKey()) + "=" + value(e.getValue()))
                .collect(java.util.stream.Collectors.joining(", "));
    }
    public static String input(MovementSimulator.Input input) {
        return String.format(Locale.ROOT, "Ввод: вперёд/назад %d, вбок %d, поворот %.1f°, спринт %s, прыжок %s, замедление %s",
                input.forward(), input.strafe(), input.yaw(), value(input.sprint()), value(input.jump()), value(input.slow()));
    }
}
