package net.ria.zth.camerarail.runtime;

import net.ria.zth.camerarail.model.SwaySettings;

/**
 * Evaluates a continuous, deterministic two-frequency sway. Amplitudes blend slowly when
 * the active step changes, so switching between "still" and "wind" does not snap.
 *
 * <p>Both oscillators are evaluated from the absolute tick, never from a phase that is
 * wrapped back to zero once per period. Wrapping only the slow oscillator is safe, but the
 * fast one runs 2.371 cycles per period, so a wrapped phase restarts it mid-cycle: every
 * {@code period} ticks all five channels jump at once, by an order of magnitude more than a
 * normal tick. On screen that reads as the camera twitching once per period, and it grows
 * with the amplitude preset while "off" stays perfectly still.</p>
 */
public final class SwayAnimator {
    private static final double BLEND = 0.08;
    private static final double SECOND_FREQUENCY = 2.371;
    private static final double TWO_PI = Math.PI * 2.0;

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
     *
     * <p>{@code tick} is an absolute tick count, not an offset inside the period. A session
     * starts it at zero, so the same track with the same seed still previews identically.</p>
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

        double angle = tick / (double) period * TWO_PI;
        return new SwayOffset(
                amplitudeX * wave(angle, phases[0]),
                amplitudeY * wave(angle, phases[1]),
                amplitudeZ * wave(angle, phases[2]),
                amplitudeYaw * wave(angle, phases[3]),
                amplitudePitch * wave(angle, phases[4])
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

    private static double wave(double angle, double offset) {
        double first = Math.sin(angle + offset);
        double second = Math.sin(angle * SECOND_FREQUENCY + offset * 1.7 + 1.113);
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
