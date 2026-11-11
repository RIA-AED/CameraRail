package red.ria.camerarail.command;

import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.MoveStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.TitleStep;
import red.ria.camerarail.model.WaitStep;
import red.ria.camerarail.support.TestMessages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditFeedbackTest {
    private static final Pose POSE = new Pose(1.5, 64, -2.25, 90, 10);

    private static EditFeedback feedback() {
        return new EditFeedback(TestMessages::bundled, "camerarail", null);
    }

    @Test
    void stepSummariesUseTheBundledMessageKeys() {
        EditFeedback feedback = feedback();
        String move = feedback.summary(new MoveStep(POSE, 120, Easing.EASE_IN, SwayPreset.OFF));
        assertTrue(move.contains("1.50, 64.00, -2.25"), move);
        assertTrue(move.contains("yaw 90.00° / pitch 10.00°"), move);
        assertTrue(move.contains("120t"), move);
        assertTrue(move.contains("ease-in"), move);
        assertTrue(move.contains("off"), move);

        String wait = feedback.summary(new WaitStep("login:ready", 6000, SwayPreset.INHERIT));
        assertTrue(wait.contains("login:ready"), wait);
        assertTrue(wait.contains("6000t"), wait);
        assertTrue(wait.contains("inherit"), wait);

        String hold = feedback.summary(new HoldStep(40, SwayPreset.NATURAL));
        assertTrue(hold.contains("40t"), hold);
        assertTrue(hold.contains("natural"), hold);

        String path = feedback.summary(new PathStep(List.of(POSE, POSE), 80, Easing.LINEAR,
                SwayPreset.GENTLE));
        assertTrue(path.contains("2 个路径点"), path);
        assertTrue(path.contains("80t"), path);
        assertTrue(path.contains("gentle"), path);

        String title = feedback.summary(new TitleStep("hello", "world", 20, 60, 20));
        assertTrue(title.contains("hello") && title.contains("world"), title);
        assertTrue(title.contains("20/60/20t"), title);
    }

    @Test
    void stepNamesPoseFormattingAndEscapingMatchTheCommandSurface() {
        EditFeedback feedback = feedback();
        assertEquals("move", feedback.stepTypeName(new MoveStep(POSE, 1, Easing.LINEAR, SwayPreset.INHERIT)));
        assertEquals("path", feedback.stepTypeName(new PathStep(List.of(POSE), 1, Easing.LINEAR,
                SwayPreset.INHERIT)));
        assertEquals("1.50, 64.00, -2.25 · yaw 90.00° / pitch 10.00°", feedback.poseShort(POSE));
        assertEquals("2.50", feedback.fmt(2.5));
        assertEquals("", EditFeedback.safeText(null));
        assertEquals("\\<red>hi\\</red>", EditFeedback.safeText("<red>hi</red>"));
    }
}
