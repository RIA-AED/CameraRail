package red.ria.camerarail.command;

import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.ExitMode;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditCompletionsTest {
    private static final Pose POSE = new Pose(0, 64, 0, 0, 0);

    @Test
    void partialFiltersCaseInsensitivelyAndKeepsTheGivenOrder() {
        List<String> options = List.of("ease-in", "Ease-Out", "linear", "sine");
        assertEquals(List.of("ease-in", "Ease-Out"), EditCompletions.partial("ease", options));
        assertEquals(List.of("Ease-Out"), EditCompletions.partial("EASE-O", options));
        assertEquals(options, EditCompletions.partial("", options));
        assertTrue(EditCompletions.partial("nope", options).isEmpty());
    }

    @Test
    void targetSuffixesOffersStepNumbersForATrack() {
        CameraTrack track = track(List.of(hold(), path(), hold()));
        assertEquals(List.of("login:1", "login:2", "login:3"),
                EditCompletions.targetSuffixes("login:", track));
        assertEquals(List.of("login:2"),
                EditCompletions.targetSuffixes("login:2", track));
    }

    @Test
    void targetSuffixesOffersPointNumbersOnlyForPathSteps() {
        CameraTrack track = track(List.of(hold(), path()));
        assertEquals(List.of("login:2:1", "login:2:2"),
                EditCompletions.targetSuffixes("login:2:", track));
        assertTrue(EditCompletions.targetSuffixes("login:1:", track).isEmpty());
        assertTrue(EditCompletions.targetSuffixes("login:9:", track).isEmpty());
    }

    @Test
    void targetSuffixesWithoutATrackSuggestsNothing() {
        assertTrue(EditCompletions.targetSuffixes("login:", null).isEmpty());
    }

    private static CameraTrack track(List<Step> steps) {
        SwaySettings sway = SwaySettings.ofPreset(SwayPreset.NATURAL, 160, 1L);
        return new CameraTrack("login", "Login", "", "world", POSE, ExitMode.ANIMATION_END, null,
                sway, false, List.of(), steps);
    }

    private static HoldStep hold() {
        return new HoldStep(20, SwayPreset.INHERIT);
    }

    private static PathStep path() {
        return new PathStep(List.of(POSE, new Pose(1, 64, 1, 0, 0)), 60, Easing.LINEAR,
                SwayPreset.INHERIT);
    }
}
