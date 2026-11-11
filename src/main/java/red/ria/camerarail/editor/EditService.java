package red.ria.camerarail.editor;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import red.ria.camerarail.api.PlaybackInfo;
import red.ria.camerarail.config.RailConfig;
import red.ria.camerarail.config.TrackParser;
import red.ria.camerarail.config.TrackRepository;
import red.ria.camerarail.message.Messages;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.CutStep;
import red.ria.camerarail.model.EffectSpec;
import red.ria.camerarail.model.ExitMode;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.MoveStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;
import red.ria.camerarail.runtime.CameraEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Stateless edit operations shared by the edit commands and the item tools.
 *
 * <p>Every operation addresses its target explicitly with an {@link EditTarget}; nothing depends
 * on a hidden "selected step". The service writes straight to the repository and saves
 * asynchronously, keeping a per-player undo stack ({@link UndoHistory}).</p>
 *
 * <p>All mutations go through {@link TrackRepository#compute(String, UnaryOperator)}: the
 * repository's current value is read, changed, validated and written back under the repository's
 * single write lock. Two edits of the same track from different Folia region threads therefore
 * apply one after the other instead of both reading the old value and the second one overwriting
 * the first. The undo snapshot is the {@code before} value handed to the changer; because
 * {@link CameraTrack} is deeply immutable it needs no copy.</p>
 *
 * <p>The Dialog editor is a separate system with its own session draft. The only state shared
 * between the two is {@link EditorLocks}: when a track is held by a Dialog session, command/tool
 * edits of that track are refused instead of silently overwriting the draft. The Dialog opens a
 * session with {@link EditorLocks#tryLock(String, UUID)} and reads the draft afterwards under
 * {@code TrackRepository.compute}, so a command edit is either visible to the draft or blocked by
 * the lock. Refactoring one surface therefore does not change the other's storage model.</p>
 */
public final class EditService implements Listener {
    /** Outcome of one stateless edit. {@code track} is the track after a successful change. */
    public record Result(boolean ok, boolean changed, String error, CameraTrack track, int stepIndex) {
        public static Result ok(CameraTrack track, int stepIndex) {
            return new Result(true, true, null, track, stepIndex);
        }

        public static Result unchanged(CameraTrack track, int stepIndex) {
            return new Result(true, false, null, track, stepIndex);
        }

        public static Result fail(String error) {
            return new Result(false, false, error, null, -1);
        }

        public boolean failed() {
            return !ok;
        }
    }

    /**
     * Internal control flow: aborts one {@link TrackRepository#compute} cycle with a user-facing
     * result. Thrown inside the changer, it leaves the stored track untouched.
     */
    private static final class AbortEdit extends RuntimeException {
        private final Result result;

        private AbortEdit(Result result) {
            super(null, null, false, false);
            this.result = result;
        }
    }

    private static AbortEdit abort(Result result) {
        return new AbortEdit(result);
    }

    private final Plugin plugin;
    private final TrackRepository repository;
    private final CameraEngine engine;
    private final EditorLocks locks;
    private final Supplier<RailConfig> config;
    private final Supplier<Messages> messages;
    private final Map<UUID, UndoHistory> undoHistories = new ConcurrentHashMap<>();

    public EditService(Plugin plugin, TrackRepository repository, CameraEngine engine, EditorLocks locks,
                       Supplier<RailConfig> config, Supplier<Messages> messages) {
        this.plugin = plugin;
        this.repository = repository;
        this.engine = engine;
        this.locks = locks;
        this.config = config;
        this.messages = messages;
        // A deleted track can no longer be undone: drop its snapshots before an undo can restore
        // the old value. The listener runs outside the repository write lock.
        this.repository.onTrackDeleted(this::forgetTrack);
        // A reload replaces the whole track map; snapshots taken against the old map must not be
        // replayed over it. This listener runs inside the repository write lock, in the same
        // (writeLock -> UndoHistory) order as the pushes in updateTrack.
        this.repository.onReloaded(this::onReload);
    }

    // ------------------------------------------------------------------ lookups

    /** The committed track; returns {@code null} when it is missing. */
    public CameraTrack view(String trackId) {
        return repository.get(trackId);
    }

    public boolean isPreviewing(Player player) {
        if (player == null) {
            return false;
        }
        return engine.playback(player).map(PlaybackInfo::preview).orElse(false);
    }

    // ------------------------------------------------------------------ track creation

    /** Creates a track at the player's current view without opening a Dialog session. */
    public Result createTrack(Player player, String rawId, String displayName) {
        String id = rawId == null ? "" : rawId.toLowerCase(Locale.ROOT);
        if (!TrackRules.isValidId(id)) {
            return Result.fail("轨道 id 只能包含小写字母、数字、- 和 _，最长 64 个字符.");
        }
        if (view(id) != null) {
            return Result.fail("轨道 " + id + " 已存在.");
        }
        if (engine.isPlaying(player)) {
            return Result.fail("镜头播放中，无法取景，请先停止预览.");
        }
        Location eye = player.getEyeLocation();
        Pose start = new Pose(eye.getX(), eye.getY(), eye.getZ(), player.getYaw(), player.getPitch());
        RailConfig cfg = config.get();
        SwaySettings sway = SwaySettings.ofPreset(cfg.defaultSwayPreset(), cfg.defaultSwayPeriod(),
                TrackParser.defaultSeed(id));
        CameraTrack track = new CameraTrack(id,
                displayName == null || displayName.isBlank() ? id : displayName, "",
                player.getWorld().getName(), start, ExitMode.ANIMATION_END, null, sway,
                cfg.followCamera(), List.of(), List.of(new HoldStep(20, SwayPreset.INHERIT)));
        boolean[] created = {false};
        repository.compute(id, current -> {
            if (current != null) {
                return current;
            }
            created[0] = true;
            return track;
        });
        if (!created[0]) {
            return Result.fail("轨道 " + id + " 已存在.");
        }
        saveAndReport(player);
        return Result.ok(track, 0);
    }

    // ------------------------------------------------------------------ generic mutations

    /**
     * Applies a whole-track change atomically. The current value is read, changed, validated and
     * written back inside one repository compute cycle; validation, the Dialog editing lock, the
     * undo record and the save all happen here.
     */
    public Result updateTrack(Player player, String trackId, UnaryOperator<CameraTrack> change) {
        if (trackId == null || trackId.isBlank()) {
            return Result.fail("找不到轨道 " + trackId + ".");
        }
        try {
            CameraTrack updated = repository.compute(trackId, current -> {
                if (current == null) {
                    throw abort(Result.fail("找不到轨道 " + trackId + "."));
                }
                Result locked = lockedFailure(player, current.id());
                if (locked != null) {
                    throw abort(locked);
                }
                CameraTrack changed;
                try {
                    changed = change.apply(current);
                } catch (AbortEdit edit) {
                    throw edit;
                } catch (RuntimeException exception) {
                    throw abort(Result.fail(message(exception)));
                }
                if (changed == null) {
                    throw abort(Result.fail("这个操作不适用于当前目标."));
                }
                if (changed.equals(current)) {
                    throw abort(Result.unchanged(current, -1));
                }
                String error = TrackRules.validate(current, changed, config.get());
                if (error != null) {
                    throw abort(Result.fail(error));
                }
                history(player).push(current.id(), current, changed);
                return changed;
            });
            saveAndReport(player);
            return Result.ok(updated, -1);
        } catch (AbortEdit edit) {
            return edit.result;
        }
    }

    /**
     * Replaces the step addressed by {@code target}. The replacer receives the current step and
     * returns its replacement, or {@code null} when the operation does not fit that step type.
     */
    public Result updateStep(Player player, EditTarget target, Function<Step, Step> replacer) {
        return updateStep(player, target, (track, step) -> replacer.apply(step));
    }

    private Result updateStep(Player player, EditTarget target, BiFunction<CameraTrack, Step, Step> replacer) {
        if (!target.hasStep()) {
            return Result.fail("请指定步骤，例如 " + target.trackId() + ":1.");
        }
        return updateTrack(player, target.trackId(), track -> {
            if (target.stepIndex() >= track.steps().size()) {
                throw abort(Result.fail(stepMissing(target)));
            }
            Step current = track.steps().get(target.stepIndex());
            Step replacement;
            try {
                replacement = replacer.apply(track, current);
            } catch (AbortEdit edit) {
                throw edit;
            } catch (RuntimeException exception) {
                throw abort(Result.fail(message(exception)));
            }
            if (replacement == null) {
                throw abort(Result.fail("这个操作不适用于步骤 " + (target.stepIndex() + 1) + "."));
            }
            List<Step> steps = new ArrayList<>(track.steps());
            steps.set(target.stepIndex(), replacement);
            return track.withSteps(steps);
        });
    }

    /** Appends {@code step} at the end, or right after the target step when one is addressed. */
    public Result addStep(Player player, EditTarget target, Step step) {
        if (step == null) {
            return Result.fail("步骤无效.");
        }
        return addStep(player, target, track -> step, false);
    }

    /**
     * Appends/inserts a step produced from the current track. {@code requireSameWorld} guards the
     * capture-based adders, whose step carries the player's current view.
     */
    private Result addStep(Player player, EditTarget target, Function<CameraTrack, Step> factory,
                           boolean requireSameWorld) {
        final int[] addedIndex = {-1};
        Result result = updateTrack(player, target.trackId(), track -> {
            if (target.hasStep() && target.stepIndex() >= track.steps().size()) {
                throw abort(Result.fail(stepMissing(target)));
            }
            if (requireSameWorld) {
                requireWorld(track, player);
            }
            Step step = factory.apply(track);
            int at = target.hasStep()
                    ? Math.min(target.stepIndex() + 1, track.steps().size())
                    : track.steps().size();
            List<Step> steps = new ArrayList<>(track.steps());
            steps.add(at, step);
            addedIndex[0] = at + 1;
            return track.withSteps(steps);
        });
        if (!result.ok() || !result.changed()) {
            return result;
        }
        return new Result(true, true, null, result.track(), addedIndex[0]);
    }

    public Result removeStep(Player player, EditTarget target) {
        if (!target.hasStep()) {
            return Result.fail("请指定要删除的步骤，例如 " + target.trackId() + ":3.");
        }
        Result result = updateTrack(player, target.trackId(), track -> {
            if (target.stepIndex() >= track.steps().size()) {
                throw abort(Result.fail(stepMissing(target)));
            }
            List<Step> steps = new ArrayList<>(track.steps());
            steps.remove(target.stepIndex());
            return track.withSteps(steps);
        });
        if (!result.ok() || !result.changed()) {
            return result;
        }
        return new Result(true, true, null, result.track(), target.stepIndex() + 1);
    }

    /** Moves the target step by {@code delta} positions; both indices are 0-based. */
    public Result moveStep(Player player, EditTarget target, int delta) {
        if (!target.hasStep()) {
            return Result.fail("请指定要移动的步骤，例如 " + target.trackId() + ":3.");
        }
        final int[] movedTo = {-1};
        Result result = updateTrack(player, target.trackId(), track -> {
            if (target.stepIndex() >= track.steps().size()) {
                throw abort(Result.fail(stepMissing(target)));
            }
            int to = target.stepIndex() + delta;
            if (to < 0 || to >= track.steps().size()) {
                throw abort(Result.fail("步骤 " + (target.stepIndex() + 1) + " 无法向这个方向移动."));
            }
            List<Step> steps = new ArrayList<>(track.steps());
            Step moved = steps.remove(target.stepIndex());
            steps.add(to, moved);
            movedTo[0] = to;
            return track.withSteps(steps);
        });
        if (!result.ok() || !result.changed()) {
            return result;
        }
        return new Result(true, true, null, result.track(), movedTo[0]);
    }

    public Result duplicateStep(Player player, EditTarget target) {
        if (!target.hasStep()) {
            return Result.fail("请指定要复制的步骤，例如 " + target.trackId() + ":3.");
        }
        Result result = updateTrack(player, target.trackId(), track -> {
            if (target.stepIndex() >= track.steps().size()) {
                throw abort(Result.fail(stepMissing(target)));
            }
            Step copy = track.steps().get(target.stepIndex());
            List<Step> steps = new ArrayList<>(track.steps());
            steps.add(target.stepIndex() + 1, copy);
            return track.withSteps(steps);
        });
        if (!result.ok() || !result.changed()) {
            return result;
        }
        return new Result(true, true, null, result.track(), target.stepIndex() + 1);
    }

    // ------------------------------------------------------------------ pose setters

    /** Sets the track start to an explicit pose. */
    public Result setStartPose(Player player, String trackId, Pose pose) {
        return updateTrack(player, trackId, track -> track.withStart(pose));
    }

    /** Sets the track exit to an explicit pose and switches the exit mode to custom. */
    public Result setExitPose(Player player, String trackId, Pose pose) {
        return updateTrack(player, trackId, track -> track.withExit(pose));
    }

    /**
     * Sets the target pose of a move/cut step or replaces a path point. A path step has no
     * single target pose, so it is rejected here and edited through its points instead.
     */
    public Result setPose(Player player, EditTarget target, Pose pose) {
        return setPose(player, target, pose, false);
    }

    private Result setPose(Player player, EditTarget target, Pose pose, boolean capture) {
        if (target.hasPoint()) {
            return setPathPointPose(player, target, pose, capture);
        }
        if (!target.hasStep()) {
            return Result.fail("请指定步骤，例如 " + target.trackId() + ":1.");
        }
        return updateStep(player, target, (track, step) -> {
            if (capture) {
                requireWorld(track, player);
            }
            if (step instanceof PathStep) {
                throw abort(Result.fail("步骤 " + (target.stepIndex() + 1)
                        + " 是路径步骤；请用 point add 追加路径点，或指向具体路径点再设置坐标."));
            }
            return switch (step) {
                case MoveStep move -> new MoveStep(pose, move.durationTicks(), move.easing(), move.sway());
                case CutStep cut -> new CutStep(pose, cut.durationTicks(), cut.sway());
                default -> throw abort(Result.fail("步骤 " + (target.stepIndex() + 1) + " 是 "
                        + step.type().configName() + " 步骤，没有可设置的目标姿态."));
            };
        });
    }

    /** Replaces the addressed path point with an explicit pose. */
    public Result setPathPointPose(Player player, EditTarget target, Pose pose) {
        return setPathPointPose(player, target, pose, false);
    }

    private Result setPathPointPose(Player player, EditTarget target, Pose pose, boolean capture) {
        if (!target.hasPoint()) {
            return Result.fail("请指定路径点，例如 " + target.trackId()
                    + ":" + (target.stepIndex() + 1) + ":2.");
        }
        return updateStep(player, target, (track, step) -> {
            PathStep path = requirePath(track, target);
            if (capture) {
                requireWorld(track, player);
            }
            if (target.pointIndex() >= path.points().size()) {
                throw abort(Result.fail(pointMissing(target)));
            }
            List<Pose> points = new ArrayList<>(path.points());
            points.set(target.pointIndex(), pose);
            return new PathStep(points, path.durationTicks(), path.easing(), path.sway());
        });
    }

    /** Captures the player's current eye view into the addressed step pose or path point. */
    public Result capturePose(Player player, EditTarget target) {
        Pose pose;
        try {
            pose = eyePose(player);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        return setPose(player, target, pose, true);
    }

    /** Captures the player's current eye view as the track start. */
    public Result captureStart(Player player, String trackId) {
        Pose pose;
        try {
            pose = eyePose(player);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        return updateTrack(player, trackId, track -> {
            requireWorld(track, player);
            return track.withStart(pose);
        });
    }

    /** Captures the player's current feet position as a custom exit. */
    public Result captureExit(Player player, String trackId) {
        Pose pose;
        try {
            pose = feetPose(player);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        return updateTrack(player, trackId, track -> {
            requireWorld(track, player);
            return track.withExit(pose);
        });
    }

    /** Appends a move step at the current view (or after the target step). */
    public Result addMoveStep(Player player, EditTarget target, int durationTicks) {
        Pose pose;
        try {
            pose = eyePose(player);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        int safeDuration = Math.max(1, Math.min(config.get().maxTrackTicks(), durationTicks));
        return addStep(player, target,
                track -> new MoveStep(pose, safeDuration, config.get().defaultEasing(), SwayPreset.INHERIT),
                true);
    }

    /** Appends a cut step at the current view (or after the target step). */
    public Result addCutStep(Player player, EditTarget target, int durationTicks) {
        Pose pose;
        try {
            pose = eyePose(player);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        int safeDuration = Math.max(0, Math.min(config.get().maxTrackTicks(), durationTicks));
        return addStep(player, target, track -> new CutStep(pose, safeDuration, SwayPreset.INHERIT), true);
    }

    /**
     * Appends a path step whose first point is the current view (a placeholder until the first
     * real waypoint replaces it), or inserts it after the target step.
     */
    public Result addPathStep(Player player, EditTarget target) {
        return addPathStep(player, target, config.get().defaultMoveDuration());
    }

    /** Appends/inserts a path step with an explicit duration. */
    public Result addPathStep(Player player, EditTarget target, int durationTicks) {
        Pose pose;
        try {
            pose = eyePose(player);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        int safeDuration = Math.max(1, Math.min(config.get().maxTrackTicks(), durationTicks));
        return addStep(player, target, track -> new PathStep(List.of(pose), safeDuration,
                config.get().defaultEasing(), SwayPreset.INHERIT), true);
    }

    // ------------------------------------------------------------------ entry effects

    /** Appends an entry effect (the Dialog editor's 入场效果 list). */
    public Result addEntryEffect(Player player, String trackId, EffectSpec spec) {
        if (spec == null) {
            return Result.fail("效果无效.");
        }
        return updateTrack(player, trackId, track -> {
            List<EffectSpec> next = new ArrayList<>(track.entryEffects());
            next.add(spec);
            return track.withEntryEffects(next);
        });
    }

    /** Replaces one entry effect by 0-based index. */
    public Result setEntryEffect(Player player, String trackId, int index, EffectSpec spec) {
        if (spec == null) {
            return Result.fail("效果无效.");
        }
        return updateTrack(player, trackId, track -> {
            if (index < 0 || index >= track.entryEffects().size()) {
                throw abort(Result.fail("入场效果 " + (index + 1) + " 不存在."));
            }
            List<EffectSpec> next = new ArrayList<>(track.entryEffects());
            next.set(index, spec);
            return track.withEntryEffects(next);
        });
    }

    /** Removes one entry effect by 0-based index. */
    public Result removeEntryEffect(Player player, String trackId, int index) {
        return updateTrack(player, trackId, track -> {
            if (index < 0 || index >= track.entryEffects().size()) {
                throw abort(Result.fail("入场效果 " + (index + 1) + " 不存在."));
            }
            List<EffectSpec> next = new ArrayList<>(track.entryEffects());
            next.remove(index);
            return track.withEntryEffects(next);
        });
    }

    // ------------------------------------------------------------------ path points

    /** Appends the player's current eye pose to the addressed path step. */
    public Result appendPathPoint(Player player, EditTarget target) {
        if (!target.hasStep()) {
            return Result.fail("请指定路径步骤，例如 " + target.trackId() + ":3.");
        }
        Pose pose;
        try {
            pose = eyePose(player);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        return updateStep(player, target, (track, step) -> {
            PathStep path = requirePath(track, target);
            requireWorld(track, player);
            PathPoints.Result appended = PathPoints.append(track, target.stepIndex(), path.points(), pose);
            if (appended.outcome() == PathPoints.Outcome.DUPLICATE) {
                throw abort(Result.fail("与上一个路径点位置相同，未添加路径点."));
            }
            if (PathPoints.exceedsLimit(appended, config.get().maxPathPoints())) {
                throw abort(Result.fail("路径点数量已达上限 " + config.get().maxPathPoints() + "."));
            }
            return new PathStep(appended.points(), path.durationTicks(), path.easing(), path.sway());
        });
    }

    /** Inserts the current eye pose after the addressed point ({@code point + insert}). */
    public Result insertPathPoint(Player player, EditTarget target) {
        if (!target.hasPoint()) {
            return Result.fail("请指定要插入位置后的路径点，例如 " + target.trackId()
                    + ":" + (target.stepIndex() + 1) + ":2.");
        }
        Pose pose;
        try {
            pose = eyePose(player);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        return updateStep(player, target, (track, step) -> {
            PathStep path = requirePath(track, target);
            requireWorld(track, player);
            if (target.pointIndex() >= path.points().size()) {
                throw abort(Result.fail(pointMissing(target)));
            }
            PathPoints.Result inserted = PathPoints.insert(track, target.stepIndex(), path.points(),
                    target.pointIndex(), pose);
            if (inserted.outcome() == PathPoints.Outcome.DUPLICATE) {
                throw abort(Result.fail("与相邻路径点位置相同，未插入路径点."));
            }
            if (PathPoints.exceedsLimit(inserted, config.get().maxPathPoints())) {
                throw abort(Result.fail("路径点数量已达上限 " + config.get().maxPathPoints() + "."));
            }
            return new PathStep(inserted.points(), path.durationTicks(), path.easing(), path.sway());
        });
    }

    /** Replaces the addressed path point with the player's current eye pose. */
    public Result replacePathPoint(Player player, EditTarget target) {
        if (!target.hasPoint()) {
            return Result.fail("请指定路径点，例如 " + target.trackId()
                    + ":" + (target.stepIndex() + 1) + ":2.");
        }
        Pose pose;
        try {
            pose = eyePose(player);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        return setPathPointPose(player, target, pose, true);
    }

    /** Removes the addressed point; a step-level target removes the last point. */
    public Result removePathPoint(Player player, EditTarget target) {
        if (!target.hasStep()) {
            return Result.fail("请指定路径步骤，例如 " + target.trackId() + ":3.");
        }
        return updateStep(player, target, (track, step) -> {
            PathStep path = requirePath(track, target);
            int index = target.hasPoint() ? target.pointIndex() : path.points().size() - 1;
            if (index < 0 || index >= path.points().size()) {
                throw abort(Result.fail(pointMissing(target)));
            }
            if (path.points().size() <= 1) {
                throw abort(Result.fail("路径至少需要保留一个点."));
            }
            List<Pose> points = new ArrayList<>(path.points());
            points.remove(index);
            return new PathStep(points, path.durationTicks(), path.easing(), path.sway());
        });
    }

    /** Moves the addressed point by {@code delta} positions. */
    public Result movePathPoint(Player player, EditTarget target, int delta) {
        if (!target.hasPoint()) {
            return Result.fail("请指定要移动的路径点，例如 " + target.trackId()
                    + ":" + (target.stepIndex() + 1) + ":2.");
        }
        return updateStep(player, target, (track, step) -> {
            PathStep path = requirePath(track, target);
            if (target.pointIndex() >= path.points().size()) {
                throw abort(Result.fail(pointMissing(target)));
            }
            int to = target.pointIndex() + delta;
            if (to < 0 || to >= path.points().size()) {
                throw abort(Result.fail("路径点 " + (target.pointIndex() + 1) + " 无法向这个方向移动."));
            }
            List<Pose> points = new ArrayList<>(path.points());
            Pose moved = points.remove(target.pointIndex());
            points.add(to, moved);
            return new PathStep(points, path.durationTicks(), path.easing(), path.sway());
        });
    }

    // ------------------------------------------------------------------ preview / save / undo

    public Result preview(Player player, EditTarget target) {
        CameraTrack track = view(target.trackId());
        if (track == null) {
            return Result.fail("找不到轨道 " + target.trackId() + ".");
        }
        if (track.steps().isEmpty()) {
            return Result.fail("轨道还没有步骤，无法预览.");
        }
        if (target.hasStep() && target.stepIndex() >= track.steps().size()) {
            return Result.fail(stepMissing(target));
        }
        if (engine.isPlaying(player)) {
            return Result.fail("当前正在播放镜头，无法开始预览.");
        }
        if (!player.getWorld().getName().equals(track.worldName())) {
            return Result.fail("请先前往轨道所在世界 " + track.worldName() + ".");
        }
        int start = target.hasStep() ? target.stepIndex() : 0;
        boolean started;
        try {
            // The 3-argument overload previews from start to the end of the track.
            started = engine.playPreview(player, track.id(), start);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not start camera preview for " + player.getName(), exception);
            started = false;
        }
        if (!started) {
            return Result.fail("预览启动失败（可能已有镜头在播放，或玩家不在轨道世界）.");
        }
        return Result.ok(track, target.hasStep() ? target.stepIndex() : -1);
    }

    public Result stopPreview(Player player) {
        Optional<PlaybackInfo> info = engine.playback(player);
        if (info.isEmpty() || !info.get().preview()) {
            return Result.fail("当前没有正在预览的轨道.");
        }
        engine.stop(player);
        return Result.ok(null, -1);
    }

    /** Forces a write of the repository to disk. */
    public Result save(Player player, String trackId) {
        CameraTrack track = view(trackId);
        if (track == null) {
            return Result.fail("找不到轨道 " + trackId + ".");
        }
        saveAndReport(player);
        return Result.ok(track, -1);
    }

    /**
     * Restores the track to its state before the player's latest command edit of it. The restore
     * only happens when the stored value still equals the recorded after snapshot; otherwise the
     * track was changed by somebody else (another player, the Dialog editor, a reload or a
     * hand-edited tracks.yml) and the undo is refused instead of reverting that work.
     */
    public Result undo(Player player, String trackId) {
        if (trackId == null || trackId.isBlank()) {
            return Result.fail("找不到轨道 " + trackId + ".");
        }
        try {
            CameraTrack restored = repository.compute(trackId, current -> {
                if (current == null) {
                    throw abort(Result.fail("找不到轨道 " + trackId + "."));
                }
                Result locked = lockedFailure(player, current.id());
                if (locked != null) {
                    throw abort(locked);
                }
                UndoHistory history = undoHistories.get(player.getUniqueId());
                if (history == null) {
                    throw abort(Result.fail("没有可以撤销的修改."));
                }
                UndoHistory.Lookup lookup = history.lookup(current.id(), current);
                if (lookup.stale()) {
                    throw abort(Result.fail("轨道在撤销后被修改过，无法撤销."));
                }
                if (!lookup.ready() || !history.remove(lookup.entry())) {
                    throw abort(Result.fail("没有可以撤销的修改."));
                }
                return lookup.entry().before();
            });
            saveAndReport(player);
            return Result.ok(restored, -1);
        } catch (AbortEdit edit) {
            return edit.result;
        }
    }

    /**
     * Forgets every undo entry. Registered with the repository, so a reload invokes it inside the
     * repository write lock (the same lock order as {@code updateTrack}'s snapshot push), which
     * closes the window where an undo could replay a pre-reload snapshot over the new map.
     */
    public void onReload() {
        undoHistories.clear();
    }

    /** Drops the undo entries of one deleted track (called by the repository delete hook). */
    private void forgetTrack(String trackId) {
        for (UndoHistory history : undoHistories.values()) {
            history.forgetTrack(trackId);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The Dialog editor holds the track in its own draft; command/tool edits must not race it.
     * Returns a user-facing failure, or {@code null} when the track is free.
     *
     * <p>The check runs inside {@link TrackRepository#compute}, i.e. under the repository write
     * lock together with the write it guards. The Dialog acquires its lock before reading the
     * draft, so either the write happens before that read (the draft sees it) or the check sees
     * the lock and refuses.</p>
     */
    private Result lockedFailure(Player player, String trackId) {
        if (locks.lockedByOther(trackId, player.getUniqueId())) {
            return Result.fail("轨道 " + trackId + " 正在被其他玩家的 Dialog 编辑器编辑，暂时不能修改.");
        }
        if (locks.locked(trackId)) {
            return Result.fail("轨道 " + trackId + " 正在 Dialog 编辑器中编辑，请先关闭编辑器会话后再修改.");
        }
        return null;
    }

    /** The player's current view; throws when a camera session is running. */
    private Pose eyePose(Player player) {
        if (engine.isPlaying(player)) {
            throw new IllegalArgumentException("镜头播放中，无法取景，请先停止预览.");
        }
        Location eye = player.getEyeLocation();
        return new Pose(eye.getX(), eye.getY(), eye.getZ(), player.getYaw(), player.getPitch());
    }

    /** The player's current feet position; throws when a camera session is running. */
    private Pose feetPose(Player player) {
        if (engine.isPlaying(player)) {
            throw new IllegalArgumentException("镜头播放中，无法取景，请先停止预览.");
        }
        Location feet = player.getLocation();
        return new Pose(feet.getX(), feet.getY(), feet.getZ(), player.getYaw(), player.getPitch());
    }

    /** Aborts unless the player stands in the track's world. */
    private static void requireWorld(CameraTrack track, Player player) {
        if (!player.getWorld().getName().equals(track.worldName())) {
            throw abort(Result.fail("请先前往轨道所在世界 " + track.worldName() + "."));
        }
    }

    /** The addressed step as a path step, or an aborted result. */
    private static PathStep requirePath(CameraTrack track, EditTarget target) {
        Step step = track.steps().get(target.stepIndex());
        if (!(step instanceof PathStep path)) {
            throw abort(Result.fail("步骤 " + (target.stepIndex() + 1) + " 不是路径步骤."));
        }
        return path;
    }

    private static String stepMissing(EditTarget target) {
        return "轨道 " + target.trackId() + " 没有步骤 " + (target.stepIndex() + 1) + ".";
    }

    private static String pointMissing(EditTarget target) {
        return "步骤 " + (target.stepIndex() + 1) + " 没有路径点 " + (target.pointIndex() + 1) + ".";
    }

    private UndoHistory history(Player player) {
        return undoHistories.computeIfAbsent(player.getUniqueId(), ignored -> new UndoHistory());
    }

    private void saveAndReport(Player player) {
        repository.saveAsyncLogged().whenComplete((ignored, error) -> {
            if (error == null || !player.isOnline()) {
                return;
            }
            player.getScheduler().execute(plugin,
                    () -> messages.get().send(player, "editor.save-failed"), () -> {
                    }, 0L);
        });
    }

    private static String message(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? "无法完成这个修改（" + current.getClass().getSimpleName() + "）."
                : message;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        undoHistories.remove(event.getPlayer().getUniqueId());
    }
}
