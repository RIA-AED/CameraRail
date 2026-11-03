package net.ria.zth.camerarail.model;

/**
 * A step that determines where the camera is while it runs.
 */
public sealed interface CameraStep extends Step
        permits MoveStep, PathStep, HoldStep, WaitStep, CutStep {
    SwayPreset sway();

    /**
     * Number of ticks this step occupies. Waits and cuts have no fixed duration.
     */
    int timedDurationTicks();
}
