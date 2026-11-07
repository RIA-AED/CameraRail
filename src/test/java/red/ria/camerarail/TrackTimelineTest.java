package red.ria.camerarail;

import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.CutStep;
import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.ExitMode;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.MoveStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;
import red.ria.camerarail.model.TitleStep;
import red.ria.camerarail.runtime.TrackTimeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackTimelineTest {
    private CameraTrack track;

    @BeforeEach
    void setUp() {
        List<Step> steps = List.of(
                new MoveStep(new Pose(10, 0, 0, 0, 0), 20, Easing.LINEAR, SwayPreset.GENTLE),
                new HoldStep(10, SwayPreset.OFF),
                new PathStep(List.of(new Pose(20, 0, 0, 0, 0), new Pose(30, 0, 0, 0, 0)),
                        30, Easing.SINE, SwayPreset.NATURAL),
                new TitleStep("t", "", 0, 0, 0),
                new CutStep(new Pose(40, 0, 0, 0, 0), 15, SwayPreset.STRONG),
                new HoldStep(5, SwayPreset.INHERIT)
        );
        track = new CameraTrack("test", "Test", "", "world",
                new Pose(0, 0, 0, 0, 0), ExitMode.ANIMATION_END, null,
                SwaySettings.ofPreset(SwayPreset.NATURAL, 160, 42),
                true, List.of(), steps);
    }

    @Test
    void poseAtStepStartFollowsMovePathAndCut() {
        assertEquals(new Pose(0, 0, 0, 0, 0), TrackTimeline.poseAtStepStart(track, 0));
        assertEquals(new Pose(10, 0, 0, 0, 0), TrackTimeline.poseAtStepStart(track, 1));
        assertEquals(new Pose(10, 0, 0, 0, 0), TrackTimeline.poseAtStepStart(track, 2));
        assertEquals(new Pose(30, 0, 0, 0, 0), TrackTimeline.poseAtStepStart(track, 3));
        assertEquals(new Pose(30, 0, 0, 0, 0), TrackTimeline.poseAtStepStart(track, 4));
        assertEquals(new Pose(40, 0, 0, 0, 0), TrackTimeline.poseAtStepStart(track, 5));
        assertEquals(new Pose(40, 0, 0, 0, 0), TrackTimeline.poseAtStepStart(track, 6));
    }

    @Test
    void poseAtStepStartClampsIndex() {
        assertEquals(new Pose(0, 0, 0, 0, 0), TrackTimeline.poseAtStepStart(track, -5));
        assertEquals(new Pose(40, 0, 0, 0, 0), TrackTimeline.poseAtStepStart(track, 99));
    }

    @Test
    void swayAtStepStartUsesLastCameraStepOverride() {
        assertEquals(SwayPreset.INHERIT, TrackTimeline.swayAtStepStart(track, 0));
        assertEquals(SwayPreset.GENTLE, TrackTimeline.swayAtStepStart(track, 1));
        assertEquals(SwayPreset.OFF, TrackTimeline.swayAtStepStart(track, 2));
        assertEquals(SwayPreset.NATURAL, TrackTimeline.swayAtStepStart(track, 3));
        assertEquals(SwayPreset.NATURAL, TrackTimeline.swayAtStepStart(track, 4));
        assertEquals(SwayPreset.STRONG, TrackTimeline.swayAtStepStart(track, 5));
        assertEquals(SwayPreset.INHERIT, TrackTimeline.swayAtStepStart(track, 6));
    }

    @Test
    void cumulativeTicksBeforeSumsTimedCameraSteps() {
        assertEquals(0, TrackTimeline.cumulativeTicksBefore(track, 0));
        assertEquals(20, TrackTimeline.cumulativeTicksBefore(track, 1));
        assertEquals(30, TrackTimeline.cumulativeTicksBefore(track, 2));
        assertEquals(60, TrackTimeline.cumulativeTicksBefore(track, 3));
        assertEquals(60, TrackTimeline.cumulativeTicksBefore(track, 4));
        assertEquals(75, TrackTimeline.cumulativeTicksBefore(track, 5));
        assertEquals(80, TrackTimeline.cumulativeTicksBefore(track, 6));
        assertEquals(80, TrackTimeline.cumulativeTicksBefore(track, 99));
    }

    @Test
    void cutWithoutDurationOccupiesNoTicks() {
        CutStep instantCut = new CutStep(new Pose(40, 0, 0, 0, 0), 0, SwayPreset.STRONG);
        CameraTrack instant = track.withSteps(List.of(instantCut, new HoldStep(5, SwayPreset.INHERIT)));

        assertEquals(0, instantCut.timedDurationTicks());
        assertEquals(0, TrackTimeline.cumulativeTicksBefore(instant, 1));
        assertEquals(5, instant.timedDurationTicks());
    }

    @Test
    void cutHoldDurationCountsTowardTheTrackTimeline() {
        assertEquals(80, track.timedDurationTicks());
        assertEquals(15, ((CutStep) track.steps().get(4)).timedDurationTicks());
    }
}
