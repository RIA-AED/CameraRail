package red.ria.camerarail.command;

import org.bukkit.entity.Player;
import red.ria.camerarail.editor.EditService;
import red.ria.camerarail.editor.EditTarget;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.EffectSpec;
import red.ria.camerarail.model.EffectStep;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.TitleStep;
import red.ria.camerarail.model.WaitStep;

import java.util.Locale;

/**
 * The track-level {@code add <type>} command: dispatch and service calls only. Argument
 * interpretation lives in {@link AddStepArguments} (pure, testable); effect resolution lives in
 * {@link EditEffects}.
 */
final class AddStepCommands {
    void handle(Player player, EditTarget target, String[] args, EditContext context) {
        CameraTrack track = context.repository().get(target.trackId());
        if (track == null) {
            context.messages().get().send(player, "track.not-found", "track", target.trackId());
            return;
        }
        EditFeedback feedback = context.feedback();
        if (args.length < 4) {
            feedback.usage(player, target.display(),
                    "add <move|path|hold|wait|cut|effect|title> [参数]");
            return;
        }
        String type = args[3].toLowerCase(Locale.ROOT);
        EditService.Result result;
        try {
            result = switch (type) {
                case "move" -> {
                    Integer duration = duration(player, args, 4,
                            context.config().get().defaultMoveDuration(), 1, context);
                    yield duration == null ? null
                            : context.service().addMoveStep(player, target, duration);
                }
                case "path" -> {
                    Integer duration = duration(player, args, 4,
                            context.config().get().defaultMoveDuration(), 1, context);
                    yield duration == null ? null
                            : context.service().addPathStep(player, target, duration);
                }
                case "hold" -> {
                    Integer duration = duration(player, args, 4,
                            context.config().get().defaultMoveDuration(), 1, context);
                    yield duration == null ? null
                            : context.service().addStep(player, target,
                                    new HoldStep(duration, SwayPreset.INHERIT));
                }
                case "cut" -> {
                    Integer duration = duration(player, args, 4, 0, 0, context);
                    yield duration == null ? null
                            : context.service().addCutStep(player, target, duration);
                }
                case "wait" -> {
                    // Omitted signals get a track-unique generated name instead of a literal "signal".
                    String signal = args.length >= 5 ? args[4]
                            : AddStepArguments.nextWaitSignal(track.waitSignals());
                    Integer timeout = duration(player, args, 5,
                            context.config().get().defaultWaitTimeoutTicks(), 0, context);
                    yield timeout == null ? null
                            : context.service().addStep(player, target,
                                    new WaitStep(signal, timeout, SwayPreset.INHERIT));
                }
                case "effect" -> {
                    // `add effect <效果>` is ADD: the add|remove token may be omitted.
                    AddStepArguments.EffectChoice choice = AddStepArguments.effectChoice(args);
                    int amplifier = 0;
                    int duration = 0;
                    if (args.length > choice.restStart()) {
                        Integer parsed = context.feedback().requireInt(player, args[choice.restStart()]);
                        if (parsed == null) {
                            yield null;
                        }
                        amplifier = parsed;
                    }
                    if (args.length > choice.restStart() + 1) {
                        Integer parsed = context.feedback().requireInt(player, args[choice.restStart() + 1]);
                        if (parsed == null) {
                            yield null;
                        }
                        duration = parsed;
                    }
                    EffectSpec spec = EditEffects.resolve(choice.action(), choice.key(), amplifier, duration,
                            context.config().get().maxTrackTicks());
                    if (spec == null) {
                        context.feedback().invalidEffect(player, choice.key());
                        yield null;
                    }
                    yield context.service().addStep(player, target, new EffectStep(spec));
                }
                case "title" -> {
                    // `add title <标题[|副标题]>` keeps the Dialog default fade times; the
                    // numeric form `add title <淡入> <停留> <淡出> <标题[|副标题]>` stays.
                    AddStepArguments.TitleChoice title = AddStepArguments.title(args);
                    if (title.invalidToken() != null) {
                        // The old parser validated stay and fade-out independently and reported
                        // every bad numeric token in that order; token 4 is always numeric in
                        // this form, so replaying 5..6 keeps the exact wording and sequence.
                        for (int index = 5; index <= 6 && index < args.length; index++) {
                            if (EditArguments.parseInt(args[index]) == null) {
                                context.feedback().invalidValue(player, args[index]);
                            }
                        }
                        yield null;
                    }
                    String[] parts = title.raw().split("\\|", 2);
                    yield context.service().addStep(player, target,
                            new TitleStep(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "",
                                    title.fadeIn(), title.stay(), title.fadeOut()));
                }
                default -> {
                    feedback.usage(player, target.display(),
                            "add <move|path|hold|wait|cut|effect|title> [参数]");
                    yield null;
                }
            };
        } catch (IllegalArgumentException exception) {
            context.messages().get().send(player, "editor.edit-failed",
                    "reason", EditFeedback.safeText(exception.getMessage()));
            return;
        }
        if (result == null) {
            return;
        }
        if (feedback.applied(player, result)) {
            feedback.announceAdded(player, result.track(), result.stepIndex());
        }
    }

    /**
     * Reports the exact token the old parser rejected: the raw token when it is not a number, the
     * parsed number when it is below the minimum (so {@code 00} reports {@code 0}).
     */
    private Integer duration(Player player, String[] args, int index, int fallback, int minimum,
                             EditContext context) {
        AddStepArguments.Duration duration = AddStepArguments.duration(args, index, fallback, minimum);
        if (duration.value() == null) {
            context.feedback().invalidValue(player, duration.invalidToken());
            return null;
        }
        return duration.value();
    }
}
