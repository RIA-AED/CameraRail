package red.ria.camerarail.editor;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import red.ria.camerarail.api.CameraTrackEndEvent;
import red.ria.camerarail.config.RailConfig;
import red.ria.camerarail.config.TrackRepository;
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
import red.ria.camerarail.model.StepType;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;
import red.ria.camerarail.model.TitleStep;
import red.ria.camerarail.model.TrackId;
import red.ria.camerarail.model.WaitStep;
import red.ria.camerarail.runtime.ExitPlacement;
import red.ria.camerarail.runtime.TrackTimeline;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Full-screen Dialog editor. Every screen is a Paper Dialog; the player never types a
 * command to edit a field. Mutations go through {@link EditorManager} on the player's
 * scheduler, so this stays Folia-safe.
 */
public final class EditorUI implements Listener {
    private static final int PAGE_SIZE = 8;
    private static final int BODY_WIDTH = 320;
    private static final int BUTTON_WIDTH = 220;
    private static final int GRID_WIDTH = 150;
    private static final ClickCallback.Options CLICK = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofHours(1))
            .build();
    /**
     * A dialog with {@code afterAction(CLOSE)} closes after its click callback; a screen opened
     * in the same tick can be swallowed by that close. Chained navigation therefore waits two
     * ticks, which also matches a player's frame time.
     */
    private static final long DIALOG_ACTION_DELAY_TICKS = 2L;
    private static final List<EffectChoice> COMMON_EFFECTS = List.of(
            new EffectChoice("blindness", "失明 (blindness)"),
            new EffectChoice("darkness", "黑暗 (darkness)"),
            new EffectChoice("slowness", "缓慢 (slowness)"),
            new EffectChoice("night_vision", "夜视 (night_vision)"),
            new EffectChoice("nausea", "反胃 (nausea)"),
            new EffectChoice("speed", "速度 (speed)"),
            new EffectChoice("invisibility", "隐身 (invisibility)"),
            new EffectChoice("glowing", "发光 (glowing)"),
            new EffectChoice("slow_falling", "缓降 (slow_falling)"),
            new EffectChoice("levitation", "漂浮 (levitation)"),
            new EffectChoice("regeneration", "生命恢复 (regeneration)"),
            new EffectChoice("resistance", "抗性提升 (resistance)"),
            new EffectChoice("fire_resistance", "抗火 (fire_resistance)"),
            new EffectChoice("water_breathing", "水下呼吸 (water_breathing)"),
            new EffectChoice("dolphins_grace", "海豚的恩惠 (dolphins_grace)"),
            new EffectChoice("jump_boost", "跳跃提升 (jump_boost)"),
            new EffectChoice("poison", "中毒 (poison)"),
            new EffectChoice("wither", "凋零 (wither)"),
            new EffectChoice("absorption", "伤害吸收 (absorption)"),
            new EffectChoice("saturation", "饱和 (saturation)")
    );

    private final Plugin plugin;
    private final EditorManager editors;
    private final TrackRepository repository;
    private final Supplier<RailConfig> config;
    private final Supplier<Messages> messages;
    /** Players whose UI-started preview should reopen the editor when it ends. */
    private final Set<UUID> reopenAfterPreview = ConcurrentHashMap.newKeySet();
    /** Opens the tool chest GUI; injected by the plugin to keep the item tools optional. */
    private Consumer<Player> toolMenu = player -> {
    };

    public EditorUI(Plugin plugin, EditorManager editors, TrackRepository repository,
                    Supplier<RailConfig> config, Supplier<Messages> messages) {
        this.plugin = plugin;
        this.editors = editors;
        this.repository = repository;
        this.config = config;
        this.messages = messages;
    }

    /** Wires the "编辑工具" buttons to the item-tool manager. */
    public void setToolMenu(Consumer<Player> toolMenu) {
        this.toolMenu = toolMenu;
    }

    private void openToolMenu(Player player) {
        player.closeDialog();
        toolMenu.accept(player);
    }

    // ------------------------------------------------------------------ entry points

    public void open(Player player) {
        if (!player.hasPermission("camerarail.edit")) {
            messages.get().send(player, "command.no-permission");
            return;
        }
        onPlayer(player, () -> {
            EditorSession session = editors.session(player);
            if (session == null) {
                showTrackList(player, 0, null);
            } else if (session.previewing) {
                showTrack(player, null);
            } else {
                restore(player, session, null);
            }
        });
    }

    private void restore(Player player, EditorSession session, Notice notice) {
        switch (session.uiScreen) {
            case OVERVIEW -> showTrack(player, notice);
            case STEPS -> showSteps(player, session.uiPage, notice);
            case STEP_FORM -> showEditStepForm(player, session.selected, null, notice);
            case PATH_POINTS -> showPathPoints(player, session.uiPage, notice);
            case PATH_POINT_FORM -> showPathPointForm(player, session.uiPoint, null, notice);
            case TRACK_SETTINGS -> showTrackSettings(player, null, notice);
            case EXIT_MODE -> showExitMode(player, notice);
            case POSE_START -> showPoseForm(player, PoseTarget.START, null, notice);
            case POSE_EXIT -> showPoseForm(player, PoseTarget.EXIT, null, notice);
            case ENTRY_EFFECTS -> showEntryEffects(player, session.uiPage, notice);
            case ENTRY_EFFECT_FORM -> {
                if (session.uiEffect >= 0 && session.uiEffect < session.draft.entryEffects().size()) {
                    showEntryEffectForm(player, session.uiEffect, null, notice);
                } else {
                    showEntryEffects(player, session.uiPage, notice);
                }
            }
        }
    }

    @EventHandler
    public void onTrackEnd(CameraTrackEndEvent event) {
        if (!event.preview()) {
            return;
        }
        Player player = event.getPlayer();
        if (!reopenAfterPreview.remove(player.getUniqueId())) {
            return;
        }
        onPlayer(player, () -> {
            EditorSession session = editors.session(player);
            if (session == null || player.isDead()
                    || !player.getWorld().getName().equals(session.worldName)) {
                return;
            }
            restore(player, session, notice("预览结束.", false));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        reopenAfterPreview.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ track list

    private void showTrackList(Player player, int page, Notice notice) {
        if (!player.hasPermission("camerarail.edit")) {
            messages.get().send(player, "command.no-permission");
            return;
        }
        if (editors.session(player) != null) {
            showTrack(player, notice);
            return;
        }
        List<CameraTrack> tracks = new ArrayList<>(repository.all().values());
        tracks.sort(Comparator.comparing(CameraTrack::id));
        int current = clampPage(page, tracks.size());
        List<ActionButton> buttons = new ArrayList<>();
        int from = current * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, tracks.size());
        for (int i = from; i < to; i++) {
            CameraTrack track = tracks.get(i);
            Component label = submenu(Component.text(track.displayName(), NamedTextColor.WHITE)
                    .append(Component.text("  " + track.id(), NamedTextColor.DARK_GRAY)));
            buttons.add(button(label, trackTooltip(track), BUTTON_WIDTH, p -> {
                editors.open(p, track.id());
                if (editors.session(p) != null) {
                    showTrack(p, null);
                } else {
                    showTrackList(p, current, notice("无法打开轨道 " + track.id() + "：请查看聊天栏.", true));
                }
            }));
        }
        if (current > 0) {
            buttons.add(button("上一页", "上一页轨道", p -> showTrackList(p, current - 1, null)));
        }
        if (current < pageCount(tracks.size()) - 1) {
            buttons.add(button("下一页", "下一页轨道", p -> showTrackList(p, current + 1, null)));
        }
        if (player.hasPermission("camerarail.manage")) {
            buttons.add(button(submenu("新建轨道"), "以当前视角创建一条轨道", p -> showCreateTrack(p, null, null)));
        }
        buttons.add(button(submenu("编辑工具"), "领取取景工具与预览工具，边走边取点", p -> openToolMenu(p)));
        buttons.add(button("刷新", "重新列出内存中的轨道", p -> showTrackList(p, current, null)));

        Component summary = tracks.isEmpty()
                ? text("还没有轨道；点击“新建轨道 >”以当前视角创建.", NamedTextColor.GRAY)
                : text("共 " + tracks.size() + " 条 · 第 " + (current + 1) + "/"
                        + pageCount(tracks.size()) + " 页", NamedTextColor.GRAY);
        show(player, dialog(text("镜头轨道", NamedTextColor.WHITE),
                body(notice, summary), List.of(), buttons, 2));
    }

    private Component trackTooltip(CameraTrack track) {
        StringBuilder text = new StringBuilder();
        text.append("id: ").append(track.id()).append('\n');
        text.append("世界: ").append(track.worldName()).append('\n');
        text.append("步骤: ").append(track.steps().size()).append('\n');
        text.append("时长: ").append(ticksLabel(track.timedDurationTicks())).append('\n');
        text.append("摇摆: ").append(swayLabel(track.sway())).append('\n');
        text.append("等待信号: ").append(track.waitSignals().isEmpty()
                ? "无" : String.join(", ", track.waitSignals()));
        return Component.text(text.toString(), NamedTextColor.GRAY);
    }

    private void showCreateTrack(Player player, Map<String, String> values, Notice notice) {
        if (!player.hasPermission("camerarail.manage")) {
            messages.get().send(player, "command.no-permission");
            return;
        }
        List<DialogInput> inputs = List.of(
                // Do not use the key "id": Paper's dialog click callback stores its callback
                // UUID under the NBT key "id". A text input with that key overwrites it and
                // every button in the dialog fails with "Failed to read field (id=...)".
                textInput("track_id", "轨道 id", value(values, "track_id", ""), 64),
                textInput("display", "显示名称", value(values, "display", ""), 64)
        );
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(formButton("创建", "以当前视角创建并开始编辑", BUTTON_WIDTH,
                response -> read(response, "track_id", "display"),
                this::createTrack));
        buttons.add(button("取消", "不创建并回到轨道列表", p -> showTrackList(p, 0, null)));
        show(player, dialog(text("新建轨道", NamedTextColor.WHITE), body(notice),
                inputs, buttons, 1));
    }

    private void createTrack(Player player, Map<String, String> values) {
        String id = value(values, "track_id", "").trim().toLowerCase(Locale.ROOT);
        String display = value(values, "display", "").trim();
        if (!TrackRules.isValidId(id)) {
            showCreateTrack(player, values, notice("id 只能包含小写字母、数字、- 和 _，最长 64 个字符.", true));
            return;
        }
        if (repository.get(id) != null) {
            showCreateTrack(player, values, notice("轨道 " + id + " 已存在.", true));
            return;
        }
        if (display.length() > 64) {
            showCreateTrack(player, values, notice("显示名称最长 64 个字符.", true));
            return;
        }
        editors.create(player, id, display);
        if (editors.session(player) != null) {
            showTrack(player, notice("已创建轨道 " + id + ".", false));
        } else {
            showCreateTrack(player, values, notice("创建失败；请查看聊天栏（可能不在轨道世界或正在预览）.", true));
        }
    }

    // ------------------------------------------------------------------ track overview

    private void showTrack(Player player, Notice notice) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        CameraTrack track = session.draft;
        session.uiScreen = EditorSession.UiScreen.OVERVIEW;
        String preset = presetLabel(track.sway());
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(submenu("步骤 · " + track.steps().size()),
                track.steps().size() + " 步 · " + ticksLabel(track.timedDurationTicks()),
                p -> showSteps(p, session.selected < 0 ? 0 : pageOf(session.selected), null)));
        if (session.previewing) {
            buttons.add(button("停止预览", "停止当前预览", this::stopPreview));
        } else {
            buttons.add(button("预览", "从起点播放整条轨道", p -> preview(p, 0, false)));
        }
        buttons.add(button(submenu("起点"), "当前: " + poseLabel(track.start()),
                p -> showPoseForm(p, PoseTarget.START, null, null)));
        buttons.add(button(submenu("退出点"), exitTooltip(track),
                p -> showExitMode(p, null)));
        buttons.add(button(submenu("轨道设置"), "摇摆 " + preset + " · 跟随"
                + (track.follow() ? "开" : "关"),
                p -> showTrackSettings(p, null, null)));
        buttons.add(button(submenu("编辑工具"), "领取取景工具与预览工具，边走边取点", p -> openToolMenu(p)));
        int entryEffectCount = track.entryEffects().size();
        buttons.add(button(submenu("入场效果 (" + entryEffectCount + ")"),
                entryEffectCount + " 个 · 点击管理入场效果",
                p -> showEntryEffects(p, 0, null)));
        if (player.hasPermission("camerarail.manage")) {
            buttons.add(button("删除轨道", "删除整条轨道", this::confirmDeleteTrack));
        }
        buttons.add(button(back("返回列表"), "退出当前编辑并回到轨道列表", p -> {
            editors.close(p);
            showTrackList(p, 0, null);
        }));

        String name = track.displayName().equals(track.id()) ? "" : track.displayName() + " · ";
        List<Component> lines = new ArrayList<>();
        lines.add(text(name + track.worldName() + " · " + track.steps().size() + " 步 · "
                + ticksLabel(track.timedDurationTicks()), NamedTextColor.GRAY));
        if (session.saveFailed) {
            lines.add(text("保存失败，请查看控制台.", NamedTextColor.RED));
        } else if (session.previewing) {
            lines.add(text("预览中.", NamedTextColor.GRAY));
        }
        if (!player.getWorld().getName().equals(track.worldName())) {
            lines.add(text("当前不在轨道世界 " + track.worldName() + "；取景会被拒绝.", NamedTextColor.RED));
        }
        if (!editors.fakeCameraAvailable()) {
            lines.add(text("镜头模式: 盔甲架回退 · FOV/失明雾不可用", NamedTextColor.GRAY));
        }
        show(player, dialog(text("轨道 · " + track.id(), NamedTextColor.WHITE),
                body(notice, lines.toArray(Component[]::new)), List.of(), buttons, 2));
    }

    private void confirmDeleteTrack(Player player) {
        EditorSession session = editors.session(player);
        if (session == null) {
            return;
        }
        if (!player.hasPermission("camerarail.manage")) {
            messages.get().send(player, "command.no-permission");
            return;
        }
        Dialog dialog = confirmation(text("删除轨道", NamedTextColor.RED), body(null,
                        text("删除 " + session.draft.id() + "？此操作不可撤销.", NamedTextColor.GRAY)),
                "删除", p -> {
                    editors.delete(p);
                    if (editors.session(p) != null) {
                        showTrack(p, notice("无法删除：可能有其他管理员正在编辑这条轨道.", true));
                    } else {
                        showTrackList(p, 0, notice("已删除轨道 " + session.draft.id() + ".", false));
                    }
                },
                "取消", p -> showTrack(p, null));
        show(player, dialog);
    }

    private void showTrackSettings(Player player, Map<String, String> values, Notice notice) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        CameraTrack track = session.draft;
        session.uiScreen = EditorSession.UiScreen.TRACK_SETTINGS;
        // The id is the track's identity (YAML key, command target, API argument, editor lock and
        // sway seed), so changing it is a manage-level operation. It still lives on this form so
        // the two names of a track are edited side by side; the save path renames first.
        boolean canRename = player.hasPermission("camerarail.manage");
        SwaySettings sway = track.sway();
        String swaySelected = presetNameOrNull(sway);
        if (swaySelected == null) {
            swaySelected = "custom";
        }
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("display", track.displayName());
        defaults.put("description", track.description());
        defaults.put("sway", swaySelected);
        defaults.put("period", String.valueOf(sway.period()));
        defaults.put("seed", String.valueOf(sway.seed()));
        defaults.put("follow", String.valueOf(track.follow()));
        if (canRename) {
            defaults.put("track_id", track.id());
        }
        Map<String, String> shown = merge(defaults, values);
        List<DialogInput> inputs = new ArrayList<>();
        if (canRename) {
            inputs.add(textInput("track_id", "轨道 id", value(shown, "track_id", track.id()), 64));
        }
        inputs.add(textInput("display", "显示名称", value(shown, "display", track.displayName()), 64));
        inputs.add(multilineInput("description", "说明", value(shown, "description", track.description()), 512));
        inputs.add(singleOption("sway", "默认摇摆", trackSwayEntries(value(shown, "sway", swaySelected))));
        inputs.add(textInput("period", "摇摆周期 (tick)", value(shown, "period", String.valueOf(sway.period())), 16));
        inputs.add(textInput("seed", "摇摆种子", value(shown, "seed", String.valueOf(sway.seed())), 32));
        inputs.add(DialogInput.bool("follow", text("跟随区块加载", NamedTextColor.GRAY),
                Boolean.parseBoolean(value(shown, "follow", String.valueOf(track.follow()))),
                "true", "false"));
        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> readWithBoolean(response, "follow", "track_id", "display", "description",
                        "sway", "period", "seed");
        PendingSave save = (p, raw) -> untouched(raw, shown, values != null)
                ? SaveOutcome.UNCHANGED : applyTrackSettings(p, raw);
        buttons.add(formButton("保存", "保存上方编辑框中的设置", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save,
                        q -> showTrack(q, notice("轨道设置已保存.", false)))));
        buttons.add(button("取消", "放弃上方未保存的输入并回到轨道概览", p -> showTrack(p, null)));
        List<Component> lines = new ArrayList<>();
        lines.add(text("世界 " + track.worldName() + " · 单位 tick · custom 保留当前振幅",
                NamedTextColor.GRAY));
        if (canRename) {
            lines.add(text("修改轨道 id 后，旧 id 的命令、点击链接和外部插件引用会失效，撤销记录会被清空.",
                    NamedTextColor.RED));
        }
        show(player, dialog(text("轨道设置 · " + track.id(), NamedTextColor.WHITE),
                body(notice, lines.toArray(Component[]::new)), inputs, buttons, 1,
                saveAndCloseButton(shown, "保存上方设置并关闭窗口（Esc 同效）", reader, save)));
    }

    private SaveOutcome applyTrackSettings(Player player, Map<String, String> raw) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return SaveOutcome.FAILED;
        }
        try {
            String oldId = session.draft.id();
            String oldDisplay = session.draft.displayName();
            String display = value(raw, "display", oldDisplay).trim();
            if (display.isEmpty()) {
                throw new FormException("显示名称不能为空.");
            }
            if (display.length() > 64) {
                throw new FormException("显示名称最长 64 个字符.");
            }
            String description = value(raw, "description", "").trim();
            if (description.length() > 512) {
                throw new FormException("说明最长 512 个字符.");
            }
            int period = integer(raw, "period", 20, config.get().maxTrackTicks(), "摇摆周期");
            long seed = longValue(raw, "seed", "摇摆种子");
            SwaySettings current = session.draft.sway();
            String storedSway = presetNameOrNull(current);
            String swayRaw = value(raw, "sway", storedSway == null ? "custom" : storedSway);
            SwaySettings nextSway;
            if ("custom".equals(swayRaw)) {
                nextSway = new SwaySettings(current.x(), current.y(), current.z(), current.yaw(),
                        current.pitch(), period, seed);
            } else {
                nextSway = SwaySettings.ofPreset(parseSway(swayRaw, false), period, seed);
            }
            boolean follow = raw.containsKey("follow")
                    ? Boolean.parseBoolean(raw.get("follow"))
                    : session.draft.follow();
            String newId = TrackId.normalize(value(raw, "track_id", oldId));
            if (!TrackId.isValid(newId)) {
                throw new FormException("轨道 id 只能包含小写字母、数字、- 和 _，最长 64 个字符.");
            }
            boolean idChanged = !newId.equals(oldId);
            if (idChanged) {
                // Rename first: it validates the id and can fail without leaving the other fields
                // written under the old id. Every earlier field has been parsed already, so the
                // only remaining failure is a race (deleted track, competing edit).
                String error = editors.rename(player, newId);
                if (error != null) {
                    throw new FormException(error);
                }
            }
            // If the display field still holds the value the form opened with, keep whatever the
            // rename did to it (a default display name follows the new id); an edited display name
            // is applied as typed.
            String nextDisplay = display.equals(oldDisplay) ? session.draft.displayName() : display;
            CameraTrack updated = session.draft.withDisplayName(nextDisplay)
                    .withDescription(description)
                    .withSway(nextSway)
                    .withFollow(follow);
            boolean settingsChanged = !updated.equals(session.draft);
            if (settingsChanged) {
                String error = editors.applyDraft(player, updated, session.selected);
                if (error != null) {
                    showTrackSettings(player, raw, notice(error, true));
                    return SaveOutcome.FAILED;
                }
            }
            return idChanged || settingsChanged ? SaveOutcome.SAVED : SaveOutcome.UNCHANGED;
        } catch (RuntimeException exception) {
            showTrackSettings(player, raw, notice(message(exception), true));
            return SaveOutcome.FAILED;
        }
    }

    private void showExitMode(Player player, Notice notice) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        CameraTrack track = session.draft;
        session.uiScreen = EditorSession.UiScreen.EXIT_MODE;
        ExitMode mode = track.exitMode();
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button((mode == ExitMode.ANIMATION_END ? "* " : "  ") + "动画结束位置",
                "默认；结束后停在镜头最后的位置",
                p -> setExitMode(p, ExitMode.ANIMATION_END)));
        buttons.add(button((mode == ExitMode.PRE_ENTRY ? "* " : "  ") + "进入前的位置",
                "结束后回到进入镜头前的位置",
                p -> setExitMode(p, ExitMode.PRE_ENTRY)));
        buttons.add(button(submenu((mode == ExitMode.CUSTOM ? "* " : "  ") + "自定义位置"),
                mode == ExitMode.CUSTOM && track.exit() != null
                        ? "当前: " + poseLabel(track.exit())
                        : "指定一个固定的脚部位置",
                p -> showPoseForm(p, PoseTarget.EXIT, null, null)));
        buttons.add(button(back("返回"), "回到轨道概览", p -> showTrack(p, null)));
        String hint = switch (mode) {
            case ANIMATION_END -> "结束后玩家会停在镜头最后的位置.";
            case PRE_ENTRY -> "结束后回到进入镜头前的位置.";
            case CUSTOM -> track.exit() == null
                    ? "还没有自定义位置；点“自定义位置 >”设置."
                    : "自定义脚部位置: " + poseLabel(track.exit());
        };
        show(player, dialog(text("轨道退出点", NamedTextColor.WHITE),
                body(notice, text("当前: " + exitModeLabel(mode), NamedTextColor.GRAY),
                        text(hint, NamedTextColor.GRAY)),
                List.of(), buttons, 1));
    }

    private void setExitMode(Player player, ExitMode mode) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        String error;
        try {
            error = editors.applyDraft(player, session.draft.withExitMode(mode), session.selected);
        } catch (RuntimeException exception) {
            showExitMode(player, notice(message(exception), true));
            return;
        }
        if (error != null) {
            showExitMode(player, notice(error, true));
            return;
        }
        showExitMode(player, notice("退出点已改为" + exitModeLabel(mode) + ".", false));
    }

    private static String exitModeLabel(ExitMode mode) {
        return switch (mode) {
            case ANIMATION_END -> "动画结束位置（默认）";
            case PRE_ENTRY -> "进入前的位置";
            case CUSTOM -> "自定义位置";
        };
    }

    private static String exitTooltip(CameraTrack track) {
        return switch (track.exitMode()) {
            case ANIMATION_END -> "动画结束位置（默认）";
            case PRE_ENTRY -> "进入前的位置";
            case CUSTOM -> "自定义: " + poseLabel(track.exit());
        };
    }

    // ------------------------------------------------------------------ steps

    private void showSteps(Player player, int page, Notice notice) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        List<Step> steps = session.draft.steps();
        int current = clampPage(page, steps.size());
        session.uiScreen = EditorSession.UiScreen.STEPS;
        session.uiPage = current;
        List<ActionButton> buttons = new ArrayList<>();
        int from = current * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, steps.size());
        for (int i = from; i < to; i++) {
            int index = i;
            Step step = steps.get(i);
            boolean selected = i == session.selected;
            Component label = submenu(Component.text(selected ? "* " : "  ", NamedTextColor.WHITE)
                    .append(Component.text((i + 1) + ". ", NamedTextColor.WHITE))
                    .append(Component.text(stepTypeDisplay(step.type()), NamedTextColor.WHITE))
                    .append(Component.text("  " + stepMeta(session.draft, i), NamedTextColor.GRAY)));
            buttons.add(button(label, stepTooltip(session.draft, i), BUTTON_WIDTH, p -> {
                session.selected = index;
                showEditStepForm(p, index, null, null);
            }));
        }
        if (current > 0) {
            buttons.add(button("上一页", "上一页步骤", p -> showSteps(p, current - 1, null)));
        }
        if (current < pageCount(steps.size()) - 1) {
            buttons.add(button("下一页", "下一页步骤", p -> showSteps(p, current + 1, null)));
        }
        buttons.add(button(submenu("添加步骤"), "选择类型并追加一个新步骤", p -> showAddStep(p, null)));
        buttons.add(button("预览全部", "从起点播放整条轨道", p -> preview(p, 0, false)));
        buttons.add(button("撤销", "撤销最近一次编辑", p -> {
            if (editors.undoQuiet(p)) {
                EditorSession after = editors.session(p);
                showSteps(p, after == null ? current : pageOf(after.selected), notice("已撤销.", false));
            } else {
                showSteps(p, current, notice("没有可以撤销的操作.", true));
            }
        }));
        buttons.add(button(back("返回"), "回到轨道概览", p -> showTrack(p, null)));

        Component summary;
        if (steps.isEmpty()) {
            summary = text("还没有步骤；点击“添加步骤 >”开始.", NamedTextColor.GRAY);
        } else {
            summary = text("共 " + steps.size() + " 步 · " + ticksLabel(session.draft.timedDurationTicks())
                    + " · 选中 " + (session.selected < 0 ? "无" : session.selected + 1)
                    + " · 第 " + (current + 1) + "/" + pageCount(steps.size()) + " 页", NamedTextColor.GRAY);
        }
        show(player, dialog(text("步骤 · " + session.draft.id(), NamedTextColor.WHITE),
                body(notice, summary), List.of(), buttons, 2));
    }

    private String stepMeta(CameraTrack track, int index) {
        Step step = track.steps().get(index);
        String time = seconds(TrackTimeline.cumulativeTicksBefore(track, index));
        return switch (step) {
            case MoveStep move -> move.durationTicks() + "t · " + time;
            case PathStep path -> path.points().size() + "点 · " + path.durationTicks() + "t · " + time;
            case HoldStep hold -> hold.durationTicks() + "t · " + time;
            case WaitStep wait -> "等待 · " + time;
            case CutStep cut -> cut.durationTicks() > 0
                    ? "硬切 " + cut.durationTicks() + "t · " + time
                    : "瞬时 · " + time;
            case EffectStep effect -> "瞬时 · " + time;
            case TitleStep title -> "瞬时 · " + time;
        };
    }

    private Component stepTooltip(CameraTrack track, int index) {
        Step step = track.steps().get(index);
        StringBuilder text = new StringBuilder();
        text.append("步骤 ").append(index + 1).append(" · ").append(stepTypeDisplay(step.type())).append('\n');
        text.append("开始: ").append(ticksLabel(TrackTimeline.cumulativeTicksBefore(track, index))).append('\n');
        if (step instanceof red.ria.camerarail.model.CameraStep camera) {
            text.append("摇摆: ").append(swayName(camera.sway())).append('\n');
        }
        switch (step) {
            case MoveStep move -> text.append("目标: ").append(poseShort(move.to())).append('\n')
                    .append("缓动: ").append(easingName(move.easing())).append('\n')
                    .append("时长: ").append(ticksLabel(move.durationTicks()));
            case PathStep path -> text.append("路径点: ").append(path.points().size()).append('\n')
                    .append("缓动: ").append(easingName(path.easing())).append('\n')
                    .append("时长: ").append(ticksLabel(path.durationTicks()));
            case HoldStep hold -> text.append("时长: ").append(ticksLabel(hold.durationTicks()));
            case WaitStep wait -> text.append("信号: ").append(wait.signal()).append('\n')
                    .append("超时: ").append(wait.timeoutTicks() == 0 ? "永不" : ticksLabel(wait.timeoutTicks()));
            case CutStep cut -> {
                text.append("目标: ").append(poseShort(cut.to()));
                if (cut.durationTicks() > 0) {
                    text.append('\n').append("停留: ").append(ticksLabel(cut.durationTicks()));
                }
            }
            case EffectStep effect -> text.append(effect.effect().describe());
            case TitleStep title -> text.append("标题: ")
                    .append(title.title().isBlank() ? "(空)" : title.title()).append('\n')
                    .append("副标题: ").append(title.subtitle().isBlank() ? "(空)" : title.subtitle());
        }
        return Component.text(text.toString(), NamedTextColor.GRAY);
    }

    private void showAddStep(Player player, Notice notice) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        session.uiScreen = EditorSession.UiScreen.STEPS;
        session.uiPage = pageOf(session.selected);
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(submenu("移动 · move"), "从当前镜头位置平滑移动到新位置",
                p -> addDefaultStep(p, StepType.MOVE)));
        buttons.add(button(submenu("路径 · path"), "创建一个路径步骤，然后逐个添加路径点",
                p -> addDefaultStep(p, StepType.PATH)));
        buttons.add(button(submenu("停留 · hold"), "在当前姿态停留固定时间",
                p -> addDefaultStep(p, StepType.HOLD)));
        buttons.add(button(submenu("等待 · wait"), "等待外部插件或命令发送信号",
                p -> addDefaultStep(p, StepType.WAIT)));
        buttons.add(button(submenu("硬切 · cut"), "瞬切到新位置",
                p -> addDefaultStep(p, StepType.CUT)));
        buttons.add(button(submenu("效果 · effect"), "施加或移除药水效果",
                p -> addDefaultStep(p, StepType.EFFECT)));
        buttons.add(button(submenu("标题 · title"), "显示标题或副标题",
                p -> addDefaultStep(p, StepType.TITLE)));
        buttons.add(button(back("返回"), "回到步骤列表",
                p -> showSteps(p, pageOf(session.selected), null)));
        show(player, dialog(text("添加步骤", NamedTextColor.WHITE), body(notice),
                List.of(), buttons, 2));
    }

    private void addDefaultStep(Player player, StepType type) {
        try {
            Step step = switch (type) {
                case MOVE -> new MoveStep(eyePose(player), config.get().defaultMoveDuration(),
                        config.get().defaultEasing(), SwayPreset.INHERIT);
                case CUT -> new CutStep(eyePose(player), 0, SwayPreset.INHERIT);
                case HOLD -> new HoldStep(config.get().defaultMoveDuration(), SwayPreset.INHERIT);
                case WAIT -> new WaitStep("signal", config.get().defaultWaitTimeoutTicks(), SwayPreset.INHERIT);
                case EFFECT -> effectStep("darkness", EffectAction.ADD, 0, 0);
                case TITLE -> new TitleStep("", "", 20, 60, 20);
                case PATH -> null;
            };
            if (type == StepType.PATH) {
                PathStep path = new PathStep(List.of(eyePose(player)), config.get().defaultMoveDuration(),
                        config.get().defaultEasing(), SwayPreset.INHERIT);
                String error = editors.addStep(player, path);
                if (error != null) {
                    showAddStep(player, notice(error, true));
                    return;
                }
                showPathPoints(player, 0, notice("已创建路径；继续添加路径点.", false));
                return;
            }
            showStepForm(player, -1, true, step,
                    type == StepType.WAIT ? Map.of("signal", "") : null, null);
        } catch (RuntimeException exception) {
            showAddStep(player, notice(message(exception), true));
        }
    }

    private void showStepForm(Player player, int index, boolean adding, Step step,
                              Map<String, String> values, Notice notice) {
        switch (step) {
            case MoveStep move -> showMoveForm(false, player, index, adding, move, values, notice);
            case CutStep cut -> showMoveForm(true, player, index, adding, cut, values, notice);
            case HoldStep hold -> showHoldForm(player, index, adding, hold, values, notice);
            case WaitStep wait -> showWaitForm(player, index, adding, wait, values, notice);
            case PathStep path -> showPathForm(player, index, adding, path, values, notice);
            case EffectStep effect -> showEffectStepForm(player, index, adding, effect, values, notice);
            case TitleStep title -> showTitleStepForm(player, index, adding, title, values, notice);
        }
    }

    private void showEditStepForm(Player player, int index, Map<String, String> values, Notice notice) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        if (index < 0 || index >= session.draft.steps().size()) {
            showSteps(player, pageOf(session.selected), notice("步骤不存在.", true));
            return;
        }
        session.selected = index;
        session.uiScreen = EditorSession.UiScreen.STEP_FORM;
        showStepForm(player, index, false, session.draft.steps().get(index), values, notice);
    }

    private void showMoveForm(boolean cut, Player player, int index, boolean adding, Step step,
                              Map<String, String> values, Notice notice) {
        Pose pose = cut ? ((CutStep) step).to() : ((MoveStep) step).to();
        int duration = cut ? ((CutStep) step).durationTicks() : ((MoveStep) step).durationTicks();
        Easing easing = cut ? Easing.EASE_IN_OUT : ((MoveStep) step).easing();
        SwayPreset sway = cut ? ((CutStep) step).sway() : ((MoveStep) step).sway();

        List<DialogInput> inputs = new ArrayList<>();
        addPoseInputs(inputs, pose, values);
        if (cut) {
            inputs.add(textInput("duration", "硬切后停留 (tick，0 = 瞬时)",
                    value(values, "duration", String.valueOf(duration)), 16));
        } else {
            inputs.add(textInput("duration", "时长 (tick)", value(values, "duration", String.valueOf(duration)), 16));
            inputs.add(singleOption("easing", "缓动", easingEntries(value(values, "easing", easingName(easing)))));
        }
        inputs.add(singleOption("sway", "摇摆", stepSwayEntries(value(values, "sway", swayName(sway)))));

        Map<String, String> shown = moveFields(pose, duration, cut, easing, sway, values);
        // A new step has nothing stored yet, so it is always written, never skipped as "untouched".
        boolean staged = values != null || adding;
        PendingSave save = (p, raw) -> untouched(raw, shown, staged)
                ? SaveOutcome.UNCHANGED : applyMove(cut, p, index, adding, step, raw);

        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "x", "y", "z", "yaw", "pitch", "duration", "easing", "sway");
        buttons.add(formButton("保存", "保存上方编辑框中的数值", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save,
                        q -> afterStepSaved(q, index, adding, false))));
        buttons.add(formButton("保存并返回", "保存上方数值后回到步骤列表", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save,
                        q -> afterStepSaved(q, index, adding, true))));
        buttons.add(captureFormButton("取当前视角", "立即把当前视角保存为这一步的目标", false, reader,
                (p, raw, captured) -> {
                    Map<String, String> next = merge(shown, input(raw));
                    putPose(next, captured);
                    if (save.save(p, next) != SaveOutcome.FAILED) {
                        afterStepSaved(p, index, adding, false);
                    }
                },
                (p, raw, error) -> showMoveForm(cut, p, index, adding, step, merge(shown, input(raw)),
                        notice(error, true))));
        buttons.add(formButton("传送到此", "先保存上方数值，再传送到这一步的目标位置；/camerarail edit 回来",
                BUTTON_WIDTH, reader, (p, raw) -> {
                    Map<String, String> input = input(raw);
                    if (save.save(p, input) == SaveOutcome.FAILED) {
                        return;
                    }
                    teleportToPose(p, parsePose(input), false);
                }));
        addEditActions(player, buttons, index, adding, reader, save);

        show(player, dialog(text(stepTitle(adding, index, cut ? "硬切" : "移动"), NamedTextColor.WHITE),
                body(notice), inputs, buttons, 2,
                formExitButton(adding, shown, "保存上方数值并关闭窗口（Esc 同效）", reader, save)));
    }

    /** The values a move/cut form opens with; used to notice an untouched form. */
    private static Map<String, String> moveFields(Pose pose, int duration, boolean cut, Easing easing,
                                                  SwayPreset sway, Map<String, String> values) {
        Map<String, String> displayed = new LinkedHashMap<>();
        putPose(displayed, pose);
        displayed.put("duration", String.valueOf(duration));
        if (!cut) {
            displayed.put("easing", easingName(easing));
        }
        displayed.put("sway", swayName(sway));
        return merge(displayed, values);
    }

    private SaveOutcome applyMove(boolean cut, Player player, int index, boolean adding, Step original,
                                  Map<String, String> raw) {
        try {
            Pose pose = parsePose(raw);
            Step updated;
            if (cut) {
                int duration = integer(raw, "duration", 0, config.get().maxTrackTicks(), "停留时长");
                updated = new CutStep(pose, duration, swayOrStored(raw, ((CutStep) original).sway()));
            } else {
                MoveStep move = (MoveStep) original;
                int duration = integer(raw, "duration", 1, config.get().maxTrackTicks(), "时长");
                Easing easing = Easing.parse(value(raw, "easing", easingName(move.easing())));
                updated = new MoveStep(pose, duration, easing, swayOrStored(raw, move.sway()));
            }
            return persistStep(player, index, adding, updated,
                    error -> showMoveForm(cut, player, index, adding, original, raw, error));
        } catch (RuntimeException exception) {
            showMoveForm(cut, player, index, adding, original, raw, notice(message(exception), true));
            return SaveOutcome.FAILED;
        }
    }

    private void showHoldForm(Player player, int index, boolean adding, HoldStep step,
                              Map<String, String> values, Notice notice) {
        List<DialogInput> inputs = List.of(
                textInput("duration", "时长 (tick)", value(values, "duration", String.valueOf(step.durationTicks())), 16),
                singleOption("sway", "摇摆", stepSwayEntries(value(values, "sway", swayName(step.sway()))))
        );
        Map<String, String> displayed = new LinkedHashMap<>();
        displayed.put("duration", String.valueOf(step.durationTicks()));
        displayed.put("sway", swayName(step.sway()));
        Map<String, String> shown = merge(displayed, values);
        boolean staged = values != null || adding;
        PendingSave save = (p, raw) -> untouched(raw, shown, staged)
                ? SaveOutcome.UNCHANGED : applyHold(p, index, adding, step, raw);
        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "duration", "sway");
        buttons.add(formButton("保存", "保存上方编辑框中的数值", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save,
                        q -> afterStepSaved(q, index, adding, false))));
        buttons.add(formButton("保存并返回", "保存上方数值后回到步骤列表", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save,
                        q -> afterStepSaved(q, index, adding, true))));
        addEditActions(player, buttons, index, adding, reader, save);
        show(player, dialog(text(stepTitle(adding, index, "停留"), NamedTextColor.WHITE),
                body(notice), inputs, buttons, 2,
                formExitButton(adding, shown, "保存上方数值并关闭窗口（Esc 同效）", reader, save)));
    }

    private SaveOutcome applyHold(Player player, int index, boolean adding, HoldStep original,
                                  Map<String, String> raw) {
        try {
            int duration = integer(raw, "duration", 1, config.get().maxTrackTicks(), "时长");
            HoldStep updated = new HoldStep(duration, swayOrStored(raw, original.sway()));
            return persistStep(player, index, adding, updated,
                    error -> showHoldForm(player, index, adding, original, raw, error));
        } catch (RuntimeException exception) {
            showHoldForm(player, index, adding, original, raw, notice(message(exception), true));
            return SaveOutcome.FAILED;
        }
    }

    private void showWaitForm(Player player, int index, boolean adding, WaitStep step,
                              Map<String, String> values, Notice notice) {
        List<DialogInput> inputs = List.of(
                textInput("signal", "信号", value(values, "signal", step.signal()), 80),
                textInput("timeout", "超时 (tick，0 = 永不)", value(values, "timeout", String.valueOf(step.timeoutTicks())), 16),
                singleOption("sway", "摇摆", stepSwayEntries(value(values, "sway", swayName(step.sway()))))
        );
        Map<String, String> displayed = new LinkedHashMap<>();
        displayed.put("signal", step.signal());
        displayed.put("timeout", String.valueOf(step.timeoutTicks()));
        displayed.put("sway", swayName(step.sway()));
        Map<String, String> shown = merge(displayed, values);
        boolean staged = values != null || adding;
        PendingSave save = (p, raw) -> untouched(raw, shown, staged)
                ? SaveOutcome.UNCHANGED : applyWait(p, index, adding, step, raw);
        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "signal", "timeout", "sway");
        buttons.add(formButton("保存", "保存上方编辑框中的数值", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save,
                        q -> afterStepSaved(q, index, adding, false))));
        buttons.add(formButton("保存并返回", "保存上方数值后回到步骤列表", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save,
                        q -> afterStepSaved(q, index, adding, true))));
        buttons.add(formButton(submenuText("已有信号"), "从所有轨道已有的 wait 信号中选择",
                BUTTON_WIDTH, reader,
                (p, raw) -> showSignalPicker(p, index, adding, step, merge(values, raw), 0, null)));
        addEditActions(player, buttons, index, adding, reader, save);
        show(player, dialog(text(stepTitle(adding, index, "等待"), NamedTextColor.WHITE),
                body(notice), inputs, buttons, 2,
                formExitButton(adding, shown, "保存上方数值并关闭窗口（Esc 同效）", reader, save)));
    }

    private void showSignalPicker(Player player, int index, boolean adding, WaitStep step,
                                  Map<String, String> values, int page, Notice notice) {
        Set<String> known = new TreeSet<>();
        EditorSession session = editors.session(player);
        if (session != null) {
            known.addAll(session.draft.waitSignals());
        }
        for (CameraTrack track : repository.all().values()) {
            known.addAll(track.waitSignals());
        }
        List<String> signals = new ArrayList<>(known);
        signals.removeIf(signal -> signal == null || signal.isBlank());
        int current = clampPage(page, signals.size());
        String selected = value(values, "signal", step.signal());
        List<ActionButton> buttons = new ArrayList<>();
        int from = current * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, signals.size());
        for (int i = from; i < to; i++) {
            String signal = signals.get(i);
            boolean active = signal.equals(selected);
            Component label = Component.text((active ? "* " : "  ") + signal, NamedTextColor.WHITE);
            buttons.add(button(label, "填入信号 " + signal, BUTTON_WIDTH, p -> {
                Map<String, String> next = copy(values);
                next.put("signal", signal);
                showWaitForm(p, index, adding, step, next, notice("已选择信号 " + signal + ".", false));
            }));
        }
        if (current > 0) {
            buttons.add(button("< 上一页", "上一页信号",
                    p -> showSignalPicker(p, index, adding, step, values, current - 1, null)));
        }
        if (to < signals.size()) {
            buttons.add(button("下一页", "下一页信号",
                    p -> showSignalPicker(p, index, adding, step, values, current + 1, null)));
        }
        buttons.add(button(back("返回"), "回到等待步骤表单，保留当前输入",
                p -> showWaitForm(p, index, adding, step, values, null)));
        String summary = signals.isEmpty()
                ? "还没有 wait 信号；可以直接在表单里输入."
                : "来自所有轨道 · " + signals.size() + " 个信号"
                        + (signals.size() > PAGE_SIZE
                        ? " · 第 " + (current + 1) + "/" + pageCount(signals.size()) + " 页" : "");
        show(player, dialog(text("已有信号", NamedTextColor.WHITE),
                body(notice, text(summary, NamedTextColor.GRAY)), List.of(), buttons, 2,
                button(back("返回"), "回到等待步骤表单，保留当前输入",
                        p -> showWaitForm(p, index, adding, step, values, null))));
    }

    private void showEffectPicker(Player player, int page, Map<String, String> values,
                                  BiConsumer<Player, Map<String, String>> onSelect,
                                  BiConsumer<Player, Map<String, String>> onBack) {
        int current = clampPage(page, COMMON_EFFECTS.size());
        List<ActionButton> buttons = new ArrayList<>();
        int from = current * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, COMMON_EFFECTS.size());
        for (int i = from; i < to; i++) {
            EffectChoice choice = COMMON_EFFECTS.get(i);
            buttons.add(button(Component.text(choice.display(), NamedTextColor.WHITE),
                    "填入 " + choice.id(), BUTTON_WIDTH, p -> {
                        Map<String, String> next = copy(values);
                        next.put("effect", choice.id());
                        onSelect.accept(p, next);
                    }));
        }
        if (current > 0) {
            buttons.add(button("< 上一页", "上一页效果",
                    p -> showEffectPicker(p, current - 1, values, onSelect, onBack)));
        }
        if (to < COMMON_EFFECTS.size()) {
            buttons.add(button("下一页", "下一页效果",
                    p -> showEffectPicker(p, current + 1, values, onSelect, onBack)));
        }
        buttons.add(button(back("返回"), "回到效果表单，保留当前输入", p -> onBack.accept(p, values)));
        String mode = editors.fakeCameraAvailable()
                ? "假玩家镜头下 blindness/slowness/speed 也有画面"
                : "当前为盔甲架回退模式：blindness/slowness/speed 无画面";
        String summary = "常用药水效果 · " + mode + " · " + COMMON_EFFECTS.size() + " 个"
                + (COMMON_EFFECTS.size() > PAGE_SIZE
                ? " · 第 " + (current + 1) + "/" + pageCount(COMMON_EFFECTS.size()) + " 页" : "");
        show(player, dialog(text("常用效果", NamedTextColor.WHITE),
                body(null, text(summary, NamedTextColor.GRAY)), List.of(), buttons, 2,
                button(back("返回"), "回到效果表单，保留当前输入", p -> onBack.accept(p, values))));
    }

    private SaveOutcome applyWait(Player player, int index, boolean adding, WaitStep original,
                                  Map<String, String> raw) {
        try {
            String signal = value(raw, "signal", "").trim();
            if (signal.isEmpty()) {
                throw new FormException("信号不能为空.");
            }
            if (signal.length() > 80) {
                throw new FormException("信号最长 80 个字符.");
            }
            int timeout = integer(raw, "timeout", 0, config.get().maxTrackTicks(), "超时");
            WaitStep updated = new WaitStep(signal, timeout, swayOrStored(raw, original.sway()));
            return persistStep(player, index, adding, updated,
                    error -> showWaitForm(player, index, adding, original, raw, error));
        } catch (RuntimeException exception) {
            showWaitForm(player, index, adding, original, raw, notice(message(exception), true));
            return SaveOutcome.FAILED;
        }
    }

    private void showPathForm(Player player, int index, boolean adding, PathStep step,
                              Map<String, String> values, Notice notice) {
        List<DialogInput> inputs = List.of(
                textInput("duration", "时长 (tick)", value(values, "duration", String.valueOf(step.durationTicks())), 16),
                singleOption("easing", "缓动", easingEntries(value(values, "easing", easingName(step.easing())))),
                singleOption("sway", "摇摆", stepSwayEntries(value(values, "sway", swayName(step.sway()))))
        );
        Map<String, String> displayed = new LinkedHashMap<>();
        displayed.put("duration", String.valueOf(step.durationTicks()));
        displayed.put("easing", easingName(step.easing()));
        displayed.put("sway", swayName(step.sway()));
        Map<String, String> shown = merge(displayed, values);
        boolean staged = values != null || adding;
        PendingSave save = (p, raw) -> untouched(raw, shown, staged)
                ? SaveOutcome.UNCHANGED : applyPath(p, index, adding, step, raw);
        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "duration", "easing", "sway");
        buttons.add(formButton("保存", "保存上方编辑框中的数值", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save,
                        q -> afterStepSaved(q, index, adding, false))));
        buttons.add(formButton("保存并返回", "保存上方数值后回到步骤列表", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save,
                        q -> afterStepSaved(q, index, adding, true))));
        buttons.add(formButton(submenuText("路径点"), step.points().size() + " 个点；先保存基础字段，再编辑路径点",
                BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save, q -> {
                    EditorSession current = editors.session(q);
                    if (current != null) {
                        current.selected = adding ? current.selected : index;
                    }
                    showPathPoints(q, 0, null);
                })));
        addEditActions(player, buttons, index, adding, reader, save);
        show(player, dialog(text(stepTitle(adding, index, "路径"), NamedTextColor.WHITE), body(notice),
                inputs, buttons, 2,
                formExitButton(adding, shown, "保存上方数值并关闭窗口（Esc 同效）", reader, save)));
    }

    private SaveOutcome applyPath(Player player, int index, boolean adding, PathStep original,
                                  Map<String, String> raw) {
        try {
            int duration = integer(raw, "duration", 1, config.get().maxTrackTicks(), "时长");
            Easing easing = Easing.parse(value(raw, "easing", easingName(original.easing())));
            SwayPreset sway = swayOrStored(raw, original.sway());
            PathStep updated = new PathStep(original.points(), duration, easing, sway);
            return persistStep(player, index, adding, updated,
                    error -> showPathForm(player, index, adding, original, raw, error));
        } catch (RuntimeException exception) {
            showPathForm(player, index, adding, original, raw, notice(message(exception), true));
            return SaveOutcome.FAILED;
        }
    }

    private void showEffectStepForm(Player player, int index, boolean adding, EffectStep step,
                                    Map<String, String> values, Notice notice) {
        EffectSpec effect = step.effect();
        List<DialogInput> inputs = List.of(
                singleOption("action", "动作", effectActionEntries(value(values, "action",
                        effect.action().name().toLowerCase(Locale.ROOT)))),
                textInput("effect", "效果 id", value(values, "effect", shortEffectKey(effect.key())), 80),
                textInput("amplifier", "等级 (0 = I 级)", value(values, "amplifier", String.valueOf(effect.amplifier())), 8),
                textInput("duration", "时长 (tick，0 = 持续)", value(values, "duration", String.valueOf(effect.durationTicks())), 16)
        );
        Map<String, String> displayed = new LinkedHashMap<>();
        displayed.put("action", effect.action().name().toLowerCase(Locale.ROOT));
        displayed.put("effect", shortEffectKey(effect.key()));
        displayed.put("amplifier", String.valueOf(effect.amplifier()));
        displayed.put("duration", String.valueOf(effect.durationTicks()));
        Map<String, String> shown = merge(displayed, values);
        boolean staged = values != null || adding;
        PendingSave save = (p, raw) -> untouched(raw, shown, staged)
                ? SaveOutcome.UNCHANGED : applyEffectStep(p, index, adding, step, raw);
        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "action", "effect", "amplifier", "duration");
        buttons.add(formButton("保存", "保存上方编辑框中的数值", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save,
                        q -> afterStepSaved(q, index, adding, false))));
        buttons.add(formButton("保存并返回", "保存上方数值后回到步骤列表", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save,
                        q -> afterStepSaved(q, index, adding, true))));
        buttons.add(formButton(submenuText("常用效果"), "从常用药水效果中选择",
                BUTTON_WIDTH, reader,
                (p, raw) -> showEffectPicker(p, 0, merge(values, raw),
                        (q, next) -> showEffectStepForm(q, index, adding, step, next,
                                notice("已选择效果.", false)),
                        (q, next) -> showEffectStepForm(q, index, adding, step, next, null))));
        addEditActions(player, buttons, index, adding, reader, save);
        List<Component> lines = new ArrayList<>();
        if (!editors.fakeCameraAvailable() && CameraEffects.isCameraDependent(effect)) {
            lines.add(fallbackEffectLine());
        }
        lines.add(effectTimingLine(false));
        if (CameraEffects.isDarkness(effect)) {
            lines.add(darknessBlendLine());
        }
        show(player, dialog(text(stepTitle(adding, index, "效果"), NamedTextColor.WHITE),
                body(notice, lines.toArray(Component[]::new)), inputs, buttons, 1,
                formExitButton(adding, shown, "保存上方数值并关闭窗口（Esc 同效）", reader, save)));
    }

    private Component fallbackEffectLine() {
        return text("当前为盔甲架回退模式：FOV、blindness 与 slowness/speed 无画面；"
                + "darkness/night_vision 仅部分生效.", NamedTextColor.GRAY);
    }

    private Component darknessBlendLine() {
        return text("darkness 的淡入/淡出约 22 tick；时长短于这个值或刚移除时，"
                + "画面只会部分变暗/变亮.", NamedTextColor.GRAY);
    }

    private Component effectTimingLine(boolean entry) {
        return text(entry
                ? "入场效果只在镜头从轨道起点开始时施加；从中间步骤开始的预览不会触发. "
                + "duration 不阻塞镜头开始."
                : "效果是瞬时步骤：duration 只决定效果持续时间，不阻塞后续动画.",
                NamedTextColor.GRAY);
    }

    private SaveOutcome applyEffectStep(Player player, int index, boolean adding, EffectStep original,
                                       Map<String, String> raw) {
        try {
            EffectAction action = EffectAction.parse(value(raw, "action", "add"));
            String key = value(raw, "effect", "").trim();
            PotionEffectType type = CameraEffects.resolveEffect(key);
            if (type == null) {
                throw new FormException("未知的药水效果 " + key + ".");
            }
            int amplifier = 0;
            int duration = 0;
            if (action == EffectAction.ADD) {
                amplifier = integer(raw, "amplifier", 0, 255, "等级");
                duration = integer(raw, "duration", 0, config.get().maxTrackTicks(), "时长");
            }
            EffectSpec spec = new EffectSpec(type, type.getKey().toString(), action, amplifier, duration);
            EffectStep updated = new EffectStep(spec);
            return persistStep(player, index, adding, updated,
                    error -> showEffectStepForm(player, index, adding, original, raw, error));
        } catch (RuntimeException exception) {
            showEffectStepForm(player, index, adding, original, raw, notice(message(exception), true));
            return SaveOutcome.FAILED;
        }
    }

    private void showTitleStepForm(Player player, int index, boolean adding, TitleStep step,
                                   Map<String, String> values, Notice notice) {
        List<DialogInput> inputs = List.of(
                multilineInput("title", "标题", value(values, "title", step.title()), 256),
                multilineInput("subtitle", "副标题", value(values, "subtitle", step.subtitle()), 256),
                textInput("fade_in", "淡入 (tick)", value(values, "fade_in", String.valueOf(step.fadeInTicks())), 8),
                textInput("stay", "停留 (tick)", value(values, "stay", String.valueOf(step.stayTicks())), 8),
                textInput("fade_out", "淡出 (tick)", value(values, "fade_out", String.valueOf(step.fadeOutTicks())), 8)
        );
        Map<String, String> displayed = new LinkedHashMap<>();
        displayed.put("title", step.title());
        displayed.put("subtitle", step.subtitle());
        displayed.put("fade_in", String.valueOf(step.fadeInTicks()));
        displayed.put("stay", String.valueOf(step.stayTicks()));
        displayed.put("fade_out", String.valueOf(step.fadeOutTicks()));
        Map<String, String> shown = merge(displayed, values);
        boolean staged = values != null || adding;
        PendingSave save = (p, raw) -> untouched(raw, shown, staged)
                ? SaveOutcome.UNCHANGED : applyTitleStep(p, index, adding, step, raw);
        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "title", "subtitle", "fade_in", "stay", "fade_out");
        buttons.add(formButton("保存", "保存上方编辑框中的数值", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save,
                        q -> afterStepSaved(q, index, adding, false))));
        buttons.add(formButton("保存并返回", "保存上方数值后回到步骤列表", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save,
                        q -> afterStepSaved(q, index, adding, true))));
        addEditActions(player, buttons, index, adding, reader, save);
        show(player, dialog(text(stepTitle(adding, index, "标题"), NamedTextColor.WHITE), body(notice,
                text("MiniMessage；至少填写标题或副标题.", NamedTextColor.GRAY)),
                inputs, buttons, 1,
                formExitButton(adding, shown, "保存上方数值并关闭窗口（Esc 同效）", reader, save)));
    }

    private SaveOutcome applyTitleStep(Player player, int index, boolean adding, TitleStep original,
                                       Map<String, String> raw) {
        try {
            String title = value(raw, "title", "");
            String subtitle = value(raw, "subtitle", "");
            if (title.isBlank() && subtitle.isBlank()) {
                throw new FormException("标题和副标题至少填写一个.");
            }
            validateMiniMessage(title, "标题");
            validateMiniMessage(subtitle, "副标题");
            int fadeIn = integer(raw, "fade_in", 0, config.get().maxTrackTicks(), "淡入");
            int stay = integer(raw, "stay", 0, config.get().maxTrackTicks(), "停留");
            int fadeOut = integer(raw, "fade_out", 0, config.get().maxTrackTicks(), "淡出");
            TitleStep updated = new TitleStep(title, subtitle, fadeIn, stay, fadeOut);
            return persistStep(player, index, adding, updated,
                    error -> showTitleStepForm(player, index, adding, original, raw, error));
        } catch (RuntimeException exception) {
            showTitleStepForm(player, index, adding, original, raw, notice(message(exception), true));
            return SaveOutcome.FAILED;
        }
    }

    // ------------------------------------------------------------------ step actions

    private String commitStep(Player player, int index, boolean adding, Step step) {
        if (adding) {
            return editors.addStep(player, step);
        }
        EditorSession session = editors.session(player);
        if (session == null) {
            return "你当前不在编辑模式.";
        }
        if (index < 0 || index >= session.draft.steps().size()) {
            return "步骤不存在.";
        }
        List<Step> steps = new ArrayList<>(session.draft.steps());
        steps.set(index, step);
        return editors.applyDraft(player, session.draft.withSteps(steps), index);
    }

    /**
     * Writes {@code updated} unless it is exactly what is stored already. On rejection the form
     * is reopened through {@code reshow}, carrying the error, so no typed value is lost.
     */
    private SaveOutcome persistStep(Player player, int index, boolean adding, Step updated,
                                    Consumer<Notice> reshow) {
        if (alreadyStored(editors.session(player), index, adding, updated)) {
            return SaveOutcome.UNCHANGED;
        }
        String error = commitStep(player, index, adding, updated);
        if (error != null) {
            reshow.accept(notice(error, true));
            return SaveOutcome.FAILED;
        }
        return SaveOutcome.SAVED;
    }

    private void afterStepSaved(Player player, int index, boolean adding, boolean returnToSteps) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        int saved = adding ? session.selected : index;
        Notice done = notice(adding ? "已添加步骤." : "已保存步骤.", false);
        if (returnToSteps) {
            showSteps(player, pageOf(saved), done);
        } else {
            showEditStepForm(player, saved, null, done);
        }
    }

    /**
     * Adds the actions shared by every step form. Actions that leave the form first persist the
     * fields on screen (应存尽存). The Save buttons stay the explicit way to write the fields
     * without navigating; 删除 and 取消 are the only actions that throw input away on purpose.
     */
    private void addEditActions(Player player, List<ActionButton> buttons, int index, boolean adding,
                                Function<DialogResponseView, Map<String, String>> reader,
                                PendingSave save) {
        EditorSession session = editors.session(player);
        int stepCount = session == null ? 0 : session.draft.steps().size();
        if (adding) {
            buttons.add(button("取消", "不添加并回到步骤列表", p -> {
                EditorSession current = editors.session(p);
                showSteps(p, current == null || current.selected < 0 ? 0 : pageOf(current.selected), null);
            }));
            return;
        }
        buttons.add(saveAction("预览此步", "先保存上方数值，再只播放选中的这一步；之前的效果不会回放",
                reader, save, (p, outcome) -> preview(p, index, true)));
        if (index > 0) {
            buttons.add(saveAction("<- 上一步", "先保存上方数值，再切到上一步", reader, save,
                    (p, outcome) -> showEditStepForm(p, index - 1, null, savedNotice(outcome))));
        }
        if (index + 1 < stepCount) {
            buttons.add(saveAction("下一步 ->", "先保存上方数值，再切到下一步", reader, save,
                    (p, outcome) -> showEditStepForm(p, index + 1, null, savedNotice(outcome))));
        }
        buttons.add(saveAction("上移", "先保存上方数值，再与上一步交换", reader, save,
                (p, outcome) -> moveStep(p, index, -1)));
        buttons.add(saveAction("下移", "先保存上方数值，再与下一步交换", reader, save,
                (p, outcome) -> moveStep(p, index, 1)));
        buttons.add(saveAction("复制", "先保存上方数值，再复制这一步并插入到后面", reader, save,
                (p, outcome) -> duplicateStep(p, index)));
        buttons.add(button("删除", "删除这一步（可撤销）；上方未保存的输入会一起丢弃",
                p -> deleteStep(p, index)));
        buttons.add(button("取消", "放弃上方未保存的输入并回到步骤列表",
                p -> showSteps(p, pageOf(index), null)));
    }

    private void moveStep(Player player, int index, int delta) {
        EditorSession session = editors.session(player);
        if (session == null) {
            return;
        }
        int target = index + delta;
        List<Step> steps = session.draft.steps();
        if (target < 0 || target >= steps.size()) {
            showEditStepForm(player, index, null, notice("已经到头了.", true));
            return;
        }
        List<Step> next = new ArrayList<>(steps);
        Step moved = next.remove(index);
        next.add(target, moved);
        String error = editors.applyDraft(player, session.draft.withSteps(next), target);
        if (error != null) {
            showEditStepForm(player, index, null, notice(error, true));
            return;
        }
        showEditStepForm(player, target, null, notice("已移动步骤.", false));
    }

    private void duplicateStep(Player player, int index) {
        EditorSession session = editors.session(player);
        if (session == null) {
            return;
        }
        List<Step> steps = session.draft.steps();
        if (index < 0 || index >= steps.size()) {
            showSteps(player, pageOf(session.selected), notice("步骤不存在.", true));
            return;
        }
        List<Step> next = new ArrayList<>(steps);
        next.add(index + 1, steps.get(index));
        String error = editors.applyDraft(player, session.draft.withSteps(next), index + 1);
        if (error != null) {
            showEditStepForm(player, index, null, notice(error, true));
            return;
        }
        showEditStepForm(player, index + 1, null, notice("已复制步骤.", false));
    }

    private void deleteStep(Player player, int index) {
        EditorSession session = editors.session(player);
        if (session == null) {
            return;
        }
        List<Step> steps = session.draft.steps();
        if (index < 0 || index >= steps.size()) {
            showSteps(player, pageOf(session.selected), notice("步骤不存在.", true));
            return;
        }
        List<Step> next = new ArrayList<>(steps);
        next.remove(index);
        int selected = next.isEmpty() ? -1 : Math.min(index, next.size() - 1);
        String error = editors.applyDraft(player, session.draft.withSteps(next), selected);
        if (error != null) {
            showEditStepForm(player, index, null, notice(error, true));
            return;
        }
        if (next.isEmpty()) {
            showSteps(player, 0, notice("已删除步骤；轨道现在没有步骤.", false));
        } else {
            showEditStepForm(player, selected, null, notice("已删除步骤.", false));
        }
    }

    // ------------------------------------------------------------------ path points

    private void showPathPoints(Player player, int page, Notice notice) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        PathStep path = selectedPath(session);
        if (path == null) {
            showEditStepForm(player, session.selected, null, notice("当前选中的不是路径步骤.", true));
            return;
        }
        int current = clampPage(page, path.points().size());
        session.uiScreen = EditorSession.UiScreen.PATH_POINTS;
        session.uiPage = current;
        List<ActionButton> buttons = new ArrayList<>();
        int from = current * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, path.points().size());
        for (int i = from; i < to; i++) {
            int pointIndex = i;
            Component label = submenu(Component.text((i + 1) + ". ", NamedTextColor.WHITE)
                    .append(Component.text(poseShort(path.points().get(i)), NamedTextColor.GRAY)));
            buttons.add(button(label, "点击编辑这个路径点；yaw " + fmt(path.points().get(i).yaw())
                    + "° / pitch " + fmt(path.points().get(i).pitch()) + "°", BUTTON_WIDTH,
                    p -> showPathPointForm(p, pointIndex, null, null)));
        }
        if (current > 0) {
            buttons.add(button("上一页", "上一页路径点", p -> showPathPoints(p, current - 1, null)));
        }
        if (current < pageCount(path.points().size()) - 1) {
            buttons.add(button("下一页", "下一页路径点", p -> showPathPoints(p, current + 1, null)));
        }
        buttons.add(captureButton("追加当前视角", "关闭窗口，立即把当前视角追加为路径点并保存", false,
                (p, pose) -> appendPathPoint(p, pose),
                (p, error) -> showPathPoints(p, current, notice(error, true))));
        buttons.add(button(back("返回"), "回到路径步骤表单", p -> showEditStepForm(p, session.selected, null, null)));

        show(player, dialog(text("路径点 · 步骤 " + (session.selected + 1), NamedTextColor.WHITE),
                body(notice, text(path.points().size() + " 个点 · " + ticksLabel(path.durationTicks())
                        + " · " + easingName(path.easing()) + " · 第 " + (current + 1) + "/"
                        + pageCount(path.points().size()) + " 页", NamedTextColor.GRAY)),
                List.of(), buttons, 2));
    }

    private void appendPathPoint(Player player, Pose pose) {
        EditorSession session = editors.session(player);
        PathStep path = selectedPath(session);
        if (session == null || path == null) {
            showSteps(player, 0, notice("当前选中的不是路径步骤.", true));
            return;
        }
        PathPoints.Result result = PathPoints.append(session.draft, session.selected, path.points(), pose);
        if (result.outcome() == PathPoints.Outcome.DUPLICATE) {
            showPathPoints(player, pageOf(path.points().size() - 1),
                    notice("与上一个路径点位置相同，未添加.", true));
            return;
        }
        if (PathPoints.exceedsLimit(result, config.get().maxPathPoints())) {
            showPathPoints(player, pageOf(path.points().size() - 1),
                    notice("路径点上限 " + config.get().maxPathPoints() + ".", true));
            return;
        }
        PathStep updated = new PathStep(result.points(), path.durationTicks(), path.easing(), path.sway());
        String error = commitPath(player, updated);
        if (error != null) {
            showPathPoints(player, pageOf(result.points().size() - 1), notice(error, true));
            return;
        }
        showPathPoints(player, pageOf(result.points().size() - 1),
                notice(result.outcome() == PathPoints.Outcome.REPLACED_SEED
                        ? "已添加路径点（创建时的占位点已替换）." : "已追加路径点.", false));
    }

    private void showPathPointForm(Player player, int pointIndex, Map<String, String> values, Notice notice) {
        EditorSession session = editors.session(player);
        PathStep path = selectedPath(session);
        if (session == null || path == null) {
            showSteps(player, 0, notice("当前选中的不是路径步骤.", true));
            return;
        }
        if (pointIndex < 0 || pointIndex >= path.points().size()) {
            showPathPoints(player, 0, notice("路径点不存在.", true));
            return;
        }
        session.uiScreen = EditorSession.UiScreen.PATH_POINT_FORM;
        session.uiPoint = pointIndex;
        Pose pose = path.points().get(pointIndex);
        List<DialogInput> inputs = new ArrayList<>();
        addPoseInputs(inputs, pose, values);
        Map<String, String> displayed = new LinkedHashMap<>();
        putPose(displayed, pose);
        Map<String, String> shown = merge(displayed, values);
        boolean staged = values != null;
        PendingSave save = (p, raw) -> untouched(raw, shown, staged)
                ? SaveOutcome.UNCHANGED : applyPathPoint(p, pointIndex, raw);
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "x", "y", "z", "yaw", "pitch");
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(formButton("保存", "保存上方编辑框中的坐标", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save, q ->
                        showPathPointForm(q, pointIndex, null, notice("已保存路径点.", false)))));
        buttons.add(captureFormButton("取当前视角", "立即把当前视角保存到这个路径点", false, reader,
                (p, raw, captured) -> saveCapturedPathPoint(p, pointIndex, captured),
                (p, raw, error) -> showPathPointForm(p, pointIndex, merge(shown, input(raw)),
                        notice(error, true))));
        buttons.add(formButton("传送到此", "先保存上方坐标，再传送到这个路径点；/camerarail edit 回来",
                BUTTON_WIDTH, reader, (p, raw) -> {
                    Map<String, String> userInput = input(raw);
                    if (save.save(p, userInput) == SaveOutcome.FAILED) {
                        return;
                    }
                    teleportToPose(p, parsePose(userInput), false);
                }));
        buttons.add(captureFormButton("插入当前视角", "先保存上方坐标，再在这个点之后插入当前视角",
                false, reader, (p, raw, captured) -> {
                    if (save.save(p, input(raw)) == SaveOutcome.FAILED) {
                        return;
                    }
                    insertPathPoint(p, pointIndex, captured);
                },
                (p, raw, error) -> showPathPointForm(p, pointIndex, merge(shown, input(raw)),
                        notice(error, true))));
        if (pointIndex > 0) {
            buttons.add(saveAction("<- 上一步", "先保存上方坐标，再切到上一个路径点", reader, save,
                    (p, outcome) -> showPathPointForm(p, pointIndex - 1, null, savedNotice(outcome))));
        }
        if (pointIndex + 1 < path.points().size()) {
            buttons.add(saveAction("下一步 ->", "先保存上方坐标，再切到下一个路径点", reader, save,
                    (p, outcome) -> showPathPointForm(p, pointIndex + 1, null, savedNotice(outcome))));
        }
        buttons.add(saveAction("上移", "先保存上方坐标，再与前一个路径点交换", reader, save,
                (p, outcome) -> movePathPoint(p, pointIndex, -1)));
        buttons.add(saveAction("下移", "先保存上方坐标，再与后一个路径点交换", reader, save,
                (p, outcome) -> movePathPoint(p, pointIndex, 1)));
        buttons.add(button("删除", "删除这个路径点（至少保留一个）；上方未保存的输入会一起丢弃",
                p -> deletePathPoint(p, pointIndex)));
        buttons.add(button("取消", "放弃上方未保存的输入并回到路径点列表",
                p -> showPathPoints(p, pageOf(pointIndex), null)));
        show(player, dialog(text("路径点 " + (pointIndex + 1), NamedTextColor.WHITE), body(notice),
                inputs, buttons, 2,
                saveAndCloseButton(shown, "保存上方坐标并关闭窗口（Esc 同效）", reader, save)));
    }

    private SaveOutcome applyPathPoint(Player player, int pointIndex, Map<String, String> raw) {
        try {
            EditorSession session = editors.session(player);
            PathStep path = selectedPath(session);
            if (path == null) {
                throw new FormException("当前选中的不是路径步骤.");
            }
            if (pointIndex < 0 || pointIndex >= path.points().size()) {
                throw new FormException("路径点不存在.");
            }
            Pose pose = parsePose(raw);
            if (pose.equals(path.points().get(pointIndex))) {
                return SaveOutcome.UNCHANGED;
            }
            List<Pose> points = new ArrayList<>(path.points());
            points.set(pointIndex, pose);
            PathStep updated = new PathStep(points, path.durationTicks(), path.easing(), path.sway());
            String error = commitPath(player, updated);
            if (error != null) {
                showPathPointForm(player, pointIndex, raw, notice(error, true));
                return SaveOutcome.FAILED;
            }
            return SaveOutcome.SAVED;
        } catch (RuntimeException exception) {
            showPathPointForm(player, pointIndex, raw, notice(message(exception), true));
            return SaveOutcome.FAILED;
        }
    }

    /** Captures the player's current view as this path point and saves it right away. */
    private void saveCapturedPathPoint(Player player, int pointIndex, Pose pose) {
        Map<String, String> raw = new LinkedHashMap<>();
        putPose(raw, pose);
        SaveOutcome outcome = applyPathPoint(player, pointIndex, raw);
        if (outcome == SaveOutcome.FAILED) {
            return;
        }
        showPathPointForm(player, pointIndex, null,
                notice(outcome == SaveOutcome.SAVED
                        ? "已取当前视角并保存." : "当前视角与已保存的路径点相同.", false));
    }

    private void movePathPoint(Player player, int pointIndex, int delta) {
        PathStep path = selectedPath(editors.session(player));
        if (path == null) {
            return;
        }
        int target = pointIndex + delta;
        if (target < 0 || target >= path.points().size()) {
            showPathPointForm(player, pointIndex, null, notice("已经到头了.", true));
            return;
        }
        List<Pose> points = new ArrayList<>(path.points());
        Pose moved = points.remove(pointIndex);
        points.add(target, moved);
        PathStep updated = new PathStep(points, path.durationTicks(), path.easing(), path.sway());
        String error = commitPath(player, updated);
        if (error != null) {
            showPathPointForm(player, pointIndex, null, notice(error, true));
            return;
        }
        showPathPointForm(player, target, null, notice("已移动路径点.", false));
    }

    private void insertPathPoint(Player player, int pointIndex, Pose pose) {
        EditorSession session = editors.session(player);
        PathStep path = selectedPath(session);
        if (path == null) {
            return;
        }
        PathPoints.Result result = PathPoints.insert(session.draft, session.selected, path.points(), pointIndex, pose);
        if (result.outcome() == PathPoints.Outcome.DUPLICATE) {
            showPathPointForm(player, pointIndex, null, notice("与相邻路径点位置相同，未插入.", true));
            return;
        }
        if (PathPoints.exceedsLimit(result, config.get().maxPathPoints())) {
            showPathPointForm(player, pointIndex, null,
                    notice("路径点上限 " + config.get().maxPathPoints() + ".", true));
            return;
        }
        PathStep updated = new PathStep(result.points(), path.durationTicks(), path.easing(), path.sway());
        String error = commitPath(player, updated);
        if (error != null) {
            showPathPointForm(player, pointIndex, null, notice(error, true));
            return;
        }
        if (result.outcome() == PathPoints.Outcome.REPLACED_SEED) {
            showPathPointForm(player, pointIndex, null, notice("已用当前视角替换创建时的占位点.", false));
            return;
        }
        showPathPointForm(player, pointIndex + 1, null, notice("已插入路径点.", false));
    }

    private void deletePathPoint(Player player, int pointIndex) {
        PathStep path = selectedPath(editors.session(player));
        if (path == null) {
            return;
        }
        if (path.points().size() <= 1) {
            showPathPointForm(player, pointIndex, null, notice("路径至少需要保留一个点.", true));
            return;
        }
        List<Pose> points = new ArrayList<>(path.points());
        points.remove(pointIndex);
        PathStep updated = new PathStep(points, path.durationTicks(), path.easing(), path.sway());
        String error = commitPath(player, updated);
        if (error != null) {
            showPathPointForm(player, pointIndex, null, notice(error, true));
            return;
        }
        showPathPoints(player, pageOf(Math.max(0, pointIndex - 1)), notice("已删除路径点.", false));
    }

    private String commitPath(Player player, PathStep updated) {
        EditorSession session = editors.session(player);
        if (session == null) {
            return "你当前不在编辑模式.";
        }
        int index = session.selected;
        if (index < 0 || index >= session.draft.steps().size()) {
            return "步骤不存在.";
        }
        List<Step> steps = new ArrayList<>(session.draft.steps());
        steps.set(index, updated);
        return editors.applyDraft(player, session.draft.withSteps(steps), index);
    }

    private PathStep selectedPath(EditorSession session) {
        if (session == null || session.selected < 0 || session.selected >= session.draft.steps().size()) {
            return null;
        }
        return session.draft.steps().get(session.selected) instanceof PathStep path ? path : null;
    }

    // ------------------------------------------------------------------ start/exit

    private void showPoseForm(Player player, PoseTarget target, Map<String, String> values, Notice notice) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        CameraTrack track = session.draft;
        session.uiScreen = target == PoseTarget.START
                ? EditorSession.UiScreen.POSE_START
                : EditorSession.UiScreen.POSE_EXIT;
        Pose current = target == PoseTarget.START ? track.start() : track.exit();
        if (current == null) {
            if (player.getWorld().getName().equals(track.worldName())) {
                Location feet = player.getLocation();
                current = new Pose(feet.getX(), feet.getY(), feet.getZ(), feet.getYaw(), feet.getPitch());
            } else if (target == PoseTarget.EXIT) {
                // Exit poses are feet positions; convert the start eye pose instead of using
                // it directly, which would put the player's feet at camera-eye height.
                Pose start = track.start();
                double eyeHeight = player.getEyeHeight();
                current = new Pose(start.x(), start.y() - eyeHeight, start.z(),
                        start.yaw(), start.pitch());
            } else {
                current = track.start();
            }
        }
        List<DialogInput> inputs = new ArrayList<>();
        addPoseInputs(inputs, current, values);
        Map<String, String> displayed = new LinkedHashMap<>();
        putPose(displayed, current);
        Map<String, String> shown = merge(displayed, values);
        boolean staged = values != null;
        PendingSave save = (p, raw) -> untouched(raw, shown, staged)
                ? SaveOutcome.UNCHANGED : applyPose(p, target, raw);
        // An exit form without a stored pose only previews the player's feet: an explicit Save
        // stores that preview, while closing the untouched form must not create a custom exit.
        boolean storeExitPreview = target == PoseTarget.EXIT && track.exit() == null;
        PendingSave explicitSave = storeExitPreview ? (p, raw) -> applyPose(p, target, raw) : save;
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "x", "y", "z", "yaw", "pitch");
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(formButton("保存", "保存上方编辑框中的坐标", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, explicitSave, q -> {
                    if (target == PoseTarget.START) {
                        showTrack(q, notice("起点已更新.", false));
                    } else {
                        showExitMode(q, notice("自定义退出点已保存.", false));
                    }
                })));
        buttons.add(captureFormButton(target == PoseTarget.EXIT ? "取当前脚下位置" : "取当前视角",
                target == PoseTarget.EXIT ? "立即把当前脚下位置保存为退出点" : "立即把当前视角保存为起点",
                target == PoseTarget.EXIT, reader,
                (p, raw, captured) -> saveCapturedPose(p, target, captured),
                (p, raw, error) -> showPoseForm(p, target, merge(shown, input(raw)), notice(error, true))));
        if (target == PoseTarget.START) {
            buttons.add(saveAction("传送到此", "先保存上方坐标，再传送到起点位置；/camerarail edit 回来",
                    reader, save, (p, outcome) -> teleportToPose(p, savedPose(p, target), false)));
        } else if (track.exit() != null) {
            buttons.add(saveAction("传送到此", "先保存上方坐标，再传送到退出位置；/camerarail edit 回来",
                    reader, save, (p, outcome) -> teleportToPose(p, savedPose(p, target), true)));
        }
        buttons.add(button("取消", target == PoseTarget.EXIT
                        ? "放弃上方未保存的输入并回到退出点设置"
                        : "放弃上方未保存的输入并回到轨道概览",
                p -> {
                    if (target == PoseTarget.EXIT) {
                        showExitMode(p, null);
                    } else {
                        showTrack(p, null);
                    }
                }));
        String hintText = target == PoseTarget.START
                ? "起点是镜头眼睛位置，通常高于地面."
                : "退出点是玩家脚部位置；保存后使用自定义位置.";
        show(player, dialog(text(target == PoseTarget.START ? "轨道起点" : "轨道退出点 · 自定义",
                        NamedTextColor.WHITE),
                body(notice, text(hintText, NamedTextColor.GRAY)), inputs, buttons, 1,
                saveAndCloseButton(shown, "保存上方坐标并关闭窗口（Esc 同效）", reader, save)));
    }

    private SaveOutcome applyPose(Player player, PoseTarget target, Map<String, String> raw) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return SaveOutcome.FAILED;
        }
        try {
            Pose pose = parsePose(raw);
            Pose stored = target == PoseTarget.START ? session.draft.start() : session.draft.exit();
            if (pose.equals(stored)) {
                return SaveOutcome.UNCHANGED;
            }
            CameraTrack updated = target == PoseTarget.START
                    ? session.draft.withStart(pose)
                    : session.draft.withExit(pose);
            String error = editors.applyDraft(player, updated, session.selected);
            if (error != null) {
                showPoseForm(player, target, raw, notice(error, true));
                return SaveOutcome.FAILED;
            }
            return SaveOutcome.SAVED;
        } catch (RuntimeException exception) {
            showPoseForm(player, target, raw, notice(message(exception), true));
            return SaveOutcome.FAILED;
        }
    }

    /** Captures the player's current view or feet position and saves it as the track pose. */
    private void saveCapturedPose(Player player, PoseTarget target, Pose pose) {
        Map<String, String> raw = new LinkedHashMap<>();
        putPose(raw, pose);
        SaveOutcome outcome = applyPose(player, target, raw);
        if (outcome == SaveOutcome.FAILED) {
            return;
        }
        String taken = target == PoseTarget.EXIT ? "已取当前脚下位置并保存." : "已取当前视角并保存.";
        showPoseForm(player, target, null,
                notice(outcome == SaveOutcome.SAVED ? taken : "与已保存的位置相同.", false));
    }

    /** The pose just stored by the start/exit form, for its 传送到此 button. */
    private Pose savedPose(Player player, PoseTarget target) {
        EditorSession session = editors.session(player);
        if (session == null) {
            return null;
        }
        return target == PoseTarget.START ? session.draft.start() : session.draft.exit();
    }

    // ------------------------------------------------------------------ entry effects

    private void showEntryEffects(Player player, int page, Notice notice) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        List<EffectSpec> effects = session.draft.entryEffects();
        int current = clampPage(page, effects.size());
        session.uiScreen = EditorSession.UiScreen.ENTRY_EFFECTS;
        session.uiPage = current;
        List<ActionButton> buttons = new ArrayList<>();
        int from = current * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, effects.size());
        for (int i = from; i < to; i++) {
            int effectIndex = i;
            EffectSpec effect = effects.get(i);
            String meta = effect.amplifier() == 0 ? "" : " · Lv" + effect.amplifier();
            String duration = effect.isInfinite() ? " · 持续" : " · " + effect.durationTicks() + "t";
            Component label = submenu(Component.text((i + 1) + ". ", NamedTextColor.WHITE)
                    .append(Component.text(shortEffectKey(effect.key()), NamedTextColor.WHITE))
                    .append(Component.text(meta + duration, NamedTextColor.GRAY)));
            buttons.add(button(label, "点击编辑；入场效果只能添加", BUTTON_WIDTH,
                    p -> showEntryEffectForm(p, effectIndex, null, null)));
        }
        if (current > 0) {
            buttons.add(button("上一页", "上一页入场效果", p -> showEntryEffects(p, current - 1, null)));
        }
        if (current < pageCount(effects.size()) - 1) {
            buttons.add(button("下一页", "下一页入场效果", p -> showEntryEffects(p, current + 1, null)));
        }
        buttons.add(button(submenu("添加效果"), "在进入镜头前追加一个效果", p -> showEntryEffectForm(p, -1, null, null)));
        buttons.add(button(back("返回"), "回到轨道概览", p -> showTrack(p, null)));
        List<Component> lines = new ArrayList<>();
        if (!editors.fakeCameraAvailable() && effects.stream().anyMatch(CameraEffects::isCameraDependent)) {
            lines.add(fallbackEffectLine());
        }
        if (effects.stream().anyMatch(CameraEffects::isDarkness)) {
            lines.add(darknessBlendLine());
        }
        lines.add(text(effects.size() + " 个 · 第 " + (current + 1) + "/"
                + pageCount(effects.size()) + " 页 · 进入镜头前施加", NamedTextColor.GRAY));
        show(player, dialog(text("入场效果 · " + session.draft.id(), NamedTextColor.WHITE),
                body(notice, lines.toArray(Component[]::new)), List.of(), buttons, 2));
    }

    private void showEntryEffectForm(Player player, int index, Map<String, String> values, Notice notice) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        List<EffectSpec> effects = session.draft.entryEffects();
        if (index >= 0) {
            session.uiScreen = EditorSession.UiScreen.ENTRY_EFFECT_FORM;
            session.uiEffect = index;
        } else {
            session.uiScreen = EditorSession.UiScreen.ENTRY_EFFECTS;
            session.uiPage = 0;
        }
        EffectSpec existing = index >= 0 && index < effects.size() ? effects.get(index) : null;
        Map<String, String> displayed = new LinkedHashMap<>();
        displayed.put("effect", existing == null ? "darkness" : shortEffectKey(existing.key()));
        displayed.put("amplifier", existing == null ? "0" : String.valueOf(existing.amplifier()));
        displayed.put("duration", existing == null ? "0" : String.valueOf(existing.durationTicks()));
        Map<String, String> shown = merge(displayed, values);
        List<DialogInput> inputs = List.of(
                textInput("effect", "效果 id", value(shown, "effect", "darkness"), 80),
                textInput("amplifier", "等级 (0 = I 级)", value(shown, "amplifier", "0"), 8),
                textInput("duration", "时长 (tick，0 = 持续)", value(shown, "duration", "0"), 16)
        );
        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "effect", "amplifier", "duration");
        PendingSave save = (p, raw) -> untouched(raw, shown, values != null || existing == null)
                ? SaveOutcome.UNCHANGED : applyEntryEffect(p, index, raw);
        buttons.add(formButton("保存", "保存上方编辑框中的数值", BUTTON_WIDTH, reader,
                (p, raw) -> saveExplicitly(p, raw, save, q -> {
                    EditorSession current = editors.session(q);
                    int count = current == null ? 0 : current.draft.entryEffects().size();
                    showEntryEffects(q, pageOf(Math.max(0, count - 1)), notice("已保存入场效果.", false));
                })));
        buttons.add(formButton(submenuText("常用效果"), "从常用药水效果中选择",
                BUTTON_WIDTH, reader,
                (p, raw) -> showEffectPicker(p, 0, merge(values, raw),
                        (q, next) -> showEntryEffectForm(q, index, next, notice("已选择效果.", false)),
                        (q, next) -> showEntryEffectForm(q, index, next, null))));
        if (existing != null) {
            buttons.add(button("删除", "删除这个入场效果；上方未保存的输入会一起丢弃",
                    p -> deleteEntryEffect(p, index)));
        }
        buttons.add(button("取消", "放弃上方未保存的输入并回到入场效果列表",
                p -> showEntryEffects(p, 0, null)));
        List<Component> lines = new ArrayList<>();
        if (!editors.fakeCameraAvailable()
                && (existing == null || CameraEffects.isCameraDependent(existing))) {
            lines.add(fallbackEffectLine());
        }
        lines.add(effectTimingLine(true));
        if (existing == null || CameraEffects.isDarkness(existing)) {
            lines.add(darknessBlendLine());
        }
        lines.add(text("只支持添加；时长 0 = 持续.", NamedTextColor.GRAY));
        show(player, dialog(text(existing == null ? "添加入场效果" : "入场效果 " + (index + 1), NamedTextColor.WHITE),
                body(notice, lines.toArray(Component[]::new)), inputs, buttons, 1,
                formExitButton(index < 0, shown, "保存上方数值并关闭窗口（Esc 同效）", reader, save)));
    }

    private SaveOutcome applyEntryEffect(Player player, int index, Map<String, String> raw) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return SaveOutcome.FAILED;
        }
        try {
            String key = value(raw, "effect", "").trim();
            PotionEffectType type = CameraEffects.resolveEffect(key);
            if (type == null) {
                throw new FormException("未知的药水效果 " + key + ".");
            }
            int amplifier = integer(raw, "amplifier", 0, 255, "等级");
            int duration = integer(raw, "duration", 0, config.get().maxTrackTicks(), "时长");
            EffectSpec spec = new EffectSpec(type, type.getKey().toString(), EffectAction.ADD, amplifier, duration);
            List<EffectSpec> effects = new ArrayList<>(session.draft.entryEffects());
            if (index >= 0 && index < effects.size()) {
                if (spec.equals(effects.get(index))) {
                    return SaveOutcome.UNCHANGED;
                }
                effects.set(index, spec);
            } else {
                effects.add(spec);
            }
            String error = editors.applyDraft(player, session.draft.withEntryEffects(effects), session.selected);
            if (error != null) {
                showEntryEffectForm(player, index, raw, notice(error, true));
                return SaveOutcome.FAILED;
            }
            return SaveOutcome.SAVED;
        } catch (RuntimeException exception) {
            showEntryEffectForm(player, index, raw, notice(message(exception), true));
            return SaveOutcome.FAILED;
        }
    }

    private void deleteEntryEffect(Player player, int index) {
        EditorSession session = editors.session(player);
        if (session == null) {
            return;
        }
        List<EffectSpec> effects = new ArrayList<>(session.draft.entryEffects());
        if (index < 0 || index >= effects.size()) {
            showEntryEffects(player, 0, notice("入场效果不存在.", true));
            return;
        }
        effects.remove(index);
        String error = editors.applyDraft(player, session.draft.withEntryEffects(effects), session.selected);
        if (error != null) {
            showEntryEffectForm(player, index, null, notice(error, true));
            return;
        }
        showEntryEffects(player, pageOf(Math.max(0, index - 1)), notice("已删除入场效果.", false));
    }

    // ------------------------------------------------------------------ preview

    private void preview(Player player, int index, boolean onlyStep) {
        EditorSession session = editors.session(player);
        if (session == null) {
            return;
        }
        if (session.draft.steps().isEmpty()) {
            showTrack(player, notice("轨道还没有步骤.", true));
            return;
        }
        int target = Math.max(0, Math.min(index, session.draft.steps().size() - 1));
        UUID id = player.getUniqueId();
        // Register before starting: a one-tick step can end before startPreview returns.
        reopenAfterPreview.add(id);
        player.closeDialog();
        if (!editors.startPreview(player, target, onlyStep ? target : -1)) {
            reopenAfterPreview.remove(id);
            showTrack(player, notice("无法开始预览（可能已有镜头在播放，或玩家不在轨道世界）.", true));
            return;
        }
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline()) {
                reopenAfterPreview.remove(id);
                return;
            }
            EditorSession current = editors.session(player);
            if (current == null) {
                reopenAfterPreview.remove(id);
                return;
            }
            if (current.previewing) {
                // The end event reopens the editor.
                return;
            }
            if (!reopenAfterPreview.remove(id)) {
                return;
            }
            showTrack(player, notice("预览未启动或已结束.", true));
        }, () -> {
        }, 25L);
    }

    private void stopPreview(Player player) {
        EditorSession session = editors.session(player);
        if (session == null) {
            return;
        }
        UUID id = player.getUniqueId();
        reopenAfterPreview.add(id);
        player.closeDialog();
        if (!editors.stopPreviewQuiet(player)) {
            reopenAfterPreview.remove(id);
            showTrack(player, notice("当前没有正在播放的预览.", true));
        }
    }

    // ------------------------------------------------------------------ capture

    private void teleportToPose(Player player, Pose pose, boolean feet) {
        EditorSession session = editors.session(player);
        if (session == null || pose == null) {
            return;
        }
        if (session.previewing) {
            player.sendActionBar(Component.text("预览中无法传送.", NamedTextColor.RED));
            return;
        }
        World world = plugin.getServer().getWorld(session.worldName);
        if (world == null) {
            player.sendActionBar(Component.text("轨道世界未加载.", NamedTextColor.RED));
            return;
        }
        Location target = feet
                ? pose.toLocation(world)
                // Camera poses are eye positions; ExitPlacement puts the standing body
                // 0.1 mm above the surface and clear of any slab/stair collision shape.
                : ExitPlacement.feet(world, pose, player.getEyeHeight());
        player.closeDialog();
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) -> {
            player.getScheduler().execute(plugin, () -> {
                if (error != null || !Boolean.TRUE.equals(success)) {
                    player.sendActionBar(Component.text("传送失败.", NamedTextColor.RED));
                } else {
                    player.sendActionBar(Component.text("已传送到此；/camerarail edit 回到编辑器.", NamedTextColor.GRAY));
                    // Same-position re-sync as the camera exit: one tick later, repeat the
                    // placement so the client receives a fresh position id/ack instead of
                    // keeping a stale awaiting-teleport phase that silently pulls the next
                    // step back.
                    player.getScheduler().runDelayed(plugin,
                            task -> resyncEditorTeleport(player, target), () -> {
                            }, 1L);
                }
            }, () -> {
            }, 0L);
        });
    }

    /**
     * Repeats an editor teleport once at the same target, one tick after the first one
     * succeeded. Same rationale as {@code CameraEngine}'s exit re-sync: the fresh
     * client-bound position id/ack resets the client's teleport acknowledgement state.
     */
    private void resyncEditorTeleport(Player player, Location target) {
        if (!player.isOnline() || !player.getWorld().equals(target.getWorld())) {
            return;
        }
        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0.0f);
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) -> {
            if (error == null && Boolean.TRUE.equals(success)) {
                return;
            }
            plugin.getLogger().log(Level.FINE,
                    "Could not re-sync editor teleport for " + player.getName(), error);
            player.getScheduler().execute(plugin, () -> {
                if (player.isOnline()) {
                    player.sendActionBar(Component.text("传送重同步失败.", NamedTextColor.GRAY));
                }
            }, () -> {
            }, 0L);
        });
    }

    /**
     * Captures the player's current eye/feet pose immediately (the caller runs in the dialog's
     * click callback) and hands it to {@code then} after {@link #DIALOG_ACTION_DELAY_TICKS}, so
     * the save/navigation part still happens after the dialog's close action while the pose is
     * the one the player saw when clicking. Any failure is reported through {@code onFailure} so
     * a caller that owns a form can reopen it with the typed values instead of dropping the
     * player on another screen; when no handler is given the editor falls back to the track
     * overview.
     */
    private void capturePose(Player player, boolean feet, Consumer<Pose> then, Consumer<String> onFailure) {
        EditorSession session = editors.session(player);
        if (session == null) {
            // Reached from a click callback: delay the replacement screen so the current dialog's
            // afterAction(CLOSE) cannot swallow it.
            player.getScheduler().execute(plugin, () -> showTrackList(player, 0, null), () -> {
            }, DIALOG_ACTION_DELAY_TICKS);
            return;
        }
        if (session.previewing) {
            captureFailed(player, onFailure, "预览中无法取景；请先停止预览.");
            return;
        }
        if (!player.getWorld().getName().equals(session.worldName)) {
            captureFailed(player, onFailure, "请先前往轨道所在世界 " + session.worldName + ".");
            return;
        }
        Pose captured;
        try {
            captured = feet ? feetPose(player) : eyePose(player);
        } catch (RuntimeException exception) {
            captureFailed(player, onFailure, message(exception));
            return;
        }
        player.closeDialog();
        player.sendActionBar(Component.text(feet ? "已取当前脚下位置." : "已取当前视角.",
                NamedTextColor.GRAY));
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline()) {
                return;
            }
            EditorSession current = editors.session(player);
            if (current == null) {
                showTrackList(player, 0, null);
                return;
            }
            if (!player.getWorld().getName().equals(current.worldName)) {
                captureFailed(player, onFailure, "请先前往轨道所在世界 " + current.worldName + ".");
                return;
            }
            try {
                then.accept(captured);
            } catch (RuntimeException exception) {
                captureFailed(player, onFailure, message(exception));
            }
        }, () -> {
        }, DIALOG_ACTION_DELAY_TICKS);
    }

    private void captureFailed(Player player, Consumer<String> onFailure, String text) {
        if (onFailure != null) {
            onFailure.accept(text);
            return;
        }
        // No form to reopen: delay the overview for the same afterAction(CLOSE) reason.
        player.getScheduler().execute(plugin, () -> showTrack(player, notice(text, true)), () -> {
        }, DIALOG_ACTION_DELAY_TICKS);
    }

    private Pose eyePose(Player player) {
        EditorSession session = editors.session(player);
        if (session == null) {
            throw new FormException("你当前不在编辑模式.");
        }
        if (session.previewing) {
            throw new FormException("预览中无法取景.");
        }
        if (!player.getWorld().getName().equals(session.worldName)) {
            throw new FormException("请先前往轨道所在世界 " + session.worldName + ".");
        }
        Location location = player.getEyeLocation();
        return new Pose(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private Pose feetPose(Player player) {
        EditorSession session = editors.session(player);
        if (session == null) {
            throw new FormException("你当前不在编辑模式.");
        }
        if (session.previewing) {
            throw new FormException("预览中无法取景.");
        }
        if (!player.getWorld().getName().equals(session.worldName)) {
            throw new FormException("请先前往轨道所在世界 " + session.worldName + ".");
        }
        Location location = player.getLocation();
        return new Pose(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    // ------------------------------------------------------------------ dialog primitives

    private Dialog dialog(Component title, List<DialogBody> body, List<DialogInput> inputs,
                          List<ActionButton> buttons, int columns) {
        return dialog(title, body, inputs, buttons, columns, closeButton());
    }

    /**
     * Builds a dialog with an explicit footer/Esc action. Forms pass a saving exit action so
     * closing the window cannot drop the fields the player just typed.
     */
    private Dialog dialog(Component title, List<DialogBody> body, List<DialogInput> inputs,
                          List<ActionButton> buttons, int columns, ActionButton exitAction) {
        List<ActionButton> actions = buttons.isEmpty() ? List.of(closeButton()) : buttons;
        return Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .body(body)
                        .inputs(inputs)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(actions)
                        .columns(columns)
                        .exitAction(exitAction)
                        .build()));
    }

    private Dialog confirmation(Component title, List<DialogBody> body, String yesLabel,
                                Consumer<Player> yes, String noLabel, Consumer<Player> no) {
        return Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .body(body)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(button(yesLabel, "", yes), button(noLabel, "", no))));
    }

    private ActionButton closeButton() {
        return closeButton("关闭窗口");
    }

    private ActionButton closeButton(String tooltip) {
        return button("关闭", tooltip, p -> p.closeDialog());
    }

    /**
     * Footer/Esc action of a form: an existing step/effect saves its fields, while an "add" form
     * closes without creating anything (Esc after opening 添加步骤 still means cancel).
     */
    private ActionButton formExitButton(boolean adding, Map<String, String> displayed, String tooltip,
                                        Function<DialogResponseView, Map<String, String>> reader,
                                        PendingSave save) {
        return adding ? closeButton("关闭窗口（不添加）")
                : saveAndCloseButton(displayed, tooltip, reader, save);
    }

    /**
     * Footer/Esc action of a form dialog: persist the fields on screen before the window goes
     * away. The client submits the current input values with the exit action, so this covers the
     * footer button and Esc alike. Fields the response does not carry fall back to {@code displayed}
     * (the values the form opened with), and a response without any values just closes the dialog.
     */
    private ActionButton saveAndCloseButton(Map<String, String> displayed, String tooltip,
                                           Function<DialogResponseView, Map<String, String>> reader,
                                           PendingSave save) {
        return formButton("保存并关闭", tooltip, BUTTON_WIDTH, reader, (player, raw) -> {
            Map<String, String> input = input(raw);
            boolean blank = input.values().stream().allMatch(value -> value == null || value.isBlank());
            if (!blank) {
                save.save(player, merge(displayed, input));
            }
        });
    }

    private ActionButton button(String label, String tooltip, Consumer<Player> action) {
        return button(text(label, NamedTextColor.WHITE),
                text(tooltip == null ? "" : tooltip, NamedTextColor.GRAY), BUTTON_WIDTH, action);
    }

    private ActionButton button(Component label, String tooltip, Consumer<Player> action) {
        return button(label, tooltip, BUTTON_WIDTH, action);
    }

    private ActionButton button(Component label, String tooltip, int width, Consumer<Player> action) {
        return button(label, text(tooltip == null ? "" : tooltip, NamedTextColor.GRAY), width, action);
    }

    private ActionButton button(Component label, Component tooltip, int width, Consumer<Player> action) {
        return ActionButton.create(label, tooltip, width, DialogAction.customClick((response, audience) -> {
            if (!(audience instanceof Player player)) {
                return;
            }
            // The dialog's afterAction(CLOSE) is applied after this callback; opening the next
            // screen in the same tick can be overwritten by that close, so give it two ticks.
            player.getScheduler().execute(plugin, () -> action.accept(player), () -> {
            }, DIALOG_ACTION_DELAY_TICKS);
        }, CLICK));
    }

    private <T> ActionButton formButton(String label, String tooltip, int width,
                                        Function<DialogResponseView, T> reader,
                                        BiConsumer<Player, T> action) {
        return ActionButton.create(text(label, NamedTextColor.WHITE),
                text(tooltip == null ? "" : tooltip, NamedTextColor.GRAY), width,
                DialogAction.customClick((response, audience) -> {
                    if (!(audience instanceof Player player)) {
                        return;
                    }
                    T value;
                    try {
                        value = reader.apply(response);
                    } catch (RuntimeException exception) {
                        value = null;
                    }
                    T finalValue = value;
                    player.getScheduler().execute(plugin, () -> action.accept(player, finalValue), () -> {
                    }, DIALOG_ACTION_DELAY_TICKS);
                }, CLICK));
    }

    /** Receives the values read at click time and the pose captured at click time. */
    @FunctionalInterface
    private interface CapturedFormAction {
        void accept(Player player, Map<String, String> raw, Pose pose);
    }

    /** Reopens a form with the values typed at click time when the capture failed. */
    @FunctionalInterface
    private interface CapturedFormFailure {
        void accept(Player player, Map<String, String> raw, String error);
    }

    /** A capture action without form values (list buttons such as "追加当前视角"). */
    @FunctionalInterface
    private interface CapturedAction {
        void accept(Player player, Pose pose);
    }

    /**
     * A button that captures the player's pose in the click callback, i.e. the view the player
     * has when clicking, instead of after the two-tick dialog-close delay. The save/navigation
     * part still waits {@link #DIALOG_ACTION_DELAY_TICKS} (inside {@link #capturePose}), so the
     * dialog's {@code afterAction(CLOSE)} cannot swallow the next screen, and the "save the fields
     * first" order is preserved. A failed capture reopens the form with the click-time values,
     * also after the delay for the same reason.
     */
    private ActionButton captureFormButton(String label, String tooltip, boolean feet,
                                           Function<DialogResponseView, Map<String, String>> reader,
                                           CapturedFormAction action, CapturedFormFailure onFailure) {
        return ActionButton.create(text(label, NamedTextColor.WHITE),
                text(tooltip == null ? "" : tooltip, NamedTextColor.GRAY), BUTTON_WIDTH,
                DialogAction.customClick((response, audience) -> {
                    if (!(audience instanceof Player player)) {
                        return;
                    }
                    Map<String, String> raw;
                    try {
                        raw = input(reader.apply(response));
                    } catch (RuntimeException exception) {
                        raw = Map.of();
                    }
                    Map<String, String> values = raw;
                    capturePose(player, feet, pose -> action.accept(player, values, pose),
                            error -> player.getScheduler().execute(plugin,
                                    () -> onFailure.accept(player, values, error), () -> {
                                    }, DIALOG_ACTION_DELAY_TICKS));
                }, CLICK));
    }

    /** The reader-less variant of {@link #captureFormButton} used by list screens. */
    private ActionButton captureButton(String label, String tooltip, boolean feet,
                                       CapturedAction action, BiConsumer<Player, String> onFailure) {
        return ActionButton.create(text(label, NamedTextColor.WHITE),
                text(tooltip == null ? "" : tooltip, NamedTextColor.GRAY), BUTTON_WIDTH,
                DialogAction.customClick((response, audience) -> {
                    if (!(audience instanceof Player player)) {
                        return;
                    }
                    capturePose(player, feet, pose -> action.accept(player, pose),
                            error -> player.getScheduler().execute(plugin,
                                    () -> onFailure.accept(player, error), () -> {
                                    }, DIALOG_ACTION_DELAY_TICKS));
                }, CLICK));
    }

    /**
     * A form action that first persists the fields currently on screen, so a dialog button never
     * silently drops typed input. 删除 and 取消 are the only actions that throw input away.
     */
    @FunctionalInterface
    private interface PendingSave {
        SaveOutcome save(Player player, Map<String, String> input);
    }

    /** What happened to a form's fields when a button asked to persist them. */
    private enum SaveOutcome {
        /** The input differed from the stored value and was written. */
        SAVED,
        /**
         * Nothing was written: either the input reproduced the stored value, or the form was
         * untouched and its displayed text was not even parsed.
         */
        UNCHANGED,
        /** The input was rejected; the form reopened with the error and the action stopped. */
        FAILED
    }

    /** Explicit 保存: write through the same dirty check, then run the form's success path. */
    private void saveExplicitly(Player player, Map<String, String> raw, PendingSave save,
                                Consumer<Player> then) {
        if (save.save(player, input(raw)) != SaveOutcome.FAILED) {
            then.accept(player);
        }
    }

    private ActionButton saveAction(String label, String tooltip,
                                    Function<DialogResponseView, Map<String, String>> reader,
                                    PendingSave save, BiConsumer<Player, SaveOutcome> then) {
        return formButton(label, tooltip, BUTTON_WIDTH, reader, (player, raw) -> {
            SaveOutcome outcome = save.save(player, input(raw));
            if (outcome != SaveOutcome.FAILED) {
                then.accept(player, outcome);
            }
        });
    }

    private static Notice savedNotice(SaveOutcome outcome) {
        return outcome == SaveOutcome.SAVED ? notice("已保存刚才的修改.", false) : null;
    }

    private static Map<String, String> input(Map<String, String> raw) {
        return raw == null ? Map.of() : raw;
    }

    /** True when the dialog still shows exactly the values it was opened with and nothing was staged. */
    private static boolean untouched(Map<String, String> input, Map<String, String> displayed, boolean staged) {
        return !staged && input.equals(displayed);
    }

    /** True when writing {@code step} would reproduce the stored step exactly. */
    private static boolean alreadyStored(EditorSession session, int index, boolean adding, Step step) {
        return !adding && session != null && index >= 0 && index < session.draft.steps().size()
                && step.equals(session.draft.steps().get(index));
    }

    private void onPlayer(Player player, Runnable action) {
        player.getScheduler().execute(plugin, action, () -> {
        }, 0L);
    }

    private void show(Player player, Dialog dialog) {
        if (player.isOnline()) {
            player.showDialog(dialog);
        }
    }

    // ------------------------------------------------------------------ form helpers

    private static Map<String, String> read(DialogResponseView response, String... keys) {
        Map<String, String> values = new LinkedHashMap<>();
        if (response == null) {
            return values;
        }
        for (String key : keys) {
            String value = response.getText(key);
            if (value != null) {
                // Keep the difference between "input absent from the response" and "input empty":
                // absent fields must fall back to their stored/displayed value, never reset it.
                values.put(key, value);
            }
        }
        return values;
    }

    private static Map<String, String> readWithBoolean(DialogResponseView response, String boolKey, String... keys) {
        Map<String, String> values = read(response, keys);
        Boolean value = response == null ? null : response.getBoolean(boolKey);
        if (value != null) {
            // Same rule as read(): an absent checkbox falls back to the stored value instead of
            // turning into "false" and silently flipping the setting.
            values.put(boolKey, String.valueOf(value));
        }
        return values;
    }

    private static void addPoseInputs(List<DialogInput> inputs, Pose pose, Map<String, String> values) {
        inputs.add(textInput("x", "x", value(values, "x", fmt(pose.x())), 32));
        inputs.add(textInput("y", "y", value(values, "y", fmt(pose.y())), 32));
        inputs.add(textInput("z", "z", value(values, "z", fmt(pose.z())), 32));
        inputs.add(textInput("yaw", "yaw (度)", value(values, "yaw", fmt(pose.yaw())), 16));
        inputs.add(textInput("pitch", "pitch (度)", value(values, "pitch", fmt(pose.pitch())), 16));
    }

    private static DialogInput textInput(String key, String label, String initial, int maxLength) {
        return DialogInput.text(key, text(label, NamedTextColor.GRAY))
                .initial(initial == null ? "" : initial)
                .maxLength(maxLength)
                .width(200)
                .build();
    }

    private static DialogInput multilineInput(String key, String label, String initial, int maxLength) {
        return DialogInput.text(key, text(label, NamedTextColor.GRAY))
                .initial(initial == null ? "" : initial)
                .maxLength(maxLength)
                .width(240)
                .multiline(TextDialogInput.MultilineOptions.create(4, 80))
                .build();
    }

    private static DialogInput singleOption(String key, String label,
                                            List<SingleOptionDialogInput.OptionEntry> entries) {
        return DialogInput.singleOption(key, text(label, NamedTextColor.GRAY), entries)
                .width(200)
                .build();
    }

    private static List<SingleOptionDialogInput.OptionEntry> optionEntries(
            String selected, List<String> values,
            Function<String, String> display, Function<String, String> description) {
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        boolean matched = false;
        for (String value : values) {
            boolean initial = value.equals(selected);
            matched |= initial;
            entries.add(optionEntry(value, display.apply(value), description.apply(value), initial));
        }
        if (!matched && !entries.isEmpty()) {
            SingleOptionDialogInput.OptionEntry first = entries.getFirst();
            entries.set(0, SingleOptionDialogInput.OptionEntry.create(first.id(), first.display(), true));
        }
        return entries;
    }

    private static SingleOptionDialogInput.OptionEntry optionEntry(String id, String display,
                                                                   String description, boolean initial) {
        Component label = text(display, NamedTextColor.WHITE);
        if (description != null && !description.isBlank()) {
            label = label.hoverEvent(HoverEvent.showText(text(description, NamedTextColor.GRAY)));
        }
        return SingleOptionDialogInput.OptionEntry.create(id, label, initial);
    }

    private static List<SingleOptionDialogInput.OptionEntry> easingEntries(String selected) {
        return optionEntries(selected, easingValues(), EditorUI::easingDisplay, EditorUI::easingDescription);
    }

    private static List<SingleOptionDialogInput.OptionEntry> stepSwayEntries(String selected) {
        return optionEntries(selected, stepSwayValues(), EditorUI::swayDisplay, EditorUI::swayDescription);
    }

    private static List<SingleOptionDialogInput.OptionEntry> trackSwayEntries(String selected) {
        List<String> values = new ArrayList<>(trackSwayValues());
        if ("custom".equals(selected)) {
            values.add(0, "custom");
        }
        return optionEntries(selected, values, EditorUI::swayDisplay, EditorUI::swayDescription);
    }

    private static List<SingleOptionDialogInput.OptionEntry> effectActionEntries(String selected) {
        return optionEntries(selected, List.of("add", "remove"),
                EditorUI::effectActionDisplay, EditorUI::effectActionDescription);
    }

    private static String easingDisplay(String id) {
        return switch (id) {
            case "linear" -> "线性 · 匀速";
            case "ease-in" -> "缓入 · 慢起";
            case "ease-out" -> "缓出 · 慢停";
            case "ease-in-out" -> "缓入缓出 · 两端慢";
            case "smoothstep" -> "平滑 · 柔和";
            case "smootherstep" -> "更平滑 · 更柔";
            case "sine" -> "正弦 · 正弦曲线";
            default -> id;
        };
    }

    private static String easingDescription(String id) {
        return switch (id) {
            case "linear" -> "线性 (linear)：匀速，没有加减速.";
            case "ease-in" -> "缓入 (ease-in)：慢起快停，逐渐加速.";
            case "ease-out" -> "缓出 (ease-out)：快起慢停，逐渐减速.";
            case "ease-in-out" -> "缓入缓出 (ease-in-out)：两端慢、中间快，最常用.";
            case "smoothstep" -> "平滑 (smoothstep)：比缓入缓出更柔和的 S 曲线.";
            case "smootherstep" -> "更平滑 (smootherstep)：首尾变化更小，适合长镜头.";
            case "sine" -> "正弦 (sine)：接近平滑的正弦加减速.";
            default -> "";
        };
    }

    private static String swayDisplay(String id) {
        return switch (id) {
            case "inherit" -> "继承 · 用轨道默认";
            case "off" -> "关闭 · 不摇摆";
            case "gentle" -> "轻微 · 呼吸感";
            case "natural" -> "自然 · 默认";
            case "strong" -> "强烈 · 明显";
            case "custom" -> "自定义 · 保留振幅";
            default -> id;
        };
    }

    private static String swayDescription(String id) {
        return switch (id) {
            case "inherit" -> "继承 (inherit)：使用轨道默认摇摆.";
            case "off" -> "关闭 (off)：完全不摇摆.";
            case "gentle" -> "轻微 (gentle)：很轻的漂移和点头，不左右转头.";
            case "natural" -> "自然 (natural)：默认幅度，位置漂移 + 轻微俯仰.";
            case "strong" -> "强烈 (strong)：明显的漂移和点头，适合强氛围镜头.";
            case "custom" -> "自定义 (custom)：保留当前自定义振幅.";
            default -> "";
        };
    }

    private static String effectActionDisplay(String id) {
        return switch (id) {
            case "add" -> "添加 · 施加";
            case "remove" -> "移除 · 移除";
            default -> id;
        };
    }

    private static String effectActionDescription(String id) {
        return switch (id) {
            case "add" -> "添加 (add)：施加或刷新药水效果.";
            case "remove" -> "移除 (remove)：移除对应药水效果.";
            default -> "";
        };
    }

    private static String stepTypeDisplay(StepType type) {
        return switch (type) {
            case MOVE -> "移动 (move)";
            case PATH -> "路径 (path)";
            case HOLD -> "停留 (hold)";
            case WAIT -> "等待 (wait)";
            case CUT -> "硬切 (cut)";
            case EFFECT -> "效果 (effect)";
            case TITLE -> "标题 (title)";
        };
    }

    private static List<String> easingValues() {
        return List.of("linear", "ease-in", "ease-out", "ease-in-out", "smoothstep", "smootherstep", "sine");
    }

    private static List<String> stepSwayValues() {
        return List.of("inherit", "off", "gentle", "natural", "strong");
    }

    private static List<String> trackSwayValues() {
        return List.of("off", "gentle", "natural", "strong");
    }

    // ------------------------------------------------------------------ parsing/formatting

    private static Pose parsePose(Map<String, String> values) {
        double x = coordinate(values, "x");
        double y = coordinate(values, "y");
        double z = coordinate(values, "z");
        float yaw = (float) coordinate(values, "yaw");
        float pitch = (float) coordinate(values, "pitch");
        if (pitch < -90.0f || pitch > 90.0f) {
            throw new FormException("pitch 必须在 -90 到 90 之间.");
        }
        return new Pose(x, y, z, yaw, pitch);
    }

    private static double coordinate(Map<String, String> values, String key) {
        String raw = value(values, key, "").trim();
        if (raw.isEmpty()) {
            throw new FormException(key + " 不能为空.");
        }
        try {
            double result = Double.parseDouble(raw);
            if (!Double.isFinite(result)) {
                throw new NumberFormatException();
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new FormException(key + " 必须是数字.");
        }
    }

    private static int integer(Map<String, String> values, String key, int min, int max, String label) {
        String raw = value(values, key, "").trim();
        if (raw.isEmpty()) {
            throw new FormException(label + " 不能为空.");
        }
        int result;
        try {
            result = Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new FormException(label + " 必须是整数.");
        }
        if (result < min || result > max) {
            throw new FormException(label + " 必须在 " + min + " 到 " + max + " 之间.");
        }
        return result;
    }

    private static long longValue(Map<String, String> values, String key, String label) {
        String raw = value(values, key, "").trim();
        if (raw.isEmpty()) {
            throw new FormException(label + " 不能为空.");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw new FormException(label + " 必须是整数.");
        }
    }

    private static SwayPreset parseSway(String raw, boolean allowInherit) {
        SwayPreset sway = SwayPreset.parse(raw);
        if (!allowInherit && sway == SwayPreset.INHERIT) {
            throw new FormException("这里不能选择 inherit.");
        }
        return sway;
    }

    /**
     * Parses the sway field of a response that fell back to the form's defaults for absent keys.
     * Returns {@code stored} when the field is absent entirely instead of resetting to inherit.
     */
    private static SwayPreset swayOrStored(Map<String, String> raw, SwayPreset stored) {
        String value = raw.get("sway");
        return value == null ? stored : parseSway(value, true);
    }

    private static void validateMiniMessage(String text, String label) {
        String error = TrackRules.validateMiniMessage(text, label);
        if (error != null) {
            throw new FormException(error);
        }
    }

    private static String value(Map<String, String> values, String key, String fallback) {
        if (values != null && values.containsKey(key)) {
            String raw = values.get(key);
            return raw == null ? "" : raw;
        }
        return fallback == null ? "" : fallback;
    }

    private static Map<String, String> copy(Map<String, String> values) {
        return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }

    private static Map<String, String> merge(Map<String, String> base, Map<String, String> overlay) {
        Map<String, String> merged = copy(base);
        if (overlay != null) {
            merged.putAll(overlay);
        }
        return merged;
    }

    private static void putPose(Map<String, String> values, Pose pose) {
        values.put("x", fmt(pose.x()));
        values.put("y", fmt(pose.y()));
        values.put("z", fmt(pose.z()));
        values.put("yaw", fmt(pose.yaw()));
        values.put("pitch", fmt(pose.pitch()));
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? "输入无效." : message;
    }

    private static String fmt(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1.0e15) {
            return Long.toString((long) value);
        }
        String text = String.format(Locale.ROOT, "%.6f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "");
            if (text.endsWith(".")) {
                text = text.substring(0, text.length() - 1);
            }
        }
        return text;
    }

    private static String fmt(float value) {
        return fmt((double) value);
    }

    private static String poseShort(Pose pose) {
        return fmt(pose.x()) + ", " + fmt(pose.y()) + ", " + fmt(pose.z());
    }

    private static String poseLabel(Pose pose) {
        return poseShort(pose) + " · yaw " + fmt(pose.yaw()) + "° / pitch " + fmt(pose.pitch()) + "°";
    }

    private static String seconds(int ticks) {
        return String.format(Locale.ROOT, "%.1fs", ticks / 20.0);
    }

    private static String ticksLabel(int ticks) {
        return ticks + "t (" + seconds(ticks) + ")";
    }

    private static String easingName(Easing easing) {
        return easing.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String swayName(SwayPreset sway) {
        return sway.name().toLowerCase(Locale.ROOT);
    }

    private static String swayLabel(SwaySettings sway) {
        String preset = presetNameOrNull(sway);
        return (preset == null ? "custom" : preset) + " · 周期 " + sway.period() + "t · seed " + sway.seed();
    }

    private static String presetLabel(SwaySettings sway) {
        String preset = presetNameOrNull(sway);
        return preset == null ? "custom" : preset;
    }

    private static String presetNameOrNull(SwaySettings sway) {
        for (SwayPreset preset : List.of(SwayPreset.OFF, SwayPreset.GENTLE, SwayPreset.NATURAL, SwayPreset.STRONG)) {
            if (sway.x() == preset.x() && sway.y() == preset.y() && sway.z() == preset.z()
                    && sway.yaw() == preset.yaw() && sway.pitch() == preset.pitch()) {
                return preset.name().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static String shortEffectKey(String key) {
        if (key == null) {
            return "";
        }
        return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }

    private static EffectStep effectStep(String key, EffectAction action, int amplifier, int duration) {
        PotionEffectType type = CameraEffects.resolveEffect(key);
        if (type == null) {
            throw new FormException("未知的药水效果 " + key + ".");
        }
        return new EffectStep(new EffectSpec(type, type.getKey().toString(), action, amplifier, duration));
    }

    private static int pageCount(int size) {
        return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private static int clampPage(int page, int size) {
        return Math.max(0, Math.min(page, pageCount(size) - 1));
    }

    private static int pageOf(int index) {
        return Math.max(0, index) / PAGE_SIZE;
    }

    // ------------------------------------------------------------------ components

    private static List<DialogBody> body(Notice notice, Component... lines) {
        List<Component> all = new ArrayList<>();
        if (notice != null) {
            all.add(text(notice.text(), notice.error() ? NamedTextColor.RED : NamedTextColor.GRAY));
        }
        for (Component line : lines) {
            all.add(line);
        }
        if (all.isEmpty()) {
            return List.of();
        }
        // One body element keeps the lines in a single box. Separate plain_message elements
        // get a full row each, which pushes the action buttons below the fold.
        Component combined = Component.empty();
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) {
                combined = combined.append(Component.newline());
            }
            combined = combined.append(all.get(i));
        }
        return List.of(DialogBody.plainMessage(combined, BODY_WIDTH));
    }

    private static String submenuText(String label) {
        return label + " >";
    }

    private static String back(String label) {
        return "< " + label;
    }

    private static String stepTitle(boolean adding, int index, String type) {
        return (adding ? "添加" : "步骤 " + (index + 1)) + " · " + type;
    }

    private static Component submenu(String label) {
        return submenu(text(label, NamedTextColor.WHITE));
    }

    private static Component submenu(Component label) {
        return label.append(Component.text(" >", NamedTextColor.GRAY));
    }

    private static Component text(String value, NamedTextColor color) {
        return Component.text(value == null ? "" : value, color);
    }

    private record EffectChoice(String id, String display) {
    }

    private record Notice(String text, boolean error) {
    }

    private static Notice notice(String text, boolean error) {
        return new Notice(text, error);
    }

    private enum PoseTarget {
        START,
        EXIT
    }

    private static final class FormException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private FormException(String message) {
            super(message);
        }
    }
}
