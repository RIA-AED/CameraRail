package red.ria.camerarail.command;

import red.ria.camerarail.model.EffectAction;

import java.util.Arrays;
import java.util.Collection;

/**
 * Pure interpretation of the {@code add <type> [参数]} tail: duration bounds, the optional
 * effect action, the two title forms and generated wait-signal names.
 *
 * <p>No Bukkit and no messages: the caller ({@link AddStepCommands}) reports {@code invalidToken}
 * through {@link EditFeedback#invalidValue} and calls the service. Keeping the branchy argument
 * rules here makes them unit-testable and keeps the command handler a thin dispatch layer.</p>
 */
final class AddStepArguments {
    private AddStepArguments() {
    }

    /**
     * One duration argument: exactly one of {@code value}/{@code invalidToken} is non-null.
     * {@code invalidToken} is the raw token when it is not a number, and the parsed number (so
     * {@code 00} reports {@code 0}) when it is below {@code minimum} — the same wording the old
     * per-command parser produced.
     */
    record Duration(Integer value, String invalidToken) {
    }

    static Duration duration(String[] args, int index, int fallback, int minimum) {
        if (args.length <= index) {
            return fallback < minimum ? new Duration(null, String.valueOf(fallback)) : new Duration(fallback, null);
        }
        Integer parsed = EditArguments.parseInt(args[index]);
        if (parsed == null) {
            return new Duration(null, args[index]);
        }
        if (parsed < minimum) {
            return new Duration(null, String.valueOf(parsed));
        }
        return new Duration(parsed, null);
    }

    /**
     * The effect target of {@code add effect [add|remove] <效果> [等级] [时长]}: when token 4 is
     * not an action it is the effect key and the action defaults to ADD ({@code restStart} is the
     * first amplifier/duration token).
     */
    record EffectChoice(EffectAction action, String key, int restStart) {
    }

    static EffectChoice effectChoice(String[] args) {
        EffectAction action = args.length > 4 ? EditArguments.parseEffectAction(args[4]) : null;
        if (action != null) {
            return new EffectChoice(action, args.length > 5 ? args[5] : "darkness", 6);
        }
        return new EffectChoice(EffectAction.ADD, args.length > 4 ? args[4] : "darkness", 5);
    }

    /**
     * The two {@code add title} forms. {@code raw} is {@code 标题|副标题} (or empty); when a numeric
     * argument is not a number, {@code invalidToken} is that token and the caller reports it.
     */
    record TitleChoice(int fadeIn, int stay, int fadeOut, String raw, String invalidToken) {
    }

    static TitleChoice title(String[] args) {
        // Non-integer first argument: the text form with the Dialog default fade times.
        if (args.length > 4 && EditArguments.parseInt(args[4]) == null) {
            return new TitleChoice(20, 60, 20, String.join(" ", Arrays.copyOfRange(args, 4, args.length)), null);
        }
        int fadeIn = 20;
        int stay = 60;
        int fadeOut = 20;
        String invalidToken = null;
        if (args.length > 4) {
            // The text form above already returned for a non-integer token, so token 4 parses.
            fadeIn = EditArguments.parseInt(args[4]);
        }
        if (args.length > 5) {
            Integer parsed = EditArguments.parseInt(args[5]);
            if (parsed == null) {
                if (invalidToken == null) {
                    invalidToken = args[5];
                }
            } else {
                stay = parsed;
            }
        }
        if (args.length > 6) {
            Integer parsed = EditArguments.parseInt(args[6]);
            if (parsed == null) {
                if (invalidToken == null) {
                    invalidToken = args[6];
                }
            } else {
                fadeOut = parsed;
            }
        }
        String raw = args.length >= 8 ? String.join(" ", Arrays.copyOfRange(args, 7, args.length)) : "";
        return new TitleChoice(fadeIn, stay, fadeOut, raw, invalidToken);
    }

    /** First wait signal name unused by this track: {@code wait}, {@code wait2}, {@code wait3}, ... */
    static String nextWaitSignal(Collection<String> usedSignals) {
        String candidate = "wait";
        int suffix = 2;
        while (usedSignals.contains(candidate)) {
            candidate = "wait" + suffix++;
        }
        return candidate;
    }
}
