package red.ria.camerarail.command;

import org.bukkit.entity.Player;
import red.ria.camerarail.editor.EditService;
import red.ria.camerarail.editor.EditTarget;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.EffectAction;
import red.ria.camerarail.model.EffectSpec;
import red.ria.camerarail.model.ExitMode;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;

import java.util.Arrays;
import java.util.Locale;

/**
 * Track-level edit actions: the track-wide settings, entry effects, the step list and the
 * preview/undo/save commands.
 */
final class TrackEditCommands {
    void handle(Player player, EditTarget target, String action, String[] args, EditContext context) {
        CameraTrack track = context.repository().get(target.trackId());
        if (track == null) {
            context.messages().get().send(player, "track.not-found", "track", target.trackId());
            return;
        }
        EditFeedback feedback = context.feedback();
        switch (action) {
            case "" -> feedback.statusTrack(player, track);
            case "display" -> setDisplay(player, track, args, context);
            case "description" -> setDescription(player, track, args, context);
            case "start" -> setStart(player, track, target, args, context);
            case "exit" -> setExit(player, track, target, args, context);
            case "exit-mode" -> setExitMode(player, track, target, args, context);
            case "sway" -> setTrackSway(player, track, target, args, context);
            case "follow" -> setFollow(player, track, target, args, context);
            case "entry" -> entryAction(player, track, target, args, context);
            case "list" -> feedback.listSteps(player, track);
            case "preview" -> EditPreview.toggle(context, player, target);
            case "stop" -> EditPreview.stop(context, player);
            case "undo" -> undo(player, track.id(), context);
            case "save" -> save(player, track.id(), context);
            default -> feedback.wrongAction(player, target, action, EditActions.TRACK_ACTIONS);
        }
    }

    private void setDisplay(Player player, CameraTrack track, String[] args, EditContext context) {
        EditFeedback feedback = context.feedback();
        if (args.length < 4) {
            feedback.usage(player, track.id(), "display <名称>");
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
        if (name.isEmpty()) {
            feedback.usage(player, track.id(), "display <名称>");
            return;
        }
        if (name.length() > 64) {
            context.messages().get().send(player, "editor.display-too-long");
            return;
        }
        EditService.Result result = context.service().updateTrack(player, track.id(), t -> t.withDisplayName(name));
        if (feedback.applied(player, result)) {
            context.messages().get().send(player, "editor.display-set", "track", track.id(), "name", name);
        }
    }

    private void setDescription(Player player, CameraTrack track, String[] args, EditContext context) {
        String description = args.length >= 4
                ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim() : "";
        if (description.length() > 512) {
            context.messages().get().send(player, "editor.description-too-long");
            return;
        }
        EditService.Result result = context.service().updateTrack(player, track.id(),
                t -> t.withDescription(description));
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.description-set", "track", track.id(),
                    "description", description.isEmpty() ? "(空)" : EditFeedback.safeText(description));
        }
    }

    private void setStart(Player player, CameraTrack track, EditTarget target, String[] args,
                          EditContext context) {
        if (args.length == 3) {
            EditService.Result result = context.service().captureStart(player, track.id());
            if (context.feedback().applied(player, result)) {
                context.messages().get().send(player, "editor.start-set", "track", track.id());
            }
            return;
        }
        Pose pose = context.feedback().requirePose(player, target, args, 3,
                new Pose(0, 0, 0, player.getYaw(), player.getPitch()),
                "start <x> <y> <z> [yaw] [pitch]");
        if (pose == null) {
            return;
        }
        EditService.Result result = context.service().setStartPose(player, track.id(), pose);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.start-pose-set", "track", track.id(),
                    "pose", context.feedback().poseShort(pose));
        }
    }

    private void setExit(Player player, CameraTrack track, EditTarget target, String[] args,
                         EditContext context) {
        if (args.length == 3) {
            EditService.Result result = context.service().captureExit(player, track.id());
            if (context.feedback().applied(player, result)) {
                context.messages().get().send(player, "editor.exit-set", "track", track.id());
            }
            return;
        }
        Pose pose = context.feedback().requirePose(player, target, args, 3,
                new Pose(0, 0, 0, player.getYaw(), player.getPitch()),
                "exit <x> <y> <z> [yaw] [pitch]");
        if (pose == null) {
            return;
        }
        EditService.Result result = context.service().setExitPose(player, track.id(), pose);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.exit-pose-set", "track", track.id(),
                    "pose", context.feedback().poseShort(pose));
        }
    }

    private void setExitMode(Player player, CameraTrack track, EditTarget target, String[] args,
                             EditContext context) {
        EditFeedback feedback = context.feedback();
        if (args.length < 4) {
            feedback.usage(player, target.display(), "exit-mode <animation-end|pre-entry|custom>");
            return;
        }
        ExitMode mode;
        try {
            mode = ExitMode.parse(args[3]);
        } catch (IllegalArgumentException exception) {
            feedback.invalidValue(player, args[3]);
            return;
        }
        if (mode == ExitMode.CUSTOM && track.exit() == null) {
            context.messages().get().send(player, "editor.exit-mode-custom",
                    "label", context.label(), "track", track.id());
            return;
        }
        EditService.Result result = context.service().updateTrack(player, track.id(), t -> t.withExitMode(mode));
        if (feedback.applied(player, result)) {
            context.messages().get().send(player, "editor.exit-mode-set", "track", track.id(),
                    "mode", exitModeLabel(mode));
        }
    }

    private void setTrackSway(Player player, CameraTrack track, EditTarget target, String[] args,
                              EditContext context) {
        EditFeedback feedback = context.feedback();
        if (args.length < 4) {
            feedback.usage(player, target.display(), "sway <off|gentle|natural|strong> [周期]");
            return;
        }
        SwayPreset preset;
        try {
            preset = SwayPreset.parse(args[3]);
        } catch (IllegalArgumentException exception) {
            feedback.invalidValue(player, args[3]);
            return;
        }
        if (preset == SwayPreset.INHERIT) {
            feedback.invalidValue(player, args[3]);
            return;
        }
        int period = track.sway().period();
        if (args.length >= 5) {
            Integer parsed = context.feedback().requireInt(player, args[4]);
            if (parsed == null) {
                return;
            }
            period = parsed;
        }
        int safePeriod = Math.max(1, Math.min(context.config().get().maxTrackTicks(), period));
        SwaySettings sway = SwaySettings.ofPreset(preset, safePeriod, track.sway().seed());
        EditService.Result result = context.service().updateTrack(player, track.id(), t -> t.withSway(sway));
        if (feedback.applied(player, result)) {
            context.messages().get().send(player, "editor.sway-set", "track", track.id(),
                    "sway", StepFields.swayName(preset), "period", String.valueOf(safePeriod));
        }
    }

    private void setFollow(Player player, CameraTrack track, EditTarget target, String[] args,
                           EditContext context) {
        EditFeedback feedback = context.feedback();
        if (args.length < 4) {
            feedback.usage(player, target.display(), "follow <true|false>");
            return;
        }
        Boolean follow = EditArguments.parseBoolean(args[3]);
        if (follow == null) {
            feedback.invalidValue(player, args[3]);
            return;
        }
        EditService.Result result = context.service().updateTrack(player, track.id(), t -> t.withFollow(follow));
        if (feedback.applied(player, result)) {
            context.messages().get().send(player, "editor.follow-set", "track", track.id(),
                    "value", follow ? "开启" : "关闭");
        }
    }

    private static String exitModeLabel(ExitMode mode) {
        return switch (mode) {
            case ANIMATION_END -> "动画结束位置";
            case PRE_ENTRY -> "进入前的位置";
            case CUSTOM -> "自定义位置";
        };
    }

    // ------------------------------------------------------------------ entry effects

    private void entryAction(Player player, CameraTrack track, EditTarget target, String[] args,
                             EditContext context) {
        EditFeedback feedback = context.feedback();
        String sub = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "" -> feedback.usage(player, target.display(), "entry <list|add|set|remove> ...");
            case "list" -> feedback.statusEntryEffects(player, track);
            case "add" -> {
                if (args.length < 5) {
                    feedback.usage(player, target.display(), "entry add <效果> [等级] [时长]");
                    return;
                }
                int amplifier = 0;
                int duration = 0;
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
                EffectSpec spec = EditEffects.resolve(EffectAction.ADD, args[4], amplifier, duration,
                context.config().get().maxTrackTicks());
                if (spec == null) {
                    context.feedback().invalidEffect(player, args[4]);
                    return;
                }
                EditService.Result result = context.service().addEntryEffect(player, track.id(), spec);
                if (feedback.applied(player, result)) {
                    context.messages().get().send(player, "editor.entry-added", "effect", spec.describe());
                }
            }
            case "set" -> {
                if (args.length < 6) {
                    feedback.usage(player, target.display(), "entry set <序号> <效果> [等级] [时长]");
                    return;
                }
                Integer index = EditArguments.positiveIndex(args[4]);
                if (index == null) {
                    feedback.invalidValue(player, args[4]);
                    return;
                }
                if (index > track.entryEffects().size()) {
                    context.messages().get().send(player, "editor.entry-missing", "index", String.valueOf(index));
                    return;
                }
                EffectSpec base = track.entryEffects().get(index - 1);
                int amplifier = base.amplifier();
                int duration = base.durationTicks();
                if (args.length >= 7) {
                    Integer parsed = context.feedback().requireInt(player, args[6]);
                    if (parsed == null) {
                        return;
                    }
                    amplifier = parsed;
                }
                if (args.length >= 8) {
                    Integer parsed = context.feedback().requireInt(player, args[7]);
                    if (parsed == null) {
                        return;
                    }
                    duration = parsed;
                }
                EffectSpec spec = EditEffects.resolve(EffectAction.ADD, args[5], amplifier, duration,
                context.config().get().maxTrackTicks());
                if (spec == null) {
                    context.feedback().invalidEffect(player, args[5]);
                    return;
                }
                EditService.Result result = context.service().setEntryEffect(player, track.id(), index - 1, spec);
                if (feedback.applied(player, result)) {
                    context.messages().get().send(player, "editor.entry-set",
                            "index", String.valueOf(index), "effect", spec.describe());
                }
            }
            case "remove" -> {
                if (args.length < 5) {
                    feedback.usage(player, target.display(), "entry remove <序号>");
                    return;
                }
                Integer index = EditArguments.positiveIndex(args[4]);
                if (index == null) {
                    feedback.invalidValue(player, args[4]);
                    return;
                }
                EditService.Result result = context.service().removeEntryEffect(player, track.id(), index - 1);
                if (feedback.applied(player, result)) {
                    context.messages().get().send(player, "editor.entry-removed", "index", String.valueOf(index));
                }
            }
            default -> feedback.usage(player, target.display(), "entry <list|add|set|remove> ...");
        }
    }

    // ------------------------------------------------------------------ undo / save

    private void undo(Player player, String trackId, EditContext context) {
        EditService.Result result = context.service().undo(player, trackId);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.undone");
        }
    }

    private void save(Player player, String trackId, EditContext context) {
        EditService.Result result = context.service().save(player, trackId);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.saved", "track", trackId);
        }
    }

}
