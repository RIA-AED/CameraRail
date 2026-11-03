package net.ria.zth.camerarail.runtime;

import net.ria.zth.camerarail.model.SwaySettings;

/**
 * Evaluates a continuous, deterministic two-frequency sway. Amplitudes blend slowly when
 * the active step changes, so switching between "still" and "wind" does not snap.
 */
public final class SwayAnimator {
    private static final double BLEND = 0.08;

    private final int period;
    private final double[] phases = new double[5];

    private double amplitudeX;
    private double amplitudeY;
    private double amplitudeZ;
    private double amplitudeYaw;
    private double amplitudePitch;
    private boolean initialized;

    public SwayAnimator(SwaySettings initial) {
        this.period = initial.period();
        long state = initial.seed() == 0 ? 0x9E3779B97F4A7C15L : initial.seed();
        for (int i = 0; i < phases.length; i++) {
            state = mix(state + i * 0x9E3779B97F4A7C15L);
            phases[i] = (state >>> 11) * 0x1.0p-53 * Math.PI * 2.0;
        }
        // Start still and fade the first target in; the configured start pose remains the
        // exact first frame instead of being offset by sway.
        this.amplitudeX = 0.0;
        this.amplitudeY = 0.0;
        this.amplitudeZ = 0.0;
        this.amplitudeYaw = 0.0;
        this.amplitudePitch = 0.0;
        this.initialized = true;
    }

    /**
     * Advances the amplitude blend by one tick and evaluates the sway at {@code tick}.
     */
    public SwayOffset tick(SwaySettings target, long tick) {
        if (!initialized) {
            amplitudeX = target.x();
            amplitudeY = target.y();
            amplitudeZ = target.z();
            amplitudeYaw = target.yaw();
            amplitudePitch = target.pitch();
            initialized = true;
        } else {
            amplitudeX += (target.x() - amplitudeX) * BLEND;
            amplitudeY += (target.y() - amplitudeY) * BLEND;
            amplitudeZ += (target.z() - amplitudeZ) * BLEND;
            amplitudeYaw += (target.yaw() - amplitudeYaw) * BLEND;
            amplitudePitch += (target.pitch() - amplitudePitch) * BLEND;
        }

        double phase = (tick % (long) period) / (double) period * Math.PI * 2.0;
        return new SwayOffset(
                amplitudeX * wave(phase, phases[0]),
                amplitudeY * wave(phase, phases[1]),
                amplitudeZ * wave(phase, phases[2]),
                amplitudeYaw * wave(phase, phases[3]),
                amplitudePitch * wave(phase, phases[4])
        );
    }

    public double amplitudeX() {
        return amplitudeX;
    }

    public double amplitudeY() {
        return amplitudeY;
    }

    public double amplitudeZ() {
        return amplitudeZ;
    }

    public double amplitudeYaw() {
        return amplitudeYaw;
    }

    public double amplitudePitch() {
        return amplitudePitch;
    }

    private static double wave(double phase, double offset) {
        double first = Math.sin(phase + offset);
        double second = Math.sin(phase * 2.371 + offset * 1.7 + 1.113);
        return 0.64 * first + 0.36 * second;
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /**
     * Offset in blocks and degrees.
     */
    public record SwayOffset(double x, double y, double z, double yaw, double pitch) {
    }
}
