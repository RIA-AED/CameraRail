package red.ria.camerarail.command;

import org.junit.jupiter.api.Test;
import red.ria.camerarail.model.CutStep;
import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.MoveStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.TitleStep;
import red.ria.camerarail.model.WaitStep;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Step-type field operations used by the edit commands: the single source for the type-specific
 * "copy with a different duration/easing/sway" switches and for the target pose lookup. The
 * command-facing action vocabulary is covered by {@link EditActionsTest}.
 */
class StepFieldsTest {
    private static final Pose POSE = new Pose(1, 2, 3, 10, 20);
    private static final Pose OTHER = new Pose(4, 5, 6, 30, 40);

    private static final MoveStep MOVE = new MoveStep(POSE, 100, Easing.LINEAR, SwayPreset.INHERIT);
    private static final PathStep PATH = new PathStep(List.of(POSE, OTHER), 100, Easing.LINEAR,
            SwayPreset.INHERIT);
    private static final HoldStep HOLD = new HoldStep(40, SwayPreset.INHERIT);
    private static final WaitStep WAIT = new WaitStep("login:ready", 6000, SwayPreset.INHERIT);
    private static final CutStep CUT = new CutStep(POSE, 0, SwayPreset.INHERIT);
    private static final TitleStep TITLE = new TitleStep("a", "b", 20, 60, 20);

    @Test
    void durationIsReplacedOnEveryTimedType() {
        assertEquals(250, ((MoveStep) StepFields.withDuration(MOVE, 250)).durationTicks());
        assertEquals(250, ((PathStep) StepFields.withDuration(PATH, 250)).durationTicks());
        assertEquals(250, ((HoldStep) StepFields.withDuration(HOLD, 250)).durationTicks());
        assertEquals(250, ((WaitStep) StepFields.withDuration(WAIT, 250)).timeoutTicks());
        assertEquals(250, ((CutStep) StepFields.withDuration(CUT, 250)).durationTicks());
        assertNull(StepFields.withDuration(TITLE, 250), "title has no duration field");
        assertSame(POSE, ((MoveStep) StepFields.withDuration(MOVE, 250)).to());
    }

    @Test
    void easingIsReplacedOnlyWhereSupported() {
        Easing eased = Easing.EASE_OUT;
        assertEquals(eased, ((MoveStep) StepFields.withEasing(MOVE, eased)).easing());
        assertEquals(eased, ((PathStep) StepFields.withEasing(PATH, eased)).easing());
        assertNull(StepFields.withEasing(HOLD, eased));
        assertNull(StepFields.withEasing(WAIT, eased));
        assertNull(StepFields.withEasing(CUT, eased));
        assertNull(StepFields.withEasing(TITLE, eased));
    }

    @Test
    void swayIsReplacedOnlyWhereSupported() {
        SwayPreset sway = SwayPreset.STRONG;
        assertEquals(sway, ((MoveStep) StepFields.withSway(MOVE, sway)).sway());
        assertEquals(sway, ((PathStep) StepFields.withSway(PATH, sway)).sway());
        assertEquals(sway, ((HoldStep) StepFields.withSway(HOLD, sway)).sway());
        assertEquals(sway, ((WaitStep) StepFields.withSway(WAIT, sway)).sway());
        assertEquals(sway, ((CutStep) StepFields.withSway(CUT, sway)).sway());
        assertNull(StepFields.withSway(TITLE, sway));
    }

    @Test
    void targetPoseExistsOnlyForPositionedSteps() {
        assertSame(POSE, StepFields.targetPose(MOVE));
        assertSame(POSE, StepFields.targetPose(CUT));
        assertNull(StepFields.targetPose(PATH));
        assertNull(StepFields.targetPose(HOLD));
        assertNull(StepFields.targetPose(WAIT));
        assertNull(StepFields.targetPose(TITLE));
    }
}
