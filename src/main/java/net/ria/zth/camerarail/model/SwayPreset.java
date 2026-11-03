package net.ria.zth.camerarail.model;

import java.util.Locale;

/**
 * Step-level sway override. The three moving presets form a ladder of roughly 2.1x per step:
 * what used to be "natural" is now "gentle", what used to be "strong" is now "natural", and
 * "strong" is one step above that, big enough to be clearly visible on purpose.
 *
 * <p>No preset turns the view left or right: {@code yaw} is always zero, so the camera drifts
 * and nods instead of panning. That keeps the breathing feel while removing the horizontal
 * rotation, which some clients render with an occasional short "catch-up" flick. A track or
 * step that really wants the old panning sway can still set {@code yaw} in its YAML map.</p>
 */
public enum SwayPreset {
    INHERIT(0.0, 0.0, 0.0, 0.0, 0.0),
    OFF(0.0, 0.0, 0.0, 0.0, 0.0),
    GENTLE(0.042, 0.024, 0.033, 0.0, 0.15),
    NATURAL(0.090, 0.053, 0.075, 0.0, 0.33),
    STRONG(0.195, 0.113, 0.165, 0.0, 0.70);

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
