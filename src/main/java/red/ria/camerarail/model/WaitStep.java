package red.ria.camerarail.model;

/**
 * Holds the camera until an external signal is delivered.
 *
 * @param signal unique signal name within the track
 * @param timeoutTicks maximum wait in ticks; 0 disables the timeout
 */
public record WaitStep(String signal, int timeoutTicks, SwayPreset sway) implements CameraStep {
    public WaitStep {
        if (signal == null || signal.isBlank()) {
            throw new IllegalArgumentException("Wait signal is required");
        }
        if (signal.length() > 80) {
            throw new IllegalArgumentException("Wait signal is longer than 80 characters");
        }
        if (timeoutTicks < 0) {
            throw new IllegalArgumentException("Wait timeout cannot be negative");
        }
        if (sway == null) {
            throw new IllegalArgumentException("Wait sway is required");
        }
    }

    @Override
    public StepType type() {
        return StepType.WAIT;
    }

    @Override
    public int timedDurationTicks() {
        return 0;
    }

    @Override
    public String describe() {
        return "等待 " + signal;
    }
}
