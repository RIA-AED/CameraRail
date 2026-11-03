package net.ria.zth.camerarail.editor;

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
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.ria.zth.camerarail.api.CameraTrackEndEvent;
import net.ria.zth.camerarail.config.RailConfig;
import net.ria.zth.camerarail.config.TrackRepository;
import net.ria.zth.camerarail.message.Messages;
import net.ria.zth.camerarail.model.CameraTrack;
import net.ria.zth.camerarail.model.CutStep;
import net.ria.zth.camerarail.model.Easing;
import net.ria.zth.camerarail.model.EffectAction;
import net.ria.zth.camerarail.model.EffectSpec;
import net.ria.zth.camerarail.model.EffectStep;
import net.ria.zth.camerarail.model.ExitMode;
import net.ria.zth.camerarail.model.HoldStep;
import net.ria.zth.camerarail.model.MoveStep;
import net.ria.zth.camerarail.model.PathStep;
import net.ria.zth.camerarail.model.Pose;
import net.ria.zth.camerarail.model.Step;
import net.ria.zth.camerarail.model.StepType;
import net.ria.zth.camerarail.model.SwayPreset;
import net.ria.zth.camerarail.model.SwaySettings;
import net.ria.zth.camerarail.model.TitleStep;
import net.ria.zth.camerarail.model.WaitStep;
import net.ria.zth.camerarail.runtime.TrackTimeline;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

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
import java.util.regex.Pattern;

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
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final MiniMessage STRICT = MiniMessage.builder().strict(true).build();
    private static final ClickCallback.Options CLICK = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofHours(1))
            .build();
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

    public EditorUI(Plugin plugin, EditorManager editors, TrackRepository repository,
                    Supplier<RailConfig> config, Supplier<Messages> messages) {
        this.plugin = plugin;
        this.editors = editors;
        this.repository = repository;
        this.config = config;
        this.messages = messages;
    }

    // ------------------------------------------------------------------ entry points

    public void open(Player player) {
        if (!player.hasPermission("zth.camera.edit")) {
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
        if (!player.hasPermission("zth.camera.edit")) {
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
        if (player.hasPermission("zth.camera.manage")) {
            buttons.add(button(submenu("新建轨道"), "以当前视角创建一条轨道", p -> showCreateTrack(p, null, null)));
        }
        buttons.add(button("刷新", "重新列出内存中的轨道", p -> showTrackList(p, current, null)));

        Component summary = tracks.isEmpty()
                ? text("还没有轨道；点击“新建轨道 >”以当前视角创建.", NamedTextColor.GRAY)
                : text("共 " + tracks.size() + " 条 · 第 " + (current + 1) + "/"
                        + pageCount(tracks.size()) + " 页", NamedTextColor.GRAY);
        show(player, dialog(text("镜头轨道", NamedTextColor.WHITE),
                body(notice, summary), List.of(), buttons, 1));
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
        if (!player.hasPermission("zth.camera.manage")) {
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
        if (!ID_PATTERN.matcher(id).matches()) {
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
        buttons.add(button(submenu("入场效果"), track.entryEffects().size() + " 个",
                p -> showEntryEffects(p, 0, null)));
        if (player.hasPermission("zth.camera.manage")) {
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
        show(player, dialog(text("轨道 · " + track.id(), NamedTextColor.WHITE),
                body(notice, lines.toArray(Component[]::new)), List.of(), buttons, 2));
    }

    private void confirmDeleteTrack(Player player) {
        EditorSession session = editors.session(player);
        if (session == null) {
            return;
        }
        if (!player.hasPermission("zth.camera.manage")) {
            messages.get().send(player, "command.no-permission");
            return;
        }
        Dialog dialog = confirmation(text("删除轨道", NamedTextColor.RED), body(null,
                        text("删除 " + session.draft.id() + "？此操作不可撤销.", NamedTextColor.GRAY)),
                "删除", p -> {
                    editors.delete(p, true);
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
        SwaySettings sway = track.sway();
        String swaySelected = presetNameOrNull(sway);
        if (swaySelected == null) {
            swaySelected = "custom";
        }
        List<DialogInput> inputs = List.of(
                textInput("display", "显示名称", value(values, "display", track.displayName()), 64),
                multilineInput("description", "说明", value(values, "description", track.description()), 512),
                singleOption("sway", "默认摇摆", trackSwayEntries(swaySelected)),
                textInput("period", "摇摆周期 (tick)", value(values, "period", String.valueOf(sway.period())), 16),
                textInput("seed", "摇摆种子", value(values, "seed", String.valueOf(sway.seed())), 32),
                DialogInput.bool("follow", text("跟随区块加载", NamedTextColor.GRAY), track.follow(), "true", "false")
        );
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(formButton("保存", "保存轨道级设置", BUTTON_WIDTH,
                response -> readWithBoolean(response, "follow", "display", "description", "sway", "period", "seed"),
                this::saveTrackSettings));
        buttons.add(button("取消", "不保存并回到轨道概览", p -> showTrack(p, null)));
        show(player, dialog(text("轨道设置 · " + track.id(), NamedTextColor.WHITE),
                body(notice, text("世界 " + track.worldName() + " · 单位 tick · custom 保留当前振幅",
                        NamedTextColor.GRAY)), inputs, buttons, 1));
    }

    private void saveTrackSettings(Player player, Map<String, String> raw) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        try {
            String display = value(raw, "display", "").trim();
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
            String swayRaw = value(raw, "sway", "natural");
            SwaySettings current = session.draft.sway();
            SwaySettings nextSway;
            if ("custom".equals(swayRaw)) {
                nextSway = new SwaySettings(current.x(), current.y(), current.z(), current.yaw(),
                        current.pitch(), period, seed);
            } else {
                nextSway = SwaySettings.ofPreset(parseSway(swayRaw, false), period, seed);
            }
            boolean follow = Boolean.parseBoolean(value(raw, "follow", "true"));
            CameraTrack updated = session.draft.withDisplayName(display)
                    .withDescription(description)
                    .withSway(nextSway)
                    .withFollow(follow);
            String error = editors.applyDraft(player, updated, session.selected);
            if (error != null) {
                showTrackSettings(player, raw, notice(error, true));
                return;
            }
            showTrack(player, notice("轨道设置已保存.", false));
        } catch (RuntimeException exception) {
            showTrackSettings(player, raw, notice(message(exception), true));
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
                body(notice, summary), List.of(), buttons, 1));
    }

    private String stepMeta(CameraTrack track, int index) {
        Step step = track.steps().get(index);
        String time = seconds(TrackTimeline.cumulativeTicksBefore(track, index));
        return switch (step) {
            case MoveStep move -> move.durationTicks() + "t · " + time;
            case PathStep path -> path.points().size() + "点 · " + path.durationTicks() + "t · " + time;
            case HoldStep hold -> hold.durationTicks() + "t · " + time;
            case WaitStep wait -> "等待 · " + time;
            case CutStep cut -> "瞬时 · " + time;
            case EffectStep effect -> "瞬时 · " + time;
            case TitleStep title -> "瞬时 · " + time;
        };
    }

    private Component stepTooltip(CameraTrack track, int index) {
        Step step = track.steps().get(index);
        StringBuilder text = new StringBuilder();
        text.append("步骤 ").append(index + 1).append(" · ").append(stepTypeDisplay(step.type())).append('\n');
        text.append("开始: ").append(ticksLabel(TrackTimeline.cumulativeTicksBefore(track, index))).append('\n');
        if (step instanceof net.ria.zth.camerarail.model.CameraStep camera) {
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
            case CutStep cut -> text.append("目标: ").append(poseShort(cut.to()));
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
                case CUT -> new CutStep(eyePose(player), SwayPreset.INHERIT);
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
        int duration = cut ? 0 : ((MoveStep) step).durationTicks();
        Easing easing = cut ? Easing.EASE_IN_OUT : ((MoveStep) step).easing();
        SwayPreset sway = cut ? ((CutStep) step).sway() : ((MoveStep) step).sway();

        List<DialogInput> inputs = new ArrayList<>();
        addPoseInputs(inputs, pose, values);
        if (!cut) {
            inputs.add(textInput("duration", "时长 (tick)", value(values, "duration", String.valueOf(duration)), 16));
            inputs.add(singleOption("easing", "缓动", easingEntries(value(values, "easing", easingName(easing)))));
        }
        inputs.add(singleOption("sway", "摇摆", stepSwayEntries(value(values, "sway", swayName(sway)))));

        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "x", "y", "z", "yaw", "pitch", "duration", "easing", "sway");
        buttons.add(formButton("保存", "保存这一步", BUTTON_WIDTH, reader,
                (p, raw) -> saveMove(cut, p, index, adding, step, raw, false)));
        buttons.add(formButton("保存并返回", "保存后回到步骤列表", BUTTON_WIDTH, reader,
                (p, raw) -> saveMove(cut, p, index, adding, step, raw, true)));
        buttons.add(button("取当前视角", "关闭窗口并立即把当前视角填入", p -> capturePose(p, false, captured -> {
            Map<String, String> next = copy(values);
            putPose(next, captured);
            showMoveForm(cut, p, index, adding, step, next, notice("已取当前视角.", false));
        })));
        buttons.add(button("传送到此", "关闭窗口并传送到已保存的目标位置；/zc edit 回来",
                p -> teleportToPose(p, pose, false)));
        addEditActions(player, buttons, index, adding);

        show(player, dialog(text(stepTitle(adding, index, cut ? "硬切" : "移动"), NamedTextColor.WHITE),
                body(notice), inputs, buttons, 2));
    }

    private void saveMove(boolean cut, Player player, int index, boolean adding, Step original,
                          Map<String, String> raw, boolean returnToSteps) {
        try {
            Pose pose = parsePose(raw);
            Step updated;
            if (cut) {
                updated = new CutStep(pose, parseSway(raw.get("sway"), true));
            } else {
                int duration = integer(raw, "duration", 1, config.get().maxTrackTicks(), "时长");
                Easing easing = Easing.parse(value(raw, "easing", "ease-in-out"));
                updated = new MoveStep(pose, duration, easing, parseSway(raw.get("sway"), true));
            }
            String error = commitStep(player, index, adding, updated);
            if (error != null) {
                showMoveForm(cut, player, index, adding, original, raw, notice(error, true));
                return;
            }
            afterStepSaved(player, index, adding, returnToSteps);
        } catch (RuntimeException exception) {
            showMoveForm(cut, player, index, adding, original, raw, notice(message(exception), true));
        }
    }

    private void showHoldForm(Player player, int index, boolean adding, HoldStep step,
                              Map<String, String> values, Notice notice) {
        List<DialogInput> inputs = List.of(
                textInput("duration", "时长 (tick)", value(values, "duration", String.valueOf(step.durationTicks())), 16),
                singleOption("sway", "摇摆", stepSwayEntries(value(values, "sway", swayName(step.sway()))))
        );
        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "duration", "sway");
        buttons.add(formButton("保存", "保存这一步", BUTTON_WIDTH, reader,
                (p, raw) -> saveHold(p, index, adding, step, raw, false)));
        buttons.add(formButton("保存并返回", "保存后回到步骤列表", BUTTON_WIDTH, reader,
                (p, raw) -> saveHold(p, index, adding, step, raw, true)));
        addEditActions(player, buttons, index, adding);
        show(player, dialog(text(stepTitle(adding, index, "停留"), NamedTextColor.WHITE),
                body(notice), inputs, buttons, 2));
    }

    private void saveHold(Player player, int index, boolean adding, HoldStep original,
                          Map<String, String> raw, boolean returnToSteps) {
        try {
            int duration = integer(raw, "duration", 1, config.get().maxTrackTicks(), "时长");
            HoldStep updated = new HoldStep(duration, parseSway(raw.get("sway"), true));
            String error = commitStep(player, index, adding, updated);
            if (error != null) {
                showHoldForm(player, index, adding, original, raw, notice(error, true));
                return;
            }
            afterStepSaved(player, index, adding, returnToSteps);
        } catch (RuntimeException exception) {
            showHoldForm(player, index, adding, original, raw, notice(message(exception), true));
        }
    }

    private void showWaitForm(Player player, int index, boolean adding, WaitStep step,
                              Map<String, String> values, Notice notice) {
        List<DialogInput> inputs = List.of(
                textInput("signal", "信号", value(values, "signal", step.signal()), 80),
                textInput("timeout", "超时 (tick，0 = 永不)", value(values, "timeout", String.valueOf(step.timeoutTicks())), 16),
                singleOption("sway", "摇摆", stepSwayEntries(value(values, "sway", swayName(step.sway()))))
        );
        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "signal", "timeout", "sway");
        buttons.add(formButton("保存", "保存这一步", BUTTON_WIDTH, reader,
                (p, raw) -> saveWait(p, index, adding, step, raw, false)));
        buttons.add(formButton("保存并返回", "保存后回到步骤列表", BUTTON_WIDTH, reader,
                (p, raw) -> saveWait(p, index, adding, step, raw, true)));
        buttons.add(formButton(submenuText("已有信号"), "从所有轨道已有的 wait 信号中选择",
                BUTTON_WIDTH, reader,
                (p, raw) -> showSignalPicker(p, index, adding, step, merge(values, raw), 0, null)));
        addEditActions(player, buttons, index, adding);
        show(player, dialog(text(stepTitle(adding, index, "等待"), NamedTextColor.WHITE),
                body(notice), inputs, buttons, 2));
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
                body(notice, text(summary, NamedTextColor.GRAY)), List.of(), buttons, 1));
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
        String summary = "常用药水效果 · 附身镜头下仅 nausea/darkness/night_vision 有画面 · "
                + COMMON_EFFECTS.size() + " 个"
                + (COMMON_EFFECTS.size() > PAGE_SIZE
                ? " · 第 " + (current + 1) + "/" + pageCount(COMMON_EFFECTS.size()) + " 页" : "");
        show(player, dialog(text("常用效果", NamedTextColor.WHITE),
                body(null, text(summary, NamedTextColor.GRAY)), List.of(), buttons, 1));
    }

    private void saveWait(Player player, int index, boolean adding, WaitStep original,
                          Map<String, String> raw, boolean returnToSteps) {
        try {
            String signal = value(raw, "signal", "").trim();
            if (signal.isEmpty()) {
                throw new FormException("信号不能为空.");
            }
            if (signal.length() > 80) {
                throw new FormException("信号最长 80 个字符.");
            }
            int timeout = integer(raw, "timeout", 0, config.get().maxTrackTicks(), "超时");
            WaitStep updated = new WaitStep(signal, timeout, parseSway(raw.get("sway"), true));
            String error = commitStep(player, index, adding, updated);
            if (error != null) {
                showWaitForm(player, index, adding, original, raw, notice(error, true));
                return;
            }
            afterStepSaved(player, index, adding, returnToSteps);
        } catch (RuntimeException exception) {
            showWaitForm(player, index, adding, original, raw, notice(message(exception), true));
        }
    }

    private void showPathForm(Player player, int index, boolean adding, PathStep step,
                              Map<String, String> values, Notice notice) {
        List<DialogInput> inputs = List.of(
                textInput("duration", "时长 (tick)", value(values, "duration", String.valueOf(step.durationTicks())), 16),
                singleOption("easing", "缓动", easingEntries(value(values, "easing", easingName(step.easing())))),
                singleOption("sway", "摇摆", stepSwayEntries(value(values, "sway", swayName(step.sway()))))
        );
        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "duration", "easing", "sway");
        buttons.add(formButton("保存", "保存时长、缓动和摇摆", BUTTON_WIDTH, reader,
                (p, raw) -> savePath(false, p, index, adding, step, raw, false)));
        buttons.add(formButton("保存并返回", "保存后回到步骤列表", BUTTON_WIDTH, reader,
                (p, raw) -> savePath(false, p, index, adding, step, raw, true)));
        buttons.add(formButton(submenuText("路径点"), step.points().size() + " 个点；先保存基础字段，再编辑路径点",
                BUTTON_WIDTH, reader,
                (p, raw) -> savePath(true, p, index, adding, step, raw, false)));
        addEditActions(player, buttons, index, adding);
        show(player, dialog(text(stepTitle(adding, index, "路径"), NamedTextColor.WHITE), body(notice),
                inputs, buttons, 2));
    }

    private void savePath(boolean editPoints, Player player, int index, boolean adding, PathStep original,
                          Map<String, String> raw, boolean returnToSteps) {
        try {
            int duration = integer(raw, "duration", 1, config.get().maxTrackTicks(), "时长");
            Easing easing = Easing.parse(value(raw, "easing", "ease-in-out"));
            SwayPreset sway = parseSway(raw.get("sway"), true);
            PathStep updated = new PathStep(original.points(), duration, easing, sway);
            String error = commitStep(player, index, adding, updated);
            if (error != null) {
                showPathForm(player, index, adding, original, raw, notice(error, true));
                return;
            }
            if (editPoints) {
                EditorSession session = editors.session(player);
                if (session != null) {
                    session.selected = adding ? session.selected : index;
                }
                showPathPoints(player, 0, null);
            } else {
                afterStepSaved(player, index, adding, returnToSteps);
            }
        } catch (RuntimeException exception) {
            showPathForm(player, index, adding, original, raw, notice(message(exception), true));
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
        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "action", "effect", "amplifier", "duration");
        buttons.add(formButton("保存", "保存这一步", BUTTON_WIDTH, reader,
                (p, raw) -> saveEffectStep(p, index, adding, step, raw, false)));
        buttons.add(formButton("保存并返回", "保存后回到步骤列表", BUTTON_WIDTH, reader,
                (p, raw) -> saveEffectStep(p, index, adding, step, raw, true)));
        buttons.add(formButton(submenuText("常用效果"), "从常用药水效果中选择",
                BUTTON_WIDTH, reader,
                (p, raw) -> showEffectPicker(p, 0, merge(values, raw),
                        (q, next) -> showEffectStepForm(q, index, adding, step, next,
                                notice("已选择效果.", false)),
                        (q, next) -> showEffectStepForm(q, index, adding, step, next, null))));
        addEditActions(player, buttons, index, adding);
        show(player, dialog(text(stepTitle(adding, index, "效果"), NamedTextColor.WHITE), body(notice),
                inputs, buttons, 1));
    }

    private void saveEffectStep(Player player, int index, boolean adding, EffectStep original,
                                Map<String, String> raw, boolean returnToSteps) {
        try {
            EffectAction action = EffectAction.parse(value(raw, "action", "add"));
            String key = value(raw, "effect", "").trim();
            PotionEffectType type = EditorManager.resolveEffect(key);
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
            String error = commitStep(player, index, adding, updated);
            if (error != null) {
                showEffectStepForm(player, index, adding, original, raw, notice(error, true));
                return;
            }
            afterStepSaved(player, index, adding, returnToSteps);
        } catch (RuntimeException exception) {
            showEffectStepForm(player, index, adding, original, raw, notice(message(exception), true));
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
        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "title", "subtitle", "fade_in", "stay", "fade_out");
        buttons.add(formButton("保存", "保存这一步", BUTTON_WIDTH, reader,
                (p, raw) -> saveTitleStep(p, index, adding, step, raw, false)));
        buttons.add(formButton("保存并返回", "保存后回到步骤列表", BUTTON_WIDTH, reader,
                (p, raw) -> saveTitleStep(p, index, adding, step, raw, true)));
        addEditActions(player, buttons, index, adding);
        show(player, dialog(text(stepTitle(adding, index, "标题"), NamedTextColor.WHITE), body(notice,
                text("MiniMessage；至少填写标题或副标题.", NamedTextColor.GRAY)),
                inputs, buttons, 1));
    }

    private void saveTitleStep(Player player, int index, boolean adding, TitleStep original,
                               Map<String, String> raw, boolean returnToSteps) {
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
            String error = commitStep(player, index, adding, updated);
            if (error != null) {
                showTitleStepForm(player, index, adding, original, raw, notice(error, true));
                return;
            }
            afterStepSaved(player, index, adding, returnToSteps);
        } catch (RuntimeException exception) {
            showTitleStepForm(player, index, adding, original, raw, notice(message(exception), true));
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

    private void addEditActions(Player player, List<ActionButton> buttons, int index, boolean adding) {
        EditorSession session = editors.session(player);
        int stepCount = session == null ? 0 : session.draft.steps().size();
        if (adding) {
            buttons.add(button("取消", "不添加并回到步骤列表", p -> {
                EditorSession current = editors.session(p);
                showSteps(p, current == null || current.selected < 0 ? 0 : pageOf(current.selected), null);
            }));
            return;
        }
        buttons.add(button("预览此步", "只播放选中的这一步；之前的效果不会回放", p -> preview(p, index, true)));
        if (index > 0) {
            buttons.add(button("<- 上一步", "不保存当前输入，按已保存内容切到上一步",
                    p -> showEditStepForm(p, index - 1, null, null)));
        }
        if (index + 1 < stepCount) {
            buttons.add(button("下一步 ->", "不保存当前输入，按已保存内容切到下一步",
                    p -> showEditStepForm(p, index + 1, null, null)));
        }
        buttons.add(button("上移", "按已保存内容把这一步向前移动", p -> moveStep(p, index, -1)));
        buttons.add(button("下移", "按已保存内容把这一步向后移动", p -> moveStep(p, index, 1)));
        buttons.add(button("复制", "按已保存内容复制这一步并插入到后面", p -> duplicateStep(p, index)));
        buttons.add(button("删除", "按已保存内容删除这一步（可撤销）", p -> deleteStep(p, index)));
        buttons.add(button("取消", "不保存并回到步骤列表", p -> showSteps(p, pageOf(index), null)));
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
        buttons.add(button("追加当前视角", "关闭窗口并立即把当前视角追加为路径点", p ->
                capturePose(p, false, pose -> appendPathPoint(p, pose))));
        buttons.add(button(back("返回"), "回到路径步骤表单", p -> showEditStepForm(p, session.selected, null, null)));

        show(player, dialog(text("路径点 · 步骤 " + (session.selected + 1), NamedTextColor.WHITE),
                body(notice, text(path.points().size() + " 个点 · " + ticksLabel(path.durationTicks())
                        + " · " + easingName(path.easing()) + " · 第 " + (current + 1) + "/"
                        + pageCount(path.points().size()) + " 页", NamedTextColor.GRAY)),
                List.of(), buttons, 1));
    }

    private void appendPathPoint(Player player, Pose pose) {
        EditorSession session = editors.session(player);
        PathStep path = selectedPath(session);
        if (session == null || path == null) {
            showSteps(player, 0, notice("当前选中的不是路径步骤.", true));
            return;
        }
        if (path.points().size() >= config.get().maxPathPoints()) {
            showPathPoints(player, pageOf(path.points().size() - 1),
                    notice("路径点上限 " + config.get().maxPathPoints() + ".", true));
            return;
        }
        List<Pose> points = new ArrayList<>(path.points());
        points.add(pose);
        PathStep updated = new PathStep(points, path.durationTicks(), path.easing(), path.sway());
        String error = commitPath(player, updated);
        if (error != null) {
            showPathPoints(player, pageOf(points.size() - 1), notice(error, true));
            return;
        }
        showPathPoints(player, pageOf(points.size() - 1), notice("已追加路径点.", false));
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
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(formButton("保存", "保存这个路径点", BUTTON_WIDTH,
                response -> read(response, "x", "y", "z", "yaw", "pitch"),
                (p, raw) -> savePathPoint(p, pointIndex, pose, raw)));
        buttons.add(button("取当前视角", "关闭窗口并立即把当前视角填入", p -> capturePose(p, false, captured -> {
            Map<String, String> next = copy(values);
            putPose(next, captured);
            showPathPointForm(p, pointIndex, next, notice("已取当前视角.", false));
        })));
        buttons.add(button("传送到此", "关闭窗口并传送到这个路径点；/zc edit 回来",
                p -> teleportToPose(p, pose, false)));
        buttons.add(button("插入当前视角", "关闭窗口并立即在这个点之后插入一个点", p ->
                capturePose(p, false, captured -> insertPathPoint(p, pointIndex, captured))));
        buttons.add(button("上移", "与前一个路径点交换", p -> movePathPoint(p, pointIndex, -1)));
        buttons.add(button("下移", "与后一个路径点交换", p -> movePathPoint(p, pointIndex, 1)));
        buttons.add(button("删除", "删除这个路径点（至少保留一个）", p -> deletePathPoint(p, pointIndex)));
        buttons.add(button("取消", "不保存并回到路径点列表", p -> showPathPoints(p, pageOf(pointIndex), null)));
        show(player, dialog(text("路径点 " + (pointIndex + 1), NamedTextColor.WHITE), body(notice),
                inputs, buttons, 2));
    }

    private void savePathPoint(Player player, int pointIndex, Pose original, Map<String, String> raw) {
        try {
            PathStep path = selectedPath(editors.session(player));
            if (path == null) {
                throw new FormException("当前选中的不是路径步骤.");
            }
            Pose pose = parsePose(raw);
            List<Pose> points = new ArrayList<>(path.points());
            points.set(pointIndex, pose);
            PathStep updated = new PathStep(points, path.durationTicks(), path.easing(), path.sway());
            String error = commitPath(player, updated);
            if (error != null) {
                showPathPointForm(player, pointIndex, raw, notice(error, true));
                return;
            }
            showPathPointForm(player, pointIndex, null, notice("已保存路径点.", false));
        } catch (RuntimeException exception) {
            showPathPointForm(player, pointIndex, raw, notice(message(exception), true));
        }
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
        PathStep path = selectedPath(editors.session(player));
        if (path == null) {
            return;
        }
        if (path.points().size() >= config.get().maxPathPoints()) {
            showPathPointForm(player, pointIndex, null,
                    notice("路径点上限 " + config.get().maxPathPoints() + ".", true));
            return;
        }
        List<Pose> points = new ArrayList<>(path.points());
        points.add(pointIndex + 1, pose);
        PathStep updated = new PathStep(points, path.durationTicks(), path.easing(), path.sway());
        String error = commitPath(player, updated);
        if (error != null) {
            showPathPointForm(player, pointIndex, null, notice(error, true));
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
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(formButton("保存", "保存这个位置", BUTTON_WIDTH,
                response -> read(response, "x", "y", "z", "yaw", "pitch"),
                (p, raw) -> savePose(p, target, raw)));
        buttons.add(button(target == PoseTarget.EXIT ? "取当前脚下位置" : "取当前视角",
                target == PoseTarget.EXIT
                        ? "关闭窗口并立即取当前脚下位置"
                        : "关闭窗口并立即取当前视角", p -> capturePose(p, target == PoseTarget.EXIT, captured -> {
            Map<String, String> next = copy(values);
            putPose(next, captured);
            showPoseForm(p, target, next, notice("已取当前位置.", false));
        })));
        if (target == PoseTarget.START) {
            buttons.add(button("传送到此", "关闭窗口并传送到已保存的起点位置；/zc edit 回来",
                    p -> teleportToPose(p, track.start(), false)));
        } else if (track.exit() != null) {
            buttons.add(button("传送到此", "关闭窗口并传送到已保存的退出位置；/zc edit 回来",
                    p -> teleportToPose(p, track.exit(), true)));
        }
        buttons.add(button("取消", target == PoseTarget.EXIT
                        ? "不保存并回到退出点设置"
                        : "不保存并回到轨道概览",
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
                body(notice, text(hintText, NamedTextColor.GRAY)), inputs, buttons, 1));
    }

    private void savePose(Player player, PoseTarget target, Map<String, String> raw) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        try {
            Pose pose = parsePose(raw);
            CameraTrack updated = target == PoseTarget.START
                    ? session.draft.withStart(pose)
                    : session.draft.withExit(pose);
            String error = editors.applyDraft(player, updated, session.selected);
            if (error != null) {
                showPoseForm(player, target, raw, notice(error, true));
                return;
            }
            if (target == PoseTarget.START) {
                showTrack(player, notice("起点已更新.", false));
            } else {
                showExitMode(player, notice("自定义退出点已保存.", false));
            }
        } catch (RuntimeException exception) {
            showPoseForm(player, target, raw, notice(message(exception), true));
        }
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
        show(player, dialog(text("入场效果 · " + session.draft.id(), NamedTextColor.WHITE),
                body(notice, text(effects.size() + " 个 · 第 " + (current + 1) + "/"
                        + pageCount(effects.size()) + " 页 · 进入镜头前施加", NamedTextColor.GRAY)),
                List.of(), buttons, 1));
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
        List<DialogInput> inputs = List.of(
                textInput("effect", "效果 id", value(values, "effect",
                        existing == null ? "darkness" : shortEffectKey(existing.key())), 80),
                textInput("amplifier", "等级 (0 = I 级)", value(values, "amplifier",
                        existing == null ? "0" : String.valueOf(existing.amplifier())), 8),
                textInput("duration", "时长 (tick，0 = 持续)", value(values, "duration",
                        existing == null ? "0" : String.valueOf(existing.durationTicks())), 16)
        );
        List<ActionButton> buttons = new ArrayList<>();
        Function<DialogResponseView, Map<String, String>> reader =
                response -> read(response, "effect", "amplifier", "duration");
        buttons.add(formButton("保存", "保存这个入场效果", BUTTON_WIDTH, reader,
                (p, raw) -> saveEntryEffect(p, index, raw)));
        buttons.add(formButton(submenuText("常用效果"), "从常用药水效果中选择",
                BUTTON_WIDTH, reader,
                (p, raw) -> showEffectPicker(p, 0, merge(values, raw),
                        (q, next) -> showEntryEffectForm(q, index, next, notice("已选择效果.", false)),
                        (q, next) -> showEntryEffectForm(q, index, next, null))));
        if (existing != null) {
            buttons.add(button("删除", "删除这个入场效果", p -> deleteEntryEffect(p, index)));
        }
        buttons.add(button("取消", "不保存并回到入场效果列表", p -> showEntryEffects(p, 0, null)));
        show(player, dialog(text(existing == null ? "添加入场效果" : "入场效果 " + (index + 1), NamedTextColor.WHITE),
                body(notice, text("只支持添加；时长 0 = 持续.", NamedTextColor.GRAY)),
                inputs, buttons, 1));
    }

    private void saveEntryEffect(Player player, int index, Map<String, String> raw) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        try {
            String key = value(raw, "effect", "").trim();
            PotionEffectType type = EditorManager.resolveEffect(key);
            if (type == null) {
                throw new FormException("未知的药水效果 " + key + ".");
            }
            int amplifier = integer(raw, "amplifier", 0, 255, "等级");
            int duration = integer(raw, "duration", 0, config.get().maxTrackTicks(), "时长");
            EffectSpec spec = new EffectSpec(type, type.getKey().toString(), EffectAction.ADD, amplifier, duration);
            List<EffectSpec> effects = new ArrayList<>(session.draft.entryEffects());
            if (index >= 0 && index < effects.size()) {
                effects.set(index, spec);
            } else {
                effects.add(spec);
            }
            String error = editors.applyDraft(player, session.draft.withEntryEffects(effects), session.selected);
            if (error != null) {
                showEntryEffectForm(player, index, raw, notice(error, true));
                return;
            }
            showEntryEffects(player, pageOf(effects.size() - 1), notice("已保存入场效果.", false));
        } catch (RuntimeException exception) {
            showEntryEffectForm(player, index, raw, notice(message(exception), true));
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
        Location target;
        if (feet) {
            target = pose.toLocation(world);
        } else {
            // Camera poses are eye positions; the player must stand at pose.y - eyeHeight.
            double eyeHeight = player.getEyeHeight();
            target = new Location(world, pose.x(), pose.y() - eyeHeight, pose.z(), pose.yaw(), pose.pitch());
        }
        player.closeDialog();
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) -> {
            player.getScheduler().execute(plugin, () -> {
                if (error != null || !Boolean.TRUE.equals(success)) {
                    player.sendActionBar(Component.text("传送失败.", NamedTextColor.RED));
                } else {
                    player.sendActionBar(Component.text("已传送到此；/zc edit 回到编辑器.", NamedTextColor.GRAY));
                }
            }, () -> {
            }, 0L);
        });
    }

    private void capturePose(Player player, boolean feet, Consumer<Pose> then) {
        EditorSession session = editors.session(player);
        if (session == null) {
            showTrackList(player, 0, null);
            return;
        }
        if (session.previewing) {
            showTrack(player, notice("预览中无法取景；请先停止预览.", true));
            return;
        }
        if (!player.getWorld().getName().equals(session.worldName)) {
            showTrack(player, notice("请先前往轨道所在世界 " + session.worldName + ".", true));
            return;
        }
        Pose captured;
        try {
            captured = feet ? feetPose(player) : eyePose(player);
        } catch (RuntimeException exception) {
            showTrack(player, notice(message(exception), true));
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
            if (current == null || !player.getWorld().getName().equals(current.worldName)) {
                return;
            }
            try {
                then.accept(captured);
            } catch (RuntimeException exception) {
                showTrack(player, notice(message(exception), true));
            }
        }, () -> {
        }, 1L);
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
                        .exitAction(closeButton())
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
        return button("关闭", "关闭窗口", p -> p.closeDialog());
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
            player.getScheduler().execute(plugin, () -> action.accept(player), () -> {
            }, 0L);
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
                    }, 0L);
                }, CLICK));
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
            values.put(key, value == null ? "" : value);
        }
        return values;
    }

    private static Map<String, String> readWithBoolean(DialogResponseView response, String boolKey, String... keys) {
        Map<String, String> values = read(response, keys);
        Boolean value = response == null ? null : response.getBoolean(boolKey);
        values.put(boolKey, String.valueOf(value != null && value));
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

    private static void validateMiniMessage(String text, String label) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            STRICT.deserialize(text);
        } catch (RuntimeException exception) {
            throw new FormException(label + "不是合法的 MiniMessage.");
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
        PotionEffectType type = EditorManager.resolveEffect(key);
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
