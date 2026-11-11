package red.ria.camerarail.command;

import org.bukkit.entity.Player;
import red.ria.camerarail.editor.EditService;
import red.ria.camerarail.editor.EditTarget;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.CutStep;
import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.EffectAction;
import red.ria.camerarail.model.EffectSpec;
import red.ria.camerarail.model.EffectStep;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.MoveStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.TitleStep;
import red.ria.camerarail.model.WaitStep;

import java.util.Arrays;
import java.util.Locale;

/**
 * Step-level edit actions: the shared structural actions plus the type-specific fields, mirroring
 * the Dialog editor's step form.
 */
final class StepEditCommands {
    void handle(Player player, EditTarget target, String action, String[] args, EditContext context) {
        CameraTrack track = context.repository().get(target.trackId());
        if (track == null) {
            context.messages().get().send(player, "track.not-found", "track", target.trackId());
            return;
        }
        if (target.stepIndex() >= track.steps().size()) {
            context.messages().get().send(player, "editor.step-missing",
                    "track", target.trackId(), "index", String.valueOf(target.stepIndex() + 1));
            return;
        }
        Step step = track.steps().get(target.stepIndex());
        switch (action) {
            case "" -> context.feedback().statusStep(player, target, step);
            case "remove" -> removeStep(player, target, context);
            case "duplicate" -> duplicateStep(player, target, context);
            case "up" -> moveStep(player, target, -1, context);
            case "down" -> moveStep(player, target, 1, context);
            case "preview" -> EditPreview.toggle(context, player, target);
            case "stop" -> EditPreview.stop(context, player);
            default -> typeStepAction(player, target, step, action, args, context);
        }
    }

    private void typeStepAction(Player player, EditTarget target, Step step, String action,
                                String[] args, EditContext context) {
        switch (step) {
            case MoveStep ignored -> {
                switch (action) {
                    case "here" -> captureStepPose(player, target, context);
                    case "pos" -> setStepPose(player, target, step, args, context);
                    case "duration" -> setDuration(player, target, args, context);
                    case "easing" -> setEasing(player, target, args, context);
                    case "sway" -> setStepSway(player, target, args, context);
                    default -> context.feedback().wrongAction(player, target, action, EditActions.actions(step));
                }
            }
            case PathStep ignored -> {
                switch (action) {
                    case "duration" -> setDuration(player, target, args, context);
                    case "easing" -> setEasing(player, target, args, context);
                    case "sway" -> setStepSway(player, target, args, context);
                    case "point" -> pathStepPoints(player, target, args, context);
                    default -> context.feedback().wrongAction(player, target, action, EditActions.actions(step));
                }
            }
            case HoldStep ignored -> {
                switch (action) {
                    case "duration" -> setDuration(player, target, args, context);
                    case "sway" -> setStepSway(player, target, args, context);
                    default -> context.feedback().wrongAction(player, target, action, EditActions.actions(step));
                }
            }
            case WaitStep ignored -> {
                switch (action) {
                    case "signal" -> setWaitSignal(player, target, args, context);
                    case "timeout" -> setWaitTimeout(player, target, args, context);
                    case "sway" -> setStepSway(player, target, args, context);
                    default -> context.feedback().wrongAction(player, target, action, EditActions.actions(step));
                }
            }
            case CutStep ignored -> {
                switch (action) {
                    case "here" -> captureStepPose(player, target, context);
                    case "pos" -> setStepPose(player, target, step, args, context);
                    case "duration" -> setDuration(player, target, args, context);
                    case "sway" -> setStepSway(player, target, args, context);
                    default -> context.feedback().wrongAction(player, target, action, EditActions.actions(step));
                }
            }
            case EffectStep effect -> {
                if ("effect".equals(action)) {
                    setEffectStep(player, target, effect, args, context);
                } else {
                    context.feedback().wrongAction(player, target, action, EditActions.actions(step));
                }
            }
            case TitleStep ignored -> {
                switch (action) {
                    case "title" -> setTitleText(player, target, args, true, context);
                    case "subtitle" -> setTitleText(player, target, args, false, context);
                    case "fade" -> setTitleFade(player, target, args, context);
                    default -> context.feedback().wrongAction(player, target, action, EditActions.actions(step));
                }
            }
        }
    }

    // ------------------------------------------------------------------ structural actions

    private void removeStep(Player player, EditTarget target, EditContext context) {
        EditService.Result result = context.service().removeStep(player, target);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.step-removed",
                    "track", target.trackId(),
                    "index", String.valueOf(target.stepIndex() + 1),
                    "label", context.label());
        }
    }

    private void duplicateStep(Player player, EditTarget target, EditContext context) {
        EditService.Result result = context.service().duplicateStep(player, target);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.step-duplicated",
                    "track", target.trackId(),
                    "index", String.valueOf(target.stepIndex() + 1),
                    "copy", String.valueOf(result.stepIndex() + 1));
        }
    }

    private void moveStep(Player player, EditTarget target, int delta, EditContext context) {
        EditService.Result result = context.service().moveStep(player, target, delta);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.step-moved",
                    "track", target.trackId(),
                    "from", String.valueOf(target.stepIndex() + 1),
                    "to", String.valueOf(result.stepIndex() + 1));
        }
    }

    // ------------------------------------------------------------------ type-specific fields

    private void captureStepPose(Player player, EditTarget target, EditContext context) {
        EditService.Result result = context.service().capturePose(player, target);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.here-updated", "target", target.display());
        }
    }

    private void setStepPose(Player player, EditTarget target, Step step, String[] args,
                             EditContext context) {
        Pose current = StepFields.targetPose(step);
        Pose pose = context.feedback().requirePose(player, target, args, 3, current,
                "pos <x> <y> <z> [yaw] [pitch]");
        if (pose == null) {
            return;
        }
        EditService.Result result = context.service().setPose(player, target, pose);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.pose-set",
                    "target", target.display(), "pose", context.feedback().poseShort(pose));
        }
    }

    private void setDuration(Player player, EditTarget target, String[] args, EditContext context) {
        if (args.length < 4) {
            context.feedback().usage(player, target.display(), "duration <tick>");
            return;
        }
        Integer ticks = context.feedback().requireInt(player, args[3]);
        if (ticks == null) {
            return;
        }
        EditService.Result result = context.service().updateStep(player, target,
                step -> StepFields.withDuration(step, ticks));
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.duration-set",
                    "target", target.display(), "ticks", String.valueOf(ticks));
        }
    }

    private void setEasing(Player player, EditTarget target, String[] args, EditContext context) {
        if (args.length < 4) {
            context.feedback().usage(player, target.display(), "easing <曲线>");
            return;
        }
        Easing easing;
        try {
            easing = Easing.parse(args[3]);
        } catch (IllegalArgumentException exception) {
            context.feedback().invalidValue(player, args[3]);
            return;
        }
        EditService.Result result = context.service().updateStep(player, target,
                step -> StepFields.withEasing(step, easing));
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.easing-set",
                    "target", target.display(), "easing", StepFields.easingName(easing));
        }
    }

    private void setStepSway(Player player, EditTarget target, String[] args, EditContext context) {
        if (args.length < 4) {
            context.feedback().usage(player, target.display(), "sway <inherit|off|gentle|natural|strong>");
            return;
        }
        SwayPreset preset;
        try {
            preset = SwayPreset.parse(args[3]);
        } catch (IllegalArgumentException exception) {
            context.feedback().invalidValue(player, args[3]);
            return;
        }
        SwayPreset chosen = preset;
        EditService.Result result = context.service().updateStep(player, target,
                current -> StepFields.withSway(current, chosen));
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.step-sway-set",
                    "target", target.display(), "sway", StepFields.swayName(chosen));
        }
    }

    private void setWaitSignal(Player player, EditTarget target, String[] args, EditContext context) {
        if (args.length < 4) {
            context.feedback().usage(player, target.display(), "signal <信号>");
            return;
        }
        String signal = args[3];
        EditService.Result result = context.service().updateStep(player, target,
                step -> step instanceof WaitStep current
                        ? new WaitStep(signal, current.timeoutTicks(), current.sway()) : null);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.signal-set",
                    "target", target.display(), "signal", EditFeedback.safeText(signal));
        }
    }

    private void setWaitTimeout(Player player, EditTarget target, String[] args, EditContext context) {
        if (args.length < 4) {
            context.feedback().usage(player, target.display(), "timeout <tick>");
            return;
        }
        Integer timeout = context.feedback().requireInt(player, args[3]);
        if (timeout == null) {
            return;
        }
        EditService.Result result = context.service().updateStep(player, target,
                step -> step instanceof WaitStep current
                        ? new WaitStep(current.signal(), timeout, current.sway()) : null);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.timeout-set",
                    "target", target.display(), "ticks", String.valueOf(timeout));
        }
    }

    private void setEffectStep(Player player, EditTarget target, EffectStep step, String[] args,
                               EditContext context) {
        if (args.length < 4) {
            context.feedback().usage(player, target.display(), "effect <add|remove> <效果> [等级] [时长]");
            return;
        }
        EffectAction action;
        try {
            action = EffectAction.parse(args[3]);
        } catch (IllegalArgumentException exception) {
            context.feedback().invalidValue(player, args[3]);
            return;
        }
        if (args.length < 5) {
            context.feedback().usage(player, target.display(), "effect <add|remove> <效果> [等级] [时长]");
            return;
        }
        int amplifier = step.effect().amplifier();
        int duration = step.effect().durationTicks();
        if (args.length >= 6) {
            Integer parsed = context.feedback().requireInt(player, args[5]);
            if (parsed == null) {
                return;
            }
            amplifier = parsed;
        }
        if (args.length >= 7) {
            Integer parsed = context.feedback().requireInt(player, args[6]);
            if (parsed == null) {
                return;
            }
            duration = parsed;
        }
        EffectSpec spec = EditEffects.resolve(action, args[4], amplifier, duration,
                context.config().get().maxTrackTicks());
        if (spec == null) {
            context.feedback().invalidEffect(player, args[4]);
            return;
        }
        EditService.Result result = context.service().updateStep(player, target, current -> new EffectStep(spec));
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.effect-step-set",
                    "target", target.display(), "summary", spec.describe());
        }
    }

    private void setTitleText(Player player, EditTarget target, String[] args, boolean main,
                              EditContext context) {
        String text = args.length >= 4
                ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim() : "";
        EditService.Result result = context.service().updateStep(player, target,
                step -> step instanceof TitleStep current
                        ? (main
                        ? new TitleStep(text, current.subtitle(), current.fadeInTicks(), current.stayTicks(),
                        current.fadeOutTicks())
                        : new TitleStep(current.title(), text, current.fadeInTicks(), current.stayTicks(),
                        current.fadeOutTicks()))
                        : null);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, main ? "editor.title-text-set" : "editor.subtitle-set",
                    "target", target.display(), "text", text.isEmpty() ? "(空)" : EditFeedback.safeText(text));
        }
    }

    private void setTitleFade(Player player, EditTarget target, String[] args, EditContext context) {
        if (args.length < 6) {
            context.feedback().usage(player, target.display(), "fade <淡入> <停留> <淡出>");
            return;
        }
        Integer fadeIn = context.feedback().requireInt(player, args[3]);
        Integer stay = context.feedback().requireInt(player, args[4]);
        Integer fadeOut = context.feedback().requireInt(player, args[5]);
        if (fadeIn == null || stay == null || fadeOut == null) {
            return;
        }
        EditService.Result result = context.service().updateStep(player, target,
                step -> step instanceof TitleStep current
                        ? new TitleStep(current.title(), current.subtitle(), fadeIn, stay, fadeOut) : null);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.fade-set",
                    "target", target.display(),
                    "fade_in", String.valueOf(fadeIn),
                    "stay", String.valueOf(stay),
                    "fade_out", String.valueOf(fadeOut));
        }
    }

    private void pathStepPoints(Player player, EditTarget target, String[] args, EditContext context) {
        String sub = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "list" -> {
                CameraTrack track = context.service().view(target.trackId());
                if (track == null) {
                    context.messages().get().send(player, "track.not-found", "track", target.trackId());
                    return;
                }
                context.feedback().listPathPoints(player, track, target);
            }
            case "add" -> {
                EditService.Result result = context.service().appendPathPoint(player, target);
                if (context.feedback().applied(player, result)) {
                    context.feedback().announcePathPoint(player, target.display(), result.track(),
                            target.stepIndex());
                }
            }
            default -> context.feedback().usage(player, target.display(), "point <list|add>");
        }
    }

}
