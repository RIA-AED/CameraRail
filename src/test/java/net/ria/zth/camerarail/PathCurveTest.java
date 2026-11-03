package net.ria.zth.camerarail;

import net.ria.zth.camerarail.model.Pose;
import net.ria.zth.camerarail.runtime.PathCurve;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathCurveTest {
    @Test
    void straightPathHasExactEndpointsAndMonotonicProgress() {
        Pose start = new Pose(0, 64, 0, 0, 0);
        Pose end = new Pose(10, 64, 0, 90, 0);
        PathCurve curve = PathCurve.through(List.of(start, end));

        assertEquals(0.0, curve.at(0).x(), 1.0e-6);
        assertEquals(10.0, curve.at(1).x(), 1.0e-6);
        assertEquals(90.0f, curve.at(1).yaw(), 1.0e-4f);
        double previous = -1;
        for (int i = 0; i <= 100; i++) {
            double x = curve.at(i / 100.0).x();
            assertTrue(x >= previous - 1.0e-6, "x went backwards at " + i);
            previous = x;
        }
    }

    @Test
    void multiPointPathIsContinuousAtTheStart() {
        List<Pose> points = List.of(
                new Pose(0, 64, 0, 0, 0),
                new Pose(10, 66, 10, 30, 0),
                new Pose(20, 64, 0, 60, 0)
        );
        PathCurve curve = PathCurve.through(points);
        Pose first = curve.at(0.0);
        Pose early = curve.at(0.001);
        assertTrue(first.distance(early) < 0.05, "early movement was " + first.distance(early));
        assertTrue(curve.length() > 20.0);
    }

    @Test
    void yawTakesTheShortRoute() {
        PathCurve curve = PathCurve.through(List.of(
                new Pose(0, 0, 0, 170, 0),
                new Pose(1, 0, 0, -170, 0)
        ));
        double yaw = curve.at(0.5).yaw();
        assertTrue(yaw > 170 || yaw < -170, "Expected shortest wrap, got " + yaw);
    }
}
