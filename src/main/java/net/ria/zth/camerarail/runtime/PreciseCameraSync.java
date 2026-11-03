package net.ria.zth.camerarail.runtime;

import org.bukkit.entity.Entity;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Switches the camera entity to the server's precise position mode.
 *
 * <p>A normally tracked entity has its rotation packed into a byte, which is a 1/256
 * turn step - about 1.4 degrees. That is invisible for a walking mob, but the camera
 * is the whole view: during a slow eased pan the quantised angle can stay still for
 * many ticks and then jump by 1.4 degrees, so the client visibly moves in little
 * steps even though it interpolates between the received targets.</p>
 *
 * <p>Minecraft keeps a precise mode for entities that must not drift
 * ({@code ClientboundEntityPositionSyncPacket}, which carries float yaw/pitch).
 * Setting the entity's precise flag makes the tracker use that packet at its normal
 * three-tick cadence; the client still interpolates over those three ticks, so the
 * motion is continuous and the camera keeps the same responsiveness as before. Paper
 * does not expose the flag through its API, so this bridge uses the mapped server
 * method; if it is absent in a future server version the camera simply keeps the
 * vanilla quantised tracking, so the plugin still works.</p>
 */
final class PreciseCameraSync {
    private static final Object LOCK = new Object();

    private static volatile boolean resolved;
    private static volatile boolean available;
    private static volatile boolean failureLogged;
    private static Method getHandle;
    private static Method setRequiresPrecisePosition;

    private PreciseCameraSync() {
    }

    static boolean isAvailable() {
        return available;
    }

    /** Switches the camera entity to the tracker's precise position mode. */
    static void enable(Entity camera, Logger logger) {
        if (!resolve(camera, logger)) {
            return;
        }
        try {
            Object handle = getHandle.invoke(camera);
            setRequiresPrecisePosition.invoke(handle, true);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            available = false;
            logFailure(logger, "enable precise camera sync", exception);
        }
    }

    private static boolean resolve(Entity camera, Logger logger) {
        if (resolved) {
            return available;
        }
        synchronized (LOCK) {
            if (resolved) {
                return available;
            }
            try {
                Method handleGetter = camera.getClass().getMethod("getHandle");
                Object handle = handleGetter.invoke(camera);
                Method precise = handle.getClass().getMethod("setRequiresPrecisePosition", boolean.class);
                getHandle = handleGetter;
                setRequiresPrecisePosition = precise;
                available = true;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                available = false;
                logFailure(logger, "resolve the precise camera sync bridge", exception);
            } finally {
                resolved = true;
            }
            return available;
        }
    }

    private static void logFailure(Logger logger, String action, Exception exception) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        logger.log(Level.FINE, "Could not " + action + "; the camera keeps the default entity"
                + " update rate", exception);
    }
}
