package net.ria.zth.camerarail.config;

import net.ria.zth.camerarail.model.CameraTrack;
import net.ria.zth.camerarail.model.CutStep;
import net.ria.zth.camerarail.model.EffectAction;
import net.ria.zth.camerarail.model.EffectSpec;
import net.ria.zth.camerarail.model.EffectStep;
import net.ria.zth.camerarail.model.ExitMode;
import net.ria.zth.camerarail.model.HoldStep;
import net.ria.zth.camerarail.model.MoveStep;
import net.ria.zth.camerarail.model.PathStep;
import net.ria.zth.camerarail.model.Pose;
import net.ria.zth.camerarail.model.Step;
import net.ria.zth.camerarail.model.SwayPreset;
import net.ria.zth.camerarail.model.SwaySettings;
import net.ria.zth.camerarail.model.TitleStep;
import net.ria.zth.camerarail.model.WaitStep;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serializes tracks back to the managed YAML file. The in-game editor rewrites the file
 * after each change, so the output is stable and easy to diff.
 */
public final class TrackWriter {
    private static final String HEADER = """
            # ZthCameraRail - 摄像机轨道
            # 时间单位为 tick，20 tick = 1 秒。
            # 本文件由插件编辑器维护，手写修改后请执行 /zth-camera reload。
            # 完整字段说明见插件 README 或 docs/yaml-reference.md。
            """;

    private TrackWriter() {
    }

    public static void write(Path file, Map<String, CameraTrack> tracks, RailConfig config) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        ConfigurationSection root = yaml.createSection("tracks");
        List<CameraTrack> ordered = new ArrayList<>(tracks.values());
        ordered.sort((left, right) -> left.id().compareTo(right.id()));
        for (CameraTrack track : ordered) {
            writeTrack(root.createSection(track.id()), track, config);
        }
        String text = HEADER + yaml.saveToString();
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeTrack(ConfigurationSection section, CameraTrack track, RailConfig config) {
        if (!track.displayName().equals(track.id())) {
            section.set("display-name", track.displayName());
        }
        if (!track.description().isBlank()) {
            section.set("description", track.description());
        }
        section.set("world", track.worldName());
        section.set("start", poseMap(track.start()));
        section.set("exit-mode", track.exitMode().configName());
        if (track.exitMode() == ExitMode.CUSTOM) {
            section.set("exit", poseMap(track.exit()));
        }
        SwaySettings sway = track.sway();
        String preset = presetName(sway);
        if (preset != null && sway.period() == config.defaultSwayPeriod()
                && sway.seed() == TrackParser.defaultSeed(track.id())) {
            section.set("sway", preset);
        } else {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("x", sway.x());
            map.put("y", sway.y());
            map.put("z", sway.z());
            map.put("yaw", sway.yaw());
            map.put("pitch", sway.pitch());
            map.put("period", sway.period());
            map.put("seed", sway.seed());
            section.set("sway", map);
        }
        section.set("follow", track.follow());
        if (!track.entryEffects().isEmpty()) {
            List<Map<String, Object>> effects = new ArrayList<>();
            for (EffectSpec effect : track.entryEffects()) {
                effects.add(effectMap(effect, false));
            }
            section.set("entry-effects", effects);
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Step step : track.steps()) {
            steps.add(stepMap(step));
        }
        section.set("steps", steps);
    }

    private static Map<String, Object> stepMap(Step step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", step.type().configName());
        switch (step) {
            case MoveStep move -> {
                map.put("to", poseMap(move.to()));
                map.put("duration", move.durationTicks());
                map.put("easing", easingName(move.easing()));
                putSway(map, move.sway());
            }
            case PathStep path -> {
                List<Map<String, Object>> points = new ArrayList<>();
                for (Pose point : path.points()) {
                    points.add(poseMap(point));
                }
                map.put("points", points);
                map.put("duration", path.durationTicks());
                map.put("easing", easingName(path.easing()));
                putSway(map, path.sway());
            }
            case HoldStep hold -> {
                map.put("duration", hold.durationTicks());
                putSway(map, hold.sway());
            }
            case WaitStep wait -> {
                map.put("signal", wait.signal());
                map.put("timeout", wait.timeoutTicks());
                putSway(map, wait.sway());
            }
            case CutStep cut -> {
                map.put("to", poseMap(cut.to()));
                if (cut.durationTicks() > 0) {
                    map.put("duration", cut.durationTicks());
                }
                putSway(map, cut.sway());
            }
            case EffectStep effect -> {
                map.putAll(effectMap(effect.effect(), true));
            }
            case TitleStep title -> {
                if (!title.title().isBlank()) {
                    map.put("title", title.title());
                }
                if (!title.subtitle().isBlank()) {
                    map.put("subtitle", title.subtitle());
                }
                map.put("fade-in", title.fadeInTicks());
                map.put("stay", title.stayTicks());
                map.put("fade-out", title.fadeOutTicks());
            }
        }
        return map;
    }

    private static void putSway(Map<String, Object> map, SwayPreset sway) {
        if (sway != SwayPreset.INHERIT) {
            map.put("sway", sway.name().toLowerCase(Locale.ROOT));
        }
    }

    private static Map<String, Object> poseMap(Pose pose) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", pose.x());
        map.put("y", pose.y());
        map.put("z", pose.z());
        map.put("yaw", (double) pose.yaw());
        map.put("pitch", (double) pose.pitch());
        return map;
    }

    private static Map<String, Object> effectMap(EffectSpec effect, boolean includeAction) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("effect", effect.key());
        if (includeAction && effect.action() != EffectAction.ADD) {
            map.put("action", effect.action().name().toLowerCase(Locale.ROOT));
        }
        if (effect.amplifier() != 0) {
            map.put("amplifier", effect.amplifier());
        }
        if (effect.durationTicks() != 0) {
            map.put("duration", effect.durationTicks());
        }
        return map;
    }

    private static String easingName(net.ria.zth.camerarail.model.Easing easing) {
        return easing.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String presetName(SwaySettings sway) {
        for (SwayPreset preset : List.of(SwayPreset.OFF, SwayPreset.GENTLE, SwayPreset.NATURAL, SwayPreset.STRONG)) {
            if (sway.x() == preset.x() && sway.y() == preset.y() && sway.z() == preset.z()
                    && sway.yaw() == preset.yaw() && sway.pitch() == preset.pitch()) {
                return preset.name().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }
}

