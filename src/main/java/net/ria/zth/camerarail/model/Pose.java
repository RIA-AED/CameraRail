package net.ria.zth.camerarail.model;

/**
 * A camera eye position and view rotation, independent of any world.
 *
 * <p>Yaw is normalized to [-180, 180) and pitch to [-90, 90]. World binding happens at
 * playback time so tracks can be loaded before all worlds are available.</p>
 */
public record Pose(double x, double y, double z, float yaw, float pitch) {
    public Pose {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Camera coordinates must be finite");
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Camera rotation must be finite");
        }
        yaw = normalizeYaw(yaw);
        pitch = clampPitch(pitch);
    }

    public static Pose of(double x, double y, double z, float yaw, float pitch) {
        return new Pose(x, y, z, yaw, pitch);
    }

    /**
     * Interpolates toward another pose using the shortest path for yaw.
     */
    public Pose lerp(Pose target, double progress) {
        double t = Easing.clamp01(progress);
        double yawDelta = shortestYawDelta(yaw, target.yaw);
        float nextYaw = normalizeYaw((float) (yaw + yawDelta * t));
        float nextPitch = clampPitch((float) (pitch + (target.pitch - pitch) * t));
        return new Pose(
                x + (target.x - x) * t,
                y + (target.y - y) * t,
                z + (target.z - z) * t,
                nextYaw,
                nextPitch
        );
    }

    /**
     * Applies a small sway offset. The offset's yaw/pitch are relative angles.
     */
    public Pose sway(double dx, double dy, double dz, double dyaw, double dpitch) {
        return new Pose(
                x + dx,
                y + dy,
                z + dz,
                normalizeYaw((float) (yaw + dyaw)),
                clampPitch((float) (pitch + dpitch))
        );
    }

    /**
     * Returns a copy with a different rotation.
     */
    public Pose withRotation(float newYaw, float newPitch) {
        return new Pose(x, y, z, newYaw, newPitch);
    }

    /**
     * Binds this pose to a world.
     */
    public org.bukkit.Location toLocation(org.bukkit.World world) {
        return new org.bukkit.Location(world, x, y, z, yaw, pitch);
    }

    /**
     * Returns the squared distance to another pose, ignoring rotation.
     */
    public double distanceSquared(Pose other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Returns the distance to another pose, ignoring rotation.
     */
    public double distance(Pose other) {
        return Math.sqrt(distanceSquared(other));
    }

    public static float normalizeYaw(float value) {
        float normalized = value % 360.0f;
        if (normalized >= 180.0f) {
            normalized -= 360.0f;
        } else if (normalized < -180.0f) {
            normalized += 360.0f;
        }
        return normalized;
    }

    public static float clampPitch(float value) {
        return Math.max(-90.0f, Math.min(90.0f, value));
    }

    /**
     * Returns the shortest signed angular delta from {@code from} to {@code to}.
     */
    public static double shortestYawDelta(float from, float to) {
        return normalizeYaw(to - from);
    }
}
