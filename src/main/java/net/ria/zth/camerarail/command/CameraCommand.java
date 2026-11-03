package net.ria.zth.camerarail.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.ria.zth.camerarail.config.RailConfig;
import net.ria.zth.camerarail.config.TrackRepository;
import net.ria.zth.camerarail.editor.EditorManager;
import net.ria.zth.camerarail.message.Messages;
import net.ria.zth.camerarail.model.CameraTrack;
import net.ria.zth.camerarail.model.Easing;
import net.ria.zth.camerarail.model.SwayPreset;
import net.ria.zth.camerarail.runtime.CameraEngine;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Single command surface. Subcommands are intentionally flat so tab completion can teach
 * the editor without a second plugin or a GUI framework.
 */
public final class CameraCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ROOT = List.of("help", "list", "info", "play", "stop", "advance",
            "reload", "create", "delete", "edit");
    private static final List<String> EDIT = List.of("open", "create", "status", "list", "help", "start", "exit",
            "add", "cut", "path", "point", "unpoint", "hold", "wait", "effect", "title", "here", "select",
            "duration", "easing", "sway", "track-sway", "follow", "display", "up", "down", "remove", "undo",
            "preview", "stop", "save", "done", "close", "delete");

    private final CameraEngine engine;
    private final TrackRepository repository;
    private final EditorManager editors;
    private final Supplier<RailConfig> config;
    private final Supplier<Messages> messages;
    private final IntSupplier reloadAction;

    public CameraCommand(CameraEngine engine, TrackRepository repository, EditorManager editors,
                         Supplier<RailConfig> config, Supplier<Messages> messages, IntSupplier reloadAction) {
        this.engine = engine;
        this.repository = repository;
        this.editors = editors;
        this.config = config;
        this.messages = messages;
        this.reloadAction = reloadAction;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("zth.camera.command")) {
            messages.get().send(sender, "command.no-permission");
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "help" -> help(sender, label);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "play" -> play(sender, args, label);
            case "stop" -> stop(sender, args, label);
            case "advance" -> advance(sender, args, label);
            case "reload" -> reload(sender);
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args, label);
            case "edit" -> edit(sender, args, label);
            default -> messages.get().send(sender, "command.unknown-subcommand", "arg", action);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(messages.get().prefixed("command.usage", "usage", "/" + label + " help"));
        line(sender, "<gray>轨道: <white>/" + label + " list</white> <dark_gray>·</dark_gray> <white>/" + label
                + " info [id]</white> <dark_gray>·</dark_gray> <white>/" + label + " play [id] [玩家]</white>");
        line(sender, "<gray>流程: <white>/" + label + " advance [信号] [玩家]</white> <dark_gray>·</dark_gray> <white>/"
                + label + " stop [玩家]</white>");
        line(sender, "<gray>编辑: <white>/" + label + " edit open [id]</white> <dark_gray>·</dark_gray> <white>/"
                + label + " edit help</white>");
        line(sender, "<gray>管理: <white>/" + label + " reload</white> <dark_gray>·</dark_gray> <white>/" + label
                + " delete [id] confirm</white>");
    }

    private void line(CommandSender sender, String miniMessage) {
        sender.sendMessage(messages.get().prefixComponent().append(messages.get().deserialize(miniMessage)));
    }

    private void list(CommandSender sender) {
        if (!require(sender, "zth.camera.command")) {
            return;
        }
        var tracks = new ArrayList<>(repository.all().values());
        tracks.sort((left, right) -> left.id().compareTo(right.id()));
        if (tracks.isEmpty()) {
            messages.get().send(sender, "track.list-empty");
            return;
        }
        messages.get().send(sender, "track.list-header", "count", String.valueOf(tracks.size()));
        for (CameraTrack track : tracks) {
            messages.get().send(sender, "track.list-entry",
                    "id", track.id(),
                    "name", track.displayName(),
                    "steps", String.valueOf(track.steps().size()),
                    "ticks", String.valueOf(track.timedDurationTicks()));
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.get().send(sender, "command.usage", "usage", "/zth-camera info [id]");
            return;
        }
        CameraTrack track = repository.get(args[1]);
        if (track == null) {
            messages.get().send(sender, "track.not-found", "track", args[1]);
            return;
        }
        messages.get().send(sender, "track.info-header", "id", track.id(), "name", track.displayName());
        infoLine(sender, "世界", track.worldName());
        infoLine(sender, "步骤", String.valueOf(track.steps().size()));
        infoLine(sender, "时长", track.timedDurationTicks() + " tick (" 
                + String.format(Locale.ROOT, "%.1f", track.timedDurationTicks() / 20.0) + "s)");
        infoLine(sender, "摇摆", "周期 " + track.sway().period() + "t / x " + track.sway().x()
                + " / yaw " + track.sway().yaw());
        infoLine(sender, "跟随区块", track.follow() ? "开启" : "关闭");
        infoLine(sender, "等待信号", track.waitSignals().isEmpty() ? "无"
                : String.join(", ", track.waitSignals()));
        if (!track.description().isBlank()) {
            infoLine(sender, "说明", track.description());
        }
        sender.sendMessage(messages.get().prefixed("track.info-preview", "id", track.id()));
        if (track.steps().isEmpty()) {
            messages.get().send(sender, "track.info-no-steps");
        }
    }

    private void infoLine(CommandSender sender, String label, String value) {
        messages.get().send(sender, "track.info-line", "label", label, "value", value);
    }

    private void play(CommandSender sender, String[] args, String label) {
        if (!require(sender, "zth.camera.play")) {
            return;
        }
        String trackId;
        if (args.length < 2) {
            // Convenience for a login/transition server with a single configured shot.
            var tracks = repository.all();
            if (tracks.size() != 1) {
                messages.get().send(sender, "command.usage", "usage", "/" + label + " play [id] [玩家]");
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
                messages.get().send(sender, "track.already-playing");
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
        if (!require(sender, "zth.camera.stop")) {
            return;
        }
        Player target = target(sender, args.length >= 2 ? args[1] : null);
        if (target == null) {
            return;
        }
        String trackId = engine.playback(target).map(net.ria.zth.camerarail.api.PlaybackInfo::trackId)
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
        if (!require(sender, "zth.camera.advance")) {
            return;
        }
        if (args.length < 2) {
            messages.get().send(sender, "command.usage",
                    "usage", "/" + label + " advance [信号|*] [玩家]");
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
        if (!require(sender, "zth.camera.reload")) {
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

    private void create(CommandSender sender, String[] args) {
        if (!require(sender, "zth.camera.manage") || !require(sender, "zth.camera.edit")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.get().send(sender, "manage.no-player");
            return;
        }
        if (args.length < 2) {
            messages.get().send(sender, "command.usage", "usage", "/zth-camera create [id] [名称]");
            return;
        }
        String display = args.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
        editors.create(player, args[1], display);
    }

    private void delete(CommandSender sender, String[] args, String label) {
        if (!require(sender, "zth.camera.manage") || !require(sender, "zth.camera.edit")) {
            return;
        }
        if (args.length < 2) {
            messages.get().send(sender, "command.usage", "usage", "/" + label + " delete [id] confirm");
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

    // ------------------------------------------------------------------ editor

    private void edit(CommandSender sender, String[] args, String label) {
        if (!require(sender, "zth.camera.edit")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.get().send(sender, "command.player-only");
            return;
        }
        if (args.length == 1 || args[1].equalsIgnoreCase("status")) {
            editors.status(player);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if ((action.equals("create") || action.equals("delete"))
                && !require(sender, "zth.camera.manage")) {
            return;
        }
        switch (action) {
            case "help" -> editors.help(player);
            case "open" -> {
                if (args.length < 3) {
                    messages.get().send(sender, "command.usage", "usage", "/" + label + " edit open [id]");
                    return;
                }
                editors.open(player, args[2]);
            }
            case "create" -> {
                if (args.length < 3) {
                    messages.get().send(sender, "command.usage", "usage", "/" + label + " edit create [id] [名称]");
                    return;
                }
                String display = args.length >= 4
                        ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : "";
                editors.create(player, args[2], display);
            }
            case "list" -> editors.listSteps(player);
            case "start" -> editors.setStart(player);
            case "exit" -> editors.setExit(player);
            case "add" -> {
                Integer duration = optionalInt(sender, args, 2, config.get().defaultMoveDuration());
                if (duration != null) {
                    editors.addMove(player, duration);
                }
            }
            case "cut" -> editors.addCut(player);
            case "path" -> editors.addPath(player);
            case "point" -> editors.addPathPoint(player);
            case "unpoint" -> editors.removePathPoint(player);
            case "hold" -> {
                Integer ticks = requiredInt(sender, args, 2, "/" + label + " edit hold [ticks]");
                if (ticks != null) {
                    editors.addHold(player, ticks);
                }
            }
            case "wait" -> {
                if (args.length < 3 || args[2].isBlank()) {
                    messages.get().send(sender, "editor.wait-usage");
                    return;
                }
                Integer timeout = optionalInt(sender, args, 3, config.get().defaultWaitTimeoutTicks());
                if (timeout != null) {
                    editors.addWait(player, args[2], timeout);
                }
            }
            case "effect" -> {
                if (args.length < 4) {
                    messages.get().send(sender, "editor.effect-usage");
                    return;
                }
                Integer amplifier = optionalInt(sender, args, 4, 0);
                Integer duration = optionalInt(sender, args, 5, 0);
                if (amplifier != null && duration != null) {
                    editors.addEffect(player, args[2], args[3], amplifier, duration);
                }
            }
            case "title" -> {
                if (args.length < 6) {
                    messages.get().send(sender, "editor.title-usage");
                    return;
                }
                Integer fadeIn = parseInt(sender, args[2]);
                Integer stay = parseInt(sender, args[3]);
                Integer fadeOut = parseInt(sender, args[4]);
                if (fadeIn == null || stay == null || fadeOut == null) {
                    messages.get().send(sender, "editor.title-usage");
                    return;
                }
                String text = String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length));
                editors.addTitle(player, fadeIn, stay, fadeOut, text);
            }
            case "here" -> editors.updateHere(player);
            case "select" -> {
                Integer index = requiredInt(sender, args, 2, "/" + label + " edit select [index]");
                if (index != null) {
                    editors.select(player, index);
                }
            }
            case "duration" -> {
                Integer ticks = requiredInt(sender, args, 2, "/" + label + " edit duration [ticks]");
                if (ticks != null) {
                    editors.setDuration(player, ticks);
                }
            }
            case "easing" -> {
                if (args.length < 3) {
                    messages.get().send(sender, "command.usage", "usage", "/" + label + " edit easing [曲线]");
                    return;
                }
                editors.setEasing(player, args[2]);
            }
            case "sway" -> {
                if (args.length < 3) {
                    messages.get().send(sender, "command.usage", "usage", "/" + label + " edit sway [预设]");
                    return;
                }
                editors.setStepSway(player, args[2]);
            }
            case "track-sway" -> {
                if (args.length < 3) {
                    messages.get().send(sender, "command.usage", "usage", "/" + label + " edit track-sway [预设]");
                    return;
                }
                editors.setTrackSway(player, args[2]);
            }
            case "follow" -> {
                if (args.length < 3 || (!args[2].equalsIgnoreCase("true") && !args[2].equalsIgnoreCase("false"))) {
                    messages.get().send(sender, "command.usage", "usage", "/" + label + " edit follow [true|false]");
                    return;
                }
                editors.setFollow(player, Boolean.parseBoolean(args[2]));
            }
            case "display" -> {
                if (args.length < 3) {
                    messages.get().send(sender, "editor.display-usage");
                    return;
                }
                editors.setDisplayName(player, String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
            }
            case "up" -> editors.moveSelected(player, true);
            case "down" -> editors.moveSelected(player, false);
            case "remove" -> editors.removeSelected(player);
            case "undo" -> editors.undo(player);
            case "preview" -> editors.preview(player);
            case "stop" -> editors.stopPreview(player);
            case "save" -> editors.save(player);
            case "done", "close" -> editors.close(player);
            case "delete" -> editors.delete(player, args.length >= 3 && args[2].equalsIgnoreCase("confirm"));
            default -> messages.get().send(sender, "command.unknown-subcommand", "arg", action);
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

    private Integer optionalInt(CommandSender sender, String[] args, int index, int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        Integer value = parseInt(sender, args[index]);
        return value == null ? null : value;
    }

    private Integer requiredInt(CommandSender sender, String[] args, int index, String usage) {
        if (args.length <= index) {
            messages.get().send(sender, "command.usage", "usage", usage);
            return null;
        }
        return parseInt(sender, args[index]);
    }

    private Integer parseInt(CommandSender sender, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            messages.get().send(sender, "editor.invalid-value", "value", raw);
            return null;
        }
    }

    // ------------------------------------------------------------------ completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("zth.camera.command")) {
            return List.of();
        }
        if (args.length == 1) {
            return partial(args[0], ROOT.stream().filter(action -> canUse(sender, action)).toList());
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (action) {
                case "play", "info", "delete" -> partial(args[1], trackIds());
                case "stop" -> partial(args[1], playerNames());
                case "advance" -> partial(args[1], advanceSuggestions(sender));
                case "edit" -> partial(args[1], EDIT.stream().filter(edit -> canUseEdit(sender, edit)).toList());
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
            if (action.equals("edit")) {
                String edit = args[1].toLowerCase(Locale.ROOT);
                return switch (edit) {
                    case "open" -> partial(args[2], trackIds());
                    case "select" -> partial(args[2], stepNumbers(sender));
                    case "wait" -> partial(args[2], allWaitSignals());
                    case "easing" -> partial(args[2], List.of("linear", "ease-in", "ease-out",
                            "ease-in-out", "smoothstep", "smootherstep", "sine"));
                    case "sway" -> partial(args[2], List.of("inherit", "off", "gentle", "natural", "strong"));
                    case "track-sway" -> partial(args[2], List.of("off", "gentle", "natural", "strong"));
                    case "follow" -> partial(args[2], List.of("true", "false"));
                    case "effect" -> partial(args[2], List.of("add", "remove"));
                    case "delete" -> partial(args[2], List.of("confirm"));
                    default -> List.of();
                };
            }
        }
        if (action.equals("edit") && args.length == 4) {
            if (args[1].equalsIgnoreCase("effect")) {
                return partial(args[3], List.of("blindness", "darkness", "slowness", "night_vision", "nausea",
                        "speed", "invisibility", "glowing"));
            }
        }
        return List.of();
    }

    private boolean canUse(CommandSender sender, String action) {
        return switch (action) {
            case "play" -> sender.hasPermission("zth.camera.play");
            case "stop" -> sender.hasPermission("zth.camera.stop");
            case "advance" -> sender.hasPermission("zth.camera.advance");
            case "reload" -> sender.hasPermission("zth.camera.reload");
            case "create", "delete" -> sender.hasPermission("zth.camera.manage")
                    && sender.hasPermission("zth.camera.edit");
            case "edit" -> sender.hasPermission("zth.camera.edit");
            default -> true;
        };
    }

    private boolean canUseEdit(CommandSender sender, String edit) {
        if (!sender.hasPermission("zth.camera.edit")) {
            return false;
        }
        return (!edit.equals("create") && !edit.equals("delete"))
                || sender.hasPermission("zth.camera.manage");
    }

    private List<String> trackIds() {
        return repository.all().keySet().stream().sorted().toList();
    }

    private List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
    }

    private List<String> advanceSuggestions(CommandSender sender) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("*");
        if (sender instanceof Player player) {
            engine.waitingSignal(player).ifPresent(suggestions::add);
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

    private List<String> stepNumbers(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        int count = editors.stepCount(player);
        List<String> numbers = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            numbers.add(String.valueOf(i));
        }
        return numbers;
    }

    private static List<String> partial(String input, List<String> options) {
        String normalized = input.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(normalized))
                .toList();
    }
}
