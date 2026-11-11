package red.ria.camerarail.command;

import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.CutStep;
import red.ria.camerarail.model.EffectStep;
import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.MoveStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.TitleStep;
import red.ria.camerarail.model.WaitStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Step-type metadata used by the command layer: the display name, the type-specific actions and
 * the shared structural actions, plus the path-step lookups the point commands need.
 *
 * <p>Kept separate from {@link EditFeedback} so the rendering module and the completion module can
 * share exactly one definition of "which actions does this step type accept".</p>
 */
final class StepFields {
    /** Actions every step type shares. */
    static final List<String> STRUCTURE_ACTIONS =
            List.of("remove", "duplicate", "up", "down", "preview", "stop");
    /** Actions of the whole track. */
    static final List<String> TRACK_ACTIONS = List.of("display", "description", "start", "exit",
            "exit-mode", "sway", "follow", "entry", "list", "add", "preview", "stop", "undo", "save");
    /** Actions of one path point. */
    static final List<String> POINT_ACTIONS =
            List.of("here", "pos", "remove", "up", "down", "insert", "stop");

    private StepFields() {
    }

    /** The type-specific actions of {@code step}, followed by the shared structural actions. */
    static List<String> actions(Step step) {
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
        actions.addAll(STRUCTURE_ACTIONS);
        return List.copyOf(actions);
    }

    /** The command-facing name of this step type. */
    static String name(Step step) {
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
