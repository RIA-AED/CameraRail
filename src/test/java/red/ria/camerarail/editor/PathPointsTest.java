package red.ria.camerarail.editor;

import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.ExitMode;
import red.ria.camerarail.model.MoveStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathPointsTest {
    private static final Pose START = new Pose(0, 64, 0, 10, 5);

    private static CameraTrack track(Pose start, List<Step> steps) {
        return new CameraTrack("test", "Test", "", "world", start, ExitMode.ANIMATION_END, null,
                SwaySettings.ofPreset(SwayPreset.NATURAL, 160, 42), true, List.of(), steps);
    }

    private static PathStep path(Pose... points) {
        return new PathStep(List.of(points), 40, Easing.LINEAR, SwayPreset.INHERIT);
    }

    @Test
    void sameSpotIgnoresTheViewAndUsesATightPositionTolerance() {
        assertTrue(PathPoints.sameSpot(START, new Pose(0, 64, 0, 10, 5)));
        assertTrue(PathPoints.sameSpot(START, new Pose(0.005, 64, 0, 10, 5)), "5 mm is the same spot");
        assertFalse(PathPoints.sameSpot(START, new Pose(0.02, 64, 0, 10, 5)), "2 cm is not");
        assertTrue(PathPoints.sameSpot(START, new Pose(0, 64, 0, 40, -20)),
                "a view swing without moving is still the same spot");
        assertTrue(PathPoints.sameSpot(START, new Pose(0, 64, 0, -170, 5)),
                "a half turn on the spot is still the same spot");
        assertFalse(PathPoints.sameSpot(null, START));
    }

    @Test
    void appendReplacesThePlaceholderRecordedWhenThePathWasCreated() {
        Pose placeholder = new Pose(0, 64, 0, 10.2f, 5); // view shifted while the dialog switched
        CameraTrack track = track(START, List.of(path(placeholder)));
        Pose captured = new Pose(6, 64, 3, 40, 0);

        PathPoints.Result result = PathPoints.append(track, 0, List.of(placeholder), captured);

        assertEquals(PathPoints.Outcome.REPLACED_SEED, result.outcome());
        assertEquals(List.of(captured), result.points());
    }

    @Test
    void appendReplacesAPlaceholderEvenWhenTheViewSwungFar() {
        Pose placeholder = new Pose(0, 64, 0, 70, 5); // player kept turning while the dialog moved
        CameraTrack track = track(START, List.of(path(placeholder)));
        Pose captured = new Pose(6, 64, 3, 40, 0);

        PathPoints.Result result = PathPoints.append(track, 0, List.of(placeholder), captured);

        assertEquals(PathPoints.Outcome.REPLACED_SEED, result.outcome());
        assertEquals(List.of(captured), result.points());
    }

    @Test
    void appendRejectsAFirstCaptureThatStillSitsOnTheStepStart() {
        Pose placeholder = new Pose(0, 64, 0, 10.2f, 5);
        CameraTrack track = track(START, List.of(path(placeholder)));
        List<Pose> points = List.of(placeholder);

        PathPoints.Result result = PathPoints.append(track, 0, points, new Pose(0, 64, 0, 45, 0));

        assertEquals(PathPoints.Outcome.DUPLICATE, result.outcome());
        assertEquals(points, result.points());
    }

    @Test
    void appendKeepsARealFirstPoint() {
        Pose first = new Pose(5, 64, 0, 0, 0);
        CameraTrack track = track(START, List.of(path(first)));
        Pose captured = new Pose(9, 64, 2, 0, 0);

        PathPoints.Result result = PathPoints.append(track, 0, List.of(first), captured);

        assertEquals(PathPoints.Outcome.ADDED, result.outcome());
        assertEquals(List.of(first, captured), result.points());
    }

    @Test
    void appendRejectsAPointThatSitsOnTheLastPoint() {
        Pose first = new Pose(5, 64, 0, 0, 0);
        Pose second = new Pose(9, 64, 2, 20, 0);
        CameraTrack track = track(START, List.of(path(first, second)));
        Pose captured = new Pose(9.005, 64, 2, 25, 0); // view moved, position did not

        PathPoints.Result result = PathPoints.append(track, 0, List.of(first, second), captured);

        assertEquals(PathPoints.Outcome.DUPLICATE, result.outcome());
        assertEquals(List.of(first, second), result.points());
    }

    @Test
    void appendAddsADistinctView() {
        Pose first = new Pose(5, 64, 0, 0, 0);
        Pose captured = new Pose(9, 64, 2, 20, 0);
        CameraTrack track = track(START, List.of(path(first)));

        PathPoints.Result result = PathPoints.append(track, 0, List.of(first), captured);

        assertEquals(PathPoints.Outcome.ADDED, result.outcome());
        assertEquals(List.of(first, captured), result.points());
    }

    @Test
    void appendUsesTheStartOfTheSelectedStepNotTheTrackStart() {
        Pose moveEnd = new Pose(20, 64, 0, 0, 0);
        Pose pathFirst = new Pose(20, 64, 0, 0.2f, 0); // placeholder of this step
        CameraTrack track = track(START, List.of(
                new MoveStep(moveEnd, 20, Easing.LINEAR, SwayPreset.INHERIT),
                path(pathFirst)));
        Pose captured = new Pose(24, 64, 4, 30, 0);

        PathPoints.Result result = PathPoints.append(track, 1, List.of(pathFirst), captured);

        assertEquals(PathPoints.Outcome.REPLACED_SEED, result.outcome());
        assertEquals(List.of(captured), result.points());
    }

    @Test
    void insertRejectsAPointThatSitsOnEitherNeighbour() {
        Pose a = new Pose(0, 64, 0, 0, 0);
        Pose b = new Pose(5, 64, 0, 10, 0);
        Pose c = new Pose(10, 64, 0, 20, 0);
        CameraTrack track = track(START, List.of(path(a, b, c)));

        PathPoints.Result repeatsPrevious = PathPoints.insert(track, 0, List.of(a, b, c), 1,
                new Pose(5.005, 64, 0, 40, 0));
        assertEquals(PathPoints.Outcome.DUPLICATE, repeatsPrevious.outcome());
        assertEquals(List.of(a, b, c), repeatsPrevious.points());

        PathPoints.Result repeatsNext = PathPoints.insert(track, 0, List.of(a, b, c), 1,
                new Pose(10, 64, 0, 60, 0));
        assertEquals(PathPoints.Outcome.DUPLICATE, repeatsNext.outcome());

        Pose middle = new Pose(7, 64, 1, 15, 0);
        PathPoints.Result distinct = PathPoints.insert(track, 0, List.of(a, b, c), 1, middle);
        assertEquals(PathPoints.Outcome.ADDED, distinct.outcome());
        assertEquals(List.of(a, b, middle, c), distinct.points());
    }

    @Test
    void insertAfterAPlaceholderReplacesIt() {
        Pose placeholder = new Pose(0, 64, 0, 10.2f, 5);
        CameraTrack track = track(START, List.of(path(placeholder)));
        Pose captured = new Pose(4, 64, 2, 45, 0);

        PathPoints.Result result = PathPoints.insert(track, 0, List.of(placeholder), 0, captured);

        assertEquals(PathPoints.Outcome.REPLACED_SEED, result.outcome());
        assertEquals(List.of(captured), result.points());
    }

    @Test
    void insertAfterARealPointAddsInTheMiddle() {
        Pose placeholder = new Pose(0, 64, 0, 10.2f, 5);
        Pose later = new Pose(8, 64, 0, 60, 0);
        CameraTrack track = track(START, List.of(path(placeholder, later)));
        Pose captured = new Pose(4, 64, 1, 30, 0);

        PathPoints.Result result = PathPoints.insert(track, 0, List.of(placeholder, later), 0, captured);

        assertEquals(PathPoints.Outcome.ADDED, result.outcome());
        assertEquals(List.of(placeholder, captured, later), result.points());
    }

    /**
     * A placeholder may sit up to 0.01 blocks from the step start, so "is this capture the same
     * as the placeholder" is the wrong question: the capture has to be compared with the step
     * start, exactly like {@link PathPoints#append} does.
     */
    @Test
    void insertComparesThePlaceholderWithTheStepStart() {
        Pose placeholder = new Pose(0.009, 64, 0, 10, 5); // 9 mm from the step start
        CameraTrack track = track(START, List.of(path(placeholder)));
        Pose captured = new Pose(0.015, 64, 0, 10, 5); // 15 mm from the step start: a real move

        PathPoints.Result result = PathPoints.insert(track, 0, List.of(placeholder), 0, captured);

        assertEquals(PathPoints.Outcome.REPLACED_SEED, result.outcome());
        assertEquals(List.of(captured), result.points());
    }

    /**
     * The limit may be configured as low as one point. Replacing the placeholder keeps the
     * count at one, so the first real waypoint must still be accepted; only a captured point
     * that actually increases the count is rejected.
     */
    @Test
    void exceedsLimitOnlyBlocksPointsThatIncreaseTheCount() {
        Pose placeholder = new Pose(0, 64, 0, 10.2f, 5);
        CameraTrack track = track(START, List.of(path(placeholder)));

        PathPoints.Result replaced = PathPoints.append(track, 0, List.of(placeholder), new Pose(6, 64, 3, 40, 0));
        assertEquals(PathPoints.Outcome.REPLACED_SEED, replaced.outcome());
        assertFalse(PathPoints.exceedsLimit(replaced, 1), "the placeholder replacement keeps one point");

        Pose first = new Pose(5, 64, 0, 0, 0);
        Pose second = new Pose(9, 64, 2, 20, 0);
        PathPoints.Result added = PathPoints.append(track, 0, List.of(first), second);
        assertEquals(PathPoints.Outcome.ADDED, added.outcome());
        assertFalse(PathPoints.exceedsLimit(added, 2));
        assertTrue(PathPoints.exceedsLimit(added, 1));

        PathPoints.Result inserted = PathPoints.insert(track, 0, List.of(first), 0, second);
        assertEquals(PathPoints.Outcome.ADDED, inserted.outcome());
        assertFalse(PathPoints.exceedsLimit(inserted, 2));
        assertTrue(PathPoints.exceedsLimit(inserted, 1));

        PathPoints.Result duplicate = PathPoints.append(track, 0, List.of(first), new Pose(5.005, 64, 0, 30, 0));
        assertEquals(PathPoints.Outcome.DUPLICATE, duplicate.outcome());
        assertFalse(PathPoints.exceedsLimit(duplicate, 1), "a rejected capture never consumes a slot");
    }
}
