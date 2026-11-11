package red.ria.camerarail.command;

import org.bukkit.entity.Player;
import red.ria.camerarail.editor.EditService;
import red.ria.camerarail.editor.EditTarget;

/**
 * The preview toggle/stop shared by every layer handler.
 *
 * <p>Preview is a toggle everywhere: if the player is already previewing, the same action stops
 * it; otherwise it starts a preview of the addressed target and announces it. Keeping the two
 * methods in one place means the three layer handlers cannot drift in wording or ordering.</p>
 */
final class EditPreview {
    private EditPreview() {
    }

    /** Toggles preview for the addressed target. */
    static void toggle(EditContext context, Player player, EditTarget target) {
        if (context.service().isPreviewing(player)) {
            stop(context, player);
            return;
        }
        EditService.Result result = context.service().preview(player, target);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.preview-started", "track", target.trackId());
        }
    }

    /** Stops the player's current preview. */
    static void stop(EditContext context, Player player) {
        EditService.Result result = context.service().stopPreview(player);
        if (context.feedback().applied(player, result)) {
            context.messages().get().send(player, "editor.preview-stopped");
        }
    }
}
