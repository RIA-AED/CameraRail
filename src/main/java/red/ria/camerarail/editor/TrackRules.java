package red.ria.camerarail.editor;

import red.ria.camerarail.config.RailConfig;
import red.ria.camerarail.message.MiniMessages;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.TitleStep;
import red.ria.camerarail.model.TrackId;
import red.ria.camerarail.model.WaitStep;

import java.util.HashSet;
import java.util.Set;

/**
 * Structural rules for a camera track, shared by the stateless command/item editor
 * ({@link EditService}) and the Dialog editor ({@link EditorManager}).
 *
 * <p>Before this class existed both editors carried their own copy of the same five checks
 * (step count, total duration, unique wait signals, path-point count, strict MiniMessage) plus
 * two more (track id format, id/world immutability), and the copies had already started to drift
 * (the command side was missing the id/world check). Keeping the rules in one pure-Java class
 * means a change to a limit is a change in one place, and the rules can be unit-tested without a
 * running server.</p>
 *
 * <p>Messages are returned as strings instead of being sent anywhere: the command surface shows
 * them as {@code editor.edit-failed} text, the Dialog editor reopens the form with them. This
 * class depends on no Bukkit type (only on the plain {@link RailConfig} record and the model
 * records), so {@code TrackRulesTest} runs in a plain JUnit JVM.</p>
 */
final class TrackRules {
    private TrackRules() {
    }

    /** Whether {@code id} (already lower-cased by the caller) is a valid track id. */
    static boolean isValidId(String id) {
        return TrackId.isValid(id);
    }

    /**
     * Validates {@code updated} against the global limits and the invariants of {@code base}.
     * Returns a user-facing error message, or {@code null} when the track may be stored.
     *
     * @param base    the stored track, or {@code null} when a new track is being created
     * @param updated the track about to be stored
     * @param config  current limits
     */
    static String validate(CameraTrack base, CameraTrack updated, RailConfig config) {
        if (updated == null) {
            return "轨道数据无效.";
        }
        // Id and world are fixed when the track is created; every editor preserves them and this
        // guard keeps a repository key from pointing at a value with a different identity.
        if (base != null) {
            if (!updated.id().equals(base.id())) {
                return "轨道 id 不能在这里修改.";
            }
            if (!updated.worldName().equals(base.worldName())) {
                return "轨道世界不能在这里修改.";
            }
        }
        if (updated.steps().size() > config.maxSteps()) {
            return "步骤数上限 " + config.maxSteps() + ".";
        }
        if (updated.timedDurationTicks() > config.maxTrackTicks()) {
            return "总时长上限 " + config.maxTrackTicks() + "t.";
        }
        Set<String> signals = new HashSet<>();
        for (Step step : updated.steps()) {
            if (step instanceof WaitStep wait && !signals.add(wait.signal())) {
                return "等待信号 " + wait.signal() + " 重复.";
            }
            if (step instanceof PathStep path && path.points().size() > config.maxPathPoints()) {
                return "路径点上限 " + config.maxPathPoints() + ".";
            }
            if (step instanceof TitleStep title) {
                String error = validateMiniMessage(title.title(), "标题");
                if (error != null) {
                    return error;
                }
                error = validateMiniMessage(title.subtitle(), "副标题");
                if (error != null) {
                    return error;
                }
            }
        }
        return null;
    }

    /**
     * Validates one MiniMessage text (a title/subtitle). Blank text is allowed because an empty
     * title is meaningful; returns the user-facing error or {@code null}.
     */
    static String validateMiniMessage(String text, String label) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            MiniMessages.strict().deserialize(text);
            return null;
        } catch (RuntimeException exception) {
            return label + "不是合法的 MiniMessage.";
        }
    }
}
