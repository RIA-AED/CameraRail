package red.ria.camerarail.model;

import java.util.regex.Pattern;

/**
 * The canonical track-id rules, shared by the YAML parser, the edit-command target parser and the
 * structural validation used by both editors.
 *
 * <p>Keeping this in one place means a hand-written {@code tracks.yml} id, an {@code edit <id>}
 * target and an id typed into the Dialog editor are all judged by the same rule.</p>
 */
public final class TrackId {
    /** Lowercase letters, digits, {@code -} and {@code _}; up to 64 characters. */
    public static final Pattern PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private TrackId() {
    }

    /** Whether the already-lower-cased id is legal. */
    public static boolean isValid(String id) {
        return id != null && PATTERN.matcher(id).matches();
    }
}
