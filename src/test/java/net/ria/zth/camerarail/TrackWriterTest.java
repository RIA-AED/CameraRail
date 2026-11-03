package net.ria.zth.camerarail;

import net.ria.zth.camerarail.config.RailConfig;
import net.ria.zth.camerarail.config.TrackParser;
import net.ria.zth.camerarail.config.TrackWriter;
import net.ria.zth.camerarail.model.CameraTrack;
import net.ria.zth.camerarail.model.Easing;
import net.ria.zth.camerarail.model.HoldStep;
import net.ria.zth.camerarail.model.MoveStep;
import net.ria.zth.camerarail.model.PathStep;
import net.ria.zth.camerarail.model.Pose;
import net.ria.zth.camerarail.model.SwayPreset;
import net.ria.zth.camerarail.model.SwaySettings;
import net.ria.zth.camerarail.model.WaitStep;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TrackWriterTest {
    private static final RailConfig CONFIG = new RailConfig(
            100, Easing.EASE_IN_OUT, SwayPreset.NATURAL, 160, true, 6000, false, false,
            7L * 24 * 60 * 60 * 1000, 256, 128, 72000, false);

    @Test
    void roundTripsAHandBuiltTrack(@TempDir Path tempDir) throws Exception {
        Pose start = new Pose(0.5, 64.0, 0.5, 10, 2);
        CameraTrack track = new CameraTrack("demo", "Demo", "description", "world", start, null,
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
        assertEquals(track.steps().get(3).type(), reparsed.steps().get(3).type());
    }
}
