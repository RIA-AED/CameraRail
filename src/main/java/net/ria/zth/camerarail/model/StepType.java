package net.ria.zth.camerarail.model;

import java.util.Locale;

public enum StepType {
    MOVE,
    PATH,
    HOLD,
    WAIT,
    CUT,
    EFFECT,
    TITLE;

    public static StepType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Step type is required");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown step type '" + raw
                    + "' (expected move, path, hold, wait, cut, effect or title)");
        }
    }

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
