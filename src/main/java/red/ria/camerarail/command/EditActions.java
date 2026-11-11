package red.ria.camerarail.command;

import red.ria.camerarail.model.CutStep;
import red.ria.camerarail.model.EffectStep;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.MoveStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.TitleStep;
import red.ria.camerarail.model.WaitStep;

import java.util.ArrayList;
import java.util.List;

/**
 * The command-facing action vocabulary of the edit family: which actions a target layer accepts
 * and which type-specific actions a step type adds on top of the structural ones.
 *
 * <p>Kept separate from {@link StepFields} so one class owns "what may be typed" (used by wrong
 * action hints, tab completion and help assertions) and the other owns "what the field does"
 * (immutable copy-with operations and step lookups). Both are stateless static holders; no
 * handler depends on the other through them.</p>
 */
final class EditActions {
    /** Actions every step type shares. */
    static final List<String> STRUCTURE_ACTIONS =
            List.of("remove", "duplicate", "up", "down", "preview", "stop");
    /** Actions of the whole track. */
    static final List<String> TRACK_ACTIONS = List.of("display", "description", "start", "exit",
            "exit-mode", "sway", "follow", "entry", "list", "add", "preview", "stop", "undo", "save");
    /** Actions of one path point. */
    static final List<String> POINT_ACTIONS =
            List.of("here", "pos", "remove", "up", "down", "insert", "stop");

    private EditActions() {
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
}
