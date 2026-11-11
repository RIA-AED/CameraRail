package red.ria.camerarail.command;

import org.bukkit.entity.Player;
import red.ria.camerarail.editor.EditTarget;

/**
 * Thin router for the stateless {@code edit} command family: one target, one action, one layer.
 *
 * <p>The target decides the layer ({@code <track>}, {@code <track>:<step>},
 * {@code <track>:<step>:<point>}); the layer decides which fields exist. The only special case is
 * the track-level {@code add <type>} action, which needs its own handler for the seven step types
 * and is therefore routed to {@link AddStepCommands} here (handlers never call each other).</p>
 */
final class EditCommands {
    /** Which handler one edit invocation reaches. */
    enum Kind {
        TRACK,
        STEP,
        POINT,
        ADD
    }

    private final TrackEditCommands trackCommands = new TrackEditCommands();
    private final AddStepCommands addStepCommands = new AddStepCommands();
    private final StepEditCommands stepCommands = new StepEditCommands();
    private final PointEditCommands pointCommands = new PointEditCommands();

    /** The dispatch rule, pure and unit-testable: the target shape decides, {@code add} is special. */
    static Kind kindOf(EditTarget target, String action) {
        if (target.hasPoint()) {
            return Kind.POINT;
        }
        if (target.hasStep()) {
            return Kind.STEP;
        }
        return "add".equals(action) ? Kind.ADD : Kind.TRACK;
    }

    /** Dispatches one edit command. */
    void handle(Player player, EditTarget target, String action, String[] args, EditContext context) {
        switch (kindOf(target, action)) {
            case POINT -> pointCommands.handle(player, target, action, args, context);
            case STEP -> stepCommands.handle(player, target, action, args, context);
            case ADD -> addStepCommands.handle(player, target, args, context);
            case TRACK -> trackCommands.handle(player, target, action, args, context);
        }
    }
}
