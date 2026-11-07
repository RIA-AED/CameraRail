package red.ria.camerarail;

import red.ria.camerarail.model.Pose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PoseTest {
    @Test
    void yawWrapsAndLerpsTheShortWay() {
        Pose from = new Pose(0, 0, 0, 170, 0);
        Pose to = new Pose(0, 0, 0, -170, 0);
        Pose middle = from.lerp(to, 0.5);
        assertEquals(-180.0f, middle.yaw(), 0.001f);

        Pose quarter = from.lerp(to, 0.25);
        assertEquals(175.0f, quarter.yaw(), 0.001f);
    }

    @Test
    void pitchIsClamped() {
        Pose pose = new Pose(0, 0, 0, 0, 180);
        assertEquals(90.0f, pose.pitch());
        assertEquals(-90.0f, new Pose(0, 0, 0, 0, -180).pitch());
    }

    @Test
    void distanceIgnoresRotation() {
        Pose a = new Pose(1, 2, 3, 0, 0);
        Pose b = new Pose(4, 6, 3, 90, -30);
        assertEquals(5.0, a.distance(b), 1.0e-9);
    }
}
