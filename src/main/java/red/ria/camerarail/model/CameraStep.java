package red.ria.camerarail.model;

/**
 * A step that determines where the camera is while it runs.
 */
public sealed interface CameraStep extends Step
        permits MoveStep, PathStep, HoldStep, WaitStep, CutStep {
    SwayPreset sway();

    /**
     * Number of ticks this step occupies on the timeline. Waits have no fixed duration;
     * a cut returns its post-jump hold, which is {@code 0} for a pure instant cut.
     */
    int timedDurationTicks();
}
