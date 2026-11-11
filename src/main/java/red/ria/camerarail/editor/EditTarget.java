package red.ria.camerarail.editor;

import red.ria.camerarail.model.TrackId;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A stateless address to one editing target: a whole track, one step of a track, or one path
 * point of one step.
 *
 * <p>Command editing used to depend on a hidden "selected step" in the player's editor session,
 * which made it easy to edit the wrong track or step after a command silently selected
 * something. Every edit command now carries its target explicitly, written as
 * {@code <track>[:<step>[:<point>]]} (indices are 1-based in the command, 0-based here).</p>
 *
 * <p>Parsing is deliberately strict: a malformed target returns {@code null} so the command can
 * show the usage line instead of guessing. Track ids are lower-cased, matching the repository's
 * case-insensitive lookup.</p>
 */
public record EditTarget(String trackId, int stepIndex, int pointIndex) {
    /** The target addresses only the track. */
    public static final int NO_STEP = -1;
    /** The target addresses only the track or a step, not a point. */
    public static final int NO_POINT = -1;

    private static final Pattern INDEX = Pattern.compile("[0-9]{1,6}");

    public EditTarget {
        if (trackId == null || trackId.isBlank()) {
            throw new IllegalArgumentException("Track id is required");
        }
        trackId = trackId.toLowerCase(Locale.ROOT);
        if (stepIndex < NO_STEP) {
            throw new IllegalArgumentException("Step index out of range");
        }
        if (pointIndex < NO_POINT) {
            throw new IllegalArgumentException("Point index out of range");
        }
        if (pointIndex != NO_POINT && stepIndex == NO_STEP) {
            throw new IllegalArgumentException("A path point requires its step");
        }
    }

    public static EditTarget track(String trackId) {
        return new EditTarget(trackId, NO_STEP, NO_POINT);
    }

    /** @param stepIndex zero-based step index */
    public static EditTarget step(String trackId, int stepIndex) {
        return new EditTarget(trackId, stepIndex, NO_POINT);
    }

    /** @param stepIndex zero-based step index, @param pointIndex zero-based point index */
    public static EditTarget point(String trackId, int stepIndex, int pointIndex) {
        if (stepIndex < 0 || pointIndex < 0) {
            throw new IllegalArgumentException("Point target requires positive indices");
        }
        return new EditTarget(trackId, stepIndex, pointIndex);
    }

    public boolean hasStep() {
        return stepIndex > NO_STEP;
    }

    public boolean hasPoint() {
        return pointIndex > NO_POINT;
    }

    /**
     * Parses {@code track}, {@code track:step} or {@code track:step:point} (1-based indices).
     * Returns {@code null} when the text is not a valid target.
     */
    public static EditTarget parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(":", -1);
        if (parts.length > 3 || parts[0].isEmpty()) {
            return null;
        }
        String trackId = parts[0].toLowerCase(Locale.ROOT);
        if (!TrackId.isValid(trackId)) {
            return null;
        }
        int step = NO_STEP;
        int point = NO_POINT;
        if (parts.length >= 2) {
            Integer value = oneBased(parts[1]);
            if (value == null) {
                return null;
            }
            step = value;
        }
        if (parts.length == 3) {
            Integer value = oneBased(parts[2]);
            if (value == null) {
                return null;
            }
            point = value;
        }
        return new EditTarget(trackId, step, point);
    }

    private static Integer oneBased(String raw) {
        if (!INDEX.matcher(raw).matches()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw);
            return value <= 0 ? null : value - 1;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** User-facing form with 1-based indices, used in messages and tab completion. */
    public String display() {
        StringBuilder text = new StringBuilder(trackId);
        if (hasStep()) {
            text.append(':').append(stepIndex + 1);
            if (hasPoint()) {
                text.append(':').append(pointIndex + 1);
            }
        }
        return text.toString();
    }

    /** User-facing form of the step part, e.g. {@code login:3}. */
    public String stepDisplay() {
        return trackId + ":" + (stepIndex + 1);
    }

    @Override
    public String toString() {
        return display();
    }
}
