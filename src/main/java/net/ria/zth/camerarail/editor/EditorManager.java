package net.ria.zth.camerarail.editor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.ria.zth.camerarail.api.CameraTrackEndEvent;
import net.ria.zth.camerarail.config.RailConfig;
import net.ria.zth.camerarail.config.TrackParser;
import net.ria.zth.camerarail.config.TrackRepository;
import net.ria.zth.camerarail.message.Messages;
import net.ria.zth.camerarail.model.CameraStep;
import net.ria.zth.camerarail.model.CameraTrack;
import net.ria.zth.camerarail.model.CutStep;
import net.ria.zth.camerarail.model.Easing;
import net.ria.zth.camerarail.model.EffectAction;
import net.ria.zth.camerarail.model.EffectSpec;
import net.ria.zth.camerarail.model.EffectStep;
import net.ria.zth.camerarail.model.HoldStep;
import net.ria.zth.camerarail.model.MoveStep;
import net.ria.zth.camerarail.model.PathStep;
import net.ria.zth.camerarail.model.Pose;
import net.ria.zth.camerarail.model.Step;
import net.ria.zth.camerarail.model.SwayPreset;
import net.ria.zth.camerarail.model.SwaySettings;
import net.ria.zth.camerarail.model.TitleStep;
import net.ria.zth.camerarail.model.WaitStep;
import net.ria.zth.camerarail.runtime.CameraEngine;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Command-driven in-game editor. Every mutation is saved immediately through the
 * repository's single-writer queue and can be undone.
 */
public final class EditorManager implements Listener {
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final MiniMessage STRICT = MiniMessage.builder().strict(true).build();

    private final Plugin plugin;
    private final TrackRepository repository;
    private final CameraEngine engine;
    private final Supplier<RailConfig> config;
    private final Supplier<Messages> messages;
    private final Map<UUID, EditorSession> editors = new ConcurrentHashMap<>();

    public EditorManager(Plugin plugin, TrackRepository repository, CameraEngine engine,
                         Supplier<RailConfig> config, Supplier<Messages> messages) {
        this.plugin = plugin;
        this.repository = repository;
        this.engine = engine;
        this.config = config;
        this.messages = messages;
    }

    public boolean hasEditors() {
        return !editors.isEmpty();
    }

    public boolean isEditing(Player player) {
        return editors.containsKey(player.getUniqueId());
    }

    public boolean isEditingTrack(String trackId) {
        return editors.values().stream().anyMatch(session -> session.draft.id().equals(trackId));
    }

    public void open(Player player, String rawId) {
        if (editors.containsKey(player.getUniqueId())) {
            messages.get().send(player, "editor.already", "track", editors.get(player.getUniqueId()).draft.id());
            return;
        }
        CameraTrack track = repository.get(rawId);
        if (track == null) {
            messages.get().send(player, "track.not-found", "track", rawId);
            return;
        }
        if (editors.values().stream().anyMatch(session -> session.draft.id().equals(track.id()))) {
            messages.get().send(player, "manage.edit-busy", "track", track.id());
            return;
        }
        if (!track.worldName().equals(player.getWorld().getName())) {
            messages.get().send(player, "editor.world-mismatch", "world", track.worldName());
            return;
        }
        EditorSession session = new EditorSession(player.getUniqueId(), track);
        editors.put(player.getUniqueId(), session);
        messages.get().send(player, "editor.opened", "track", track.id(), "world", track.worldName());
        status(player);
    }

    public void create(Player player, String rawId, String displayName) {
        if (editors.containsKey(player.getUniqueId())) {
            messages.get().send(player, "editor.already", "track", editors.get(player.getUniqueId()).draft.id());
            return;
        }
        String id = rawId.toLowerCase(Locale.ROOT);
        if (!ID_PATTERN.matcher(id).matches()) {
            messages.get().send(player, "manage.invalid-id");
            return;
        }
        if (repository.get(id) != null) {
            messages.get().send(player, "manage.exists", "track", id);
            return;
        }
        if (editors.values().stream().anyMatch(session -> session.draft.id().equals(id))) {
            messages.get().send(player, "manage.edit-busy", "track", id);
            return;
        }
        Pose start = eyePose(player);
        if (start == null) {
            return;
        }
        RailConfig cfg = config.get();
        SwaySettings sway = SwaySettings.ofPreset(cfg.defaultSwayPreset(), cfg.defaultSwayPeriod(),
                TrackParser.defaultSeed(id));
        CameraTrack track = new CameraTrack(id, displayName == null || displayName.isBlank() ? id : displayName,
                "", player.getWorld().getName(), start, null, sway, cfg.followCamera(), List.of(),
                List.of(new HoldStep(20, SwayPreset.INHERIT)));
        repository.upsert(track);
        EditorSession session = new EditorSession(player.getUniqueId(), track);
        editors.put(player.getUniqueId(), session);
        persist(player, repository.saveAsync());
        messages.get().send(player, "editor.created", "track", id);
        status(player);
    }

    public void close(Player player) {
        EditorSession session = editors.remove(player.getUniqueId());
        if (session == null) {
            messages.get().send(player, "editor.not-editing");
            return;
        }
        if (session.previewing && engine.isPlaying(player)) {
            engine.stop(player);
        }
        messages.get().send(player, "editor.closed");
    }

    public void handleQuit(Player player) {
        EditorSession session = editors.remove(player.getUniqueId());
        if (session != null && session.previewing) {
            engine.stop(player);
        }
    }

    public void status(Player player) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null) {
            messages.get().send(player, "editor.not-editing");
            return;
        }
        String selected = session.selected < 0 ? "未选择" : String.valueOf(session.selected + 1);
        String state = session.previewing ? "预览中" : session.saveFailed ? "保存失败" : "自动保存";
        player.sendMessage(messages.get().prefixed("editor.status",
                "track", session.draft.id(),
                "selected", selected,
                "count", String.valueOf(session.draft.steps().size()),
                "duration", String.valueOf(session.draft.timedDurationTicks()),
                "state", state));
        Step step = selectedStep(session);
        if (step != null) {
            player.sendMessage(messages.get().prefixed("editor.selected",
                    "index", String.valueOf(session.selected + 1), "summary", step.describe()));
        }
    }

    public void listSteps(Player player) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null) {
            messages.get().send(player, "editor.not-editing");
            return;
        }
        player.sendMessage(messages.get().prefixed("track.info-header",
                "id", session.draft.id(), "name", session.draft.displayName()));
        if (session.draft.steps().isEmpty()) {
            messages.get().send(player, "track.info-no-steps");
            return;
        }
        for (int i = 0; i < session.draft.steps().size(); i++) {
            int index = i;
            Step step = session.draft.steps().get(i);
            Component line = Component.text("· ", NamedTextColor.DARK_GRAY)
                    .append(Component.text((i + 1) + " ", NamedTextColor.WHITE))
                    .append(Component.text(step.type().configName(), NamedTextColor.WHITE))
                    .append(Component.text(" · ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(step.describe(), NamedTextColor.GRAY))
                    .clickEvent(ClickEvent.runCommand("/zc edit select " + (i + 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("点击选中步骤 " + (i + 1), NamedTextColor.GRAY)));
            player.sendMessage(line);
        }
    }

    public void setStart(Player player) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        Pose pose = eyePose(player);
        if (pose == null) {
            return;
        }
        session.remember();
        session.draft = session.draft.withStart(pose);
        persisted(player, session);
        messages.get().send(player, "editor.start-set");
    }

    public void setExit(Player player) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        Pose pose = feetPose(player);
        if (pose == null) {
            return;
        }
        session.remember();
        session.draft = session.draft.withExit(pose);
        persisted(player, session);
        messages.get().send(player, "editor.exit-set");
    }

    public void addMove(Player player, int duration) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        Pose pose = eyePose(player);
        if (pose == null) {
            return;
        }
        int safeDuration = Math.max(1, Math.min(config.get().maxTrackTicks(), duration));
        append(player, session, new MoveStep(pose, safeDuration, config.get().defaultEasing(),
                SwayPreset.INHERIT), "editor.added");
    }

    public void addCut(Player player) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        Pose pose = eyePose(player);
        if (pose == null) {
            return;
        }
        append(player, session, new CutStep(pose, SwayPreset.INHERIT), "editor.added");
    }

    public void addPath(Player player) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        Pose pose = eyePose(player);
        if (pose == null) {
            return;
        }
        append(player, session, new PathStep(List.of(pose), config.get().defaultMoveDuration(),
                config.get().defaultEasing(), SwayPreset.INHERIT), "editor.added");
        messages.get().send(player, "editor.path-created");
    }

    public void addPathPoint(Player player) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        PathStep path = selectedPath(session);
        if (path == null) {
            messages.get().send(player, "editor.need-path");
            return;
        }
        if (path.points().size() >= config.get().maxPathPoints()) {
            messages.get().send(player, "editor.invalid-value", "value", "路径点数量已达上限");
            return;
        }
        Pose pose = eyePose(player);
        if (pose == null) {
            return;
        }
        List<Pose> points = new ArrayList<>(path.points());
        points.add(pose);
        replaceSelected(player, session, new PathStep(points, path.durationTicks(), path.easing(), path.sway()),
                false);
        messages.get().send(player, "editor.path-point-added", "count", String.valueOf(points.size()));
    }

    public void removePathPoint(Player player) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        PathStep path = selectedPath(session);
        if (path == null) {
            messages.get().send(player, "editor.need-path");
            return;
        }
        if (path.points().size() <= 1) {
            messages.get().send(player, "editor.path-point-min");
            return;
        }
        List<Pose> points = new ArrayList<>(path.points());
        points.removeLast();
        replaceSelected(player, session, new PathStep(points, path.durationTicks(), path.easing(), path.sway()),
                false);
        messages.get().send(player, "editor.path-point-removed", "count", String.valueOf(points.size()));
    }

    public void addHold(Player player, int ticks) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        int safeTicks = Math.max(1, Math.min(config.get().maxTrackTicks(), ticks));
        append(player, session, new HoldStep(safeTicks, SwayPreset.INHERIT), "editor.added");
    }

    public void addWait(Player player, String signal, int timeoutTicks) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        if (signal == null || signal.isBlank() || signal.length() > 80) {
            messages.get().send(player, "editor.wait-usage");
            return;
        }
        for (Step step : session.draft.steps()) {
            if (step instanceof WaitStep wait && wait.signal().equals(signal)) {
                messages.get().send(player, "editor.duplicate-signal", "signal", signal);
                return;
            }
        }
        int safeTimeout = Math.max(0, Math.min(config.get().maxTrackTicks(), timeoutTicks));
        append(player, session, new WaitStep(signal, safeTimeout, SwayPreset.INHERIT), "editor.added");
    }

    public void addEffect(Player player, String actionRaw, String effectRaw, int amplifier, int duration) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        EffectAction action;
        try {
            action = EffectAction.parse(actionRaw);
        } catch (IllegalArgumentException exception) {
            messages.get().send(player, "editor.invalid-value", "value", actionRaw);
            return;
        }
        PotionEffectType type = resolveEffect(effectRaw);
        if (type == null) {
            messages.get().send(player, "editor.invalid-effect", "effect", effectRaw);
            return;
        }
        int safeAmplifier = Math.max(0, Math.min(255, amplifier));
        int safeDuration = Math.max(0, Math.min(config.get().maxTrackTicks(), duration));
        EffectSpec spec = new EffectSpec(type, type.getKey().toString(), action, safeAmplifier, safeDuration);
        append(player, session, new EffectStep(spec), "editor.effect-added");
    }

    public void addTitle(Player player, int fadeIn, int stay, int fadeOut, String text) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        String[] split = text.split("\\|", 2);
        String title = split[0].trim();
        String subtitle = split.length > 1 ? split[1].trim() : "";
        if (title.isBlank() && subtitle.isBlank()) {
            messages.get().send(player, "editor.invalid-title");
            return;
        }
        try {
            if (!title.isBlank()) {
                STRICT.deserialize(title);
            }
            if (!subtitle.isBlank()) {
                STRICT.deserialize(subtitle);
            }
        } catch (RuntimeException exception) {
            messages.get().send(player, "editor.invalid-title");
            return;
        }
        append(player, session, new TitleStep(title, subtitle, Math.max(0, fadeIn), Math.max(0, stay),
                Math.max(0, fadeOut)), "editor.added");
    }

    /**
     * Updates the selected step from the player's current eye pose.
     */
    public void updateHere(Player player) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        if (session.selected < 0) {
            setStart(player);
            return;
        }
        Step step = selectedStep(session);
        if (step == null) {
            messages.get().send(player, "editor.no-step");
            return;
        }
        Pose pose = eyePose(player);
        if (pose == null) {
            return;
        }
        Step replacement = switch (step) {
            case MoveStep move -> new MoveStep(pose, move.durationTicks(), move.easing(), move.sway());
            case CutStep cut -> new CutStep(pose, cut.sway());
            case PathStep path -> {
                if (path.points().size() >= config.get().maxPathPoints()) {
                    messages.get().send(player, "editor.invalid-value", "value", "路径点数量已达上限");
                    yield null;
                }
                List<Pose> points = new ArrayList<>(path.points());
                points.add(pose);
                yield new PathStep(points, path.durationTicks(), path.easing(), path.sway());
            }
            default -> null;
        };
        if (replacement == null) {
            messages.get().send(player, "editor.not-camera-step");
            return;
        }
        replaceSelected(player, session, replacement);
    }

    public void setDuration(Player player, int ticks) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        Step step = selectedStep(session);
        if (step == null) {
            messages.get().send(player, "editor.no-step");
            return;
        }
        int safe = Math.max(1, Math.min(config.get().maxTrackTicks(), ticks));
        Step replacement = switch (step) {
            case MoveStep move -> new MoveStep(move.to(), safe, move.easing(), move.sway());
            case PathStep path -> new PathStep(path.points(), safe, path.easing(), path.sway());
            case HoldStep hold -> new HoldStep(safe, hold.sway());
            default -> null;
        };
        if (replacement == null) {
            messages.get().send(player, "editor.need-duration");
            return;
        }
        replaceSelected(player, session, replacement, false);
        messages.get().send(player, "editor.duration-set", "ticks", String.valueOf(safe));
    }

    public void setEasing(Player player, String raw) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        Easing easing;
        try {
            easing = Easing.parse(raw);
        } catch (IllegalArgumentException exception) {
            messages.get().send(player, "editor.invalid-value", "value", raw);
            return;
        }
        Step step = selectedStep(session);
        if (step == null) {
            messages.get().send(player, "editor.no-step");
            return;
        }
        Step replacement = switch (step) {
            case MoveStep move -> new MoveStep(move.to(), move.durationTicks(), easing, move.sway());
            case PathStep path -> new PathStep(path.points(), path.durationTicks(), easing, path.sway());
            default -> null;
        };
        if (replacement == null) {
            messages.get().send(player, "editor.not-camera-step");
            return;
        }
        replaceSelected(player, session, replacement, false);
        messages.get().send(player, "editor.easing-set", "easing", raw.toLowerCase(Locale.ROOT));
    }

    public void setStepSway(Player player, String raw) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        SwayPreset preset;
        try {
            preset = SwayPreset.parse(raw);
        } catch (IllegalArgumentException exception) {
            messages.get().send(player, "editor.invalid-value", "value", raw);
            return;
        }
        Step step = selectedStep(session);
        if (step == null) {
            messages.get().send(player, "editor.no-step");
            return;
        }
        Step replacement = withSway(step, preset);
        if (replacement == null) {
            messages.get().send(player, "editor.not-camera-step");
            return;
        }
        replaceSelected(player, session, replacement, false);
        messages.get().send(player, "editor.step-sway-set", "sway", preset.name().toLowerCase(Locale.ROOT));
    }

    public void setTrackSway(Player player, String raw) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        SwayPreset preset;
        try {
            preset = SwayPreset.parse(raw);
        } catch (IllegalArgumentException exception) {
            messages.get().send(player, "editor.invalid-value", "value", raw);
            return;
        }
        if (preset == SwayPreset.INHERIT) {
            messages.get().send(player, "editor.invalid-value", "value", raw);
            return;
        }
        session.remember();
        SwaySettings settings = SwaySettings.ofPreset(preset, session.draft.sway().period(),
                session.draft.sway().seed());
        session.draft = session.draft.withSway(settings);
        persisted(player, session);
        messages.get().send(player, "editor.sway-set", "sway", preset.name().toLowerCase(Locale.ROOT));
    }

    public void setFollow(Player player, boolean follow) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        session.remember();
        session.draft = session.draft.withFollow(follow);
        persisted(player, session);
        messages.get().send(player, "editor.follow-set", "value", follow ? "true" : "false");
    }

    public void setDisplayName(Player player, String name) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        if (name == null || name.isBlank()) {
            messages.get().send(player, "editor.display-usage");
            return;
        }
        session.remember();
        session.draft = session.draft.withDisplayName(name.trim());
        persisted(player, session);
        messages.get().send(player, "editor.display-set", "name", name.trim());
    }

    public void select(Player player, int oneBased) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        int max = session.draft.steps().size();
        if (oneBased < 1 || oneBased > max) {
            messages.get().send(player, "editor.selected-index-invalid", "max", String.valueOf(max));
            return;
        }
        session.selected = oneBased - 1;
        Step step = selectedStep(session);
        messages.get().send(player, "editor.selected", "index", String.valueOf(oneBased),
                "summary", step == null ? "" : step.describe());
    }

    public void moveSelected(Player player, boolean up) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        int index = session.selected;
        if (index < 0) {
            messages.get().send(player, "editor.no-step");
            return;
        }
        int target = up ? index - 1 : index + 1;
        if (target < 0 || target >= session.draft.steps().size()) {
            return;
        }
        session.remember();
        List<Step> steps = new ArrayList<>(session.draft.steps());
        Step moved = steps.remove(index);
        steps.add(target, moved);
        session.draft = session.draft.withSteps(steps);
        session.selected = target;
        persisted(player, session);
        messages.get().send(player, "editor.moved", "index", String.valueOf(target + 1));
    }

    public void removeSelected(Player player) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        int index = session.selected;
        if (index < 0) {
            messages.get().send(player, "editor.no-step");
            return;
        }
        session.remember();
        List<Step> steps = new ArrayList<>(session.draft.steps());
        steps.remove(index);
        session.draft = session.draft.withSteps(steps);
        session.selected = Math.min(index, steps.size() - 1);
        persisted(player, session);
        messages.get().send(player, "editor.removed", "index", String.valueOf(index + 1));
    }

    public void undo(Player player) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        if (!session.restore()) {
            messages.get().send(player, "editor.nothing-to-undo");
            return;
        }
        persisted(player, session);
        messages.get().send(player, "editor.undone");
    }

    public void save(Player player) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        repository.upsert(session.draft);
        persist(player, repository.saveAsync());
        messages.get().send(player, "editor.saved", "track", session.draft.id());
    }

    public void preview(Player player) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        if (session.previewing) {
            // Preview acts as a toggle; the command hint says "再次执行停止".
            stopPreview(player);
            return;
        }
        if (session.draft.steps().isEmpty()) {
            messages.get().send(player, "editor.preview-no-steps");
            return;
        }
        if (engine.isPlaying(player)) {
            messages.get().send(player, session.previewing ? "editor.preview-active" : "editor.preview-other");
            return;
        }
        repository.upsert(session.draft);
        persist(player, repository.saveAsync());
        session.uiPreview = false;
        if (engine.playPreview(player, session.draft.id())) {
            session.previewing = true;
            messages.get().send(player, "editor.preview-started", "track", session.draft.id());
            // A canceled start event does not produce an end event. Detect that case so the
            // editor does not stay locked in "previewing" forever.
            player.getScheduler().execute(plugin, () -> {
                if (editors.get(player.getUniqueId()) == session
                        && session.previewing && !engine.isPlaying(player)) {
                    session.previewing = false;
                    session.uiPreview = false;
                    messages.get().send(player, "editor.preview-failed");
                }
            }, () -> {
            }, 5L);
        } else {
            messages.get().send(player, "editor.preview-failed");
        }
    }

    public void stopPreview(Player player) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        if (session.previewing) {
            session.uiPreview = false;
            engine.stop(player);
            session.previewing = false;
            messages.get().send(player, "editor.preview-stopped");
        }
    }

    public void delete(Player player, boolean confirm) {
        EditorSession session = requireEditor(player);
        if (session == null) {
            return;
        }
        if (!confirm) {
            messages.get().send(player, "editor.delete-confirm",
                    "track", session.draft.id(),
                    "command", "/zth-camera edit delete confirm");
            return;
        }
        if (editors.values().stream().anyMatch(other -> other != session
                && other.draft.id().equals(session.draft.id()))) {
            messages.get().send(player, "manage.edit-busy", "track", session.draft.id());
            return;
        }
        if (session.previewing && engine.isPlaying(player)) {
            engine.stop(player);
        }
        repository.delete(session.draft.id());
        persist(player, repository.saveAsync());
        editors.remove(player.getUniqueId());
        messages.get().send(player, "editor.deleted", "track", session.draft.id());
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onTrackEnd(CameraTrackEndEvent event) {
        if (!event.preview()) {
            return;
        }
        EditorSession session = editors.get(event.getPlayer().getUniqueId());
        if (session != null && session.previewing) {
            boolean ui = session.uiPreview;
            session.previewing = false;
            session.uiPreview = false;
            if (!ui) {
                messages.get().send(event.getPlayer(), "editor.preview-ended");
            }
        }
    }

    private void append(Player player, EditorSession session, Step step, String messageKey) {
        if (session.draft.steps().size() >= config.get().maxSteps()) {
            messages.get().send(player, "editor.limit", "limit", "步骤数上限 " + config.get().maxSteps());
            return;
        }
        if (session.draft.timedDurationTicks() + timedTicks(step) > config.get().maxTrackTicks()) {
            messages.get().send(player, "editor.limit", "limit", "总时长上限 " + config.get().maxTrackTicks() + "t");
            return;
        }
        session.remember();
        List<Step> steps = new ArrayList<>(session.draft.steps());
        steps.add(step);
        session.draft = session.draft.withSteps(steps);
        session.selected = steps.size() - 1;
        persisted(player, session);
        messages.get().send(player, messageKey,
                "index", String.valueOf(steps.size()), "summary", step.describe());
    }

    private void replaceSelected(Player player, EditorSession session, Step step) {
        replaceSelected(player, session, step, true);
    }

    private void replaceSelected(Player player, EditorSession session, Step step, boolean announce) {
        if (session.selected < 0 || session.selected >= session.draft.steps().size()) {
            messages.get().send(player, "editor.no-step");
            return;
        }
        Step previous = session.draft.steps().get(session.selected);
        long totalAfter = session.draft.timedDurationTicks() - timedTicks(previous) + timedTicks(step);
        if (totalAfter > config.get().maxTrackTicks()) {
            messages.get().send(player, "editor.limit", "limit", "总时长上限 " + config.get().maxTrackTicks() + "t");
            return;
        }
        session.remember();
        List<Step> steps = new ArrayList<>(session.draft.steps());
        steps.set(session.selected, step);
        session.draft = session.draft.withSteps(steps);
        persisted(player, session);
        if (announce) {
            messages.get().send(player, "editor.updated",
                    "index", String.valueOf(session.selected + 1), "summary", step.describe());
        }
    }

    private void persisted(Player player, EditorSession session) {
        repository.upsert(session.draft);
        persist(player, repository.saveAsync());
    }

    private void persist(Player player, java.util.concurrent.CompletableFuture<Void> future) {
        EditorSession session = editors.get(player.getUniqueId());
        future.whenComplete((ignored, error) -> {
            if (error == null) {
                if (session != null) {
                    session.saveFailed = false;
                }
                return;
            }
            if (session != null) {
                session.saveFailed = true;
            }
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not save tracks.yml", error);
            if (player.isOnline()) {
                player.getScheduler().execute(plugin,
                        () -> messages.get().send(player, "editor.save-failed"), () -> {
                        }, 0L);
            }
        });
    }

    private EditorSession requireEditor(Player player) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null) {
            messages.get().send(player, "editor.not-editing");
        }
        return session;
    }

    private Step selectedStep(EditorSession session) {
        if (session.selected < 0 || session.selected >= session.draft.steps().size()) {
            return null;
        }
        return session.draft.steps().get(session.selected);
    }

    private PathStep selectedPath(EditorSession session) {
        Step step = selectedStep(session);
        return step instanceof PathStep path ? path : null;
    }

    private Pose eyePose(Player player) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session != null && session.previewing) {
            messages.get().send(player, "editor.preview-active");
            return null;
        }
        if (session != null && !player.getWorld().getName().equals(session.worldName)) {
            messages.get().send(player, "editor.world-mismatch", "world", session.worldName);
            return null;
        }
        var location = player.getEyeLocation();
        return new Pose(location.getX(), location.getY(), location.getZ(), player.getYaw(), player.getPitch());
    }

    private Pose feetPose(Player player) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session != null && session.previewing) {
            messages.get().send(player, "editor.preview-active");
            return null;
        }
        if (session != null && !player.getWorld().getName().equals(session.worldName)) {
            messages.get().send(player, "editor.world-mismatch", "world", session.worldName);
            return null;
        }
        var location = player.getLocation();
        return new Pose(location.getX(), location.getY(), location.getZ(), player.getYaw(), player.getPitch());
    }

    private static int timedTicks(Step step) {
        return step instanceof CameraStep cameraStep ? cameraStep.timedDurationTicks() : 0;
    }

    private static Step withSway(Step step, SwayPreset sway) {
        return switch (step) {
            case MoveStep move -> new MoveStep(move.to(), move.durationTicks(), move.easing(), sway);
            case PathStep path -> new PathStep(path.points(), path.durationTicks(), path.easing(), sway);
            case HoldStep hold -> new HoldStep(hold.durationTicks(), sway);
            case WaitStep wait -> new WaitStep(wait.signal(), wait.timeoutTicks(), sway);
            case CutStep cut -> new CutStep(cut.to(), sway);
            default -> null;
        };
    }

    public static PotionEffectType resolveEffect(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        normalized = switch (normalized) {
            case "slow" -> "slowness";
            case "blind" -> "blindness";
            case "nv" -> "night_vision";
            default -> normalized;
        };
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }
        NamespacedKey key = NamespacedKey.fromString(normalized);
        return key == null ? null : Registry.EFFECT.get(key);
    }

    // ------------------------------------------------------------------ dialog editor API

    /**
     * Session owned by the player, or {@code null}. Dialog actions run on the player's
     * scheduler, the same thread that owns the session.
     */
    EditorSession session(Player player) {
        return editors.get(player.getUniqueId());
    }

    /**
     * Replaces the whole draft. Returns {@code null} on success, or a user-facing error.
     * The caller is responsible for running on the player's scheduler.
     */
    String applyDraft(Player player, CameraTrack updated, int selected) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null) {
            return "你当前不在编辑模式.";
        }
        String error = validateDraft(session, updated);
        if (error != null) {
            return error;
        }
        session.remember();
        session.draft = updated;
        session.selected = updated.steps().isEmpty()
                ? -1
                : Math.max(0, Math.min(selected, updated.steps().size() - 1));
        persisted(player, session);
        return null;
    }

    /**
     * Appends a step and selects it. Returns {@code null} on success, or a user-facing error.
     */
    String addStep(Player player, Step step) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null) {
            return "你当前不在编辑模式.";
        }
        if (step == null) {
            return "步骤无效.";
        }
        List<Step> steps = new ArrayList<>(session.draft.steps());
        steps.add(step);
        CameraTrack updated = session.draft.withSteps(steps);
        String error = validateDraft(session, updated);
        if (error != null) {
            return error;
        }
        session.remember();
        session.draft = updated;
        session.selected = steps.size() - 1;
        persisted(player, session);
        return null;
    }

    /**
     * Undoes one edit without chat feedback. Returns false when there is nothing to undo.
     */
    boolean undoQuiet(Player player) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null || !session.restore()) {
            return false;
        }
        persisted(player, session);
        return true;
    }

    /**
     * Starts a UI preview at {@code startIndex} without chat feedback. The end event is
     * handled by {@link EditorUI}, which reopens the editor.
     */
    boolean startPreview(Player player, int startIndex) {
        return startPreview(player, startIndex, -1);
    }

    /**
     * Starts a UI preview of the inclusive interval {@code [startIndex, endIndex]}. A
     * negative {@code endIndex} means "to the end of the track".
     */
    boolean startPreview(Player player, int startIndex, int endIndex) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null || session.previewing || engine.isPlaying(player)) {
            return false;
        }
        if (session.draft.steps().isEmpty()) {
            return false;
        }
        int index = Math.max(0, Math.min(startIndex, session.draft.steps().size() - 1));
        int last = session.draft.steps().size() - 1;
        int end = endIndex < 0 ? last : Math.max(index, Math.min(endIndex, last));
        repository.upsert(session.draft);
        persist(player, repository.saveAsync());
        session.previewing = true;
        session.uiPreview = true;
        boolean started;
        try {
            started = engine.playPreview(player, session.draft.id(), index, end);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not start camera preview for " + player.getName(), exception);
            started = false;
        }
        if (!started) {
            session.previewing = false;
            session.uiPreview = false;
            return false;
        }
        // A canceled CameraTrackStartEvent produces no end event. Release the flag so the
        // UI can be reopened instead of leaving the editor locked in "previewing".
        player.getScheduler().execute(plugin, () -> {
            if (editors.get(player.getUniqueId()) == session
                    && session.previewing && !engine.isPlaying(player)) {
                session.previewing = false;
                session.uiPreview = false;
            }
        }, () -> {
        }, 5L);
        return true;
    }

    /**
     * Stops a UI preview. The end event reopens the editor; this method intentionally
     * leaves {@code previewing} set so the normal end handling runs.
     */
    boolean stopPreviewQuiet(Player player) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null || !session.previewing) {
            return false;
        }
        session.uiPreview = true;
        return engine.stop(player);
    }

    private String validateDraft(EditorSession session, CameraTrack updated) {
        if (updated == null) {
            return "轨道数据无效.";
        }
        if (!updated.id().equals(session.draft.id())) {
            return "轨道 id 不能在这里修改.";
        }
        if (!updated.worldName().equals(session.worldName)) {
            return "轨道世界不能在这里修改.";
        }
        if (updated.steps().size() > config.get().maxSteps()) {
            return "步骤数上限 " + config.get().maxSteps() + ".";
        }
        if (updated.timedDurationTicks() > config.get().maxTrackTicks()) {
            return "总时长上限 " + config.get().maxTrackTicks() + "t.";
        }
        Set<String> signals = new HashSet<>();
        for (Step step : updated.steps()) {
            if (step instanceof WaitStep wait && !signals.add(wait.signal())) {
                return "等待信号 " + wait.signal() + " 重复.";
            }
            if (step instanceof PathStep path && path.points().size() > config.get().maxPathPoints()) {
                return "路径点上限 " + config.get().maxPathPoints() + ".";
            }
            if (step instanceof TitleStep title) {
                String error = validateMiniMessage(title.title(), "标题");
                if (error != null) {
                    return error;
                }
                error = validateMiniMessage(title.subtitle(), "副标题");
                if (error != null) {
                    return error;
                }
            }
        }
        return null;
    }

    private static String validateMiniMessage(String text, String label) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            STRICT.deserialize(text);
            return null;
        } catch (RuntimeException exception) {
            return label + "不是合法的 MiniMessage.";
        }
    }

    public Optional<String> editingTrack(Player player) {
        EditorSession session = editors.get(player.getUniqueId());
        return session == null ? Optional.empty() : Optional.of(session.draft.id());
    }

    public int stepCount(Player player) {
        EditorSession session = editors.get(player.getUniqueId());
        return session == null ? 0 : session.draft.steps().size();
    }

    public boolean isPreviewing(Player player) {
        EditorSession session = editors.get(player.getUniqueId());
        return session != null && session.previewing;
    }
}
