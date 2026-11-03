package net.ria.zth.camerarail.model;

/**
 * Timing and amplitude of the idle camera sway. Amplitudes are in blocks and degrees.
 *
 * @param period base period in ticks
 * @param seed deterministic phase seed, so previews are repeatable
 */
public record SwaySettings(double x, double y, double z, double yaw, double pitch, int period, long seed) {
    public SwaySettings {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(yaw) || !Double.isFinite(pitch)) {
            throw new IllegalArgumentException("Sway amplitudes must be finite");
        }
        if (period < 20) {
            throw new IllegalArgumentException("Sway period must be at least 20 ticks");
        }
        if (x < 0.0 || y < 0.0 || z < 0.0 || yaw < 0.0 || pitch < 0.0) {
            throw new IllegalArgumentException("Sway amplitudes cannot be negative");
        }
    }

    public static SwaySettings ofPreset(SwayPreset preset, int period, long seed) {
        if (preset == SwayPreset.INHERIT) {
            throw new IllegalArgumentException("INHERIT is not a concrete sway preset");
        }
        return new SwaySettings(preset.x(), preset.y(), preset.z(), preset.yaw(), preset.pitch(), period, seed);
    }

    public SwaySettings withAmplitudes(double newX, double newY, double newZ, double newYaw, double newPitch) {
        return new SwaySettings(newX, newY, newZ, newYaw, newPitch, period, seed);
    }

    public SwaySettings withPeriod(int newPeriod) {
        return new SwaySettings(x, y, z, yaw, pitch, newPeriod, seed);
    }

    public SwaySettings withSeed(long newSeed) {
        return new SwaySettings(x, y, z, yaw, pitch, period, newSeed);
    }

    public boolean isStill() {
        return x == 0.0 && y == 0.0 && z == 0.0 && yaw == 0.0 && pitch == 0.0;
    }
}
