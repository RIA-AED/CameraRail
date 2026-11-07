package red.ria.camerarail.model;

import java.util.List;

/**
 * Moves through an ordered list of camera waypoints. Position follows a centripetal
 * Catmull-Rom spline; rotation is interpolated over unwrapped yaw angles.
 */
public record PathStep(List<Pose> points, int durationTicks, Easing easing, SwayPreset sway)
        implements CameraStep {
    public PathStep {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("Path requires at least one point");
        }
        points = List.copyOf(points);
        if (durationTicks < 1) {
            throw new IllegalArgumentException("Path duration must be at least 1 tick");
        }
        if (easing == null) {
            throw new IllegalArgumentException("Path easing is required");
        }
        if (sway == null) {
            throw new IllegalArgumentException("Path sway is required");
        }
    }

    @Override
    public StepType type() {
        return StepType.PATH;
    }

    @Override
    public int timedDurationTicks() {
        return durationTicks;
    }

    @Override
    public String describe() {
        return "路径 " + points.size() + "点 · " + durationTicks + "t · "
                + easing.name().toLowerCase(java.util.Locale.ROOT);
    }
}
