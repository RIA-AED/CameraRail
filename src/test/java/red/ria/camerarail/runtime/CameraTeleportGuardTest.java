package red.ria.camerarail.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paper raises a {@code TeleportCause.SPECTATE} body teleport from
 * {@code ServerPlayer#setCamera} every time the camera changes, including the plugin's own
 * camera re-sends and re-locks. The listener only vetoes that cause while a shot is locked,
 * so these events have to be recognised as internal exactly when the plugin itself is
 * changing the camera (issue #3: entering a shot must not show the "无法离开镜头" hint).
 */
class CameraTeleportGuardTest {
    @Test
    void markedCameraChangeIsInternal() {
        assertTrue(CameraEngine.isInternalCameraChange(true, 0));
    }

    @Test
    void ownTeleportInFlightIsInternal() {
        // The camera re-send wraps its setSpectatorTarget calls in an internal teleport as
        // well, so an event delivered while that teleport is still open must be ignored.
        assertTrue(CameraEngine.isInternalCameraChange(false, 1));
    }

    @Test
    void settledSessionDoesNotSwallowRealSpectatorTeleports() {
        assertFalse(CameraEngine.isInternalCameraChange(false, 0));
    }
}
