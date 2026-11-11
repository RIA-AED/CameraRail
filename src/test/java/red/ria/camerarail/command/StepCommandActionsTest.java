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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepCommandActionsTest {
    private static final Pose POSE = new Pose(1, 2, 3, 0, 0);

    @Test
    void everyStepTypeGetsItsOwnFieldsPlusStructuralActions() {
        MoveStep move = new MoveStep(POSE, 100, Easing.LINEAR, SwayPreset.INHERIT);
        PathStep path = new PathStep(List.of(POSE, POSE), 100, Easing.LINEAR, SwayPreset.INHERIT);
        HoldStep hold = new HoldStep(40, SwayPreset.INHERIT);
        WaitStep wait = new WaitStep("login:ready", 6000, SwayPreset.INHERIT);
        CutStep cut = new CutStep(POSE, 0, SwayPreset.INHERIT);
        TitleStep title = new TitleStep("a", "b", 20, 60, 20);

        assertFields(move, "here", "pos", "duration", "easing", "sway");
        assertFields(path, "duration", "easing", "sway", "point");
        assertFields(hold, "duration", "sway");
        assertFields(wait, "signal", "timeout", "sway");
        assertFields(cut, "here", "pos", "duration", "sway");
        assertFields(title, "title", "subtitle", "fade");

        assertFalse(EditActions.actions(title).contains("duration"));
        assertFalse(EditActions.actions(title).contains("sway"));
        assertFalse(EditActions.actions(path).contains("here"));
        assertFalse(EditActions.actions(wait).contains("easing"));
    }

    private static void assertFields(Step step, String... fields) {
        List<String> actions = EditActions.actions(step);
        for (String field : fields) {
            assertTrue(actions.contains(field), step.type() + " should support " + field + ": " + actions);
        }
        for (String structural : List.of("remove", "duplicate", "up", "down", "preview", "stop")) {
            assertTrue(actions.contains(structural), step.type() + " should support " + structural);
        }
    }
}
