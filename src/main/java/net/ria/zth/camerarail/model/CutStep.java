package net.ria.zth.camerarail.model;

/**
 * Instantly changes to another pose. Useful for deliberate hard cuts between shots.
 */
public record CutStep(Pose to, SwayPreset sway) implements CameraStep {
    public CutStep {
        if (to == null) {
            throw new IllegalArgumentException("Cut target is required");
        }
        if (sway == null) {
            throw new IllegalArgumentException("Cut sway is required");
        }
    }

    @Override
    public StepType type() {
        return StepType.CUT;
    }

    @Override
    public int timedDurationTicks() {
        return 0;
    }

    @Override
    public String describe() {
        return "切镜";
    }
}
