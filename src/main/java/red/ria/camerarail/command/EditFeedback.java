package red.ria.camerarail.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import red.ria.camerarail.editor.EditService;
import red.ria.camerarail.editor.EditTarget;
import red.ria.camerarail.message.ChatText;
import red.ria.camerarail.message.Messages;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.CutStep;
import red.ria.camerarail.model.Easing;
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
import red.ria.camerarail.runtime.CameraEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Renders everything the editing commands answer with: success/failure feedback, status and list
 * views, wrong-action hints and the clickable action lines.
 *
 * <p>One instance is created per command invocation with the {@code label} the player used, so
 * every click-to-fill hint and usage line mentions the same alias the command was typed with.
 * The class owns the message keys and layout; callers only hand it domain objects.</p>
 */
public final class EditFeedback {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Component ACTION_SEPARATOR = Component.text(" · ", NamedTextColor.DARK_GRAY);

    private final Supplier<Messages> messages;
    private final String label;
    private final CameraEngine engine;

    public EditFeedback(Supplier<Messages> messages, String label, CameraEngine engine) {
        this.messages = messages;
        this.label = label;
        this.engine = engine;
    }

    // ------------------------------------------------------------------ feedback

    /** Sends the failure or "no change" feedback and reports whether the change was written. */
    public boolean applied(Player player, EditService.Result result) {
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

    /** Reports an unparsable enum/number/boolean the same way every command does. */
    public void invalidValue(CommandSender sender, String raw) {
        messages.get().send(sender, "editor.invalid-value", "value", raw);
    }

    // ------------------------------------------------------------------ status views

    /** {@code /camerarail info <id>}: the read-only view, including the play-preview link. */
    public void infoTrack(CommandSender sender, CameraTrack track) {
        sendTrackFields(sender, track);
        sender.sendMessage(messages.get().prefixed("track.info-preview",
                "id", track.id(), "label", label));
        if (track.steps().isEmpty()) {
            messages.get().send(sender, "track.info-no-steps");
        }
    }

    /** {@code edit <轨道>} status: the editing view, never pointing at real playback. */
    public void statusTrack(Player player, CameraTrack track) {
        sendTrackFields(player, track);
        if (track.steps().isEmpty()) {
            messages.get().send(player, "track.info-no-steps");
        }
        sendActions(player, track.id(), messages.get().component("editor.track-hint-head"),
                List.of("list", "add", "preview", "stop", "undo", "save"));
        sendActions(player, track.id(), messages.get().component("editor.track-settings-head"),
                List.of("display", "description", "start", "exit", "exit-mode", "sway", "follow", "entry"));
    }

    /** The track fields shared by the read-only view and the editing status view. */
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

    public void statusEntryEffects(Player player, CameraTrack track) {
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

    public void statusStep(Player player, EditTarget target, Step step) {
        messages.get().send(player, "editor.step-status",
                "track", target.trackId(),
                "index", String.valueOf(target.stepIndex() + 1),
                "type", stepTypeName(step),
                "summary", summary(step));
        sendActions(player, target.display(),
                messages.get().component("editor.actions-hint"), StepFields.actions(step));
        if (step instanceof PathStep path) {
            messages.get().send(player, "editor.path-point-count",
                    "count", String.valueOf(path.points().size()));
        }
    }

    public void statusPoint(Player player, EditTarget target, Pose pose) {
        messages.get().send(player, "editor.point-status",
                "target", target.display(),
                "x", fmt(pose.x()), "y", fmt(pose.y()), "z", fmt(pose.z()),
                "yaw", fmt(pose.yaw()), "pitch", fmt(pose.pitch()));
        sendActions(player, target.display(),
                messages.get().component("editor.actions-hint"), StepFields.POINT_ACTIONS);
    }

    /** The step list of a track; each row is click-to-fill. */
    public void listSteps(Player player, CameraTrack track) {
        player.sendMessage(messages.get().prefixed("track.info-header",
                "id", track.id(), "name", track.displayName()));
        // The step list is an editing view: point at command editing, never at real playback.
        sendActions(player, track.id(), messages.get().component("editor.actions-hint"),
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

    /** The path points of one path step; each row is click-to-fill. */
    public void listPathPoints(Player player, CameraTrack track, EditTarget target) {
        PathStep path = StepFields.pathStep(track, target.stepIndex());
        if (path == null) {
            messages.get().send(player, "editor.need-path");
            return;
        }
        messages.get().send(player, "editor.path-point-count",
                "count", String.valueOf(path.points().size()));
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

    // ------------------------------------------------------------------ errors and announcements

    public void wrongAction(Player player, EditTarget target, String action, List<String> actions) {
        Component head = messages.get().component("editor.wrong-action-head",
                "target", target.display(), "action", safeText(action));
        sendActions(player, target.display(), head, actions);
    }

    public void usage(Player player, String target, String syntax) {
        messages.get().send(player, "editor.action-usage",
                "label", label, "target", target, "syntax", syntax);
    }

    /** The action reference printed by {@code /camerarail edit} without arguments. */
    public void actionUsage(CommandSender sender) {
        new CommandHelp(messages.get(), label, sender::hasPermission)
                .sendOverview(sender, CommandHelp.Scope.EDITOR);
    }

    public void announceAdded(Player player, CameraTrack updated, int index) {
        if (updated == null || index <= 0 || index > updated.steps().size()) {
            return;
        }
        Step step = updated.steps().get(index - 1);
        messages.get().send(player, "editor.appended-step",
                "track", updated.id(),
                "index", String.valueOf(index),
                "summary", summary(step));
        messages.get().send(player, "editor.added-hint",
                "label", label,
                "target", updated.id() + ":" + index,
                "type", stepTypeName(step));
        if (step instanceof PathStep) {
            messages.get().send(player, "editor.path-created",
                    "track", updated.id(),
                    "index", String.valueOf(index),
                    "label", label);
        }
    }

    public void announcePathPoint(Player player, String target, CameraTrack updated, int stepIndex) {
        messages.get().send(player, "editor.path-point-added",
                "target", target, "count", String.valueOf(StepFields.pathCount(updated, stepIndex)));
    }

    // ------------------------------------------------------------------ summaries and formatting

    public String summary(Step step) {
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

    /** The command-facing name of this step type. */
    public String stepTypeName(Step step) {
        return StepFields.name(step);
    }

    public String poseShort(Pose pose) {
        return fmt(pose.x()) + ", " + fmt(pose.y()) + ", " + fmt(pose.z())
                + " · yaw " + fmt(pose.yaw()) + "° / pitch " + fmt(pose.pitch()) + "°";
    }

    public String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Escapes user text so it cannot inject MiniMessage tags into a message. */
    public static String safeText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("<", "\\<");
    }

    private static String titleText(String raw) {
        return raw == null || raw.isBlank() ? "(空)" : safeText(raw);
    }

    private static String swayName(SwayPreset preset) {
        return StepFields.swayName(preset);
    }

    private static String easingName(Easing easing) {
        return StepFields.easingName(easing);
    }

    // ------------------------------------------------------------------ input parsing with feedback

    Integer optionalInt(CommandSender sender, String[] args, int index, int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        return parseInt(sender, args[index]);
    }

    Integer optionalDuration(CommandSender sender, String[] args, int index, int fallback, int minimum) {
        Integer value = optionalInt(sender, args, index, fallback);
        if (value == null) {
            return null;
        }
        if (value < minimum) {
            invalidValue(sender, String.valueOf(value));
            return null;
        }
        return value;
    }

    Integer optionalNonNegativeInt(CommandSender sender, String raw) {
        return parseInt(sender, raw);
    }

    Integer positiveIndex(CommandSender sender, String raw) {
        Integer value = parseInt(sender, raw);
        if (value == null) {
            return null;
        }
        if (value < 1) {
            invalidValue(sender, raw);
            return null;
        }
        return value;
    }

    Integer parseInt(CommandSender sender, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            invalidValue(sender, raw);
            return null;
        }
    }

    Double parseDouble(CommandSender sender, String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException exception) {
            invalidValue(sender, raw);
            return null;
        }
    }

    /**
     * Parses {@code x y z [yaw] [pitch]}; yaw/pitch fall back to the current target or the
     * player's own rotation when omitted.
     */
    Pose parsePose(Player player, String[] args, int from, Pose fallback,
                   EditTarget target, String syntax) {
        if (args.length < from + 3) {
            usage(player, target.display(), syntax);
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

    static Boolean parseBoolean(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> Boolean.TRUE;
            case "false", "off", "no", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ clickable actions

    private void sendActions(Player player, String target, Component head, List<String> actions) {
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
}
