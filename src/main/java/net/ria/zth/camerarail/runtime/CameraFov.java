package net.ria.zth.camerarail.runtime;

/**
 * FOV math for the fake-player camera.
 *
 * <p>The client does not apply {@code slowness}/{@code speed} attribute modifiers to a
 * client-only entity; those are normally sent by the server as an attribute update. The
 * overlay therefore computes the effective movement speed itself and writes it with
 * {@code ClientboundUpdateAttributesPacket}.</p>
 */
final class CameraFov {
    /** Vanilla player walking speed. */
    static final double BASE_WALKING_SPEED = 0.1;
    /** Vanilla movement-speed modifier per slowness level: -15%. */
    static final double SLOWNESS_PER_LEVEL = 0.15;
    /** Vanilla movement-speed modifier per speed level: +20%. */
    static final double SPEED_PER_LEVEL = 0.20;
    /** Sentinel for "effect not present". */
    static final int ABSENT = -1;

    private CameraFov() {
    }

    /**
     * Effective movement-speed attribute value from the active camera effects.
     *
     * @param slownessAmplifier zero-based slowness level, or {@link #ABSENT}
     * @param speedAmplifier    zero-based speed level, or {@link #ABSENT}
     */
    static double movementSpeed(int slownessAmplifier, int speedAmplifier) {
        double speed = BASE_WALKING_SPEED;
        if (slownessAmplifier >= 0) {
            speed *= Math.max(0.0, 1.0 - SLOWNESS_PER_LEVEL * (slownessAmplifier + 1));
        }
        if (speedAmplifier >= 0) {
            speed *= 1.0 + SPEED_PER_LEVEL * (speedAmplifier + 1);
        }
        return Math.max(0.0, Math.min(2.0, speed));
    }

    /**
     * The vanilla FOV multiplier as read by {@code AbstractClientPlayer}, before the
     * client's own clamp/smoothing. Useful for documentation and tests.
     */
    static double fovMultiplier(double movementSpeed) {
        double modifier = ((movementSpeed / BASE_WALKING_SPEED) + 1.0) / 2.0;
        return Math.max(0.1, Math.min(1.5, modifier));
    }
}
