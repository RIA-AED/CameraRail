package red.ria.camerarail.model;

import java.util.Locale;

/**
 * Where the player is placed when the camera stops.
 */
public enum ExitMode {
    /** The camera's final position (default). */
    ANIMATION_END("animation-end"),
    /** The position the player had before the camera started. */
    PRE_ENTRY("pre-entry"),
    /** An explicit player-feet pose stored in {@link CameraTrack#exit()}. */
    CUSTOM("custom");

    private final String configName;

    ExitMode(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    public static ExitMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ANIMATION_END;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "animation-end", "animation_end", "end" -> ANIMATION_END;
            case "pre-entry", "pre_entry", "original" -> PRE_ENTRY;
            case "custom" -> CUSTOM;
            default -> throw new IllegalArgumentException("Unknown exit mode '" + raw
                    + "' (use animation-end, pre-entry or custom)");
        };
    }
}
