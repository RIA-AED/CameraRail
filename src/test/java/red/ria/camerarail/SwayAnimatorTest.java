package red.ria.camerarail;

import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;
import red.ria.camerarail.runtime.SwayAnimator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwayAnimatorTest {
    private static final List<SwayPreset> MOVING_PRESETS =
            List.of(SwayPreset.GENTLE, SwayPreset.NATURAL, SwayPreset.STRONG);
    private static final long[] SEEDS = {0L, 42L, 7L, -4815162342L};
    private static final int[] PERIODS = {20, 60, 160, 400};

    @Test
    void zeroSwayStaysStill() {
        SwaySettings still = SwaySettings.ofPreset(SwayPreset.OFF, 160, 42);
        SwayAnimator animator = new SwayAnimator(still);
        for (int tick = 0; tick < 300; tick++) {
            var offset = animator.tick(still, tick);
            assertEquals(0.0, offset.x(), 1.0e-12);
            assertEquals(0.0, offset.yaw(), 1.0e-12);
        }
    }

    @Test
    void amplitudesStayWithinConfiguredBounds() {
        SwaySettings sway = SwaySettings.ofPreset(SwayPreset.NATURAL, 160, 42);
        SwayAnimator animator = new SwayAnimator(sway);
        for (int tick = 0; tick < 500; tick++) {
            var offset = animator.tick(sway, tick);
            assertTrue(Math.abs(offset.x()) <= sway.x() + 1.0e-9);
            assertTrue(Math.abs(offset.y()) <= sway.y() + 1.0e-9);
            assertTrue(Math.abs(offset.z()) <= sway.z() + 1.0e-9);
            assertTrue(Math.abs(offset.yaw()) <= sway.yaw() + 1.0e-9);
            assertTrue(Math.abs(offset.pitch()) <= sway.pitch() + 1.0e-9);
        }
    }

    @Test
    void targetChangesBlendInsteadOfSnapping() {
        SwaySettings on = SwaySettings.ofPreset(SwayPreset.STRONG, 160, 7);
        SwayAnimator animator = new SwayAnimator(SwaySettings.ofPreset(SwayPreset.OFF, 160, 7));
        animator.tick(on, 0);
        assertTrue(animator.amplitudeX() < on.x());
        for (int i = 0; i < 200; i++) {
            animator.tick(on, i);
        }
        assertEquals(on.x(), animator.amplitudeX(), 0.002);
    }

    /**
     * The sway is applied to the camera every tick, so a single tick that moves much
     * further than its neighbours is a visible twitch in the view. A two-frequency sway
     * cannot move the camera further in one tick than {@code amplitude * 2pi/period}
     * times the sum of the frequency weights; two extra units of headroom cover any
     * reasonable mix. A phase that is wrapped back to zero once per period breaks that
     * bound at the seam by roughly an order of magnitude.
     */
    @Test
    void swayNeverJumpsBetweenTicks() {
        for (SwayPreset preset : MOVING_PRESETS) {
            for (long seed : SEEDS) {
                for (int period : PERIODS) {
                    SwaySettings settings = SwaySettings.ofPreset(preset, period, seed);
                    double step = 2.0 * Math.PI / period;
                    assertSmooth(preset, seed, period, settings, step, Channel.X);
                    assertSmooth(preset, seed, period, settings, step, Channel.YAW);
                    assertSmooth(preset, seed, period, settings, step, Channel.PITCH);
                }
            }
        }
    }

    /** The same tick must always produce the same sway, so previews stay repeatable. */
    @Test
    void swayIsDeterministic() {
        SwaySettings settings = SwaySettings.ofPreset(SwayPreset.STRONG, 160, 42);
        SwayAnimator first = new SwayAnimator(settings);
        SwayAnimator second = new SwayAnimator(settings);
        for (int tick = 0; tick < 900; tick++) {
            SwayAnimator.SwayOffset expected = first.tick(settings, tick);
            SwayAnimator.SwayOffset actual = second.tick(settings, tick);
            assertEquals(expected.x(), actual.x(), 0.0);
            assertEquals(expected.yaw(), actual.yaw(), 0.0);
            assertEquals(expected.pitch(), actual.pitch(), 0.0);
        }
    }

    /** A session may run for a day; the absolute phase must still be smooth that late. */
    @Test
    void swayStaysSmoothLateInALongSession() {
        SwaySettings settings = SwaySettings.ofPreset(SwayPreset.STRONG, 20, 42);
        SwayAnimator animator = new SwayAnimator(settings);
        double bound = settings.yaw() * 2.0 * Math.PI / settings.period() * 2.0;
        long start = 24L * 60 * 60 * 20 - 400;
        SwayAnimator.SwayOffset previous = null;
        for (long tick = start; tick < start + 400; tick++) {
            SwayAnimator.SwayOffset offset = animator.tick(settings, tick);
            if (previous != null) {
                assertTrue(Math.abs(offset.yaw() - previous.yaw()) <= bound,
                        "yaw jumped at late tick " + tick);
            }
            previous = offset;
        }
    }

    private enum Channel {
        X, YAW, PITCH
    }

    private static void assertSmooth(SwayPreset preset, long seed, int period,
                                     SwaySettings settings, double step, Channel channel) {
        SwayAnimator animator = new SwayAnimator(settings);
        double amplitude = switch (channel) {
            case X -> settings.x();
            case YAW -> settings.yaw();
            case PITCH -> settings.pitch();
        };
        double bound = amplitude * step * 2.0;
        SwayAnimator.SwayOffset previous = null;
        int lastTick = 200 + period * 3;
        for (int tick = 0; tick <= lastTick; tick++) {
            SwayAnimator.SwayOffset offset = animator.tick(settings, tick);
            if (previous != null && tick >= 200) {
                double delta = Math.abs(channel == Channel.X ? offset.x() - previous.x()
                        : channel == Channel.YAW ? offset.yaw() - previous.yaw()
                        : offset.pitch() - previous.pitch());
                assertTrue(delta <= bound,
                        "sway " + preset + " seed=" + seed + " period=" + period + " " + channel
                                + " jumped " + delta + (channel == Channel.X ? " blocks" : " deg")
                                + " in one tick at tick " + tick + " (limit " + bound + ")");
            }
            previous = offset;
        }
    }
}
