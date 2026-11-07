package red.ria.camerarail.runtime;

import red.ria.camerarail.model.Pose;

import java.util.ArrayList;
import java.util.List;

/**
 * Arc-length parameterized centripetal Catmull-Rom curve through camera waypoints.
 *
 * <p>The first keyframe is the pose the path starts from. The curve is sampled once at
 * construction time, so per-tick evaluation is a binary search and a lerp.</p>
 */
public final class PathCurve {
    private static final int SUBDIVISIONS = 24;
    private static final double ALPHA = 0.5;
    private static final double EPSILON = 1.0e-4;

    private final double[] xs;
    private final double[] ys;
    private final double[] zs;
    private final double[] yaws;
    private final double[] pitches;
    private final double[] distances;
    private final double totalLength;

    private PathCurve(List<Pose> keyframes) {
        int segments = keyframes.size() - 1;
        int sampleCount = segments * SUBDIVISIONS + 1;
        xs = new double[sampleCount];
        ys = new double[sampleCount];
        zs = new double[sampleCount];
        yaws = new double[sampleCount];
        pitches = new double[sampleCount];
        distances = new double[sampleCount];

        double[] unwrappedYaw = new double[keyframes.size()];
        unwrappedYaw[0] = keyframes.getFirst().yaw();
        for (int i = 1; i < keyframes.size(); i++) {
            unwrappedYaw[i] = unwrappedYaw[i - 1]
                    + Pose.shortestYawDelta(keyframes.get(i - 1).yaw(), keyframes.get(i).yaw());
        }

        int output = 0;
        for (int segment = 0; segment < segments; segment++) {
            Pose p0 = keyframes.get(Math.max(0, segment - 1));
            Pose p1 = keyframes.get(segment);
            Pose p2 = keyframes.get(segment + 1);
            Pose p3 = keyframes.get(Math.min(keyframes.size() - 1, segment + 2));

            double t0 = 0.0;
            double t1 = t0 + knotDistance(p0, p1);
            double t2 = t1 + knotDistance(p1, p2);
            double t3 = t2 + knotDistance(p2, p3);
            double yaw0 = unwrappedYaw[Math.max(0, segment - 1)];
            double yaw1 = unwrappedYaw[segment];
            double yaw2 = unwrappedYaw[segment + 1];
            double yaw3 = unwrappedYaw[Math.min(keyframes.size() - 1, segment + 2)];

            for (int sub = 0; sub < SUBDIVISIONS; sub++) {
                double local = (double) sub / SUBDIVISIONS;
                double u = t1 + (t2 - t1) * local;
                double x = catmullRom(p0.x(), p1.x(), p2.x(), p3.x(), t0, t1, t2, t3, u);
                double y = catmullRom(p0.y(), p1.y(), p2.y(), p3.y(), t0, t1, t2, t3, u);
                double z = catmullRom(p0.z(), p1.z(), p2.z(), p3.z(), t0, t1, t2, t3, u);
                double yaw = catmullRom(yaw0, yaw1, yaw2, yaw3, t0, t1, t2, t3, u);
                double pitch = catmullRom(p0.pitch(), p1.pitch(), p2.pitch(), p3.pitch(), t0, t1, t2, t3, u);

                if (output == 0) {
                    Pose first = keyframes.getFirst();
                    xs[0] = first.x();
                    ys[0] = first.y();
                    zs[0] = first.z();
                    yaws[0] = first.yaw();
                    pitches[0] = first.pitch();
                    distances[0] = 0.0;
                } else {
                    xs[output] = x;
                    ys[output] = y;
                    zs[output] = z;
                    yaws[output] = yaw;
                    pitches[output] = Pose.clampPitch((float) pitch);
                    distances[output] = distances[output - 1] + Math.sqrt(
                            sq(x - xs[output - 1]) + sq(y - ys[output - 1]) + sq(z - zs[output - 1]));
                }
                output++;
            }
        }

        Pose last = keyframes.getLast();
        if (output < sampleCount) {
            xs[output] = last.x();
            ys[output] = last.y();
            zs[output] = last.z();
            yaws[output] = unwrappedYaw[unwrappedYaw.length - 1];
            pitches[output] = last.pitch();
            distances[output] = distances[output - 1] + Math.sqrt(
                    sq(xs[output] - xs[output - 1])
                            + sq(ys[output] - ys[output - 1])
                            + sq(zs[output] - zs[output - 1]));
        }
        // The exact endpoint wins over the final tiny spline step.
        xs[xs.length - 1] = last.x();
        ys[ys.length - 1] = last.y();
        zs[zs.length - 1] = last.z();
        yaws[yaws.length - 1] = unwrappedYaw[unwrappedYaw.length - 1];
        pitches[pitches.length - 1] = last.pitch();
        distances[distances.length - 1] = distances[distances.length - 2] + Math.sqrt(
                sq(xs[xs.length - 1] - xs[xs.length - 2])
                        + sq(ys[ys.length - 1] - ys[ys.length - 2])
                        + sq(zs[zs.length - 1] - zs[zs.length - 2]));
        totalLength = distances[distances.length - 1];
    }

    /**
     * Builds a curve through the given keyframes. The first pose is the starting pose.
     */
    public static PathCurve through(List<Pose> keyframes) {
        if (keyframes == null || keyframes.size() < 2) {
            throw new IllegalArgumentException("A path curve needs at least two keyframes");
        }
        return new PathCurve(List.copyOf(keyframes));
    }

    /**
     * Evaluates the curve at a normalized progress in [0, 1].
     */
    public Pose at(double progress) {
        double t = Math.max(0.0, Math.min(1.0, progress));
        if (t <= 0.0) {
            return new Pose(xs[0], ys[0], zs[0], (float) yaws[0], (float) pitches[0]);
        }
        if (t >= 1.0) {
            return new Pose(xs[xs.length - 1], ys[ys.length - 1], zs[zs.length - 1],
                    (float) yaws[yaws.length - 1], (float) pitches[pitches.length - 1]);
        }
        if (totalLength < 1.0e-6) {
            double position = t * (xs.length - 1);
            int index = (int) Math.floor(position);
            double local = position - index;
            return lerpSample(index, Math.min(index + 1, xs.length - 1), local);
        }
        double target = t * totalLength;
        int low = 0;
        int high = distances.length - 1;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (distances[middle] <= target) {
                low = middle;
            } else {
                high = middle;
            }
        }
        double segmentLength = distances[high] - distances[low];
        double local = segmentLength < 1.0e-9 ? 0.0 : (target - distances[low]) / segmentLength;
        return lerpSample(low, high, local);
    }

    public double length() {
        return totalLength;
    }

    private Pose poseAt(int index) {
        return new Pose(xs[index], ys[index], zs[index], (float) yaws[index], (float) pitches[index]);
    }

    private Pose lerpSample(int from, int to, double local) {
        float yaw = Pose.normalizeYaw((float) (yaws[from] + (yaws[to] - yaws[from]) * local));
        float pitch = Pose.clampPitch((float) (pitches[from] + (pitches[to] - pitches[from]) * local));
        return new Pose(
                xs[from] + (xs[to] - xs[from]) * local,
                ys[from] + (ys[to] - ys[from]) * local,
                zs[from] + (zs[to] - zs[from]) * local,
                yaw,
                pitch
        );
    }

    private static double knotDistance(Pose a, Pose b) {
        return Math.max(EPSILON, Math.pow(Math.sqrt(a.distanceSquared(b)), ALPHA));
    }

    private static double catmullRom(
            double p0, double p1, double p2, double p3,
            double t0, double t1, double t2, double t3, double u
    ) {
        double a1 = lerp(p0, p1, (u - t0) / (t1 - t0));
        double a2 = lerp(p1, p2, (u - t1) / (t2 - t1));
        double a3 = lerp(p2, p3, (u - t2) / (t3 - t2));
        double b1 = lerp(a1, a2, (u - t0) / (t2 - t0));
        double b2 = lerp(a2, a3, (u - t1) / (t3 - t1));
        return lerp(b1, b2, (u - t1) / (t2 - t1));
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private static double sq(double value) {
        return value * value;
    }

    /**
     * Computes approximate length of a list of poses, used by the editor to estimate speed.
     */
    public static double approximateLength(List<Pose> keyframes) {
        if (keyframes == null || keyframes.size() < 2) {
            return 0.0;
        }
        return PathCurve.through(keyframes).length();
    }

    /**
     * Returns the sampled points, useful for diagnostics and tests.
     */
    public List<Pose> samples() {
        List<Pose> result = new ArrayList<>(xs.length);
        for (int i = 0; i < xs.length; i++) {
            result.add(poseAt(i));
        }
        return List.copyOf(result);
    }
}
