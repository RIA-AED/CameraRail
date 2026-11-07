package red.ria.camerarail.runtime;

import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.ExitMode;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entry effects cover the hard cut to the track's own start pose. The editor previews a
 * single step (or a range starting at a selected step) by spawning the camera at the end
 * pose of the preceding steps; that moment is not an entry in real playback, so the effects
 * must not fire there - otherwise "预览此步" applies blindness/darkness in the middle of an
 * editing session.
 */
class SessionEntryEffectsTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void realPlaybackEntersAtTheTrackStart() {
        assertTrue(session(false, 0, Integer.MAX_VALUE).appliesEntryEffects());
    }

    @Test
    void previewFromTheTrackStartStillAppliesEntryEffects() {
        assertTrue(session(true, 0, Integer.MAX_VALUE).appliesEntryEffects());
        // "预览此步" on the first step also cuts straight from the body to the track start.
        assertTrue(session(true, 0, 0).appliesEntryEffects());
    }

    @Test
    void previewFromALaterStepSkipsEntryEffects() {
        assertFalse(session(true, 1, Integer.MAX_VALUE).appliesEntryEffects());
        assertFalse(session(true, 3, Integer.MAX_VALUE).appliesEntryEffects());
    }

    @Test
    void singleStepPreviewSkipsEntryEffects() {
        assertFalse(session(true, 1, 1).appliesEntryEffects());
        assertFalse(session(true, 2, 2).appliesEntryEffects());
    }

    private static Session session(boolean preview, int startIndex, int endIndex) {
        return new Session(player(), track(), null, preview, startIndex, endIndex);
    }

    private static CameraTrack track() {
        return new CameraTrack("test", "Test", "", "world",
                new Pose(0, 64, 0, 0, 0), ExitMode.ANIMATION_END, null,
                SwaySettings.ofPreset(SwayPreset.NATURAL, 160, 42),
                true, List.of(),
                List.of(new HoldStep(20, SwayPreset.INHERIT), new HoldStep(20, SwayPreset.INHERIT)));
    }

    /**
     * Session only reads {@code getUniqueId()} from the player, so a throwaway interface
     * proxy is enough - the runtime itself needs a live server.
     */
    private static Player player() {
        return (Player) Proxy.newProxyInstance(
                SessionEntryEffectsTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) {
                        return PLAYER_ID;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        return null;
    }
}
