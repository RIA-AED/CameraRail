package red.ria.camerarail;

import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.runtime.PathCurve;
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

    /**
     * The reported track repeats the start pose as the first path point, so the first two
     * keyframes share a position and only the yaw differs by 0.15 degrees. The positional
     * knot between them used to collapse to {@code EPSILON} while the yaw still changed, and
     * the yaw spline extrapolated from that interval: the first ticks swung the view through
     * -136, 90, 0, -43, ... degrees (seven direction changes).
     */
    @Test
    void repeatedStartKeyframeDoesNotTwistTheView() {
        List<Pose> keyframes = List.of(
                new Pose(-768.5960366769492, 88.42543160649326, -1638.4297406690155,
                        25.251800537109375f, 0.15006253f),
                new Pose(-768.5960366769492, 88.42543160649326, -1638.4297406690155,
                        25.101806640625f, 0.15006253f),
                new Pose(-771.3529030798197, 88.42543160649326, -1635.7716660359158,
                        9.801788330078125f, 1.8000615f),
                new Pose(-772.4570665924632, 88.05673015184239, -1627.2108570948774,
                        0.35198974609375f, 9.300059f),
                new Pose(-772.4950845532034, 86.5616641323954, -1615.4824485594586,
                        -0.097991943359375f, 0.9000549f)
        );
        PathCurve curve = PathCurve.through(keyframes);

        assertYawStaysWithinKeyframes(curve, keyframes, 1.0f);
        assertPitchStaysWithinKeyframes(curve, keyframes, 0.5f);
    }

    @Test
    void turningWithoutMovingDoesNotOvershootTheView() {
        List<Pose> keyframes = List.of(
                new Pose(0, 64, 0, 0, 0),
                new Pose(0, 64, 0, 90, 10),
                new Pose(6, 64, 0, 90, 10),
                new Pose(12, 64, 6, 170, 0)
        );
        PathCurve curve = PathCurve.through(keyframes);

        assertYawStaysWithinKeyframes(curve, keyframes, 1.0f);
        assertPitchStaysWithinKeyframes(curve, keyframes, 0.5f);
    }

    @Test
    void nearCoincidentKeyframesDoNotOvershootTheView() {
        // One millimetre of travel with a 90 degree turn is enough to collapse the positional
        // knot gap, so it has to be covered by the rotation parameterisation as well.
        List<Pose> keyframes = List.of(
                new Pose(0, 64, 0, 0, 0),
                new Pose(0.001, 64, 0, 90, 10),
                new Pose(6, 64, 0, 90, 10),
                new Pose(12, 64, 6, 170, 0)
        );
        PathCurve curve = PathCurve.through(keyframes);

        assertYawStaysWithinKeyframes(curve, keyframes, 1.0f);
        assertPitchStaysWithinKeyframes(curve, keyframes, 0.5f);
    }

    /**
     * The reported track played at its own pace: 60 ticks, ease-in-out. The old implementation
     * changed yaw direction seven times and moved up to 161.6 degrees per tick in the first
     * eleven ticks; the track only turns around once on its way to the last point.
     */
    @Test
    void reportedTrackPlaysWithoutASwingAtTheStart() {
        List<Pose> keyframes = List.of(
                new Pose(-768.5960366769492, 88.42543160649326, -1638.4297406690155,
                        25.251800537109375f, 0.15006253f),
                new Pose(-768.5960366769492, 88.42543160649326, -1638.4297406690155,
                        25.101806640625f, 0.15006253f),
                new Pose(-771.3529030798197, 88.42543160649326, -1635.7716660359158,
                        9.801788330078125f, 1.8000615f),
                new Pose(-772.4570665924632, 88.05673015184239, -1627.2108570948774,
                        0.35198974609375f, 9.300059f),
                new Pose(-772.4950845532034, 86.5616641323954, -1615.4824485594586,
                        -0.097991943359375f, 0.9000549f),
                new Pose(-772.4761779970917, 86.5616641323954, -1603.2010399710955,
                        -0.097991943359375f, 0.9000549f),
                new Pose(-772.4621275562587, 88.04957095631949, -1594.5752944935446,
                        -0.097991943359375f, 0.9000549f),
                new Pose(-772.501368855588, 88.04957095631949, -1581.5846492318994,
                        0.201995849609375f, 5.100056f)
        );
        PathCurve curve = PathCurve.through(keyframes);
        int duration = 60;
        int direction = 0;
        int reversals = 0;
        float previousYaw = keyframes.getFirst().yaw();
        for (int tick = 1; tick <= duration; tick++) {
            float yaw = curve.at(Easing.EASE_IN_OUT.apply(tick / (double) duration)).yaw();
            float delta = Pose.normalizeYaw(yaw - previousYaw);
            assertTrue(Math.abs(delta) < 3.0f, "yaw jumped " + delta + " degrees on tick " + tick);
            int sign = delta > 1.0e-6f ? 1 : delta < -1.0e-6f ? -1 : 0;
            if (sign != 0 && direction != 0 && sign != direction) {
                reversals++;
            }
            if (sign != 0) {
                direction = sign;
            }
            previousYaw = yaw;
        }
        assertTrue(reversals <= 1, "yaw changed direction " + reversals + " times");
    }

    /**
     * A path whose keyframes all share a position falls back to the zero-length branch of
     * {@link PathCurve#at(double)}; it still has to distribute the rotation without NaN or
     * overshoot. This one is protective only: the old implementation passes it too, so the
     * start-of-path regression above is what guards the reported bug.
     */
    @Test
    void rotationOnlyPathStaysWellFormed() {
        List<Pose> keyframes = List.of(
                new Pose(0, 64, 0, 0, 0),
                new Pose(0, 64, 0, 90, 10),
                new Pose(0, 64, 0, 170, 5)
        );
        PathCurve curve = PathCurve.through(keyframes);
        assertEquals(0.0, curve.length(), 1.0e-9);
        for (int tick = 0; tick <= 40; tick++) {
            Pose pose = curve.at(tick / 40.0);
            assertTrue(Float.isFinite(pose.yaw()) && Float.isFinite(pose.pitch()), "non-finite pose on tick " + tick);
            assertTrue(pose.yaw() >= -1.0f && pose.yaw() <= 171.0f, "yaw " + pose.yaw() + " on tick " + tick);
            assertTrue(pose.pitch() >= -1.0f && pose.pitch() <= 11.0f, "pitch " + pose.pitch() + " on tick " + tick);
        }
    }

    /**
     * Asserts the sampled yaw stays inside the keyframe yaw range. The keyframes used here do
     * not cross ±180°, so comparing the normalized yaw directly is enough; a path that wraps
     * would have to be compared after unfolding first.
     */
    private static void assertYawStaysWithinKeyframes(PathCurve curve, List<Pose> keyframes, float tolerance) {
        float minYaw = Float.MAX_VALUE;
        float maxYaw = -Float.MAX_VALUE;
        for (Pose pose : keyframes) {
            minYaw = Math.min(minYaw, pose.yaw());
            maxYaw = Math.max(maxYaw, pose.yaw());
        }
        for (int i = 0; i <= 500; i++) {
            float yaw = curve.at(i / 500.0).yaw();
            assertTrue(yaw >= minYaw - tolerance && yaw <= maxYaw + tolerance,
                    "yaw " + yaw + " left [" + minYaw + ", " + maxYaw + "] at sample " + i);
        }
    }

    private static void assertPitchStaysWithinKeyframes(PathCurve curve, List<Pose> keyframes, float tolerance) {
        float minPitch = Float.MAX_VALUE;
        float maxPitch = -Float.MAX_VALUE;
        for (Pose pose : keyframes) {
            minPitch = Math.min(minPitch, pose.pitch());
            maxPitch = Math.max(maxPitch, pose.pitch());
        }
        for (int i = 0; i <= 500; i++) {
            float pitch = curve.at(i / 500.0).pitch();
            assertTrue(pitch >= minPitch - tolerance && pitch <= maxPitch + tolerance,
                    "pitch " + pitch + " left [" + minPitch + ", " + maxPitch + "] at sample " + i);
        }
    }
}
