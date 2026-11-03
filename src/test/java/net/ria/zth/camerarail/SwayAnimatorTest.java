package net.ria.zth.camerarail;

import net.ria.zth.camerarail.model.SwayPreset;
import net.ria.zth.camerarail.model.SwaySettings;
import net.ria.zth.camerarail.runtime.SwayAnimator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwayAnimatorTest {
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
}
