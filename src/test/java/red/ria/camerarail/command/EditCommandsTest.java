package red.ria.camerarail.command;

import org.junit.jupiter.api.Test;
import red.ria.camerarail.editor.EditTarget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one dispatch rule of {@link EditCommands}: the target shape decides which layer handler
 * receives the action, with the track-level {@code add} going to its own handler. The handlers
 * themselves need a Bukkit player, so this pins the pure routing.
 */
class EditCommandsTest {
    @Test
    void targetShapeSelectsTheLayer() {
        assertEquals(EditCommands.Kind.TRACK, EditCommands.kindOf(EditTarget.track("login"), "display"));
        assertEquals(EditCommands.Kind.STEP, EditCommands.kindOf(EditTarget.step("login", 1), "duration"));
        assertEquals(EditCommands.Kind.POINT, EditCommands.kindOf(EditTarget.point("login", 1, 0), "pos"));
    }

    @Test
    void addOnlyBecomesItsOwnKindAtTrackLevel() {
        assertEquals(EditCommands.Kind.ADD, EditCommands.kindOf(EditTarget.track("login"), "add"));
        assertEquals(EditCommands.Kind.STEP, EditCommands.kindOf(EditTarget.step("login", 1), "add"),
                "add is not a step action; it stays a step-level wrong action");
        assertEquals(EditCommands.Kind.POINT, EditCommands.kindOf(EditTarget.point("login", 1, 0), "add"));
    }

    @Test
    void parsedCommandTargetsSelectTheSameLayer() {
        EditTarget track = EditTarget.parse("login");
        EditTarget step = EditTarget.parse("login:2");
        EditTarget point = EditTarget.parse("login:2:3");
        assertNotNull(track);
        assertNotNull(step);
        assertNotNull(point);
        assertEquals(EditCommands.Kind.TRACK, EditCommands.kindOf(track, "list"));
        assertEquals(EditCommands.Kind.STEP, EditCommands.kindOf(step, ""));
        assertEquals(EditCommands.Kind.POINT, EditCommands.kindOf(point, ""));
    }

    @Test
    void pointTargetsAreCheckedBeforeSteps() {
        // A point target also has hasStep(), so the order inside kindOf matters.
        EditTarget point = EditTarget.parse("login:1:1");
        assertTrue(point.hasStep());
        assertEquals(EditCommands.Kind.POINT, EditCommands.kindOf(point, ""));
    }
}
