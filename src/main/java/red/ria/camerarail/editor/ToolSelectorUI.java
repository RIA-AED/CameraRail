package red.ria.camerarail.editor;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import red.ria.camerarail.config.TrackRepository;
import red.ria.camerarail.message.Messages;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.StepType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Dialog selector for the item tools: the player picks a track, then a capture or preview
 * target on that track.
 *
 * <p>The selector only collects the choice; the selection itself lives in {@link EditorTools}
 * and is written through {@link Callback}, which also closes the dialog, refreshes the
 * ActionBar and sends the chat confirmation. Every screen is a Paper Dialog and every button
 * action is dispatched on the player's scheduler, so this is Folia-safe.</p>
 */
public final class ToolSelectorUI {
    private static final int PAGE_SIZE = 8;
    private static final int BODY_WIDTH = 320;
    private static final int BUTTON_WIDTH = 220;
    private static final String PERMISSION = "camerarail.edit";
    private static final ClickCallback.Options CLICK = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofHours(1))
            .build();
    /** Give the dialog's afterAction(CLOSE) time to finish before opening the next screen. */
    private static final long DIALOG_ACTION_DELAY_TICKS = 2L;

    /** Receives the player's choice; implemented by {@link EditorTools}. */
    public interface Callback {
        /**
         * A capture target was picked. {@code stepIndex} is 0-based; it is {@code -1} for the
         * track-level targets ({@link EditorTools.CaptureTarget#START} and
         * {@link EditorTools.CaptureTarget#EXIT}).
         */
        void captureTargetChosen(Player player, String trackId, EditorTools.CaptureTarget target, int stepIndex);

        /** A preview target was picked; {@code stepIndex} is 0-based and {@code -1} means the track start. */
        void previewTargetChosen(Player player, String trackId, int stepIndex);

        /** The player asked to drop the current selection of {@code tool}. */
        void toolChoiceCleared(Player player, EditorTools.ToolKind tool);
    }

    /** One row of an entry page: a capture target, or a preview step when {@code target} is null. */
    private record TargetEntry(EditorTools.CaptureTarget target, int stepIndex,
                               Component label, Component tooltip) {
    }

    private final Plugin plugin;
    private final TrackRepository repository;
    private final EditService edits;
    private final Supplier<Messages> messages;
    private final Callback callback;

    public ToolSelectorUI(Plugin plugin, TrackRepository repository, EditService edits,
                          Supplier<Messages> messages, Callback callback) {
        this.plugin = plugin;
        this.repository = repository;
        this.edits = edits;
        this.messages = messages;
        this.callback = callback;
    }

    /** Opens the selector at the track list for {@code tool}. */
    public void open(Player player, EditorTools.ToolKind tool) {
        if (!player.hasPermission(PERMISSION)) {
            messages.get().send(player, "command.no-permission");
            return;
        }
        onPlayer(player, () -> showTrackList(player, tool, 0));
    }

    // ------------------------------------------------------------------ screens

    private void showTrackList(Player player, EditorTools.ToolKind tool, int page) {
        List<CameraTrack> tracks = new ArrayList<>(repository.all().values());
        tracks.sort(Comparator.comparing(CameraTrack::id));
        int current = clampPage(page, tracks.size());
        List<ActionButton> buttons = new ArrayList<>();
        int from = current * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, tracks.size());
        for (int i = from; i < to; i++) {
            CameraTrack track = tracks.get(i);
            Component label = submenu(Component.text(track.displayName(), NamedTextColor.WHITE)
                    .append(Component.text("  " + track.id() + " · " + track.steps().size() + " 步",
                            NamedTextColor.DARK_GRAY)));
            buttons.add(button(label, trackTooltip(track), p -> showTargets(p, tool, track.id(), 0)));
        }
        if (current > 0) {
            buttons.add(button("上一页", "上一页轨道", p -> showTrackList(p, tool, current - 1)));
        }
        if (current < pageCount(tracks.size()) - 1) {
            buttons.add(button("下一页", "下一页轨道", p -> showTrackList(p, tool, current + 1)));
        }
        buttons.add(closeButton());

        List<Component> lines = new ArrayList<>();
        lines.add(text(tool == EditorTools.ToolKind.CAPTURE
                ? "取景工具 · 先选择要写入的轨道."
                : "预览工具 · 先选择要预览的轨道.", NamedTextColor.GRAY));
        if (tracks.isEmpty()) {
            lines.add(text("还没有轨道.", NamedTextColor.GRAY));
        } else {
            lines.add(text("共 " + tracks.size() + " 条 · 第 " + (current + 1) + "/"
                    + pageCount(tracks.size()) + " 页", NamedTextColor.GRAY));
        }
        show(player, dialog(text(tool.displayName() + " · 选择轨道", NamedTextColor.WHITE),
                lines, buttons, 2));
    }

    private void showTargets(Player player, EditorTools.ToolKind tool, String trackId, int page) {
        CameraTrack track = edits.view(trackId);
        if (track == null) {
            showTrackList(player, tool, 0);
            return;
        }
        List<TargetEntry> entries = tool == EditorTools.ToolKind.CAPTURE
                ? captureEntries(track) : previewEntries(track);
        int current = clampPage(page, entries.size());
        List<ActionButton> buttons = new ArrayList<>();
        int from = current * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, entries.size());
        for (int i = from; i < to; i++) {
            TargetEntry entry = entries.get(i);
            buttons.add(button(entry.label(), entry.tooltip(),
                    p -> choose(p, tool, trackId, entry)));
        }
        if (current > 0) {
            buttons.add(button("上一页", "上一页目标", p -> showTargets(p, tool, trackId, current - 1)));
        }
        if (current < pageCount(entries.size()) - 1) {
            buttons.add(button("下一页", "下一页目标", p -> showTargets(p, tool, trackId, current + 1)));
        }
        buttons.add(button(back("返回轨道列表"), "重新选择轨道", p -> showTrackList(p, tool, 0)));
        buttons.add(button("清除选择", "清除当前工具的目标", p -> callback.toolChoiceCleared(p, tool)));
        buttons.add(closeButton());

        List<Component> lines = new ArrayList<>();
        lines.add(text(tool == EditorTools.ToolKind.CAPTURE
                ? "选择起点/退出点后右键写入；选择路径步骤后右键追加路径点."
                : "选择步骤后右键开始预览；预览中按 Shift 退出预览.", NamedTextColor.GRAY));
        lines.add(entries.isEmpty()
                ? text("这条轨道没有可选目标.", NamedTextColor.GRAY)
                : text("共 " + entries.size() + " 个目标 · 第 " + (current + 1) + "/"
                        + pageCount(entries.size()) + " 页", NamedTextColor.GRAY));
        if (!player.getWorld().getName().equals(track.worldName())) {
            lines.add(text("当前不在轨道世界 " + track.worldName() + "，写入与预览会被拒绝.",
                    NamedTextColor.RED));
        }
        if (tool == EditorTools.ToolKind.CAPTURE && track.steps().stream()
                .noneMatch(step -> step instanceof PathStep)) {
            lines.add(text("这条轨道还没有路径步骤.", NamedTextColor.GRAY));
        }
        show(player, dialog(text(tool.displayName() + " · " + track.id(), NamedTextColor.WHITE),
                lines, buttons, 2));
    }

    private void choose(Player player, EditorTools.ToolKind tool, String trackId, TargetEntry entry) {
        if (!player.hasPermission(PERMISSION)) {
            messages.get().send(player, "command.no-permission");
            return;
        }
        if (tool == EditorTools.ToolKind.CAPTURE) {
            callback.captureTargetChosen(player, trackId, entry.target(), entry.stepIndex());
        } else {
            callback.previewTargetChosen(player, trackId, entry.stepIndex());
        }
    }

    // ------------------------------------------------------------------ entries

    private List<TargetEntry> captureEntries(CameraTrack track) {
        List<TargetEntry> entries = new ArrayList<>();
        entries.add(new TargetEntry(EditorTools.CaptureTarget.START, -1, submenu("起点"),
                text("把当前视角写入轨道起点", NamedTextColor.GRAY)));
        entries.add(new TargetEntry(EditorTools.CaptureTarget.EXIT, -1, submenu("退出点"),
                text("把当前脚部坐标与朝向写入退出点", NamedTextColor.GRAY)));
        for (int i = 0; i < track.steps().size(); i++) {
            if (!(track.steps().get(i) instanceof PathStep path)) {
                continue;
            }
            String label = "路径 " + (i + 1) + " · " + path.points().size() + " 点 · "
                    + seconds(path.durationTicks());
            entries.add(new TargetEntry(EditorTools.CaptureTarget.PATH, i, submenu(label),
                    text("步骤 " + (i + 1) + " · " + path.points().size() + " 个路径点 · "
                            + path.durationTicks() + "t · "
                            + path.easing().name().toLowerCase(Locale.ROOT), NamedTextColor.GRAY)));
        }
        return entries;
    }

    private List<TargetEntry> previewEntries(CameraTrack track) {
        List<TargetEntry> entries = new ArrayList<>();
        entries.add(new TargetEntry(null, -1, submenu("从轨道开头"),
                text("从第 1 步开始播放整条轨道", NamedTextColor.GRAY)));
        for (int i = 0; i < track.steps().size(); i++) {
            Step step = track.steps().get(i);
            entries.add(new TargetEntry(null, i,
                    submenu("步骤 " + (i + 1) + " · " + shorten(step.describe(), 34)),
                    text(stepTypeDisplay(step.type()) + " · " + step.describe(), NamedTextColor.GRAY)));
        }
        return entries;
    }

    // ------------------------------------------------------------------ dialog primitives

    private void onPlayer(Player player, Runnable action) {
        player.getScheduler().execute(plugin, action, () -> {
        }, 0L);
    }

    private void show(Player player, Dialog dialog) {
        if (player.isOnline()) {
            player.showDialog(dialog);
        }
    }

    private Dialog dialog(Component title, List<Component> lines, List<ActionButton> buttons,
                          int columns) {
        List<DialogBody> body = new ArrayList<>();
        if (!lines.isEmpty()) {
            Component combined = Component.empty();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    combined = combined.append(Component.newline());
                }
                combined = combined.append(lines.get(i));
            }
            body.add(DialogBody.plainMessage(combined, BODY_WIDTH));
        }
        List<ActionButton> actions = buttons.isEmpty() ? List.of(closeButton()) : buttons;
        return Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .body(body)
                        .inputs(List.of())
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(actions)
                        .columns(columns)
                        .exitAction(closeButton())
                        .build()));
    }

    private ActionButton closeButton() {
        return button("关闭", "关闭窗口", Player::closeDialog);
    }

    private ActionButton button(String label, String tooltip, Consumer<Player> action) {
        return button(text(label, NamedTextColor.WHITE),
                text(tooltip == null ? "" : tooltip, NamedTextColor.GRAY), BUTTON_WIDTH, action);
    }

    private ActionButton button(Component label, Component tooltip, Consumer<Player> action) {
        return button(label, tooltip, BUTTON_WIDTH, action);
    }

    private ActionButton button(Component label, Component tooltip, int width, Consumer<Player> action) {
        return ActionButton.create(label, tooltip, width, DialogAction.customClick((response, audience) -> {
            if (!(audience instanceof Player player)) {
                return;
            }
            player.getScheduler().execute(plugin, () -> action.accept(player), () -> {
            }, DIALOG_ACTION_DELAY_TICKS);
        }, CLICK));
    }

    // ------------------------------------------------------------------ text helpers

    private static Component trackTooltip(CameraTrack track) {
        String text = "id: " + track.id() + "\n显示名: " + track.displayName()
                + "\n世界: " + track.worldName() + "\n步骤: " + track.steps().size()
                + "\n时长: " + seconds(track.timedDurationTicks());
        return Component.text(text, NamedTextColor.GRAY);
    }

    private static String stepTypeDisplay(StepType type) {
        return switch (type) {
            case MOVE -> "移动";
            case PATH -> "路径";
            case HOLD -> "停留";
            case WAIT -> "等待";
            case CUT -> "硬切";
            case EFFECT -> "效果";
            case TITLE -> "标题";
        };
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String seconds(int ticks) {
        return String.format(Locale.ROOT, "%.1fs", ticks / 20.0);
    }

    private static int pageCount(int size) {
        return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private static int clampPage(int page, int size) {
        return Math.max(0, Math.min(page, pageCount(size) - 1));
    }

    private static Component submenu(String label) {
        return submenu(text(label, NamedTextColor.WHITE));
    }

    private static Component submenu(Component label) {
        return label.append(Component.text(" >", NamedTextColor.GRAY));
    }

    private static String back(String label) {
        return "< " + label;
    }

    private static Component text(String value, NamedTextColor color) {
        return Component.text(value == null ? "" : value, color);
    }
}
