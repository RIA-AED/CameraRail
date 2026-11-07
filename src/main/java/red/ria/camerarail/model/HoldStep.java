package red.ria.camerarail.model;

/**
 * Keeps the camera still (apart from sway) for a fixed number of ticks.
 */
public record HoldStep(int durationTicks, SwayPreset sway) implements CameraStep {
    public HoldStep {
        if (durationTicks < 1) {
            throw new IllegalArgumentException("Hold duration must be at least 1 tick");
        }
        if (sway == null) {
            throw new IllegalArgumentException("Hold sway is required");
        }
    }

    @Override
    public StepType type() {
        return StepType.HOLD;
    }

    @Override
    public int timedDurationTicks() {
        return durationTicks;
    }

    @Override
    public String describe() {
        return "停留 " + durationTicks + "t";
    }
}
