package red.ria.camerarail.model;

/**
 * Instantly changes to another pose. Useful for deliberate hard cuts between shots.
 *
 * <p>The jump has no interpolation. {@code durationTicks} then keeps the camera at
 * {@code to}: {@code 0} is a pure cut that occupies no ticks at all (the next step starts
 * on the same tick), while a positive value holds the new pose - and its sway - for that
 * many ticks, which count toward the track's timed duration.</p>
 */
public record CutStep(Pose to, int durationTicks, SwayPreset sway) implements CameraStep {
    public CutStep {
        if (to == null) {
            throw new IllegalArgumentException("Cut target is required");
        }
        if (durationTicks < 0) {
            throw new IllegalArgumentException("Cut duration cannot be negative");
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
        return durationTicks;
    }

    @Override
    public String describe() {
        return durationTicks > 0 ? "硬切 " + durationTicks + "t" : "硬切";
    }
}
