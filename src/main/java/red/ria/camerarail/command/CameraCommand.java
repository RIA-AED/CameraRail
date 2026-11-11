package red.ria.camerarail.command;

import red.ria.camerarail.config.RailConfig;
import red.ria.camerarail.config.TrackRepository;
import red.ria.camerarail.editor.EditService;
import red.ria.camerarail.editor.EditTarget;
import red.ria.camerarail.editor.EditorManager;
import red.ria.camerarail.editor.EditorTools;
import red.ria.camerarail.editor.EditorUI;
import red.ria.camerarail.message.Messages;
import red.ria.camerarail.model.CameraEffects;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.CutStep;
import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.EffectAction;
import red.ria.camerarail.model.EffectSpec;
import red.ria.camerarail.model.EffectStep;
import red.ria.camerarail.model.ExitMode;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.MoveStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;
import red.ria.camerarail.model.TitleStep;
import red.ria.camerarail.model.WaitStep;
import red.ria.camerarail.runtime.CameraEngine;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Single command surface. The editing half mirrors the Dialog hierarchy instead of flattening
 * everything into one action list:
 *
 * <ul>
 *   <li>{@code edit <track>} — track-wide: settings (display/description/start/exit/exit-mode/
 *       sway/follow), entry effects, the step collection ({@code list}, {@code add <type>}) and
 *       preview/undo/save.</li>
 *   <li>{@code edit <track>:<step>} — the step's own type-specific fields, plus the structural
 *       actions shared by every step (remove/duplicate/up/down/preview).</li>
 *   <li>{@code edit <track>:<step>:<point>} — one path point.</li>
 * </ul>
 *
 * Nothing depends on a hidden "selected step", so every command is safe to run in any order.
 */
public final class CameraCommand implements CommandExecutor, TabCompleter {
    private final CameraEngine engine;
    private final TrackRepository repository;
    private final EditorManager editors;
    private final EditorUI editorUi;
    private final EditService editService;
    private final EditorTools editorTools;
    private final Supplier<RailConfig> config;
    private final Supplier<Messages> messages;
    private final IntSupplier reloadAction;
    private final EditCompletions completions;

    public CameraCommand(CameraEngine engine, TrackRepository repository, EditorManager editors, EditorUI editorUi,
                         EditService editService, EditorTools editorTools,
                         Supplier<RailConfig> config, Supplier<Messages> messages, IntSupplier reloadAction) {
        this.engine = engine;
        this.repository = repository;
        this.editors = editors;
        this.editorUi = editorUi;
        this.editService = editService;
        this.editorTools = editorTools;
        this.config = config;
        this.messages = messages;
        this.reloadAction = reloadAction;
        this.completions = new EditCompletions(engine, repository, messages);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("camerarail.command")) {
            messages.get().send(sender, "command.no-permission");
            return true;
        }
        if (args.length == 0) {
            help(sender, args, label);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        // Keep this switch in sync with CommandHelp.ROOT_ENTRIES: the table drives /camerarail help
        // and tab completion.
        switch (action) {
            case "help" -> help(sender, args, label);
            case "list" -> list(sender, label);
            case "info" -> info(sender, args, label);
            case "play" -> play(sender, args, label);
            case "stop" -> stop(sender, args, label);
            case "advance" -> advance(sender, args, label);
            case "reload" -> reload(sender);
            case "create" -> create(sender, args, label);
            case "delete" -> delete(sender, args, label);
            case "editor" -> editor(sender, args, label);
            case "tools" -> tools(sender, label);
            case "edit" -> edit(sender, args, label);
            default -> messages.get().send(sender, "command.unknown-subcommand", "arg", action, "label", label);
        }
        return true;
    }

    private void help(CommandSender sender, String[] args, String label) {
        CommandHelp help = new CommandHelp(messages.get(), label, sender::hasPermission);
        if (args.length >= 2 && args[1].equalsIgnoreCase("edit")) {
            // "help edit" is the action reference of the stateless edit command family.
            if (args.length >= 3) {
                help.sendDetail(sender, CommandHelp.Scope.EDITOR, args[2]);
            } else {
                help.sendOverview(sender, CommandHelp.Scope.EDITOR);
            }
            return;
        }
        if (args.length >= 2) {
            help.sendDetail(sender, CommandHelp.Scope.ROOT, args[1]);
        } else {
            help.sendOverview(sender, CommandHelp.Scope.ROOT);
        }
    }

    /**
     * Opens the Dialog editor: without arguments at the track list, with a track id directly on
     * that track's session, and with {@code close} it closes the current session.
     */
    private void editor(CommandSender sender, String[] args, String label) {
        if (!require(sender, "camerarail.edit")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.get().send(sender, "command.player-only");
            return;
        }
        if (args.length >= 2) {
            if (args.length > 2) {
                messages.get().send(player, "editor.usage", "label", label);
                return;
            }
            if (args[1].equalsIgnoreCase("close")) {
                editors.close(player);
                return;
            }
            // Reuse the edit-target id rules so `editor <id>` accepts exactly what `edit <id>` does.
            EditTarget target = EditTarget.parse(args[1]);
            if (target == null || target.hasStep()) {
                messages.get().send(player, "editor.usage", "label", label);
                return;
            }
            editors.open(player, target.trackId());
        }
        editorUi.open(player);
    }

    /** Opens the editing-tool chest GUI. */
    private void tools(CommandSender sender, String label) {
        if (!require(sender, "camerarail.edit")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.get().send(sender, "command.player-only");
            return;
        }
        editorTools.openMenu(player);
    }

    private void list(CommandSender sender, String label) {
        if (!require(sender, "camerarail.command")) {
            return;
        }
        var tracks = new ArrayList<>(repository.all().values());
        tracks.sort((left, right) -> left.id().compareTo(right.id()));
        if (tracks.isEmpty()) {
            messages.get().send(sender, "track.list-empty", "label", label);
            return;
        }
        messages.get().send(sender, "track.list-header", "count", String.valueOf(tracks.size()));
        for (CameraTrack track : tracks) {
            messages.get().send(sender, "track.list-entry",
                    "label", label,
                    "id", track.id(),
                    "name", track.displayName(),
                    "steps", String.valueOf(track.steps().size()),
                    "ticks", String.valueOf(track.timedDurationTicks()));
        }
    }

    private void info(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            messages.get().send(sender, "command.usage", "usage", "/" + label + " info <id>");
            return;
        }
        CameraTrack track = repository.get(args[1]);
        if (track == null) {
            messages.get().send(sender, "track.not-found", "track", args[1]);
            return;
        }
        new EditFeedback(messages, label, engine).infoTrack(sender, track);
    }

    private void play(CommandSender sender, String[] args, String label) {
        if (!require(sender, "camerarail.play")) {
            return;
        }
        String trackId;
        if (args.length < 2) {
            // Convenience for a login/transition server with a single configured shot.
            var tracks = repository.all();
            if (tracks.size() != 1) {
                messages.get().send(sender, "command.usage", "usage", "/" + label + " play <id> [玩家]");
                return;
            }
            trackId = tracks.keySet().iterator().next();
        } else {
            trackId = args[1];
        }
        Player target = target(sender, args.length >= 3 ? args[2] : null);
        if (target == null) {
            return;
        }
        CameraTrack track = repository.get(trackId);
        if (track == null) {
            messages.get().send(sender, "track.not-found", "track", trackId);
            return;
        }
        if (engine.isPlaying(target)) {
            if (target.equals(sender)) {
                messages.get().send(sender, "track.already-playing", "label", label);
            } else {
                messages.get().send(sender, "track.play-failed", "track", track.id(),
                        "reason", "该玩家已在镜头中");
            }
            return;
        }
        if (!engine.play(target, track.id())) {
            messages.get().send(sender, "track.play-failed", "track", track.id(), "reason", "轨道不可用");
            return;
        }
        if (target.equals(sender)) {
            messages.get().send(sender, "track.play-started", "track", track.id());
        } else {
            messages.get().send(sender, "track.play-other", "player", target.getName(), "track", track.id());
        }
    }

    private void stop(CommandSender sender, String[] args, String label) {
        if (!require(sender, "camerarail.stop")) {
            return;
        }
        Player target = target(sender, args.length >= 2 ? args[1] : null);
        if (target == null) {
            return;
        }
        String trackId = engine.playback(target).map(red.ria.camerarail.api.PlaybackInfo::trackId)
                .orElse("当前轨道");
        if (!engine.stop(target)) {
            messages.get().send(sender, "track.no-session");
            return;
        }
        if (target.equals(sender)) {
            messages.get().send(sender, "track.stop", "track", trackId);
        } else {
            messages.get().send(sender, "track.stop-other", "player", target.getName(), "track", trackId);
        }
    }

    private void advance(CommandSender sender, String[] args, String label) {
        if (!require(sender, "camerarail.advance")) {
            return;
        }
        if (args.length < 2) {
            messages.get().send(sender, "command.usage",
                    "usage", "/" + label + " advance <信号|*> [玩家]");
            return;
        }
        Player target = target(sender, args.length >= 3 ? args[2] : null);
        if (target == null) {
            return;
        }
        Optional<String> waiting = engine.waitingSignal(target);
        boolean any = args[1].equals("*");
        boolean matchesCurrent = any || waiting.map(args[1]::equals).orElse(false);
        boolean accepted = any ? engine.advance(target) : engine.advance(target, args[1]);
        if (!accepted) {
            messages.get().send(sender, "track.advance-none");
            return;
        }
        if (!matchesCurrent) {
            messages.get().send(sender, "track.advance-queued", "signal", any ? "*" : args[1]);
        } else {
            messages.get().send(sender, "track.advance", "signal", any ? "*" : args[1]);
        }
    }

    private void reload(CommandSender sender) {
        if (!require(sender, "camerarail.reload")) {
            return;
        }
        if (editors.hasEditors()) {
            messages.get().send(sender, "reload.editing");
            return;
        }
        int count = reloadAction.getAsInt();
        if (count < 0) {
            messages.get().send(sender, "reload.failure", "reason", "配置文件存在无法修复的错误，详见控制台");
        } else {
            messages.get().send(sender, "reload.success", "tracks", String.valueOf(count));
        }
    }

    private void create(CommandSender sender, String[] args, String label) {
        if (!require(sender, "camerarail.manage") || !require(sender, "camerarail.edit")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.get().send(sender, "manage.no-player");
            return;
        }
        if (args.length < 2) {
            messages.get().send(sender, "command.usage", "usage", "/" + label + " create <id> [名称]");
            return;
        }
        String display = args.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
        EditService.Result result = editService.createTrack(player, args[1], display);
        if (result.failed()) {
            messages.get().send(player, "editor.edit-failed", "reason", result.error());
            return;
        }
        messages.get().send(player, "editor.created", "track", result.track().id());
    }

    private void delete(CommandSender sender, String[] args, String label) {
        if (!require(sender, "camerarail.manage") || !require(sender, "camerarail.edit")) {
            return;
        }
        if (args.length < 2) {
            messages.get().send(sender, "command.usage", "usage", "/" + label + " delete <id> confirm");
            return;
        }
        CameraTrack track = repository.get(args[1]);
        if (track == null) {
            messages.get().send(sender, "track.not-found", "track", args[1]);
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            messages.get().send(sender, "manage.delete-confirm",
                    "track", track.id(), "command", "/" + label + " delete " + track.id() + " confirm");
            return;
        }
        if (editors.isEditingTrack(track.id())) {
            messages.get().send(sender, "manage.edit-busy", "track", track.id());
            return;
        }
        repository.delete(track.id());
        repository.saveAsyncLogged().whenComplete((ignored, error) -> {
            if (error == null) {
                messages.get().send(sender, "manage.deleted", "track", track.id());
            } else {
                messages.get().send(sender, "editor.save-failed");
            }
        });
    }

    // ------------------------------------------------------------------ editor entry

    /**
     * The stateless edit command family. Argument 1 is always a target parameter; the level of
     * the target decides which fields exist, exactly like the Dialog screens.
     */
    private void edit(CommandSender sender, String[] args, String label) {
        if (!require(sender, "camerarail.edit")) {
            return;
        }
        EditFeedback feedback = new EditFeedback(messages, label, engine);
        if (args.length == 1) {
            // Read-only reference, so the console may list the actions too.
            feedback.actionUsage(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.get().send(sender, "command.player-only");
            return;
        }
        EditTarget target = EditTarget.parse(args[1]);
        if (target == null) {
            messages.get().send(player, "editor.target-usage", "label", label);
            return;
        }
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
        if (target.hasPoint()) {
            pointAction(player, target, action, args, feedback);
        } else if (target.hasStep()) {
            stepAction(player, target, action, args, label, feedback);
        } else {
            trackAction(player, target, action, args, label, feedback);
        }
    }

    // ------------------------------------------------------------------ track level

    private void trackAction(Player player, EditTarget target, String action, String[] args,
                             String label, EditFeedback feedback) {
        CameraTrack track = repository.get(target.trackId());
        if (track == null) {
            messages.get().send(player, "track.not-found", "track", target.trackId());
            return;
        }
        switch (action) {
            case "" -> feedback.statusTrack(player, track);
            case "display" -> setDisplay(player, track, args, feedback);
            case "description" -> setDescription(player, track, args, feedback);
            case "start" -> setStart(player, track, target, args, feedback);
            case "exit" -> setExit(player, track, target, args, feedback);
            case "exit-mode" -> setExitMode(player, track, target, args, label, feedback);
            case "sway" -> setTrackSway(player, track, target, args, feedback);
            case "follow" -> setFollow(player, track, target, args, feedback);
            case "entry" -> entryAction(player, track, target, args, feedback);
            case "list" -> feedback.listSteps(player, track);
            case "add" -> addStep(player, track, target, args, feedback);
            case "preview" -> preview(player, target, feedback);
            case "stop" -> stopPreview(player, feedback);
            case "undo" -> undo(player, track.id(), feedback);
            case "save" -> save(player, track.id(), feedback);
            default -> feedback.wrongAction(player, target, action, StepFields.TRACK_ACTIONS);
        }
    }

    private void setDisplay(Player player, CameraTrack track, String[] args, EditFeedback feedback) {
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
            messages.get().send(player, "editor.display-too-long");
            return;
        }
        EditService.Result result = editService.updateTrack(player, track.id(), t -> t.withDisplayName(name));
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.display-set", "track", track.id(), "name", name);
        }
    }

    private void setDescription(Player player, CameraTrack track, String[] args, EditFeedback feedback) {
        String description = args.length >= 4
                ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim() : "";
        if (description.length() > 512) {
            messages.get().send(player, "editor.description-too-long");
            return;
        }
        EditService.Result result = editService.updateTrack(player, track.id(), t -> t.withDescription(description));
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.description-set", "track", track.id(),
                    "description", description.isEmpty() ? "(空)" : EditFeedback.safeText(description));
        }
    }

    private void setStart(Player player, CameraTrack track, EditTarget target, String[] args,
                          EditFeedback feedback) {
        if (args.length == 3) {
            EditService.Result result = editService.captureStart(player, track.id());
            if (feedback.applied(player, result)) {
                messages.get().send(player, "editor.start-set", "track", track.id());
            }
            return;
        }
        Pose pose = feedback.parsePose(player, args, 3,
                new Pose(0, 0, 0, player.getYaw(), player.getPitch()), target,
                "start <x> <y> <z> [yaw] [pitch]");
        if (pose == null) {
            return;
        }
        EditService.Result result = editService.setStartPose(player, track.id(), pose);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.start-pose-set", "track", track.id(),
                    "pose", feedback.poseShort(pose));
        }
    }

    private void setExit(Player player, CameraTrack track, EditTarget target, String[] args,
                         EditFeedback feedback) {
        if (args.length == 3) {
            EditService.Result result = editService.captureExit(player, track.id());
            if (feedback.applied(player, result)) {
                messages.get().send(player, "editor.exit-set", "track", track.id());
            }
            return;
        }
        Pose pose = feedback.parsePose(player, args, 3,
                new Pose(0, 0, 0, player.getYaw(), player.getPitch()), target,
                "exit <x> <y> <z> [yaw] [pitch]");
        if (pose == null) {
            return;
        }
        EditService.Result result = editService.setExitPose(player, track.id(), pose);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.exit-pose-set", "track", track.id(),
                    "pose", feedback.poseShort(pose));
        }
    }

    private void setExitMode(Player player, CameraTrack track, EditTarget target, String[] args,
                             String label, EditFeedback feedback) {
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
            messages.get().send(player, "editor.exit-mode-custom",
                    "label", label, "track", track.id());
            return;
        }
        EditService.Result result = editService.updateTrack(player, track.id(), t -> t.withExitMode(mode));
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.exit-mode-set", "track", track.id(), "mode", exitModeLabel(mode));
        }
    }

    private void setTrackSway(Player player, CameraTrack track, EditTarget target, String[] args,
                              EditFeedback feedback) {
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
            Integer parsed = feedback.parseInt(player, args[4]);
            if (parsed == null) {
                return;
            }
            period = parsed;
        }
        int safePeriod = Math.max(1, Math.min(config.get().maxTrackTicks(), period));
        SwaySettings sway = SwaySettings.ofPreset(preset, safePeriod, track.sway().seed());
        EditService.Result result = editService.updateTrack(player, track.id(), t -> t.withSway(sway));
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.sway-set", "track", track.id(),
                    "sway", StepFields.swayName(preset), "period", String.valueOf(safePeriod));
        }
    }

    private void setFollow(Player player, CameraTrack track, EditTarget target, String[] args,
                           EditFeedback feedback) {
        if (args.length < 4) {
            feedback.usage(player, target.display(), "follow <true|false>");
            return;
        }
        Boolean follow = EditFeedback.parseBoolean(args[3]);
        if (follow == null) {
            feedback.invalidValue(player, args[3]);
            return;
        }
        EditService.Result result = editService.updateTrack(player, track.id(), t -> t.withFollow(follow));
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.follow-set", "track", track.id(), "value", follow ? "开启" : "关闭");
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
                             EditFeedback feedback) {
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
                    Integer parsed = feedback.optionalNonNegativeInt(player, args[5]);
                    if (parsed == null) {
                        return;
                    }
                    amplifier = parsed;
                }
                if (args.length >= 7) {
                    Integer parsed = feedback.optionalNonNegativeInt(player, args[6]);
                    if (parsed == null) {
                        return;
                    }
                    duration = parsed;
                }
                EffectSpec spec = effectSpec(player, EffectAction.ADD, args[4], amplifier, duration);
                if (spec == null) {
                    return;
                }
                EditService.Result result = editService.addEntryEffect(player, track.id(), spec);
                if (feedback.applied(player, result)) {
                    messages.get().send(player, "editor.entry-added", "effect", spec.describe());
                }
            }
            case "set" -> {
                if (args.length < 6) {
                    feedback.usage(player, target.display(), "entry set <序号> <效果> [等级] [时长]");
                    return;
                }
                Integer index = feedback.positiveIndex(player, args[4]);
                if (index == null) {
                    return;
                }
                if (index > track.entryEffects().size()) {
                    messages.get().send(player, "editor.entry-missing", "index", String.valueOf(index));
                    return;
                }
                EffectSpec base = track.entryEffects().get(index - 1);
                int amplifier = base.amplifier();
                int duration = base.durationTicks();
                if (args.length >= 7) {
                    Integer parsed = feedback.optionalNonNegativeInt(player, args[6]);
                    if (parsed == null) {
                        return;
                    }
                    amplifier = parsed;
                }
                if (args.length >= 8) {
                    Integer parsed = feedback.optionalNonNegativeInt(player, args[7]);
                    if (parsed == null) {
                        return;
                    }
                    duration = parsed;
                }
                EffectSpec spec = effectSpec(player, EffectAction.ADD, args[5], amplifier, duration);
                if (spec == null) {
                    return;
                }
                EditService.Result result = editService.setEntryEffect(player, track.id(), index - 1, spec);
                if (feedback.applied(player, result)) {
                    messages.get().send(player, "editor.entry-set",
                            "index", String.valueOf(index), "effect", spec.describe());
                }
            }
            case "remove" -> {
                if (args.length < 5) {
                    feedback.usage(player, target.display(), "entry remove <序号>");
                    return;
                }
                Integer index = feedback.positiveIndex(player, args[4]);
                if (index == null) {
                    return;
                }
                EditService.Result result = editService.removeEntryEffect(player, track.id(), index - 1);
                if (feedback.applied(player, result)) {
                    messages.get().send(player, "editor.entry-removed", "index", String.valueOf(index));
                }
            }
            default -> feedback.usage(player, target.display(), "entry <list|add|set|remove> ...");
        }
    }

    // ------------------------------------------------------------------ steps: add

    private void addStep(Player player, CameraTrack track, EditTarget target, String[] args,
                         EditFeedback feedback) {
        if (args.length < 4) {
            feedback.usage(player, target.display(), "add <move|path|hold|wait|cut|effect|title> [参数]");
            return;
        }
        String type = args[3].toLowerCase(Locale.ROOT);
        EditService.Result result;
        try {
            result = switch (type) {
                case "move" -> {
                    Integer duration = feedback.optionalDuration(player, args, 4,
                            config.get().defaultMoveDuration(), 1);
                    yield duration == null ? null
                            : editService.addMoveStep(player, target, duration);
                }
                case "path" -> {
                    Integer duration = feedback.optionalDuration(player, args, 4,
                            config.get().defaultMoveDuration(), 1);
                    yield duration == null ? null
                            : editService.addPathStep(player, target, duration);
                }
                case "hold" -> {
                    Integer duration = feedback.optionalDuration(player, args, 4,
                            config.get().defaultMoveDuration(), 1);
                    yield duration == null ? null
                            : editService.addStep(player, target,
                                    new HoldStep(duration, SwayPreset.INHERIT));
                }
                case "cut" -> {
                    Integer duration = feedback.optionalDuration(player, args, 4, 0, 0);
                    yield duration == null ? null
                            : editService.addCutStep(player, target, duration);
                }
                case "wait" -> {
                    // Omitted signals get a track-unique generated name instead of a literal "signal".
                    String signal = args.length >= 5 ? args[4] : nextWaitSignal(track);
                    Integer timeout = feedback.optionalDuration(player, args, 5,
                            config.get().defaultWaitTimeoutTicks(), 0);
                    yield timeout == null ? null
                            : editService.addStep(player, target,
                                    new WaitStep(signal, timeout, SwayPreset.INHERIT));
                }
                case "effect" -> {
                    // `add effect <效果>` is ADD: the add|remove token may be omitted.
                    EffectAction action = EffectAction.ADD;
                    String key;
                    int first;
                    if (args.length > 4 && isEffectAction(args[4])) {
                        action = parseEffectAction(feedback, player, args[4]);
                        if (action == null) {
                            yield null;
                        }
                        key = args.length > 5 ? args[5] : "darkness";
                        first = 6;
                    } else {
                        key = args.length > 4 ? args[4] : "darkness";
                        first = 5;
                    }
                    int amplifier = 0;
                    int duration = 0;
                    if (args.length > first) {
                        Integer parsed = feedback.optionalNonNegativeInt(player, args[first]);
                        if (parsed == null) {
                            yield null;
                        }
                        amplifier = parsed;
                    }
                    if (args.length > first + 1) {
                        Integer parsed = feedback.optionalNonNegativeInt(player, args[first + 1]);
                        if (parsed == null) {
                            yield null;
                        }
                        duration = parsed;
                    }
                    EffectSpec spec = effectSpec(player, action, key, amplifier, duration);
                    yield spec == null ? null : editService.addStep(player, target, new EffectStep(spec));
                }
                case "title" -> {
                    // `add title <标题[|副标题]>` keeps the Dialog default fade times; the
                    // numeric form `add title <淡入> <停留> <淡出> <标题[|副标题]>` stays.
                    int fadeIn = 20;
                    int stay = 60;
                    int fadeOut = 20;
                    String raw;
                    if (args.length > 4 && !isInteger(args[4])) {
                        raw = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                    } else {
                        Integer parsedFadeIn = feedback.optionalInt(player, args, 4, 20);
                        Integer parsedStay = feedback.optionalInt(player, args, 5, 60);
                        Integer parsedFadeOut = feedback.optionalInt(player, args, 6, 20);
                        if (parsedFadeIn == null || parsedStay == null || parsedFadeOut == null) {
                            yield null;
                        }
                        fadeIn = parsedFadeIn;
                        stay = parsedStay;
                        fadeOut = parsedFadeOut;
                        raw = args.length >= 8
                                ? String.join(" ", Arrays.copyOfRange(args, 7, args.length)) : "";
                    }
                    String[] parts = raw.split("\\|", 2);
                    String title = parts[0].trim();
                    String subtitle = parts.length > 1 ? parts[1].trim() : "";
                    yield editService.addStep(player, target,
                            new TitleStep(title, subtitle, fadeIn, stay, fadeOut));
                }
                default -> {
                    feedback.usage(player, target.display(),
                            "add <move|path|hold|wait|cut|effect|title> [参数]");
                    yield null;
                }
            };
        } catch (IllegalArgumentException exception) {
            messages.get().send(player, "editor.edit-failed",
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
    /** First wait signal name unused by this track: {@code wait}, {@code wait2}, {@code wait3}, ... */
    private static String nextWaitSignal(CameraTrack track) {
        Set<String> used = track.waitSignals();
        String candidate = "wait";
        int suffix = 2;
        while (used.contains(candidate)) {
            candidate = "wait" + suffix++;
        }
        return candidate;
    }

    // ------------------------------------------------------------------ step level

    private void stepAction(Player player, EditTarget target, String action, String[] args,
                            String label, EditFeedback feedback) {
        CameraTrack track = repository.get(target.trackId());
        if (track == null) {
            messages.get().send(player, "track.not-found", "track", target.trackId());
            return;
        }
        if (target.stepIndex() >= track.steps().size()) {
            messages.get().send(player, "editor.step-missing",
                    "track", target.trackId(), "index", String.valueOf(target.stepIndex() + 1));
            return;
        }
        Step step = track.steps().get(target.stepIndex());
        switch (action) {
            case "" -> feedback.statusStep(player, target, step);
            case "remove" -> removeStep(player, target, label, feedback);
            case "duplicate" -> duplicateStep(player, target, feedback);
            case "up" -> moveStep(player, target, -1, feedback);
            case "down" -> moveStep(player, target, 1, feedback);
            case "preview" -> preview(player, target, feedback);
            case "stop" -> stopPreview(player, feedback);
            default -> typeStepAction(player, target, step, action, args, feedback);
        }
    }

    private void typeStepAction(Player player, EditTarget target, Step step, String action,
                                String[] args, EditFeedback feedback) {
        switch (step) {
            case MoveStep ignored -> {
                switch (action) {
                    case "here" -> captureStepPose(player, target, feedback);
                    case "pos" -> setStepPose(player, target, step, args, feedback);
                    case "duration" -> setDuration(player, target, args, feedback);
                    case "easing" -> setEasing(player, target, args, feedback);
                    case "sway" -> setStepSway(player, target, step, args, feedback);
                    default -> feedback.wrongAction(player, target, action, StepFields.actions(step));
                }
            }
            case PathStep ignored -> {
                switch (action) {
                    case "duration" -> setDuration(player, target, args, feedback);
                    case "easing" -> setEasing(player, target, args, feedback);
                    case "sway" -> setStepSway(player, target, step, args, feedback);
                    case "point" -> pathStepPoints(player, target, args, feedback);
                    default -> feedback.wrongAction(player, target, action, StepFields.actions(step));
                }
            }
            case HoldStep ignored -> {
                switch (action) {
                    case "duration" -> setDuration(player, target, args, feedback);
                    case "sway" -> setStepSway(player, target, step, args, feedback);
                    default -> feedback.wrongAction(player, target, action, StepFields.actions(step));
                }
            }
            case WaitStep ignored -> {
                switch (action) {
                    case "signal" -> setWaitSignal(player, target, args, feedback);
                    case "timeout" -> setWaitTimeout(player, target, args, feedback);
                    case "sway" -> setStepSway(player, target, step, args, feedback);
                    default -> feedback.wrongAction(player, target, action, StepFields.actions(step));
                }
            }
            case CutStep ignored -> {
                switch (action) {
                    case "here" -> captureStepPose(player, target, feedback);
                    case "pos" -> setStepPose(player, target, step, args, feedback);
                    case "duration" -> setDuration(player, target, args, feedback);
                    case "sway" -> setStepSway(player, target, step, args, feedback);
                    default -> feedback.wrongAction(player, target, action, StepFields.actions(step));
                }
            }
            case EffectStep effect -> {
                if ("effect".equals(action)) {
                    setEffectStep(player, target, effect, args, feedback);
                } else {
                    feedback.wrongAction(player, target, action, StepFields.actions(step));
                }
            }
            case TitleStep ignored -> {
                switch (action) {
                    case "title" -> setTitleText(player, target, args, true, feedback);
                    case "subtitle" -> setTitleText(player, target, args, false, feedback);
                    case "fade" -> setTitleFade(player, target, args, feedback);
                    default -> feedback.wrongAction(player, target, action, StepFields.actions(step));
                }
            }
        }
    }

    private void captureStepPose(Player player, EditTarget target, EditFeedback feedback) {
        EditService.Result result = editService.capturePose(player, target);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.here-updated", "target", target.display());
        }
    }

    private void setStepPose(Player player, EditTarget target, Step step, String[] args,
                             EditFeedback feedback) {
        if (args.length < 5) {
            feedback.usage(player, target.display(), "pos <x> <y> <z> [yaw] [pitch]");
            return;
        }
        Pose current = stepTargetPose(step);
        Pose pose = feedback.parsePose(player, args, 3, current, target,
                "pos <x> <y> <z> [yaw] [pitch]");
        if (pose == null) {
            return;
        }
        EditService.Result result = editService.setPose(player, target, pose);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.pose-set",
                    "target", target.display(), "pose", feedback.poseShort(pose));
        }
    }

    private static Pose stepTargetPose(Step step) {
        return switch (step) {
            case MoveStep move -> move.to();
            case CutStep cut -> cut.to();
            default -> new Pose(0, 0, 0, 0, 0);
        };
    }

    private void setDuration(Player player, EditTarget target, String[] args, EditFeedback feedback) {
        if (args.length < 4) {
            feedback.usage(player, target.display(), "duration <tick>");
            return;
        }
        Integer ticks = feedback.parseInt(player, args[3]);
        if (ticks == null) {
            return;
        }
        EditService.Result result = editService.updateStep(player, target, step -> switch (step) {
            case MoveStep move -> new MoveStep(move.to(), ticks, move.easing(), move.sway());
            case PathStep path -> new PathStep(path.points(), ticks, path.easing(), path.sway());
            case HoldStep hold -> new HoldStep(ticks, hold.sway());
            case CutStep cut -> new CutStep(cut.to(), ticks, cut.sway());
            case WaitStep wait -> new WaitStep(wait.signal(), ticks, wait.sway());
            default -> null;
        });
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.duration-set",
                    "target", target.display(), "ticks", String.valueOf(ticks));
        }
    }

    private void setEasing(Player player, EditTarget target, String[] args, EditFeedback feedback) {
        if (args.length < 4) {
            feedback.usage(player, target.display(), "easing <曲线>");
            return;
        }
        Easing easing;
        try {
            easing = Easing.parse(args[3]);
        } catch (IllegalArgumentException exception) {
            feedback.invalidValue(player, args[3]);
            return;
        }
        EditService.Result result = editService.updateStep(player, target, step -> switch (step) {
            case MoveStep move -> new MoveStep(move.to(), move.durationTicks(), easing, move.sway());
            case PathStep path -> new PathStep(path.points(), path.durationTicks(), easing, path.sway());
            default -> null;
        });
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.easing-set",
                    "target", target.display(), "easing", StepFields.easingName(easing));
        }
    }

    private void setStepSway(Player player, EditTarget target, Step step, String[] args,
                             EditFeedback feedback) {
        if (args.length < 4) {
            feedback.usage(player, target.display(), "sway <inherit|off|gentle|natural|strong>");
            return;
        }
        SwayPreset preset;
        try {
            preset = SwayPreset.parse(args[3]);
        } catch (IllegalArgumentException exception) {
            feedback.invalidValue(player, args[3]);
            return;
        }
        SwayPreset chosen = preset;
        EditService.Result result = editService.updateStep(player, target, current -> switch (current) {
            case MoveStep move -> new MoveStep(move.to(), move.durationTicks(), move.easing(), chosen);
            case PathStep path -> new PathStep(path.points(), path.durationTicks(), path.easing(), chosen);
            case HoldStep hold -> new HoldStep(hold.durationTicks(), chosen);
            case WaitStep wait -> new WaitStep(wait.signal(), wait.timeoutTicks(), chosen);
            case CutStep cut -> new CutStep(cut.to(), cut.durationTicks(), chosen);
            default -> null;
        });
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.step-sway-set",
                    "target", target.display(), "sway", StepFields.swayName(chosen));
        }
    }

    private void setWaitSignal(Player player, EditTarget target, String[] args, EditFeedback feedback) {
        if (args.length < 4) {
            feedback.usage(player, target.display(), "signal <信号>");
            return;
        }
        String signal = args[3];
        EditService.Result result = editService.updateStep(player, target,
                step -> step instanceof WaitStep current
                        ? new WaitStep(signal, current.timeoutTicks(), current.sway()) : null);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.signal-set",
                    "target", target.display(), "signal", EditFeedback.safeText(signal));
        }
    }

    private void setWaitTimeout(Player player, EditTarget target, String[] args, EditFeedback feedback) {
        if (args.length < 4) {
            feedback.usage(player, target.display(), "timeout <tick>");
            return;
        }
        Integer timeout = feedback.parseInt(player, args[3]);
        if (timeout == null) {
            return;
        }
        EditService.Result result = editService.updateStep(player, target,
                step -> step instanceof WaitStep current
                        ? new WaitStep(current.signal(), timeout, current.sway()) : null);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.timeout-set",
                    "target", target.display(), "ticks", String.valueOf(timeout));
        }
    }

    private void setEffectStep(Player player, EditTarget target, EffectStep step, String[] args,
                               EditFeedback feedback) {
        if (args.length < 4) {
            feedback.usage(player, target.display(), "effect <add|remove> <效果> [等级] [时长]");
            return;
        }
        EffectAction action;
        try {
            action = EffectAction.parse(args[3]);
        } catch (IllegalArgumentException exception) {
            feedback.invalidValue(player, args[3]);
            return;
        }
        if (args.length < 5) {
            feedback.usage(player, target.display(), "effect <add|remove> <效果> [等级] [时长]");
            return;
        }
        int amplifier = step.effect().amplifier();
        int duration = step.effect().durationTicks();
        if (args.length >= 6) {
            Integer parsed = feedback.optionalNonNegativeInt(player, args[5]);
            if (parsed == null) {
                return;
            }
            amplifier = parsed;
        }
        if (args.length >= 7) {
            Integer parsed = feedback.optionalNonNegativeInt(player, args[6]);
            if (parsed == null) {
                return;
            }
            duration = parsed;
        }
        EffectSpec spec = effectSpec(player, action, args[4], amplifier, duration);
        if (spec == null) {
            return;
        }
        EditService.Result result = editService.updateStep(player, target, current -> new EffectStep(spec));
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.effect-step-set",
                    "target", target.display(), "summary", spec.describe());
        }
    }

    private void setTitleText(Player player, EditTarget target, String[] args, boolean main,
                              EditFeedback feedback) {
        String text = args.length >= 4
                ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim() : "";
        EditService.Result result = editService.updateStep(player, target, step -> step instanceof TitleStep current
                ? (main
                ? new TitleStep(text, current.subtitle(), current.fadeInTicks(), current.stayTicks(),
                        current.fadeOutTicks())
                : new TitleStep(current.title(), text, current.fadeInTicks(), current.stayTicks(),
                        current.fadeOutTicks()))
                : null);
        if (feedback.applied(player, result)) {
            messages.get().send(player, main ? "editor.title-text-set" : "editor.subtitle-set",
                    "target", target.display(), "text", text.isEmpty() ? "(空)" : EditFeedback.safeText(text));
        }
    }

    private void setTitleFade(Player player, EditTarget target, String[] args, EditFeedback feedback) {
        if (args.length < 6) {
            feedback.usage(player, target.display(), "fade <淡入> <停留> <淡出>");
            return;
        }
        Integer fadeIn = feedback.optionalNonNegativeInt(player, args[3]);
        Integer stay = feedback.optionalNonNegativeInt(player, args[4]);
        Integer fadeOut = feedback.optionalNonNegativeInt(player, args[5]);
        if (fadeIn == null || stay == null || fadeOut == null) {
            return;
        }
        EditService.Result result = editService.updateStep(player, target,
                step -> step instanceof TitleStep current
                        ? new TitleStep(current.title(), current.subtitle(), fadeIn, stay, fadeOut) : null);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.fade-set",
                    "target", target.display(),
                    "fade_in", String.valueOf(fadeIn),
                    "stay", String.valueOf(stay),
                    "fade_out", String.valueOf(fadeOut));
        }
    }

    private void pathStepPoints(Player player, EditTarget target, String[] args, EditFeedback feedback) {
        String sub = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "list" -> {
                CameraTrack track = editService.view(target.trackId());
                if (track == null) {
                    messages.get().send(player, "track.not-found", "track", target.trackId());
                    return;
                }
                feedback.listPathPoints(player, track, target);
            }
            case "add" -> {
                EditService.Result result = editService.appendPathPoint(player, target);
                if (feedback.applied(player, result)) {
                    feedback.announcePathPoint(player, target.display(), result.track(), target.stepIndex());
                }
            }
            default -> feedback.usage(player, target.display(), "point <list|add>");
        }
    }

    // ------------------------------------------------------------------ structural step actions

    private void removeStep(Player player, EditTarget target, String label, EditFeedback feedback) {
        EditService.Result result = editService.removeStep(player, target);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.step-removed",
                    "track", target.trackId(),
                    "index", String.valueOf(target.stepIndex() + 1),
                    "label", label);
        }
    }

    private void duplicateStep(Player player, EditTarget target, EditFeedback feedback) {
        EditService.Result result = editService.duplicateStep(player, target);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.step-duplicated",
                    "track", target.trackId(),
                    "index", String.valueOf(target.stepIndex() + 1),
                    "copy", String.valueOf(result.stepIndex() + 1));
        }
    }

    private void moveStep(Player player, EditTarget target, int delta, EditFeedback feedback) {
        EditService.Result result = editService.moveStep(player, target, delta);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.step-moved",
                    "track", target.trackId(),
                    "from", String.valueOf(target.stepIndex() + 1),
                    "to", String.valueOf(result.stepIndex() + 1));
        }
    }

    private void preview(Player player, EditTarget target, EditFeedback feedback) {
        // Preview is a toggle, exactly like the Dialog editor's preview button.
        if (editService.isPreviewing(player)) {
            stopPreview(player, feedback);
            return;
        }
        EditService.Result result = editService.preview(player, target);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.preview-started", "track", target.trackId());
        }
    }

    private void stopPreview(Player player, EditFeedback feedback) {
        EditService.Result result = editService.stopPreview(player);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.preview-stopped");
        }
    }

    private void undo(Player player, String trackId, EditFeedback feedback) {
        EditService.Result result = editService.undo(player, trackId);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.undone");
        }
    }

    private void save(Player player, String trackId, EditFeedback feedback) {
        EditService.Result result = editService.save(player, trackId);
        if (feedback.applied(player, result)) {
            messages.get().send(player, "editor.saved", "track", trackId);
        }
    }

    // ------------------------------------------------------------------ point level

    private void pointAction(Player player, EditTarget target, String action, String[] args,
                             EditFeedback feedback) {
        CameraTrack track = repository.get(target.trackId());
        if (track == null) {
            messages.get().send(player, "track.not-found", "track", target.trackId());
            return;
        }
        PathStep path = StepFields.pathStep(track, target.stepIndex());
        if (path == null || target.pointIndex() >= path.points().size()) {
            messages.get().send(player, "editor.point-missing", "target", target.display());
            return;
        }
        switch (action) {
            case "" -> feedback.statusPoint(player, target, path.points().get(target.pointIndex()));
            case "here" -> {
                EditService.Result result = editService.capturePose(player, target);
                if (feedback.applied(player, result)) {
                    messages.get().send(player, "editor.here-updated", "target", target.display());
                }
            }
            case "pos" -> {
                if (args.length < 6) {
                    feedback.usage(player, target.display(), "pos <x> <y> <z> [yaw] [pitch]");
                    return;
                }
                Pose pose = feedback.parsePose(player, args, 3,
                        path.points().get(target.pointIndex()), target,
                        "pos <x> <y> <z> [yaw] [pitch]");
                if (pose == null) {
                    return;
                }
                EditService.Result result = editService.setPathPointPose(player, target, pose);
                if (feedback.applied(player, result)) {
                    messages.get().send(player, "editor.pose-set",
                            "target", target.display(), "pose", feedback.poseShort(pose));
                }
            }
            case "remove" -> {
                EditService.Result result = editService.removePathPoint(player, target);
                if (feedback.applied(player, result)) {
                    messages.get().send(player, "editor.path-point-removed",
                            "target", target.display(),
                            "count", String.valueOf(StepFields.pathCount(result.track(), target.stepIndex())));
                }
            }
            case "up" -> movePoint(player, target, -1, feedback);
            case "down" -> movePoint(player, target, 1, feedback);
            case "insert" -> {
                EditService.Result result = editService.insertPathPoint(player, target);
                if (feedback.applied(player, result)) {
                    messages.get().send(player, "editor.path-point-inserted",
                            "target", target.display(),
                            "count", String.valueOf(StepFields.pathCount(result.track(), target.stepIndex())));
                }
            }
            case "stop" -> stopPreview(player, feedback);
            default -> feedback.wrongAction(player, target, action, StepFields.POINT_ACTIONS);
        }
    }

    private void movePoint(Player player, EditTarget target, int delta, EditFeedback feedback) {
        EditService.Result result = editService.movePathPoint(player, target, delta);
        if (feedback.applied(player, result)) {
            int to = target.pointIndex() + 1 + delta;
            messages.get().send(player, "editor.path-point-moved",
                    "target", target.trackId() + ":" + (target.stepIndex() + 1) + ":" + to);
        }
    }

    // ------------------------------------------------------------------ parsing

    private Player target(CommandSender sender, String name) {
        if (name == null) {
            if (sender instanceof Player player) {
                return player;
            }
            messages.get().send(sender, "command.player-only");
            return null;
        }
        Player player = Bukkit.getOnlinePlayers().stream()
                .filter(online -> online.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
        if (player == null) {
            messages.get().send(sender, "command.target-not-found", "player", name);
        }
        return player;
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        messages.get().send(sender, "command.no-permission");
        return false;
    }

    /** Whether the token is the optional {@code add|remove} action of an effect command. */
    private static boolean isEffectAction(String raw) {
        try {
            EffectAction.parse(raw);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Whether the token was typed as a number (used to tell the two {@code add title} forms apart). */
    private static boolean isInteger(String raw) {
        try {
            Integer.parseInt(raw);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private EffectAction parseEffectAction(EditFeedback feedback, CommandSender sender, String raw) {
        try {
            return EffectAction.parse(raw);
        } catch (IllegalArgumentException exception) {
            feedback.invalidValue(sender, raw);
            return null;
        }
    }

    private EffectSpec effectSpec(Player player, EffectAction action, String key, int amplifier, int duration) {
        PotionEffectType type = CameraEffects.resolveEffect(key);
        if (type == null) {
            messages.get().send(player, "editor.invalid-effect", "effect", EditFeedback.safeText(key));
            return null;
        }
        if (action == EffectAction.ADD) {
            amplifier = Math.max(0, Math.min(255, amplifier));
            duration = Math.max(0, Math.min(config.get().maxTrackTicks(), duration));
        } else {
            amplifier = 0;
            duration = 0;
        }
        return new EffectSpec(type, type.getKey().toString(), action, amplifier, duration);
    }

    // ------------------------------------------------------------------ completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return completions.complete(sender, args);
    }
}
