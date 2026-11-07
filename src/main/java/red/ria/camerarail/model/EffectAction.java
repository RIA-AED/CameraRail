package red.ria.camerarail.model;

import java.util.Locale;

public enum EffectAction {
    ADD,
    REMOVE;

    public static EffectAction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ADD;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown effect action '" + raw + "' (expected add or remove)");
        }
    }
}
