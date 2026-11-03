package net.ria.zth.camerarail.model;

import java.util.Locale;

/**
 * Step-level sway override. Amplitudes are intentionally small: the camera should feel
 * alive, not like it is being shaken.
 */
public enum SwayPreset {
    INHERIT(0.0, 0.0, 0.0, 0.0, 0.0),
    OFF(0.0, 0.0, 0.0, 0.0, 0.0),
    GENTLE(0.012, 0.008, 0.010, 0.12, 0.05),
    NATURAL(0.028, 0.016, 0.022, 0.28, 0.10),
    STRONG(0.060, 0.035, 0.050, 0.60, 0.22);

    private final double x;
    private final double y;
    private final double z;
    private final double yaw;
    private final double pitch;

    SwayPreset(double x, double y, double z, double yaw, double pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public double yaw() {
        return yaw;
    }

    public double pitch() {
        return pitch;
    }

    public static SwayPreset parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return INHERIT;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "inherit", "default" -> INHERIT;
            case "off", "none", "still" -> OFF;
            case "gentle", "soft", "light" -> GENTLE;
            case "natural", "normal" -> NATURAL;
            case "strong", "heavy" -> STRONG;
            default -> throw new IllegalArgumentException(
                    "Unknown sway preset '" + raw + "' (expected inherit, off, gentle, natural or strong)");
        };
    }

    /**
     * Resolves this override against the track's timing (period and seed).
     */
    public SwaySettings resolve(SwaySettings trackSway) {
        return switch (this) {
            case INHERIT -> trackSway;
            case OFF -> trackSway.withAmplitudes(0.0, 0.0, 0.0, 0.0, 0.0);
            case GENTLE, NATURAL, STRONG -> trackSway.withAmplitudes(x, y, z, yaw, pitch);
        };
    }
}
