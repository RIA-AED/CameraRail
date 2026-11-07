package red.ria.camerarail;

import red.ria.camerarail.config.RailConfig;
import red.ria.camerarail.config.TrackParser;
import red.ria.camerarail.config.TrackWriter;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.CutStep;
import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.ExitMode;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.MoveStep;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;
import red.ria.camerarail.model.WaitStep;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TrackWriterTest {
    private static final RailConfig CONFIG = new RailConfig(
            100, Easing.EASE_IN_OUT, SwayPreset.NATURAL, 160, true, 6000, false, true, false,
            7L * 24 * 60 * 60 * 1000, 256, 128, 72000, false);

    @Test
    void roundTripsAHandBuiltTrack(@TempDir Path tempDir) throws Exception {
        Pose start = new Pose(0.5, 64.0, 0.5, 10, 2);
        CameraTrack track = new CameraTrack("demo", "Demo", "description", "world", start,
                ExitMode.ANIMATION_END, null,
                SwaySettings.ofPreset(SwayPreset.GENTLE, 160, TrackParser.defaultSeed("demo")), false,
                List.of(),
                List.of(
                        new MoveStep(new Pose(4, 65, 4, 20, 3), 40, Easing.LINEAR, SwayPreset.NATURAL),
                        new HoldStep(20, SwayPreset.INHERIT),
                        new WaitStep("ready", 100, SwayPreset.OFF),
                        new PathStep(List.of(new Pose(5, 65, 5, 30, 0), new Pose(6, 65, 6, 40, 0)),
                                60, Easing.SMOOTHSTEP, SwayPreset.INHERIT)
                ));

        Path file = tempDir.resolve("tracks.yml");
        TrackWriter.write(file, Map.of(track.id(), track), CONFIG);

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection section = yaml.getConfigurationSection("tracks.demo");
        assertNotNull(section);
        CameraTrack reparsed = new TrackParser(CONFIG).parseTrack("demo", section);

        assertEquals(track.id(), reparsed.id());
        assertEquals(track.displayName(), reparsed.displayName());
        assertEquals(track.worldName(), reparsed.worldName());
        assertEquals(track.start().yaw(), reparsed.start().yaw());
        assertEquals(track.steps().size(), reparsed.steps().size());
        assertEquals(track.timedDurationTicks(), reparsed.timedDurationTicks());
        assertEquals(track.sway().x(), reparsed.sway().x());
        assertEquals(ExitMode.ANIMATION_END, reparsed.exitMode());
        assertEquals(track.steps().get(3).type(), reparsed.steps().get(3).type());
    }

    @Test
    void roundTripsCutHoldDurations(@TempDir Path tempDir) throws Exception {
        CameraTrack track = new CameraTrack("cuts", "Cuts", "", "world", new Pose(0, 64, 0, 0, 0),
                ExitMode.ANIMATION_END, null,
                SwaySettings.ofPreset(SwayPreset.NATURAL, 160, TrackParser.defaultSeed("cuts")), false,
                List.of(),
                List.of(
                        new CutStep(new Pose(1, 64, 1, 90, 0), 40, SwayPreset.NATURAL),
                        new CutStep(new Pose(2, 65, 2, 180, -5), 0, SwayPreset.INHERIT)
                ));

        Path file = tempDir.resolve("tracks.yml");
        TrackWriter.write(file, Map.of(track.id(), track), CONFIG);

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection section = yaml.getConfigurationSection("tracks.cuts");
        assertNotNull(section);
        List<Map<?, ?>> written = section.getMapList("steps");
        assertEquals(40, ((Number) written.get(0).get("duration")).intValue());
        assertFalse(written.get(1).containsKey("duration"), "a pure instant cut should not write duration");

        CameraTrack reparsed = new TrackParser(CONFIG).parseTrack("cuts", section);
        assertEquals(track.steps(), reparsed.steps());
        assertEquals(40, reparsed.timedDurationTicks());
        CutStep held = (CutStep) reparsed.steps().get(0);
        assertEquals(40, held.durationTicks());
        assertEquals(0, ((CutStep) reparsed.steps().get(1)).durationTicks());
    }

    @Test
    void writesAndReparsesACustomExitPose(@TempDir Path tempDir) throws Exception {
        Pose start = new Pose(0, 64, 0, 0, 0);
        Pose exit = new Pose(1, 65, 2, 90, 0);
        CameraTrack track = new CameraTrack("custom", "Custom", "", "world", start,
                ExitMode.CUSTOM, exit,
                SwaySettings.ofPreset(SwayPreset.NATURAL, 160, TrackParser.defaultSeed("custom")), false,
                List.of(), List.of());
        Path file = tempDir.resolve("tracks.yml");
        TrackWriter.write(file, Map.of(track.id(), track), CONFIG);

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        CameraTrack reparsed = new TrackParser(CONFIG).parseTrack("custom",
                yaml.getConfigurationSection("tracks.custom"));
        assertEquals(ExitMode.CUSTOM, reparsed.exitMode());
        assertNotNull(reparsed.exit());
        assertEquals(exit.x(), reparsed.exit().x(), 0.0001);
        assertEquals(exit.yaw(), reparsed.exit().yaw(), 0.0001);
    }
}
