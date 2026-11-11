package red.ria.camerarail.command;

import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.CutStep;
import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.EffectStep;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.MoveStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.TitleStep;
import red.ria.camerarail.model.WaitStep;

import java.util.Locale;

/**
 * Step-type field operations used by the edit handlers: the single source for the type-specific
 * "copy with a different duration/easing/sway" switches, the target-pose lookup and the path-step
 * queries/formatting the point commands need.
 *
 * <p>The command-facing action vocabulary lives in {@link EditActions}; this class only answers
 * "what is this step's field X" and never decides which commands may be typed.</p>
 */
final class StepFields {
    private StepFields() {
    }

    /**
     * A copy of {@code step} with a different duration. Returns {@code null} for types without a
     * duration field; the service turns that into the usual "not applicable" failure.
     */
    static Step withDuration(Step step, int ticks) {
        return switch (step) {
            case MoveStep move -> new MoveStep(move.to(), ticks, move.easing(), move.sway());
            case PathStep path -> new PathStep(path.points(), ticks, path.easing(), path.sway());
            case HoldStep hold -> new HoldStep(ticks, hold.sway());
            case CutStep cut -> new CutStep(cut.to(), ticks, cut.sway());
            case WaitStep wait -> new WaitStep(wait.signal(), ticks, wait.sway());
            case EffectStep ignored -> null;
            case TitleStep ignored -> null;
        };
    }

    /** A copy of {@code step} with a different easing, or {@code null} for types without one. */
    static Step withEasing(Step step, Easing easing) {
        return switch (step) {
            case MoveStep move -> new MoveStep(move.to(), move.durationTicks(), easing, move.sway());
            case PathStep path -> new PathStep(path.points(), path.durationTicks(), easing, path.sway());
            case HoldStep ignored -> null;
            case WaitStep ignored -> null;
            case CutStep ignored -> null;
            case EffectStep ignored -> null;
            case TitleStep ignored -> null;
        };
    }

    /** A copy of {@code step} with a different sway, or {@code null} for types without one. */
    static Step withSway(Step step, SwayPreset sway) {
        return switch (step) {
            case MoveStep move -> new MoveStep(move.to(), move.durationTicks(), move.easing(), sway);
            case PathStep path -> new PathStep(path.points(), path.durationTicks(), path.easing(), sway);
            case HoldStep hold -> new HoldStep(hold.durationTicks(), sway);
            case WaitStep wait -> new WaitStep(wait.signal(), wait.timeoutTicks(), sway);
            case CutStep cut -> new CutStep(cut.to(), cut.durationTicks(), sway);
            case EffectStep ignored -> null;
            case TitleStep ignored -> null;
        };
    }

    /** The target pose of a move/cut step, or {@code null} for every other step type. */
    static Pose targetPose(Step step) {
        return switch (step) {
            case MoveStep move -> move.to();
            case CutStep cut -> cut.to();
            case PathStep ignored -> null;
            case HoldStep ignored -> null;
            case WaitStep ignored -> null;
            case EffectStep ignored -> null;
            case TitleStep ignored -> null;
        };
    }

    /** The path step at {@code stepIndex}, or {@code null} when the index is missing or not a path. */
    static PathStep pathStep(CameraTrack track, int stepIndex) {
        if (track == null || stepIndex < 0 || stepIndex >= track.steps().size()) {
            return null;
        }
        return track.steps().get(stepIndex) instanceof PathStep path ? path : null;
    }

    /** Number of path points of the path step at {@code stepIndex}; zero when it is not a path. */
    static int pathCount(CameraTrack track, int stepIndex) {
        PathStep path = pathStep(track, stepIndex);
        return path == null ? 0 : path.points().size();
    }

    static String swayName(SwayPreset preset) {
        return preset.name().toLowerCase(Locale.ROOT);
    }

    static String easingName(Easing easing) {
        return easing.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
