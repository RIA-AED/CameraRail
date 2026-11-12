package red.ria.camerarail.command;

import org.junit.jupiter.api.Test;
import red.ria.camerarail.model.CutStep;
import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.MoveStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.TitleStep;
import red.ria.camerarail.model.WaitStep;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command-facing action vocabulary: which actions each layer/step type advertises for
 * {@code wrongAction} hints and tab completion. Field mutation itself is covered by
 * {@link StepFieldsTest}.
 */
class EditActionsTest {
    private static final Pose POSE = new Pose(1, 2, 3, 10, 20);
    private static final MoveStep MOVE = new MoveStep(POSE, 100, Easing.LINEAR, SwayPreset.INHERIT);
    private static final PathStep PATH = new PathStep(List.of(POSE), 100, Easing.LINEAR,
            SwayPreset.INHERIT);
    private static final HoldStep HOLD = new HoldStep(40, SwayPreset.INHERIT);
    private static final WaitStep WAIT = new WaitStep("login:ready", 6000, SwayPreset.INHERIT);
    private static final CutStep CUT = new CutStep(POSE, 0, SwayPreset.INHERIT);
    private static final TitleStep TITLE = new TitleStep("a", "b", 20, 60, 20);

    @Test
    void typeNamesAreStable() {
        assertEquals("move", EditActions.name(MOVE));
        assertEquals("path", EditActions.name(PATH));
        assertEquals("hold", EditActions.name(HOLD));
        assertEquals("wait", EditActions.name(WAIT));
        assertEquals("cut", EditActions.name(CUT));
        assertEquals("title", EditActions.name(TITLE));
    }

    @Test
    void structuralActionsAreSharedAndTypeFieldsAreNot() {
        for (Step step : List.of(MOVE, PATH, HOLD, WAIT, CUT, TITLE)) {
            for (String action : EditActions.STRUCTURE_ACTIONS) {
                assertTrue(EditActions.actions(step).contains(action),
                        step.type() + " should support " + action);
            }
        }
        assertEquals(List.of("duration", "easing", "sway", "point"),
                EditActions.actions(PATH).stream()
                        .filter(action -> !EditActions.STRUCTURE_ACTIONS.contains(action)).toList());
    }

    @Test
    void layerActionListsAreTheCommandVocabulary() {
        assertEquals(List.of("here", "pos", "remove", "up", "down", "insert", "stop"),
                EditActions.POINT_ACTIONS);
        assertEquals(List.of("display", "description", "start", "exit", "exit-mode", "sway",
                "follow", "id", "entry", "list", "add", "preview", "stop", "undo", "save"),
                EditActions.TRACK_ACTIONS);
    }
}
