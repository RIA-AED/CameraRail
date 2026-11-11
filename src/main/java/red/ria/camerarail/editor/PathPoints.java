package red.ria.camerarail.editor;

import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.runtime.TrackTimeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a captured camera view is worth storing as a path point.
 *
 * <p>Creating a path step records the player's current view as its first point; the user then
 * walks the camera and appends waypoints. Every dialog round trip takes a few ticks and the
 * player's view is not frozen while a dialog is open, so a camera can change direction (or be
 * pushed a few millimetres) between the capture of the step start and the capture of the first
 * waypoint. A point that is practically at the position the step already starts from is a
 * zero-length segment in a path that travels: progress is distributed by travelled distance, so
 * it spends no timeline, and it is the shape that used to feed the rotation spline a degenerate
 * knot. These helpers drop such a capture when it is the first point and refuse to append or
 * insert a point that sits on its neighbour.</p>
 *
 * <p>The guard compares positions only, and deliberately so: in a path that travels, two points
 * at the same position describe no movement, no matter how far apart the views are, so storing
 * the second one can never add motion to the path (an in-place turn belongs in a {@code move}
 * or {@code cut} step). The tolerance is 0.01 blocks, which is sub-pixel and far below a
 * walking step, so an intentional waypoint further away is always kept.</p>
 */
final class PathPoints {
    /** Positions closer than this are the same spot. */
    private static final double SAME_POSITION_BLOCKS = 0.01;

    /** What the caller should do with the returned points. */
    enum Outcome {
        /** The captured view was appended or inserted. */
        ADDED,
        /** The captured view replaced the placeholder a new path starts with. */
        REPLACED_SEED,
        /** The captured view sits on a neighbour and should not be stored. */
        DUPLICATE
    }

    record Result(List<Pose> points, Outcome outcome) {
        Result {
            points = List.copyOf(points);
        }
    }

    private PathPoints() {
    }

    /**
     * Whether two poses are at the same spot for path-building purposes (within 0.01 blocks).
     * Rotation is ignored: a point that does not move the camera adds no path segment.
     */
    static boolean sameSpot(Pose a, Pose b) {
        return a != null && b != null && a.distance(b) <= SAME_POSITION_BLOCKS;
    }

    /**
     * Appends a captured view to a path step. When the path still only holds the placeholder
     * recorded when the step was created, that placeholder is replaced instead of building a
     * zero-length first segment.
     *
     * @param track     the draft the step belongs to, used to find the pose the step starts at
     * @param stepIndex index of the path step in {@code track}
     * @param points    the step's current points
     * @param captured  the view captured from the player
     */
    static Result append(CameraTrack track, int stepIndex, List<Pose> points, Pose captured) {
        Pose start = TrackTimeline.poseAtStepStart(track, stepIndex);
        List<Pose> next = new ArrayList<>(points);
        boolean seed = isPlaceholder(next, start);
        if (seed) {
            next.clear();
        }
        Pose previous = next.isEmpty() ? start : next.getLast();
        if (sameSpot(previous, captured)) {
            return new Result(points, Outcome.DUPLICATE);
        }
        next.add(captured);
        return new Result(next, seed ? Outcome.REPLACED_SEED : Outcome.ADDED);
    }

    /**
     * Inserts a captured view after {@code afterIndex}. A view that sits on either neighbour is
     * rejected; a still-unused placeholder for the step start is replaced by the insertion at
     * index 0 instead of shifting it into the middle of the path.
     */
    static Result insert(CameraTrack track, int stepIndex, List<Pose> points, int afterIndex, Pose captured) {
        Pose start = TrackTimeline.poseAtStepStart(track, stepIndex);
        List<Pose> next = new ArrayList<>(points);
        if (afterIndex <= 0 && isPlaceholder(next, start)) {
            if (sameSpot(start, captured)) {
                return new Result(points, Outcome.DUPLICATE);
            }
            next.set(0, captured);
            return new Result(next, Outcome.REPLACED_SEED);
        }
        int before = afterIndex;
        int after = afterIndex + 1;
        if ((before >= 0 && before < next.size() && sameSpot(next.get(before), captured))
                || (after >= 0 && after < next.size() && sameSpot(next.get(after), captured))) {
            return new Result(points, Outcome.DUPLICATE);
        }
        next.add(Math.min(afterIndex + 1, next.size()), captured);
        return new Result(next, Outcome.ADDED);
    }

    /**
     * Whether a result would exceed the configured path-point limit. Replacing the placeholder
     * keeps the point count unchanged, so it is always allowed; the guard is also checked after
     * the duplicate test, so a rejected capture never consumes a slot.
     */
    static boolean exceedsLimit(Result result, int maxPoints) {
        return result.outcome() == Outcome.ADDED && result.points().size() > maxPoints;
    }

    private static boolean isPlaceholder(List<Pose> points, Pose stepStart) {
        return points.size() == 1 && sameSpot(points.getFirst(), stepStart);
    }
}
