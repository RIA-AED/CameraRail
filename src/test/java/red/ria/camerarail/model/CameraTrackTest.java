package red.ria.camerarail.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link CameraTrack#withId(String)} is the model half of a rename: it must move the identity
 * without touching anything that affects how the camera behaves.
 */
class CameraTrackTest {
    private static final Pose POSE = new Pose(1, 2, 3, 10, 20);
    private static final SwaySettings SWAY = SwaySettings.ofPreset(SwayPreset.NATURAL, 160, 7L);

    private static CameraTrack track(String id, String displayName) {
        return new CameraTrack(id, displayName, "note", "world", POSE, ExitMode.ANIMATION_END, null,
                SWAY, true, List.of(), List.of(new HoldStep(20, SwayPreset.INHERIT)));
    }

    @Test
    void defaultDisplayNameFollowsTheNewId() {
        CameraTrack renamed = track("login", "login").withId("Intro");

        assertEquals("intro", renamed.id());
        assertEquals("intro", renamed.displayName());
    }

    @Test
    void customDisplayNameIsKept() {
        assertEquals("登录过场", track("login", "登录过场").withId("intro").displayName());
    }

    @Test
    void everythingButTheIdentityIsPreserved() {
        CameraTrack original = track("login", "login");
        CameraTrack renamed = original.withId("intro");

        assertEquals(original.description(), renamed.description());
        assertEquals(original.worldName(), renamed.worldName());
        assertEquals(original.start(), renamed.start());
        assertEquals(original.exitMode(), renamed.exitMode());
        assertEquals(original.exit(), renamed.exit());
        assertEquals(original.sway(), renamed.sway());
        assertEquals(original.follow(), renamed.follow());
        assertEquals(original.entryEffects(), renamed.entryEffects());
        assertEquals(original.steps(), renamed.steps());
        assertNotEquals(original, renamed);
    }

    @Test
    void idIsNormalizedToLowercase() {
        assertEquals("intro", track("login", "login").withId("INTRO").id());
        assertEquals("intro", track("login", "login").withId(" Intro ").id());
    }
}
