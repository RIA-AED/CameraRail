package red.ria.camerarail.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import red.ria.camerarail.api.PlaybackInfo;
import red.ria.camerarail.config.RailConfig;
import red.ria.camerarail.config.TrackRepository;
import red.ria.camerarail.editor.EditService;
import red.ria.camerarail.editor.EditTarget;
import red.ria.camerarail.editor.EditorManager;
import red.ria.camerarail.editor.EditorTools;
import red.ria.camerarail.editor.EditorUI;
import red.ria.camerarail.message.Messages;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.runtime.CameraEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Single command surface: the root dispatcher for playback, management and help, plus the entry
 * point of the stateless {@code edit} family.
 *
 * <p>The editing half mirrors the Dialog hierarchy instead of flattening everything into one
 * action list. The target decides the layer: {@code edit <track>} addresses track-wide settings,
 * {@code edit <track>:<step>} the step's own type fields plus the shared structural actions, and
 * {@code edit <track>:<step>:<point>} one path point. This class only parses the target and
 * assembles the per-invocation {@link EditContext}; the layer handlers live in
 * {@link EditCommands}, {@link TrackEditCommands}, {@link AddStepCommands},
 * {@link StepEditCommands} and {@link PointEditCommands}. Nothing depends on a hidden "selected
 * step", so every command is safe to run in any order.</p>
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
    private final EditCommands editCommands = new EditCommands();

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
        String trackId = engine.playback(target).map(PlaybackInfo::trackId).orElse("当前轨道");
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
        String display = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
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
     * the target decides which handler receives the action.
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
        EditContext context = new EditContext(editService, repository, config, messages, feedback, label);
        editCommands.handle(player, target, action, args, context);
    }

    // ------------------------------------------------------------------ helpers

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

    // ------------------------------------------------------------------ completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return completions.complete(sender, args, alias);
    }
}
