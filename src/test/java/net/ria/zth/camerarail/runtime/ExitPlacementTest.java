package net.ria.zth.camerarail.runtime;

import net.ria.zth.camerarail.model.Pose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure placement math used when the camera hands control back.
 *
 * <p>The world-block part of {@link ExitPlacement#feet} needs a live server and is covered
 * by the end-to-end check; everything here is world-free.</p>
 */
class ExitPlacementTest {
    private static final double SAFE_EPSILON = 1.0E-4;
    /**
     * The 0.1 mm lift is exact, but the result still contains the 4.77e-9 float
     * eye-height underflow that motivated the lift, so a 1e-9 delta would be too tight.
     */
    private static final double EPSILON_TOLERANCE = 1.0E-8;

    @Test
    void feetYClearsTheFloatEyeHeightUnderflow() {
        Pose pose = new Pose(0.0, 64.62, 0.0, 0.0f, 0.0f);
        double rawFeet = pose.y() - (double) 1.62f;
        // Why the epsilon exists: the float eye height makes the raw feet position land
        // 4.77e-9 below the block top instead of exactly on it.
        assertEquals(62.99999999523163, rawFeet, 1.0E-12, "raw float-precision feet Y");
        assertTrue(rawFeet < 63.0, "raw feet must be embedded below the block top");

        double y = ExitPlacement.feetY(pose, 1.62f);
        assertTrue(y > 63.0, "feet must end up above the block top, was " + y);
        assertEquals(63.0 + SAFE_EPSILON, y, EPSILON_TOLERANCE);
    }

    @Test
    void liftOutOfBlockPushesFeetAboveTheCollisionShape() {
        // The float-underflow feet Y from the bug report is inside the block at [62, 63).
        assertEquals(63.0 + SAFE_EPSILON,
                ExitPlacement.liftOutOfBlock(62.99999999523163, 62.0, 63.0), 1.0E-9);
        // Already above the shape: unchanged.
        assertEquals(63.0001, ExitPlacement.liftOutOfBlock(63.0001, 62.0, 63.0), 0.0);
        // Mid-shape (e.g. feet buried half a block in a slab): lifted to the shape top.
        assertEquals(63.0 + SAFE_EPSILON,
                ExitPlacement.liftOutOfBlock(62.5, 62.0, 63.0), 1.0E-9);
        // Below the shape: unchanged.
        assertEquals(61.9, ExitPlacement.liftOutOfBlock(61.9, 62.0, 63.0), 0.0);
        // Exactly on the shape top is a legal standing position: unchanged.
        assertEquals(63.0, ExitPlacement.liftOutOfBlock(63.0, 62.0, 63.0), 0.0);
    }
}
