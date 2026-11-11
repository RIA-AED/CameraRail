package red.ria.camerarail.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditTargetTest {
    @Test
    void parsesTrackStepAndPointTargets() {
        EditTarget track = EditTarget.parse("login");
        assertEquals("login", track.trackId());
        assertFalse(track.hasStep());
        assertFalse(track.hasPoint());
        assertEquals("login", track.display());

        EditTarget step = EditTarget.parse("login:3");
        assertEquals(2, step.stepIndex());
        assertFalse(step.hasPoint());
        assertEquals("login:3", step.display());
        assertEquals("login:3", step.stepDisplay());

        EditTarget point = EditTarget.parse("login:3:2");
        assertEquals(2, point.stepIndex());
        assertEquals(1, point.pointIndex());
        assertTrue(point.hasPoint());
        assertEquals("login:3:2", point.display());
        assertEquals("login:3", point.stepDisplay());
    }

    @Test
    void namesThatUsedToBeReservedArePlainTrackIds() {
        // The edit command no longer has subcommands at position 1: every token there is a
        // track id parameter, so there is no escape prefix and nothing to disambiguate.
        for (String id : new String[]{"gui", "help", "tools", "done", "close", "list", "start"}) {
            EditTarget target = EditTarget.parse(id);
            assertEquals(id, target.trackId(), id);
            assertFalse(target.hasStep());
        }
    }

    @Test
    void trackIdsAreCaseInsensitive() {
        assertEquals("login", EditTarget.parse("LOGIN").trackId());
        assertEquals("login", EditTarget.parse("Login:2").trackId());
    }

    @Test
    void rejectsMalformedTargets() {
        for (String raw : new String[]{"", " ", ":", "login:", "login:0", "login:x", "login:-1",
                "login:1:0", "login:1:x", "login:1:2:3", "@login", "login name", "login::2"}) {
            assertNull(EditTarget.parse(raw), raw);
        }
    }

    @Test
    void buildsTargetsWithValidation() {
        assertEquals("login:1", EditTarget.step("login", 0).display());
        assertEquals("login:1:1", EditTarget.point("login", 0, 0).display());
        assertThrows(IllegalArgumentException.class, () -> EditTarget.point("login", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> EditTarget.point("login", 0, -1));
        assertThrows(IllegalArgumentException.class, () -> EditTarget.track(" "));
    }
}
