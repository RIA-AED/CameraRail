package red.ria.camerarail.editor;

import org.junit.jupiter.api.Test;
import red.ria.camerarail.config.RailConfig;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.ExitMode;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;
import red.ria.camerarail.model.TitleStep;
import red.ria.camerarail.model.WaitStep;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the shared structural rules. TrackRules touches no Bukkit type, so the whole
 * command surface / Dialog editor validation matrix is testable in a plain JUnit JVM.
 */
class TrackRulesTest {
    private static final Pose POSE = new Pose(1, 2, 3, 0, 0);

    private static RailConfig config(int maxSteps, int maxPathPoints, int maxTrackTicks) {
        return new RailConfig(100, Easing.EASE_IN_OUT, SwayPreset.NATURAL, 160, true, 6000,
                false, true, true, 1_000L, maxSteps, maxPathPoints, maxTrackTicks, false);
    }

    private static RailConfig config() {
        return config(256, 128, 72_000);
    }

    private static CameraTrack track(String id, String world, List<Step> steps) {
        return new CameraTrack(id, id, "", world, POSE, ExitMode.ANIMATION_END, null,
                SwaySettings.ofPreset(SwayPreset.NATURAL, 160, 1L), false, List.of(), steps);
    }

    private static CameraTrack track(String id, List<Step> steps) {
        return track(id, "world", steps);
    }

    private static List<Step> holds(int count, int ticks) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            steps.add(new HoldStep(ticks, SwayPreset.INHERIT));
        }
        return steps;
    }

    @Test
    void validTrackPassesAgainstItsOwnBase() {
        CameraTrack base = track("login", holds(2, 20));
        assertNull(TrackRules.validate(base, track("login", holds(2, 20)), config()));
    }

    @Test
    void newTrackSkipsTheIdentityChecks() {
        CameraTrack created = track("fresh", "world", holds(1, 20));
        assertNull(TrackRules.validate(null, created, config()),
                "a new track has no base to compare id/world with");
    }

    @Test
    void nullUpdatedIsRejected() {
        assertEquals("轨道数据无效.", TrackRules.validate(track("login", List.of()), null, config()));
    }

    @Test
    void idMustNotChange() {
        CameraTrack base = track("login", List.of());
        assertEquals("轨道 id 不能在这里修改.",
                TrackRules.validate(base, track("other", List.of()), config()));
    }

    @Test
    void worldMustNotChange() {
        CameraTrack base = track("login", "world", List.of());
        assertEquals("轨道世界不能在这里修改.",
                TrackRules.validate(base, track("login", "other_world", List.of()), config()));
    }

    @Test
    void stepCountIsLimited() {
        CameraTrack base = track("login", List.of());
        assertEquals("步骤数上限 2.",
                TrackRules.validate(base, track("login", holds(3, 1)), config(2, 128, 72_000)));
    }

    @Test
    void totalTimedDurationIsLimited() {
        CameraTrack base = track("login", List.of());
        assertEquals("总时长上限 10t.",
                TrackRules.validate(base, track("login", holds(2, 20)), config(256, 128, 10)));
    }

    @Test
    void waitSignalsMustBeUnique() {
        CameraTrack base = track("login", List.of());
        List<Step> steps = List.of(
                new WaitStep("ready", 0, SwayPreset.INHERIT),
                new WaitStep("ready", 0, SwayPreset.INHERIT));
        assertEquals("等待信号 ready 重复.", TrackRules.validate(base, track("login", steps), config()));
    }

    @Test
    void pathPointCountIsLimited() {
        CameraTrack base = track("login", List.of());
        PathStep path = new PathStep(List.of(POSE, POSE), 100, Easing.LINEAR, SwayPreset.INHERIT);
        assertEquals("路径点上限 1.",
                TrackRules.validate(base, track("login", List.of(path)), config(256, 1, 72_000)));
    }

    @Test
    void titleMiniMessageIsParsedStrictly() {
        CameraTrack base = track("login", List.of());
        TitleStep brokenTitle = new TitleStep("<red>unclosed", "", 20, 60, 20);
        assertEquals("标题不是合法的 MiniMessage.",
                TrackRules.validate(base, track("login", List.of(brokenTitle)), config()));
        TitleStep brokenSubtitle = new TitleStep("ok", "<red>unclosed", 20, 60, 20);
        assertEquals("副标题不是合法的 MiniMessage.",
                TrackRules.validate(base, track("login", List.of(brokenSubtitle)), config()));
    }

    @Test
    void validAndBlankMiniMessagePasses() {
        CameraTrack base = track("login", List.of());
        TitleStep valid = new TitleStep("<red>Hello</red>", "", 20, 60, 20);
        assertNull(TrackRules.validate(base, track("login", List.of(valid)), config()));
        assertNull(TrackRules.validateMiniMessage(null, "标题"));
        assertNull(TrackRules.validateMiniMessage("  ", "标题"));
        assertNull(TrackRules.validateMiniMessage("<red>ok</red>", "标题"));
    }

    @Test
    void trackIdPatternIsCanonical() {
        assertTrue(TrackRules.isValidId("login"));
        assertTrue(TrackRules.isValidId("a_b-1"));
        assertTrue(TrackRules.isValidId("a".repeat(64)));
        assertFalse(TrackRules.isValidId(null));
        assertFalse(TrackRules.isValidId(""));
        assertFalse(TrackRules.isValidId(" Login"));
        assertFalse(TrackRules.isValidId("LOGIN"));
        assertFalse(TrackRules.isValidId("-login"));
        assertFalse(TrackRules.isValidId("login name"));
        assertFalse(TrackRules.isValidId("a".repeat(65)));
    }
}
