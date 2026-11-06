package net.ria.zth.camerarail.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CameraFovTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void baseSpeedIsVanilla() {
        assertEquals(0.1, CameraFov.movementSpeed(CameraFov.ABSENT, CameraFov.ABSENT), EPSILON);
        assertEquals(1.0, CameraFov.fovMultiplier(0.1), EPSILON);
    }

    @Test
    void slownessNarrowsFov() {
        assertEquals(0.04, CameraFov.movementSpeed(3, CameraFov.ABSENT), EPSILON);
        assertEquals(0.7, CameraFov.fovMultiplier(0.04), EPSILON);
    }

    @Test
    void speedWidensFov() {
        assertEquals(0.14, CameraFov.movementSpeed(CameraFov.ABSENT, 1), EPSILON);
        assertEquals(1.2, CameraFov.fovMultiplier(0.14), EPSILON);
    }

    @Test
    void bothEffectsMultiplyAndStayInVanillaClamp() {
        assertEquals(0.102, CameraFov.movementSpeed(0, 0), EPSILON);
        assertEquals(1.01, CameraFov.fovMultiplier(0.102), EPSILON);
        assertEquals(0.5, CameraFov.fovMultiplier(0.0), EPSILON);
        assertEquals(1.5, CameraFov.fovMultiplier(2.0), EPSILON);
    }

    @Test
    void excessiveSlownessDoesNotGoNegative() {
        assertEquals(0.0, CameraFov.movementSpeed(20, CameraFov.ABSENT), EPSILON);
    }
}
