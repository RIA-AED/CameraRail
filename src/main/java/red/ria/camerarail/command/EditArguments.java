package red.ria.camerarail.command;

import red.ria.camerarail.model.Pose;

/**
 * Pure argument parsing for the edit commands: no {@code CommandSender}, no messages.
 *
 * <p>Every method returns {@code null} when the token cannot be parsed. The caller decides how to
 * report the failure ({@link EditFeedback#invalidValue} for a bad value, {@link EditFeedback#usage}
 * when a required coordinate is missing), exactly as the previous parser-plus-feedback methods did.
 * Keeping the parsing here makes the numeric/boolean/pose rules unit-testable and keeps
 * {@link EditFeedback} focused on rendering.</p>
 */
final class EditArguments {
    private EditArguments() {
    }

    /** Parses a base-10 integer, or {@code null}. */
    static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Parses a floating point number (including the spellings accepted by the old parser). */
    static Double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Parses {@code true/false/on/off/yes/no/1/0}, or {@code null}. */
    static Boolean parseBoolean(String raw) {
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "on", "yes", "1" -> Boolean.TRUE;
            case "false", "off", "no", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * Parses {@code x y z [yaw] [pitch]} starting at {@code from}; yaw/pitch fall back to
     * {@code fallback} when omitted. Returns {@code null} when a coordinate is missing or not a
     * number (the caller distinguishes the two with the argument length and
     * {@link #firstInvalidNumber}).
     */
    static Pose parsePose(String[] args, int from, Pose fallback) {
        if (args.length < from + 3) {
            return null;
        }
        Double x = parseDouble(args[from]);
        Double y = parseDouble(args[from + 1]);
        Double z = parseDouble(args[from + 2]);
        if (x == null || y == null || z == null) {
            return null;
        }
        float yaw = fallback.yaw();
        float pitch = fallback.pitch();
        if (args.length > from + 3) {
            Double parsed = parseDouble(args[from + 3]);
            if (parsed == null) {
                return null;
            }
            yaw = parsed.floatValue();
        }
        if (args.length > from + 4) {
            Double parsed = parseDouble(args[from + 4]);
            if (parsed == null) {
                return null;
            }
            pitch = parsed.floatValue();
        }
        return new Pose(x, y, z, yaw, pitch);
    }

    /**
     * The first token in the pose window {@code [from, from + 5)} that is not a number, or the
     * first coordinate token when none is found. Only used to reproduce the old per-token
     * {@code editor.invalid-value} message after {@link #parsePose} returned {@code null}.
     */
    static String firstInvalidNumber(String[] args, int from) {
        int end = Math.min(args.length, from + 5);
        for (int index = from; index < end; index++) {
            if (parseDouble(args[index]) == null) {
                return args[index];
            }
        }
        return args[from];
    }

    /** Parses the optional {@code add|remove} action token, or {@code null}. */
    static red.ria.camerarail.model.EffectAction parseEffectAction(String raw) {
        try {
            return red.ria.camerarail.model.EffectAction.parse(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** A 1-based index, or {@code null} when the token is not a positive integer. */
    static Integer positiveIndex(String raw) {
        Integer value = parseInt(raw);
        return value == null || value < 1 ? null : value;
    }
}
