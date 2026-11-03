package net.ria.zth.camerarail.model;

/**
 * Smoothly moves from the current pose to {@code to}.
 */
public record MoveStep(Pose to, int durationTicks, Easing easing, SwayPreset sway) implements CameraStep {
    public MoveStep {
        if (to == null) {
            throw new IllegalArgumentException("Move target is required");
        }
        if (durationTicks < 1) {
            throw new IllegalArgumentException("Move duration must be at least 1 tick");
        }
        if (easing == null) {
            throw new IllegalArgumentException("Move easing is required");
        }
        if (sway == null) {
            throw new IllegalArgumentException("Move sway is required");
        }
    }

    @Override
    public StepType type() {
        return StepType.MOVE;
    }

    @Override
    public int timedDurationTicks() {
        return durationTicks;
    }

    @Override
    public String describe() {
        return "移动 " + durationTicks + "t · " + easing.name().toLowerCase(java.util.Locale.ROOT);
    }
}
