package net.ria.zth.camerarail;

import net.ria.zth.camerarail.config.RailConfig;
import net.ria.zth.camerarail.config.TrackParser;
import net.ria.zth.camerarail.model.CameraTrack;
import net.ria.zth.camerarail.model.Easing;
import net.ria.zth.camerarail.model.MoveStep;
import net.ria.zth.camerarail.model.PathStep;
import net.ria.zth.camerarail.model.SwayPreset;
import net.ria.zth.camerarail.model.WaitStep;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackParserTest {
    private static final RailConfig CONFIG = new RailConfig(
            100, Easing.EASE_IN_OUT, SwayPreset.NATURAL, 160, true, 6000, false, false,
            7L * 24 * 60 * 60 * 1000, 256, 128, 72000, false);

    @Test
    void parsesACompleteTrackAndYamlOffBooleans() {
        CameraTrack track = parse("""
                display-name: Demo
                world: world
                start: { x: 0.5, y: 64.0, z: 0.5, yaw: 10, pitch: 2 }
                sway: off
                follow: false
                steps:
                  - type: move
                    to: { x: 4.5, y: 66.0, z: 4.5, yaw: 20, pitch: 3 }
                    duration: 40
                    easing: linear
                    sway: off
                  - type: hold
                    duration: 20
                  - type: wait
                    signal: ready
                    timeout: 100
                  - type: path
                    duration: 60
                    easing: smoothstep
                    points:
                      - { x: 5.5, y: 66.0, z: 5.5, yaw: 30, pitch: 0 }
                      - { x: 8.5, y: 65.0, z: 8.5, yaw: 40, pitch: 0 }
                """);

        assertEquals("demo", track.id());
        assertEquals("Demo", track.displayName());
        assertEquals(4, track.steps().size());
        assertTrue(track.sway().isStill());
        assertFalse(track.follow());
        MoveStep move = assertInstanceOf(MoveStep.class, track.steps().get(0));
        assertEquals(SwayPreset.OFF, move.sway());
        assertEquals(40, move.durationTicks());
        assertInstanceOf(WaitStep.class, track.steps().get(2));
        PathStep path = assertInstanceOf(PathStep.class, track.steps().get(3));
        assertEquals(2, path.points().size());
        assertEquals(120, track.timedDurationTicks());
    }

    @Test
    void rejectsUnknownKeysWithThePath() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> parse("""
                world: world
                start: { x: 0, y: 64, z: 0 }
                typo: true
                steps: []
                """));
        assertTrue(exception.getMessage().contains("typo"));
    }

    @Test
    void rejectsDuplicateWaitSignals() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> parse("""
                world: world
                start: { x: 0, y: 64, z: 0 }
                steps:
                  - { type: wait, signal: same }
                  - { type: wait, signal: same }
                """));
        assertTrue(exception.getMessage().contains("duplicate"));
    }

    @Test
    void rejectsStepsWrittenAsAMapping() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> parse("""
                world: world
                start: { x: 0, y: 64, z: 0 }
                steps:
                  type: hold
                  duration: 20
                """));
        assertTrue(exception.getMessage().contains("steps"));
        assertTrue(exception.getMessage().contains("list"));
    }

    @Test
    void rejectsEntryEffectsWrittenAsAMapping() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> parse("""
                world: world
                start: { x: 0, y: 64, z: 0 }
                entry-effects:
                  effect: blindness
                steps: []
                """));
        assertTrue(exception.getMessage().contains("entry-effects"));
        assertTrue(exception.getMessage().contains("list"));
    }

    @Test
    void rejectsOutOfRangePitch() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> parse("""
                world: world
                start: { x: 0, y: 64, z: 0, pitch: 120 }
                steps: []
                """));
        assertTrue(exception.getMessage().contains("pitch"));
    }

    private static CameraTrack parse(String yamlText) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(yamlText));
        ConfigurationSection section = yaml;
        return new TrackParser(CONFIG).parseTrack("demo", section);
    }
}
