package net.ria.zth.camerarail.runtime;

import org.bukkit.entity.Entity;

import java.lang.reflect.Field;
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
 * method, and falls back to writing the flag field when a build renames the setter.
 * {@link #readFlag(Entity)} reports whether the flag actually reads back, and when
 * neither works the camera simply keeps the vanilla quantised tracking.</p>
 */
final class PreciseCameraSync {
    private static final Object LOCK = new Object();
    private static final String FLAG_NAME = "requiresPrecisePosition";

    private static volatile boolean resolved;
    private static volatile boolean available;
    private static volatile boolean failureLogged;
    private static Method getHandle;
    /** Setter method; null when only the flag field can be written. */
    private static Method setter;
    /** Field used for writing and reading back; null when the setter path works. */
    private static Field flagField;

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
            if (setter != null) {
                setter.invoke(handle, true);
            } else {
                flagField.setBoolean(handle, true);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            available = false;
            logFailure(logger, "enable precise camera sync", exception);
        }
    }

    /**
     * Reads back the entity's precise flag, so a bridge that resolves but does not take
     * effect can be told apart from a working one. Returns {@code null} when the flag
     * cannot be read on this server.
     */
    static Boolean readFlag(Entity camera) {
        if (!available) {
            return Boolean.FALSE;
        }
        try {
            Object handle = getHandle.invoke(camera);
            if (flagField != null) {
                return flagField.getBoolean(handle);
            }
            for (String name : new String[]{"requiresPrecisePosition", "isRequiresPrecisePosition",
                    "getRequiresPrecisePosition"}) {
                try {
                    Method getter = handle.getClass().getMethod(name);
                    if (getter.getReturnType() == boolean.class) {
                        return (Boolean) getter.invoke(handle);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try the next name, then the field.
                }
            }
            Field field = findFlagField(handle.getClass());
            return field == null ? null : field.getBoolean(handle);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
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
                getHandle = handleGetter;
                try {
                    setter = handle.getClass().getMethod("setRequiresPrecisePosition", boolean.class);
                    available = true;
                } catch (NoSuchMethodException renamed) {
                    // A build may rename the setter while keeping the flag field.
                    flagField = findFlagField(handle.getClass());
                    available = flagField != null;
                    if (!available) {
                        throw renamed;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                available = false;
                logFailure(logger, "resolve the precise camera sync bridge", exception);
            } finally {
                resolved = true;
            }
            return available;
        }
    }

    /** Finds the flag on the class or one of its superclasses and makes it accessible. */
    private static Field findFlagField(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(FLAG_NAME);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException missing) {
                // Keep walking up the hierarchy.
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
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
