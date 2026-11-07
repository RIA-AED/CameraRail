package red.ria.camerarail.runtime;

import red.ria.camerarail.model.CameraStep;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.CutStep;
import red.ria.camerarail.model.MoveStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.SwayPreset;

/**
 * Pure timeline queries for a track. The editor uses these to start a preview in the middle
 * of a track and to label steps with their cumulative start time.
 */
public final class TrackTimeline {
    private TrackTimeline() {
    }

    /**
     * Normalizes a step index to the range {@code [0, steps.size()]}.
     */
    public static int clampStep(CameraTrack track, int stepIndex) {
        return Math.max(0, Math.min(stepIndex, track.steps().size()));
    }

    /**
     * Camera pose at the instant {@code stepIndex} begins. Effects, titles, holds and waits
     * leave the pose unchanged.
     */
    public static Pose poseAtStepStart(CameraTrack track, int stepIndex) {
        Pose pose = track.start();
        int limit = clampStep(track, stepIndex);
        for (int i = 0; i < limit; i++) {
            Step step = track.steps().get(i);
            switch (step) {
                case MoveStep move -> pose = move.to();
                case PathStep path -> pose = path.points().getLast();
                case CutStep cut -> pose = cut.to();
                default -> {
                }
            }
        }
        return pose;
    }

    /**
     * Step sway override active when {@code stepIndex} begins. Step sway only changes the
     * amplitudes, so reusing the last override keeps the phase continuous.
     */
    public static SwayPreset swayAtStepStart(CameraTrack track, int stepIndex) {
        SwayPreset sway = SwayPreset.INHERIT;
        int limit = clampStep(track, stepIndex);
        for (int i = 0; i < limit; i++) {
            if (track.steps().get(i) instanceof CameraStep cameraStep) {
                sway = cameraStep.sway();
            }
        }
        return sway;
    }

    /**
     * Timed ticks contributed by the steps before {@code stepIndex}.
     */
    public static int cumulativeTicksBefore(CameraTrack track, int stepIndex) {
        int total = 0;
        int limit = clampStep(track, stepIndex);
        for (int i = 0; i < limit; i++) {
            if (track.steps().get(i) instanceof CameraStep cameraStep) {
                total += cameraStep.timedDurationTicks();
            }
        }
        return total;
    }
}
