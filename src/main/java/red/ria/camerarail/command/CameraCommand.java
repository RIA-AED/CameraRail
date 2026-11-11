package red.ria.camerarail.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import red.ria.camerarail.config.RailConfig;
import red.ria.camerarail.config.TrackRepository;
import red.ria.camerarail.editor.EditService;
import red.ria.camerarail.editor.EditTarget;
import red.ria.camerarail.editor.EditorManager;
import red.ria.camerarail.editor.EditorTools;
import red.ria.camerarail.editor.EditorUI;
import red.ria.camerarail.message.ChatText;
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
    private static final List<String> EASING_NAMES = List.of("linear", "ease-in", "ease-out",
            "ease-in-out", "smoothstep", "smootherstep", "sine");
    private static final List<String> STEP_SWAY_NAMES = List.of("inherit", "off", "gentle", "natural", "strong");
    private static final List<String> TRACK_SWAY_NAMES = List.of("off", "gentle", "natural", "strong");
    private static final List<String> EFFECT_NAMES = List.of("blindness", "darkness", "slowness",
            "night_vision", "nausea", "speed", "invisibility", "glowing");
    private static final List<String> EXIT_MODE_NAMES = List.of("animation-end", "pre-entry", "custom");
    private static final List<String> ADD_TYPES = List.of("move", "path", "hold", "wait", "cut", "effect", "title");
    private static final List<String> STEP_STRUCTURE_ACTIONS =
            List.of("remove", "duplicate", "up", "down", "preview", "stop");
    private static final List<String> TRACK_ACTIONS = List.of("display", "description", "start", "exit",
            "exit-mode", "sway", "follow", "entry", "list", "add", "preview", "stop", "undo", "save");
    private static final List<String> ENTRY_ACTIONS = List.of("list", "add", "set", "remove");
    private static final List<String> POINT_ACTIONS =
            List.of("here", "pos", "remove", "up", "down", "insert", "stop");
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final CameraEngine engine;
    private final TrackRepository repository;
    private final EditorManager editors;
    private final EditorUI editorUi;
    private final EditService editService;
    private final EditorTools editorTools;
    private final Supplier<RailConfig> config;
    private final Supplier<Messages> messages;
    private final IntSupplier reloadAction;

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
        sendTrackInfo(sender, track, label);
    }

    /** The track fields shared by the read-only track view and the editing status view. */
    private void sendTrackFields(CommandSender sender, CameraTrack track) {
        sender.sendMessage(messages.get().prefixed("track.info-header",
                "id", track.id(), "name", track.displayName()));
        infoLine(sender, "世界", track.worldName());
        infoLine(sender, "步骤", String.valueOf(track.steps().size()));
        infoLine(sender, "时长", track.timedDurationTicks() + " tick ("
                + String.format(Locale.ROOT, "%.1f", track.timedDurationTicks() / 20.0) + "s)");
        infoLine(sender, "摇摆", "周期 " + track.sway().period() + "t / x " + track.sway().x()
                + " / yaw " + track.sway().yaw());
        infoLine(sender, "跟随区块", track.follow() ? "开启" : "关闭");
        infoLine(sender, "镜头模式", engine.fakeCameraAvailable()
                ? "假玩家 (PacketEvents)" : "盔甲架回退");
        infoLine(sender, "入场效果", entryEffectsLabel(track));
        infoLine(sender, "等待信号", track.waitSignals().isEmpty() ? "无"
                : String.join(", ", track.waitSignals()));
        if (!track.description().isBlank()) {
            infoLine(sender, "说明", track.description());
        }
    }

    /** {@code /camerarail info <id>}: the read-only view, including the play-preview link. */
    private void sendTrackInfo(CommandSender sender, CameraTrack track, String label) {
        sendTrackFields(sender, track);
        sender.sendMessage(messages.get().prefixed("track.info-preview", "id", track.id(), "label", label));
        if (track.steps().isEmpty()) {
            messages.get().send(sender, "track.info-no-steps");
        }
    }

    /** {@code edit <轨道>} status: the editing view, never pointing at real playback. */
    private void statusTrack(Player player, CameraTrack track, String label) {
        sendTrackFields(player, track);
        if (track.steps().isEmpty()) {
            messages.get().send(player, "track.info-no-steps");
        }
        sendActions(player, label, track.id(), messages.get().component("editor.track-hint-head"),
                List.of("list", "add", "preview", "stop", "undo", "save"));
        sendActions(player, label, track.id(), messages.get().component("editor.track-settings-head"),
                List.of("display", "description", "start", "exit", "exit-mode", "sway", "follow", "entry"));
    }

    private void infoLine(CommandSender sender, String label, String value) {
        messages.get().send(sender, "track.info-line", "label", label, "value", value);
    }

    private static String entryEffectsLabel(CameraTrack track) {
        if (track.entryEffects().isEmpty()) {
            return "无";
        }
        List<String> parts = new ArrayList<>();
        for (EffectSpec effect : track.entryEffects()) {
            parts.add(effect.describe());
        }
        return String.join("、", parts);
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
        if (args.length == 1) {
            // Read-only reference, so the console may list the actions too.
            new CommandHelp(messages.get(), label, sender::hasPermission)
                    .sendOverview(sender, CommandHelp.Scope.EDITOR);
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
            pointAction(player, target, action, args, label);
        } else if (target.hasStep()) {
            stepAction(player, target, action, args, label);
        } else {
            trackAction(player, target, action, args, label);
        }
    }

    // ------------------------------------------------------------------ track level

    private void trackAction(Player player, EditTarget target, String action, String[] args, String label) {
        CameraTrack track = repository.get(target.trackId());
        if (track == null) {
            messages.get().send(player, "track.not-found", "track", target.trackId());
            return;
        }
        switch (action) {
            case "" -> statusTrack(player, track, label);
            case "display" -> setDisplay(player, track, args, label);
            case "description" -> setDescription(player, track, args, label);
            case "start" -> setStart(player, track, target, args, label);
            case "exit" -> setExit(player, track, target, args, label);
            case "exit-mode" -> setExitMode(player, track, target, args, label);
            case "sway" -> setTrackSway(player, track, target, args, label);
            case "follow" -> setFollow(player, track, target, args, label);
            case "entry" -> entryAction(player, track, target, args, label);
            case "list" -> listSteps(player, track.id(), label);
            case "add" -> addStep(player, track, target, args, label);
            case "preview" -> preview(player, target, label);
            case "stop" -> stopPreview(player);
            case "undo" -> undo(player, track.id());
            case "save" -> save(player, track.id());
            default -> wrongAction(player, target, action, TRACK_ACTIONS, label);
        }
    }

    private void setDisplay(Player player, CameraTrack track, String[] args, String label) {
        if (args.length < 4) {
            usage(player, label, track.id(), "display <名称>");
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
        if (name.isEmpty()) {
            usage(player, label, track.id(), "display <名称>");
            return;
        }
        if (name.length() > 64) {
            messages.get().send(player, "editor.display-too-long");
            return;
        }
        EditService.Result result = editService.updateTrack(player, track.id(), t -> t.withDisplayName(name));
        if (applied(player, result)) {
            messages.get().send(player, "editor.display-set", "track", track.id(), "name", name);
        }
    }

    private void setDescription(Player player, CameraTrack track, String[] args, String label) {
        String description = args.length >= 4
                ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim() : "";
        if (description.length() > 512) {
            messages.get().send(player, "editor.description-too-long");
            return;
        }
        EditService.Result result = editService.updateTrack(player, track.id(), t -> t.withDescription(description));
        if (applied(player, result)) {
            messages.get().send(player, "editor.description-set", "track", track.id(),
                    "description", description.isEmpty() ? "(空)" : safeText(description));
        }
    }

    private void setStart(Player player, CameraTrack track, EditTarget target, String[] args, String label) {
        if (args.length == 3) {
            EditService.Result result = editService.captureStart(player, track.id());
            if (applied(player, result)) {
                messages.get().send(player, "editor.start-set", "track", track.id());
            }
            return;
        }
        Pose pose = parsePose(player, args, 3,
                new Pose(0, 0, 0, player.getYaw(), player.getPitch()), label, target, "start <x> <y> <z> [yaw] [pitch]");
        if (pose == null) {
            return;
        }
        EditService.Result result = editService.setStartPose(player, track.id(), pose);
        if (applied(player, result)) {
            messages.get().send(player, "editor.start-pose-set", "track", track.id(), "pose", poseShort(pose));
        }
    }

    private void setExit(Player player, CameraTrack track, EditTarget target, String[] args, String label) {
        if (args.length == 3) {
            EditService.Result result = editService.captureExit(player, track.id());
            if (applied(player, result)) {
                messages.get().send(player, "editor.exit-set", "track", track.id());
            }
            return;
        }
        Pose pose = parsePose(player, args, 3,
                new Pose(0, 0, 0, player.getYaw(), player.getPitch()), label, target, "exit <x> <y> <z> [yaw] [pitch]");
        if (pose == null) {
            return;
        }
        EditService.Result result = editService.setExitPose(player, track.id(), pose);
        if (applied(player, result)) {
            messages.get().send(player, "editor.exit-pose-set", "track", track.id(), "pose", poseShort(pose));
        }
    }

    private void setExitMode(Player player, CameraTrack track, EditTarget target, String[] args, String label) {
        if (args.length < 4) {
            usage(player, label, target.display(), "exit-mode <animation-end|pre-entry|custom>");
            return;
        }
        ExitMode mode;
        try {
            mode = ExitMode.parse(args[3]);
        } catch (IllegalArgumentException exception) {
            messages.get().send(player, "editor.invalid-value", "value", args[3]);
            return;
        }
        if (mode == ExitMode.CUSTOM && track.exit() == null) {
            messages.get().send(player, "editor.exit-mode-custom",
                    "label", label, "track", track.id());
            return;
        }
        EditService.Result result = editService.updateTrack(player, track.id(), t -> t.withExitMode(mode));
        if (applied(player, result)) {
            messages.get().send(player, "editor.exit-mode-set", "track", track.id(), "mode", exitModeLabel(mode));
        }
    }

    private void setTrackSway(Player player, CameraTrack track, EditTarget target, String[] args, String label) {
        if (args.length < 4) {
            usage(player, label, target.display(), "sway <off|gentle|natural|strong> [周期]");
            return;
        }
        SwayPreset preset;
        try {
            preset = SwayPreset.parse(args[3]);
        } catch (IllegalArgumentException exception) {
            messages.get().send(player, "editor.invalid-value", "value", args[3]);
            return;
        }
        if (preset == SwayPreset.INHERIT) {
            messages.get().send(player, "editor.invalid-value", "value", args[3]);
            return;
        }
        int period = track.sway().period();
        if (args.length >= 5) {
            Integer parsed = parseInt(player, args[4]);
            if (parsed == null) {
                return;
            }
            period = parsed;
        }
        int safePeriod = Math.max(1, Math.min(config.get().maxTrackTicks(), period));
        SwaySettings sway = SwaySettings.ofPreset(preset, safePeriod, track.sway().seed());
        EditService.Result result = editService.updateTrack(player, track.id(), t -> t.withSway(sway));
        if (applied(player, result)) {
            messages.get().send(player, "editor.sway-set", "track", track.id(),
                    "sway", swayName(preset), "period", String.valueOf(safePeriod));
        }
    }

    private void setFollow(Player player, CameraTrack track, EditTarget target, String[] args, String label) {
        if (args.length < 4) {
            usage(player, label, target.display(), "follow <true|false>");
            return;
        }
        Boolean follow = parseBoolean(args[3]);
        if (follow == null) {
            messages.get().send(player, "editor.invalid-value", "value", args[3]);
            return;
        }
        EditService.Result result = editService.updateTrack(player, track.id(), t -> t.withFollow(follow));
        if (applied(player, result)) {
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

    private void entryAction(Player player, CameraTrack track, EditTarget target, String[] args, String label) {
        String sub = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "" -> usage(player, label, target.display(), "entry <list|add|set|remove> ...");
            case "list" -> statusEntryEffects(player, track);
            case "add" -> {
                if (args.length < 5) {
                    usage(player, label, target.display(), "entry add <效果> [等级] [时长]");
                    return;
                }
                int amplifier = 0;
                int duration = 0;
                if (args.length >= 6) {
                    Integer parsed = optionalNonNegativeInt(player, args[5]);
                    if (parsed == null) {
                        return;
                    }
                    amplifier = parsed;
                }
                if (args.length >= 7) {
                    Integer parsed = optionalNonNegativeInt(player, args[6]);
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
                if (applied(player, result)) {
                    messages.get().send(player, "editor.entry-added", "effect", spec.describe());
                }
            }
            case "set" -> {
                if (args.length < 6) {
                    usage(player, label, target.display(), "entry set <序号> <效果> [等级] [时长]");
                    return;
                }
                Integer index = positiveIndex(player, args[4]);
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
                    Integer parsed = optionalNonNegativeInt(player, args[6]);
                    if (parsed == null) {
                        return;
                    }
                    amplifier = parsed;
                }
                if (args.length >= 8) {
                    Integer parsed = optionalNonNegativeInt(player, args[7]);
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
                if (applied(player, result)) {
                    messages.get().send(player, "editor.entry-set",
                            "index", String.valueOf(index), "effect", spec.describe());
                }
            }
            case "remove" -> {
                if (args.length < 5) {
                    usage(player, label, target.display(), "entry remove <序号>");
                    return;
                }
                Integer index = positiveIndex(player, args[4]);
                if (index == null) {
                    return;
                }
                EditService.Result result = editService.removeEntryEffect(player, track.id(), index - 1);
                if (applied(player, result)) {
                    messages.get().send(player, "editor.entry-removed", "index", String.valueOf(index));
                }
            }
            default -> usage(player, label, target.display(), "entry <list|add|set|remove> ...");
        }
    }

    private void statusEntryEffects(Player player, CameraTrack track) {
        messages.get().send(player, "editor.entry-list-header",
                "count", String.valueOf(track.entryEffects().size()));
        if (track.entryEffects().isEmpty()) {
            messages.get().send(player, "editor.entry-empty");
            return;
        }
        for (int i = 0; i < track.entryEffects().size(); i++) {
            messages.get().send(player, "editor.entry-list-entry",
                    "index", String.valueOf(i + 1), "effect", track.entryEffects().get(i).describe());
        }
    }

    // ------------------------------------------------------------------ steps: add

    private void addStep(Player player, CameraTrack track, EditTarget target, String[] args, String label) {
        if (args.length < 4) {
            usage(player, label, target.display(), "add <move|path|hold|wait|cut|effect|title> [参数]");
            return;
        }
        String type = args[3].toLowerCase(Locale.ROOT);
        EditService.Result result;
        try {
            result = switch (type) {
                case "move" -> {
                    Integer duration = optionalDuration(player, args, 4, config.get().defaultMoveDuration(), 1);
                    yield duration == null ? null
                            : editService.addMoveStep(player, target, duration);
                }
                case "path" -> {
                    Integer duration = optionalDuration(player, args, 4, config.get().defaultMoveDuration(), 1);
                    yield duration == null ? null
                            : editService.addPathStep(player, target, duration);
                }
                case "hold" -> {
                    Integer duration = optionalDuration(player, args, 4, config.get().defaultMoveDuration(), 1);
                    yield duration == null ? null
                            : editService.addStep(player, target,
                                    new HoldStep(duration, SwayPreset.INHERIT));
                }
                case "cut" -> {
                    Integer duration = optionalDuration(player, args, 4, 0, 0);
                    yield duration == null ? null
                            : editService.addCutStep(player, target, duration);
                }
                case "wait" -> {
                    // Omitted signals get a track-unique generated name instead of a literal "signal".
                    String signal = args.length >= 5 ? args[4] : nextWaitSignal(track);
                    Integer timeout = optionalDuration(player, args, 5,
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
                        action = parseEffectAction(player, args[4]);
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
                        Integer parsed = optionalNonNegativeInt(player, args[first]);
                        if (parsed == null) {
                            yield null;
                        }
                        amplifier = parsed;
                    }
                    if (args.length > first + 1) {
                        Integer parsed = optionalNonNegativeInt(player, args[first + 1]);
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
                        Integer parsedFadeIn = optionalInt(player, args, 4, 20);
                        Integer parsedStay = optionalInt(player, args, 5, 60);
                        Integer parsedFadeOut = optionalInt(player, args, 6, 20);
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
                    usage(player, label, target.display(), "add <move|path|hold|wait|cut|effect|title> [参数]");
                    yield null;
                }
            };
        } catch (IllegalArgumentException exception) {
            messages.get().send(player, "editor.edit-failed", "reason", safeText(exception.getMessage()));
            return;
        }
        if (result == null) {
            return;
        }
        announceAdded(player, track.id(), label, result);
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

    private void announceAdded(Player player, String trackId, String label, EditService.Result result) {
        if (!applied(player, result)) {
            return;
        }
        int index = result.stepIndex();
        CameraTrack track = result.track();
        if (track == null || index <= 0 || index > track.steps().size()) {
            return;
        }
        Step step = track.steps().get(index - 1);
        messages.get().send(player, "editor.appended-step",
                "track", trackId,
                "index", String.valueOf(index),
                "summary", summary(step));
        messages.get().send(player, "editor.added-hint",
                "label", label,
                "target", trackId + ":" + index,
                "type", stepTypeName(step));
        if (step instanceof PathStep) {
            messages.get().send(player, "editor.path-created",
                    "track", trackId,
                    "index", String.valueOf(index),
                    "label", label);
        }
    }

    // ------------------------------------------------------------------ step level

    private void stepAction(Player player, EditTarget target, String action, String[] args, String label) {
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
            case "" -> statusStep(player, target, step, label);
            case "remove" -> removeStep(player, target, label);
            case "duplicate" -> duplicateStep(player, target);
            case "up" -> moveStep(player, target, -1);
            case "down" -> moveStep(player, target, 1);
            case "preview" -> preview(player, target, label);
            case "stop" -> stopPreview(player);
            default -> typeStepAction(player, track, target, step, action, args, label);
        }
    }

    private void statusStep(Player player, EditTarget target, Step step, String label) {
        messages.get().send(player, "editor.step-status",
                "track", target.trackId(),
                "index", String.valueOf(target.stepIndex() + 1),
                "type", stepTypeName(step),
                "summary", summary(step));
        sendActions(player, label, target.display(),
                messages.get().component("editor.actions-hint"), stepTypeActions(step));
        if (step instanceof PathStep path) {
            messages.get().send(player, "editor.path-point-count",
                    "count", String.valueOf(path.points().size()));
        }
    }

    private void typeStepAction(Player player, CameraTrack track, EditTarget target, Step step,
                                String action, String[] args, String label) {
        switch (step) {
            case MoveStep move -> {
                switch (action) {
                    case "here" -> captureStepPose(player, target, label);
                    case "pos" -> setStepPose(player, target, step, args, label);
                    case "duration" -> setDuration(player, target, args, label);
                    case "easing" -> setEasing(player, target, args, label);
                    case "sway" -> setStepSway(player, target, step, args, label);
                    default -> wrongAction(player, target, action, stepTypeActions(step), label);
                }
            }
            case PathStep path -> {
                switch (action) {
                    case "duration" -> setDuration(player, target, args, label);
                    case "easing" -> setEasing(player, target, args, label);
                    case "sway" -> setStepSway(player, target, step, args, label);
                    case "point" -> pathStepPoints(player, target, args, label);
                    default -> wrongAction(player, target, action, stepTypeActions(step), label);
                }
            }
            case HoldStep hold -> {
                switch (action) {
                    case "duration" -> setDuration(player, target, args, label);
                    case "sway" -> setStepSway(player, target, step, args, label);
                    default -> wrongAction(player, target, action, stepTypeActions(step), label);
                }
            }
            case WaitStep wait -> {
                switch (action) {
                    case "signal" -> setWaitSignal(player, target, wait, args, label);
                    case "timeout" -> setWaitTimeout(player, target, wait, args, label);
                    case "sway" -> setStepSway(player, target, step, args, label);
                    default -> wrongAction(player, target, action, stepTypeActions(step), label);
                }
            }
            case CutStep cut -> {
                switch (action) {
                    case "here" -> captureStepPose(player, target, label);
                    case "pos" -> setStepPose(player, target, step, args, label);
                    case "duration" -> setDuration(player, target, args, label);
                    case "sway" -> setStepSway(player, target, step, args, label);
                    default -> wrongAction(player, target, action, stepTypeActions(step), label);
                }
            }
            case EffectStep effect -> {
                if ("effect".equals(action)) {
                    setEffectStep(player, target, effect, args, label);
                } else {
                    wrongAction(player, target, action, stepTypeActions(step), label);
                }
            }
            case TitleStep title -> {
                switch (action) {
                    case "title" -> setTitleText(player, target, args, true, label);
                    case "subtitle" -> setTitleText(player, target, args, false, label);
                    case "fade" -> setTitleFade(player, target, title, args, label);
                    default -> wrongAction(player, target, action, stepTypeActions(step), label);
                }
            }
        }
    }

    private void captureStepPose(Player player, EditTarget target, String label) {
        EditService.Result result = editService.capturePose(player, target);
        if (applied(player, result)) {
            messages.get().send(player, "editor.here-updated", "target", target.display());
        }
    }

    private void setStepPose(Player player, EditTarget target, Step step, String[] args, String label) {
        if (args.length < 5) {
            usage(player, label, target.display(), "pos <x> <y> <z> [yaw] [pitch]");
            return;
        }
        Pose current = stepTargetPose(step);
        Pose pose = parsePose(player, args, 3, current, label, target, "pos <x> <y> <z> [yaw] [pitch]");
        if (pose == null) {
            return;
        }
        EditService.Result result = editService.setPose(player, target, pose);
        if (applied(player, result)) {
            messages.get().send(player, "editor.pose-set",
                    "target", target.display(), "pose", poseShort(pose));
        }
    }

    private static Pose stepTargetPose(Step step) {
        return switch (step) {
            case MoveStep move -> move.to();
            case CutStep cut -> cut.to();
            default -> new Pose(0, 0, 0, 0, 0);
        };
    }

    private void setDuration(Player player, EditTarget target, String[] args, String label) {
        if (args.length < 4) {
            usage(player, label, target.display(), "duration <tick>");
            return;
        }
        Integer ticks = parseInt(player, args[3]);
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
        if (applied(player, result)) {
            messages.get().send(player, "editor.duration-set",
                    "target", target.display(), "ticks", String.valueOf(ticks));
        }
    }

    private void setEasing(Player player, EditTarget target, String[] args, String label) {
        if (args.length < 4) {
            usage(player, label, target.display(), "easing <曲线>");
            return;
        }
        Easing easing;
        try {
            easing = Easing.parse(args[3]);
        } catch (IllegalArgumentException exception) {
            messages.get().send(player, "editor.invalid-value", "value", args[3]);
            return;
        }
        EditService.Result result = editService.updateStep(player, target, step -> switch (step) {
            case MoveStep move -> new MoveStep(move.to(), move.durationTicks(), easing, move.sway());
            case PathStep path -> new PathStep(path.points(), path.durationTicks(), easing, path.sway());
            default -> null;
        });
        if (applied(player, result)) {
            messages.get().send(player, "editor.easing-set",
                    "target", target.display(), "easing", easingName(easing));
        }
    }

    private void setStepSway(Player player, EditTarget target, Step step, String[] args, String label) {
        if (args.length < 4) {
            usage(player, label, target.display(), "sway <inherit|off|gentle|natural|strong>");
            return;
        }
        SwayPreset sway;
        try {
            sway = SwayPreset.parse(args[3]);
        } catch (IllegalArgumentException exception) {
            messages.get().send(player, "editor.invalid-value", "value", args[3]);
            return;
        }
        SwayPreset preset = sway;
        EditService.Result result = editService.updateStep(player, target, current -> switch (current) {
            case MoveStep move -> new MoveStep(move.to(), move.durationTicks(), move.easing(), preset);
            case PathStep path -> new PathStep(path.points(), path.durationTicks(), path.easing(), preset);
            case HoldStep hold -> new HoldStep(hold.durationTicks(), preset);
            case WaitStep wait -> new WaitStep(wait.signal(), wait.timeoutTicks(), preset);
            case CutStep cut -> new CutStep(cut.to(), cut.durationTicks(), preset);
            default -> null;
        });
        if (applied(player, result)) {
            messages.get().send(player, "editor.step-sway-set",
                    "target", target.display(), "sway", swayName(preset));
        }
    }

    private void setWaitSignal(Player player, EditTarget target, WaitStep wait, String[] args, String label) {
        if (args.length < 4) {
            usage(player, label, target.display(), "signal <信号>");
            return;
        }
        String signal = args[3];
        EditService.Result result = editService.updateStep(player, target,
                step -> step instanceof WaitStep current
                        ? new WaitStep(signal, current.timeoutTicks(), current.sway()) : null);
        if (applied(player, result)) {
            messages.get().send(player, "editor.signal-set",
                    "target", target.display(), "signal", safeText(signal));
        }
    }

    private void setWaitTimeout(Player player, EditTarget target, WaitStep wait, String[] args, String label) {
        if (args.length < 4) {
            usage(player, label, target.display(), "timeout <tick>");
            return;
        }
        Integer timeout = parseInt(player, args[3]);
        if (timeout == null) {
            return;
        }
        EditService.Result result = editService.updateStep(player, target,
                step -> step instanceof WaitStep current
                        ? new WaitStep(current.signal(), timeout, current.sway()) : null);
        if (applied(player, result)) {
            messages.get().send(player, "editor.timeout-set",
                    "target", target.display(), "ticks", String.valueOf(timeout));
        }
    }

    private void setEffectStep(Player player, EditTarget target, EffectStep step, String[] args, String label) {
        if (args.length < 4) {
            usage(player, label, target.display(), "effect <add|remove> <效果> [等级] [时长]");
            return;
        }
        EffectAction action;
        try {
            action = EffectAction.parse(args[3]);
        } catch (IllegalArgumentException exception) {
            messages.get().send(player, "editor.invalid-value", "value", args[3]);
            return;
        }
        if (args.length < 5) {
            usage(player, label, target.display(), "effect <add|remove> <效果> [等级] [时长]");
            return;
        }
        int amplifier = step.effect().amplifier();
        int duration = step.effect().durationTicks();
        if (args.length >= 6) {
            Integer parsed = optionalNonNegativeInt(player, args[5]);
            if (parsed == null) {
                return;
            }
            amplifier = parsed;
        }
        if (args.length >= 7) {
            Integer parsed = optionalNonNegativeInt(player, args[6]);
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
        if (applied(player, result)) {
            messages.get().send(player, "editor.effect-step-set",
                    "target", target.display(), "summary", spec.describe());
        }
    }

    private void setTitleText(Player player, EditTarget target, String[] args, boolean main, String label) {
        String action = main ? "title" : "subtitle";
        String text = args.length >= 4
                ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim() : "";
        EditService.Result result = editService.updateStep(player, target, step -> step instanceof TitleStep current
                ? (main
                ? new TitleStep(text, current.subtitle(), current.fadeInTicks(), current.stayTicks(),
                        current.fadeOutTicks())
                : new TitleStep(current.title(), text, current.fadeInTicks(), current.stayTicks(),
                        current.fadeOutTicks()))
                : null);
        if (applied(player, result)) {
            messages.get().send(player, main ? "editor.title-text-set" : "editor.subtitle-set",
                    "target", target.display(), "text", text.isEmpty() ? "(空)" : safeText(text));
        }
    }

    private void setTitleFade(Player player, EditTarget target, TitleStep title, String[] args, String label) {
        if (args.length < 6) {
            usage(player, label, target.display(), "fade <淡入> <停留> <淡出>");
            return;
        }
        Integer fadeIn = optionalNonNegativeInt(player, args[3]);
        Integer stay = optionalNonNegativeInt(player, args[4]);
        Integer fadeOut = optionalNonNegativeInt(player, args[5]);
        if (fadeIn == null || stay == null || fadeOut == null) {
            return;
        }
        EditService.Result result = editService.updateStep(player, target,
                step -> step instanceof TitleStep current
                        ? new TitleStep(current.title(), current.subtitle(), fadeIn, stay, fadeOut) : null);
        if (applied(player, result)) {
            messages.get().send(player, "editor.fade-set",
                    "target", target.display(),
                    "fade_in", String.valueOf(fadeIn),
                    "stay", String.valueOf(stay),
                    "fade_out", String.valueOf(fadeOut));
        }
    }

    private void pathStepPoints(Player player, EditTarget target, String[] args, String label) {
        String sub = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "list" -> listPathPoints(player, target, label);
            case "add" -> {
                EditService.Result result = editService.appendPathPoint(player, target);
                announcePathPoint(player, target, result);
            }
            default -> usage(player, label, target.display(), "point <list|add>");
        }
    }

    // ------------------------------------------------------------------ structural step actions

    private void removeStep(Player player, EditTarget target, String label) {
        EditService.Result result = editService.removeStep(player, target);
        if (applied(player, result)) {
            messages.get().send(player, "editor.step-removed",
                    "track", target.trackId(),
                    "index", String.valueOf(target.stepIndex() + 1),
                    "label", label);
        }
    }

    private void duplicateStep(Player player, EditTarget target) {
        EditService.Result result = editService.duplicateStep(player, target);
        if (applied(player, result)) {
            messages.get().send(player, "editor.step-duplicated",
                    "track", target.trackId(),
                    "index", String.valueOf(target.stepIndex() + 1),
                    "copy", String.valueOf(result.stepIndex() + 1));
        }
    }

    private void moveStep(Player player, EditTarget target, int delta) {
        EditService.Result result = editService.moveStep(player, target, delta);
        if (applied(player, result)) {
            messages.get().send(player, "editor.step-moved",
                    "track", target.trackId(),
                    "from", String.valueOf(target.stepIndex() + 1),
                    "to", String.valueOf(result.stepIndex() + 1));
        }
    }

    private void preview(Player player, EditTarget target, String label) {
        // Preview is a toggle, exactly like the Dialog editor's preview button.
        if (editService.isPreviewing(player)) {
            stopPreview(player);
            return;
        }
        EditService.Result result = editService.preview(player, target);
        if (applied(player, result)) {
            messages.get().send(player, "editor.preview-started", "track", target.trackId());
        }
    }

    private void stopPreview(Player player) {
        EditService.Result result = editService.stopPreview(player);
        if (applied(player, result)) {
            messages.get().send(player, "editor.preview-stopped");
        }
    }

    private void undo(Player player, String trackId) {
        EditService.Result result = editService.undo(player, trackId);
        if (applied(player, result)) {
            messages.get().send(player, "editor.undone");
        }
    }

    private void save(Player player, String trackId) {
        EditService.Result result = editService.save(player, trackId);
        if (applied(player, result)) {
            messages.get().send(player, "editor.saved", "track", trackId);
        }
    }

    // ------------------------------------------------------------------ point level

    private void pointAction(Player player, EditTarget target, String action, String[] args, String label) {
        CameraTrack track = repository.get(target.trackId());
        if (track == null) {
            messages.get().send(player, "track.not-found", "track", target.trackId());
            return;
        }
        PathStep path = pathStep(track, target.stepIndex());
        if (path == null || target.pointIndex() >= path.points().size()) {
            messages.get().send(player, "editor.point-missing", "target", target.display());
            return;
        }
        switch (action) {
            case "" -> statusPoint(player, target, path, label);
            case "here" -> {
                EditService.Result result = editService.capturePose(player, target);
                if (applied(player, result)) {
                    messages.get().send(player, "editor.here-updated", "target", target.display());
                }
            }
            case "pos" -> {
                if (args.length < 6) {
                    usage(player, label, target.display(), "pos <x> <y> <z> [yaw] [pitch]");
                    return;
                }
                Pose pose = parsePose(player, args, 3, path.points().get(target.pointIndex()),
                        label, target, "pos <x> <y> <z> [yaw] [pitch]");
                if (pose == null) {
                    return;
                }
                EditService.Result result = editService.setPathPointPose(player, target, pose);
                if (applied(player, result)) {
                    messages.get().send(player, "editor.pose-set",
                            "target", target.display(), "pose", poseShort(pose));
                }
            }
            case "remove" -> {
                EditService.Result result = editService.removePathPoint(player, target);
                if (applied(player, result)) {
                    messages.get().send(player, "editor.path-point-removed",
                            "target", target.display(),
                            "count", String.valueOf(pathCount(result.track(), target.stepIndex())));
                }
            }
            case "up" -> movePoint(player, target, -1);
            case "down" -> movePoint(player, target, 1);
            case "insert" -> {
                EditService.Result result = editService.insertPathPoint(player, target);
                if (applied(player, result)) {
                    messages.get().send(player, "editor.path-point-inserted",
                            "target", target.display(),
                            "count", String.valueOf(pathCount(result.track(), target.stepIndex())));
                }
            }
            case "stop" -> stopPreview(player);
            default -> wrongAction(player, target, action, POINT_ACTIONS, label);
        }
    }

    private void statusPoint(Player player, EditTarget target, PathStep path, String label) {
        Pose pose = path.points().get(target.pointIndex());
        messages.get().send(player, "editor.point-status",
                "target", target.display(),
                "x", fmt(pose.x()), "y", fmt(pose.y()), "z", fmt(pose.z()),
                "yaw", fmt(pose.yaw()), "pitch", fmt(pose.pitch()));
        sendActions(player, label, target.display(),
                messages.get().component("editor.actions-hint"), POINT_ACTIONS);
    }

    private void movePoint(Player player, EditTarget target, int delta) {
        EditService.Result result = editService.movePathPoint(player, target, delta);
        if (applied(player, result)) {
            int to = target.pointIndex() + 1 + delta;
            messages.get().send(player, "editor.path-point-moved",
                    "target", target.trackId() + ":" + (target.stepIndex() + 1) + ":" + to);
        }
    }

    private void announcePathPoint(Player player, EditTarget target, EditService.Result result) {
        if (!applied(player, result)) {
            return;
        }
        CameraTrack track = result.track();
        int count = pathCount(track, target.stepIndex());
        messages.get().send(player, "editor.path-point-added",
                "target", target.display(), "count", String.valueOf(count));
    }

    // ------------------------------------------------------------------ clickable actions

    private static final Component ACTION_SEPARATOR = Component.text(" · ", NamedTextColor.DARK_GRAY);

    /** Sends one or more click-to-fill action lines: head, wrapped action chips, help tail. */
    private void sendActions(Player player, String label, String target, Component head, List<String> actions) {
        Component tail = messages.get().component("editor.actions-hint-tail",
                "usage", "/" + label + " help edit <动作>");
        int maxWidth = ChatText.CHAT_WIDTH - ChatText.width(messages.get().prefixComponent()) - 4;
        for (Component line : actionLines(head, tail, label, target, actions, maxWidth)) {
            player.sendMessage(messages.get().prefixComponent().append(line));
        }
    }

    /**
     * Builds the clickable action lines shared by the step/point status, the track status and
     * {@code wrongAction}: every action is a click-to-fill chip and the lines wrap at
     * {@code maxWidth}. Package-private so the layout is unit-testable without a server.
     */
    static List<Component> actionLines(Component head, Component tail, String label, String target,
                                       List<String> actions, int maxWidth) {
        List<Component> lines = new ArrayList<>();
        Component current = head;
        int width = ChatText.width(head);
        boolean empty = true;
        for (String action : actions) {
            Component chip = actionChip(label, target, action);
            int separatorWidth = empty ? 0 : ChatText.width(ACTION_SEPARATOR);
            int chipWidth = ChatText.width(chip);
            if (!empty && width + separatorWidth + chipWidth > maxWidth) {
                lines.add(current);
                current = Component.empty();
                width = 0;
                empty = true;
                separatorWidth = 0;
            }
            if (!empty) {
                current = current.append(ACTION_SEPARATOR);
                width += separatorWidth;
            }
            current = current.append(chip);
            width += chipWidth;
            empty = false;
        }
        int tailWidth = ChatText.width(tail);
        if (!empty && width + tailWidth > maxWidth) {
            lines.add(current);
            current = tail;
        } else {
            current = current.append(tail);
        }
        lines.add(current);
        return lines;
    }

    private static Component actionChip(String label, String target, String action) {
        String command = "/" + label + " edit " + target + " " + action + " ";
        return Component.text(action, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(
                        Component.text("点击填入 " + command.trim(), NamedTextColor.GRAY)));
    }

    // ------------------------------------------------------------------ feedback

    /** Sends the failure or "no change" feedback and reports whether the change was written. */
    private boolean applied(Player player, EditService.Result result) {
        if (result.failed()) {
            messages.get().send(player, "editor.edit-failed", "reason", safeText(result.error()));
            return false;
        }
        if (!result.changed()) {
            messages.get().send(player, "editor.no-change");
            return false;
        }
        return true;
    }

    private void wrongAction(Player player, EditTarget target, String action,
                             List<String> actions, String label) {
        Component head = messages.get().component("editor.wrong-action-head",
                "target", target.display(), "action", safeText(action));
        sendActions(player, label, target.display(), head, actions);
    }

    private void usage(Player player, String label, String target, String syntax) {
        messages.get().send(player, "editor.action-usage",
                "label", label, "target", target, "syntax", syntax);
    }

    private void listSteps(Player player, String trackId, String label) {
        CameraTrack track = editService.view(trackId);
        if (track == null) {
            messages.get().send(player, "track.not-found", "track", trackId);
            return;
        }
        player.sendMessage(messages.get().prefixed("track.info-header",
                "id", track.id(), "name", track.displayName()));
        // The step list is an editing view: point at command editing, never at real playback.
        sendActions(player, label, track.id(), messages.get().component("editor.actions-hint"),
                List.of("add", "preview"));
        if (track.steps().isEmpty()) {
            messages.get().send(player, "track.info-no-steps");
            return;
        }
        for (int i = 0; i < track.steps().size(); i++) {
            int index = i;
            Step step = track.steps().get(i);
            String suggestion = "/" + label + " edit " + track.id() + ":" + (i + 1) + " ";
            Component line = Component.text("· ", NamedTextColor.DARK_GRAY)
                    .append(Component.text((i + 1) + " ", NamedTextColor.WHITE))
                    .append(Component.text(stepTypeName(step), NamedTextColor.WHITE))
                    .append(Component.text(" · ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(step.describe(), NamedTextColor.GRAY))
                    .clickEvent(ClickEvent.suggestCommand(suggestion))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            "点击填入步骤 " + (index + 1) + " 的编辑命令", NamedTextColor.GRAY)));
            player.sendMessage(line);
        }
    }

    private void listPathPoints(Player player, EditTarget target, String label) {
        CameraTrack track = editService.view(target.trackId());
        if (track == null) {
            messages.get().send(player, "track.not-found", "track", target.trackId());
            return;
        }
        PathStep path = pathStep(track, target.stepIndex());
        if (path == null) {
            messages.get().send(player, "editor.need-path");
            return;
        }
        messages.get().send(player, "editor.path-point-count", "count", String.valueOf(path.points().size()));
        for (int i = 0; i < path.points().size(); i++) {
            int index = i;
            String suggestion = "/" + label + " edit " + target.trackId() + ":"
                    + (target.stepIndex() + 1) + ":" + (i + 1) + " ";
            Component line = Component.text("· ", NamedTextColor.DARK_GRAY)
                    .append(Component.text((i + 1) + " ", NamedTextColor.WHITE))
                    .append(Component.text(poseShort(path.points().get(i)), NamedTextColor.GRAY))
                    .clickEvent(ClickEvent.suggestCommand(suggestion))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            "点击填入路径点 " + (index + 1) + " 的编辑命令", NamedTextColor.GRAY)));
            player.sendMessage(line);
        }
    }

    // ------------------------------------------------------------------ summaries

    /** Actions that depend on the concrete step type, followed by the shared structural actions. */
    static List<String> stepTypeActions(Step step) {
        List<String> own = switch (step) {
            case MoveStep ignored -> List.of("here", "pos", "duration", "easing", "sway");
            case PathStep ignored -> List.of("duration", "easing", "sway", "point");
            case HoldStep ignored -> List.of("duration", "sway");
            case WaitStep ignored -> List.of("signal", "timeout", "sway");
            case CutStep ignored -> List.of("here", "pos", "duration", "sway");
            case EffectStep ignored -> List.of("effect");
            case TitleStep ignored -> List.of("title", "subtitle", "fade");
        };
        List<String> actions = new ArrayList<>(own);
        actions.addAll(STEP_STRUCTURE_ACTIONS);
        return List.copyOf(actions);
    }

    static String stepTypeName(Step step) {
        return switch (step) {
            case MoveStep ignored -> "move";
            case PathStep ignored -> "path";
            case HoldStep ignored -> "hold";
            case WaitStep ignored -> "wait";
            case CutStep ignored -> "cut";
            case EffectStep ignored -> "effect";
            case TitleStep ignored -> "title";
        };
    }

    private String summary(Step step) {
        return switch (step) {
            case MoveStep move -> messageText("editor.step-summary-move",
                    "x", fmt(move.to().x()), "y", fmt(move.to().y()), "z", fmt(move.to().z()),
                    "yaw", fmt(move.to().yaw()), "pitch", fmt(move.to().pitch()),
                    "ticks", String.valueOf(move.durationTicks()),
                    "easing", easingName(move.easing()),
                    "sway", swayName(move.sway()));
            case PathStep path -> messageText("editor.step-summary-path",
                    "points", String.valueOf(path.points().size()),
                    "ticks", String.valueOf(path.durationTicks()),
                    "easing", easingName(path.easing()),
                    "sway", swayName(path.sway()));
            case HoldStep hold -> messageText("editor.step-summary-hold",
                    "ticks", String.valueOf(hold.durationTicks()),
                    "sway", swayName(hold.sway()));
            case WaitStep wait -> messageText("editor.step-summary-wait",
                    "signal", safeText(wait.signal()),
                    "timeout", wait.timeoutTicks() == 0 ? "永不" : wait.timeoutTicks() + "t",
                    "sway", swayName(wait.sway()));
            case CutStep cut -> messageText("editor.step-summary-cut",
                    "x", fmt(cut.to().x()), "y", fmt(cut.to().y()), "z", fmt(cut.to().z()),
                    "yaw", fmt(cut.to().yaw()), "pitch", fmt(cut.to().pitch()),
                    "ticks", String.valueOf(cut.durationTicks()),
                    "sway", swayName(cut.sway()));
            case EffectStep effect -> messageText("editor.step-summary-effect",
                    "effect", effect.effect().describe());
            case TitleStep title -> messageText("editor.step-summary-title",
                    "title", titleText(title.title()),
                    "subtitle", titleText(title.subtitle()),
                    "fade_in", String.valueOf(title.fadeInTicks()),
                    "stay", String.valueOf(title.stayTicks()),
                    "fade_out", String.valueOf(title.fadeOutTicks()));
        };
    }

    private String messageText(String key, String... placeholders) {
        return PLAIN.serialize(messages.get().component(key, placeholders));
    }

    private static String titleText(String raw) {
        return raw == null || raw.isBlank() ? "(空)" : safeText(raw);
    }

    /** Escapes user text so it cannot inject MiniMessage tags into a message. */
    private static String safeText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("<", "\\<");
    }

    private static String swayName(SwayPreset preset) {
        return preset.name().toLowerCase(Locale.ROOT);
    }

    private static String easingName(Easing easing) {
        return easing.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static PathStep pathStep(CameraTrack track, int stepIndex) {
        if (track == null || stepIndex < 0 || stepIndex >= track.steps().size()) {
            return null;
        }
        return track.steps().get(stepIndex) instanceof PathStep path ? path : null;
    }

    private static int pathCount(CameraTrack track, int stepIndex) {
        PathStep path = pathStep(track, stepIndex);
        return path == null ? 0 : path.points().size();
    }

    private static String poseShort(Pose pose) {
        return fmt(pose.x()) + ", " + fmt(pose.y()) + ", " + fmt(pose.z())
                + " · yaw " + fmt(pose.yaw()) + "° / pitch " + fmt(pose.pitch()) + "°";
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
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

    private Integer optionalInt(CommandSender sender, String[] args, int index, int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        Integer value = parseInt(sender, args[index]);
        return value == null ? null : value;
    }

    private Integer optionalDuration(CommandSender sender, String[] args, int index, int fallback, int minimum) {
        Integer value = optionalInt(sender, args, index, fallback);
        if (value == null) {
            return null;
        }
        if (value < minimum) {
            messages.get().send(sender, "editor.invalid-value", "value", String.valueOf(value));
            return null;
        }
        return value;
    }

    private Integer optionalNonNegativeInt(CommandSender sender, String raw) {
        return parseInt(sender, raw);
    }

    private Integer positiveIndex(CommandSender sender, String raw) {
        Integer value = parseInt(sender, raw);
        if (value == null) {
            return null;
        }
        if (value < 1) {
            messages.get().send(sender, "editor.invalid-value", "value", raw);
            return null;
        }
        return value;
    }

    private Integer parseInt(CommandSender sender, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            messages.get().send(sender, "editor.invalid-value", "value", raw);
            return null;
        }
    }

    private Double parseDouble(CommandSender sender, String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException exception) {
            messages.get().send(sender, "editor.invalid-value", "value", raw);
            return null;
        }
    }

    /**
     * Parses {@code x y z [yaw] [pitch]}; yaw/pitch fall back to the current target or the
     * player's own rotation when omitted.
     */
    private Pose parsePose(Player player, String[] args, int from, Pose fallback,
                           String label, EditTarget target, String syntax) {
        if (args.length < from + 3) {
            usage(player, label, target.display(), syntax);
            return null;
        }
        Double x = parseDouble(player, args[from]);
        Double y = parseDouble(player, args[from + 1]);
        Double z = parseDouble(player, args[from + 2]);
        if (x == null || y == null || z == null) {
            return null;
        }
        float yaw = fallback.yaw();
        float pitch = fallback.pitch();
        if (args.length > from + 3) {
            Double parsed = parseDouble(player, args[from + 3]);
            if (parsed == null) {
                return null;
            }
            yaw = parsed.floatValue();
        }
        if (args.length > from + 4) {
            Double parsed = parseDouble(player, args[from + 4]);
            if (parsed == null) {
                return null;
            }
            pitch = parsed.floatValue();
        }
        return new Pose(x, y, z, yaw, pitch);
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

    private EffectAction parseEffectAction(CommandSender sender, String raw) {
        try {
            return EffectAction.parse(raw);
        } catch (IllegalArgumentException exception) {
            messages.get().send(sender, "editor.invalid-value", "value", raw);
            return null;
        }
    }

    private static Boolean parseBoolean(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> Boolean.TRUE;
            case "false", "off", "no", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private EffectSpec effectSpec(Player player, EffectAction action, String key, int amplifier, int duration) {
        PotionEffectType type = CameraEffects.resolveEffect(key);
        if (type == null) {
            messages.get().send(player, "editor.invalid-effect", "effect", safeText(key));
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
        if (!sender.hasPermission("camerarail.command")) {
            return List.of();
        }
        CommandHelp help = new CommandHelp(messages.get(), alias, sender::hasPermission);
        if (args.length == 1) {
            List<String> names = new ArrayList<>();
            names.add("help");
            names.addAll(help.names(CommandHelp.Scope.ROOT));
            return partial(args[0], names);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("edit")) {
            return sender.hasPermission("camerarail.edit") ? editCompletion(args) : List.of();
        }
        if (args.length == 2) {
            return switch (action) {
                case "play", "info", "delete" -> partial(args[1], trackIds());
                case "stop" -> partial(args[1], playerNames());
                case "advance" -> partial(args[1], advanceSuggestions(sender));
                case "help" -> partial(args[1], help.names(CommandHelp.Scope.ROOT));
                case "editor" -> partial(args[1], editorArguments());
                default -> List.of();
            };
        }
        if (args.length == 3) {
            if (action.equals("play") || action.equals("stop") || action.equals("advance")) {
                return partial(args[2], playerNames());
            }
            if (action.equals("delete")) {
                return partial(args[2], List.of("confirm"));
            }
            if (action.equals("help") && args[1].equalsIgnoreCase("edit")) {
                return partial(args[2], help.names(CommandHelp.Scope.EDITOR));
            }
        }
        return List.of();
    }

    private List<String> editCompletion(String[] args) {
        String token = args.length >= 2 ? args[1] : "";
        if (args.length == 2) {
            if (token.contains(":")) {
                return targetSuffixes(token);
            }
            // Offer "id:" next to an exact track id so the client can continue into step numbers.
            List<String> candidates = new ArrayList<>(trackIds());
            for (String id : trackIds()) {
                if (id.equalsIgnoreCase(token)) {
                    candidates.add(id + ":");
                }
            }
            return partial(token, candidates);
        }
        EditTarget target = EditTarget.parse(token);
        if (target == null) {
            return List.of();
        }
        CameraTrack track = repository.get(target.trackId());
        if (track == null) {
            return List.of();
        }
        if (target.hasPoint()) {
            // Only offer point actions when the target really addresses an existing path point.
            PathStep path = pathStep(track, target.stepIndex());
            if (path == null || target.pointIndex() >= path.points().size()) {
                return List.of();
            }
            return args.length == 3 ? partial(args[2], POINT_ACTIONS) : List.of();
        }
        if (target.hasStep()) {
            if (target.stepIndex() >= track.steps().size()) {
                return List.of();
            }
            Step step = track.steps().get(target.stepIndex());
            List<String> actions = stepTypeActions(step);
            if (args.length == 3) {
                return partial(args[2], actions);
            }
            if (!actions.contains(args[2].toLowerCase(Locale.ROOT))) {
                return List.of();
            }
            return stepCompletion(step, args);
        }
        if (args.length == 3) {
            return partial(args[2], TRACK_ACTIONS);
        }
        if (!TRACK_ACTIONS.contains(args[2].toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        return trackCompletion(track, args);
    }

    private List<String> stepCompletion(Step step, String[] args) {
        String action = args[2].toLowerCase(Locale.ROOT);
        int current = args.length - 1;
        return switch (action) {
            case "easing" -> current == 3 ? partial(args[current], EASING_NAMES) : List.of();
            case "sway" -> current == 3 ? partial(args[current], STEP_SWAY_NAMES) : List.of();
            case "point" -> current == 3 ? partial(args[current], List.of("list", "add")) : List.of();
            case "effect" -> {
                if (current == 3) {
                    yield partial(args[current], List.of("add", "remove"));
                }
                if (current == 4) {
                    yield partial(args[current], EFFECT_NAMES);
                }
                yield List.of();
            }
            case "signal" -> current == 3 ? partial(args[current], allWaitSignals()) : List.of();
            default -> List.of();
        };
    }

    private List<String> trackCompletion(CameraTrack track, String[] args) {
        String action = args[2].toLowerCase(Locale.ROOT);
        int current = args.length - 1;
        return switch (action) {
            case "sway" -> current == 3 ? partial(args[current], TRACK_SWAY_NAMES) : List.of();
            case "exit-mode" -> current == 3 ? partial(args[current], EXIT_MODE_NAMES) : List.of();
            case "follow" -> current == 3 ? partial(args[current], List.of("true", "false")) : List.of();
            case "add" -> addCompletion(args);
            case "entry" -> entryCompletion(track, args);
            default -> List.of();
        };
    }

    private List<String> addCompletion(String[] args) {
        int current = args.length - 1;
        if (current == 3) {
            return partial(args[current], ADD_TYPES);
        }
        String type = args[3].toLowerCase(Locale.ROOT);
        return switch (type) {
            case "effect" -> {
                if (current == 4) {
                    yield partial(args[current], List.of("add", "remove"));
                }
                if (current == 5) {
                    yield partial(args[current], EFFECT_NAMES);
                }
                yield List.of();
            }
            case "wait" -> current == 4 ? partial(args[current], allWaitSignals()) : List.of();
            default -> List.of();
        };
    }

    private List<String> entryCompletion(CameraTrack track, String[] args) {
        int current = args.length - 1;
        if (current == 3) {
            return partial(args[current], ENTRY_ACTIONS);
        }
        String sub = args[3].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "add" -> current == 4 ? partial(args[current], EFFECT_NAMES) : List.of();
            case "set" -> {
                if (current == 4) {
                    yield partial(args[current], indexNumbers(track.entryEffects().size()));
                }
                yield current == 5 ? partial(args[current], EFFECT_NAMES) : List.of();
            }
            case "remove" -> current == 4
                    ? partial(args[current], indexNumbers(track.entryEffects().size())) : List.of();
            default -> List.of();
        };
    }

    private static List<String> indexNumbers(int count) {
        List<String> numbers = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            numbers.add(String.valueOf(i));
        }
        return numbers;
    }

    /** Completes {@code track:} with step numbers and {@code track:step:} with point numbers. */
    private List<String> targetSuffixes(String token) {
        int first = token.indexOf(':');
        int second = token.indexOf(':', first + 1);
        CameraTrack track = repository.get(token.substring(0, first));
        if (track == null) {
            return List.of();
        }
        if (second < 0) {
            String prefix = token.substring(0, first + 1);
            List<String> numbers = new ArrayList<>();
            CameraTrack stepSource = track;
            for (int i = 1; i <= stepSource.steps().size(); i++) {
                numbers.add(prefix + i);
            }
            return partial(token, numbers);
        }
        EditTarget stepTarget = EditTarget.parse(token.substring(0, second));
        PathStep path = stepTarget == null ? null : pathStep(track, stepTarget.stepIndex());
        if (path == null) {
            return List.of();
        }
        String prefix = token.substring(0, second + 1);
        List<String> numbers = new ArrayList<>();
        for (int i = 1; i <= path.points().size(); i++) {
            numbers.add(prefix + i);
        }
        return partial(token, numbers);
    }

    private List<String> trackIds() {
        return repository.all().keySet().stream().sorted().toList();
    }

    /** Track ids plus {@code close} for {@code /camerarail editor [<轨道>|close]}. */
    private List<String> editorArguments() {
        List<String> values = new ArrayList<>(trackIds());
        values.add("close");
        return values;
    }

    private List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
    }

    private List<String> advanceSuggestions(CommandSender sender) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("*");
        if (sender instanceof Player player) {
            engine.waitingSignal(player).ifPresent(signals -> {
                if (!suggestions.contains(signals)) {
                    suggestions.add(signals);
                }
            });
        }
        for (String signal : allWaitSignals()) {
            if (!suggestions.contains(signal)) {
                suggestions.add(signal);
            }
        }
        return suggestions;
    }

    private List<String> allWaitSignals() {
        List<String> signals = new ArrayList<>();
        for (CameraTrack track : repository.all().values()) {
            signals.addAll(track.waitSignals());
        }
        signals.sort(String::compareTo);
        return signals;
    }

    private static List<String> partial(String input, List<String> options) {
        String normalized = input.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(normalized))
                .toList();
    }
}
