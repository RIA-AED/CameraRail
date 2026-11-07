package red.ria.camerarail.model;

import java.util.Locale;

/**
 * Easing curves used by camera movement. All curves map 0 to 0 and 1 to 1.
 */
public enum Easing {
    LINEAR {
        @Override
        public double apply(double t) {
            return t;
        }
    },
    EASE_IN {
        @Override
        public double apply(double t) {
            return t * t;
        }
    },
    EASE_OUT {
        @Override
        public double apply(double t) {
            double inverse = 1.0 - t;
            return 1.0 - inverse * inverse;
        }
    },
    EASE_IN_OUT {
        @Override
        public double apply(double t) {
            return t < 0.5
                    ? 2.0 * t * t
                    : 1.0 - Math.pow(-2.0 * t + 2.0, 2.0) / 2.0;
        }
    },
    SMOOTHSTEP {
        @Override
        public double apply(double t) {
            return t * t * (3.0 - 2.0 * t);
        }
    },
    SMOOTHERSTEP {
        @Override
        public double apply(double t) {
            return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
        }
    },
    SINE {
        @Override
        public double apply(double t) {
            return (1.0 - Math.cos(Math.PI * t)) / 2.0;
        }
    };

    /**
     * Applies the easing curve. Inputs outside [0, 1] are clamped.
     */
    public abstract double apply(double t);

    /**
     * Resolves a user-facing easing name. A few friendly aliases are accepted.
     *
     * @param raw raw config value
     * @return resolved easing
     * @throws IllegalArgumentException when the name is unknown
     */
    public static Easing parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Easing name cannot be empty");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "linear" -> LINEAR;
            case "ease-in", "easein", "in" -> EASE_IN;
            case "ease-out", "easeout", "out" -> EASE_OUT;
            case "ease-in-out", "easeinout", "in-out", "smooth" -> EASE_IN_OUT;
            case "smoothstep" -> SMOOTHSTEP;
            case "smootherstep" -> SMOOTHERSTEP;
            case "sine", "sine-in-out" -> SINE;
            default -> throw new IllegalArgumentException(
                    "Unknown easing '" + raw + "' (expected linear, ease-in, ease-out, ease-in-out, "
                            + "smoothstep, smootherstep or sine)");
        };
    }

    /**
     * Clamps a normalized time value.
     */
    public static double clamp01(double value) {
        if (value <= 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }
}
