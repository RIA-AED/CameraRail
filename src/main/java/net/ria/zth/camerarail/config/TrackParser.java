package net.ria.zth.camerarail.config;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.ria.zth.camerarail.model.CameraTrack;
import net.ria.zth.camerarail.model.CutStep;
import net.ria.zth.camerarail.model.Easing;
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
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict YAML parser. It reports the exact path of a mistake instead of silently using
 * defaults, which keeps hand-written tracks predictable.
 */
public final class TrackParser {
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Set<String> POSE_KEYS = Set.of("x", "y", "z", "yaw", "pitch");
    private static final Set<String> STEP_COMMON = Set.of("type", "sway");
    private static final Map<String, String> EFFECT_ALIASES = createEffectAliases();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder().strict(true).build();

    private final RailConfig config;

    public TrackParser(RailConfig config) {
        this.config = config;
    }

    /**
     * Parses one track. The returned track is immutable and has no world reference.
     *
     * @throws IllegalArgumentException on any schema error, with a path in the message
     */
    public CameraTrack parseTrack(String rawId, ConfigurationSection section) {
        if (rawId == null || !ID_PATTERN.matcher(rawId).matches()) {
            throw new IllegalArgumentException("Invalid track id '" + rawId
                    + "' (use lowercase letters, digits, '-' and '_', max 64 chars)");
        }
        String id = rawId.toLowerCase(Locale.ROOT);
        String base = "tracks." + rawId;
        checkKeys(section, base, Set.of("display-name", "description", "world", "start", "exit-mode", "exit",
                "sway", "follow", "entry-effects", "steps"));

        String world = requiredString(section, "world", base + ".world");
        Pose start = parsePose(requiredSection(section, "start", base + ".start"), base + ".start");
        Pose exit = section.isSet("exit") ? parsePose(requiredSection(section, "exit", base + ".exit"),
                base + ".exit") : null;
        ExitMode exitMode = parseExitMode(section, base, exit);
        SwaySettings sway = parseTrackSway(section.get("sway"), base + ".sway", id);
        boolean follow = section.isSet("follow") ? requiredBoolean(section, "follow", base + ".follow")
                : config.followCamera();
        String displayName = optionalString(section, "display-name", id);
        String description = optionalString(section, "description", "");

        List<EffectSpec> entryEffects = parseEntryEffects(section, base);
        List<Step> steps = parseSteps(section, base);

        Set<String> waitSignals = new HashSet<>();
        for (Step step : steps) {
            if (step instanceof WaitStep wait && !waitSignals.add(wait.signal())) {
                throw new IllegalArgumentException(base + " contains duplicate wait signal '" + wait.signal() + "'");
            }
        }

        CameraTrack track = new CameraTrack(id, displayName, description, world, start, exitMode, exit,
                sway, follow, entryEffects, steps);
        if (track.timedDurationTicks() > config.maxTrackTicks()) {
            throw new IllegalArgumentException(base + " is longer than limits.max-track-ticks ("
                    + config.maxTrackTicks() + " ticks)");
        }
        return track;
    }

    private ExitMode parseExitMode(ConfigurationSection section, String base, Pose exit) {
        if (!section.isSet("exit-mode")) {
            return exit == null ? ExitMode.ANIMATION_END : ExitMode.CUSTOM;
        }
        String raw = requiredString(section, "exit-mode", base + ".exit-mode");
        ExitMode mode;
        try {
            mode = ExitMode.parse(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(base + ".exit-mode: " + exception.getMessage());
        }
        if (mode == ExitMode.CUSTOM && exit == null) {
            throw new IllegalArgumentException(base + ".exit-mode is 'custom' but " + base + ".exit is missing");
        }
        if (mode != ExitMode.CUSTOM && exit != null) {
            throw new IllegalArgumentException(base + ".exit is only allowed with exit-mode: custom");
        }
        return mode;
    }

    private List<Step> parseSteps(ConfigurationSection section, String base) {
        if (section.isSet("steps") && !section.isList("steps")) {
            throw new IllegalArgumentException(base + ".steps must be a YAML list");
        }
        List<?> raw = section.getList("steps");
        if (raw == null) {
            return List.of();
        }
        if (raw.size() > config.maxSteps()) {
            throw new IllegalArgumentException(base + ".steps has more than " + config.maxSteps() + " entries");
        }
        List<Step> steps = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            String path = base + ".steps[" + index + "]";
            ConfigurationSection step = asSection(raw.get(index), path);
            steps.add(parseStep(step, path));
        }
        return List.copyOf(steps);
    }

    private Step parseStep(ConfigurationSection section, String path) {
        String typeName = requiredString(section, "type", path + ".type");
        SwayPreset sway = parseSwayOverride(section.get("sway"), path + ".sway");
        return switch (typeName.toLowerCase(Locale.ROOT)) {
            case "move" -> {
                checkKeys(section, path, Set.of("type", "sway", "to", "duration", "easing"));
                Pose to = parsePose(requiredSection(section, "to", path + ".to"), path + ".to");
                int duration = duration(section, path, config.defaultMoveDuration());
                Easing easing = easing(section, path);
                yield new MoveStep(to, duration, easing, sway);
            }
            case "path" -> {
                checkKeys(section, path, Set.of("type", "sway", "points", "duration", "easing"));
                List<Pose> points = parsePoints(section, path);
                int duration = duration(section, path, config.defaultMoveDuration());
                Easing easing = easing(section, path);
                yield new PathStep(points, duration, easing, sway);
            }
            case "hold" -> {
                checkKeys(section, path, Set.of("type", "sway", "duration"));
                yield new HoldStep(duration(section, path, config.defaultMoveDuration()), sway);
            }
            case "wait" -> {
                checkKeys(section, path, Set.of("type", "sway", "signal", "timeout"));
                String signal = requiredString(section, "signal", path + ".signal");
                int timeout = section.isSet("timeout")
                        ? nonNegativeInt(section, "timeout", path + ".timeout", config.maxTrackTicks())
                        : config.defaultWaitTimeoutTicks();
                yield new WaitStep(signal, timeout, sway);
            }
            case "cut" -> {
                checkKeys(section, path, Set.of("type", "sway", "to"));
                yield new CutStep(parsePose(requiredSection(section, "to", path + ".to"), path + ".to"), sway);
            }
            case "effect" -> {
                checkKeys(section, path, Set.of("type", "effect", "action", "amplifier", "duration"));
                yield new EffectStep(parseEffect(section, path, true));
            }
            case "title" -> {
                checkKeys(section, path, Set.of("type", "title", "subtitle", "fade-in", "stay", "fade-out"));
                String title = optionalString(section, "title", "");
                String subtitle = optionalString(section, "subtitle", "");
                validateMiniMessage(title, path + ".title");
                validateMiniMessage(subtitle, path + ".subtitle");
                if (title.isBlank() && subtitle.isBlank()) {
                    throw new IllegalArgumentException(path + " requires a title or subtitle");
                }
                int fadeIn = nonNegativeInt(section, "fade-in", path + ".fade-in", Integer.MAX_VALUE, 20);
                int stay = nonNegativeInt(section, "stay", path + ".stay", Integer.MAX_VALUE, 60);
                int fadeOut = nonNegativeInt(section, "fade-out", path + ".fade-out", Integer.MAX_VALUE, 20);
                yield new TitleStep(title, subtitle, fadeIn, stay, fadeOut);
            }
            default -> throw new IllegalArgumentException(path + ".type: Unknown step type '" + typeName + "'");
        };
    }

    private List<Pose> parsePoints(ConfigurationSection section, String path) {
        List<?> raw = section.getList("points");
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException(path + ".points requires at least one point");
        }
        if (raw.size() > config.maxPathPoints()) {
            throw new IllegalArgumentException(path + ".points has more than " + config.maxPathPoints() + " points");
        }
        List<Pose> points = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            String pointPath = path + ".points[" + index + "]";
            points.add(parsePose(asSection(raw.get(index), pointPath), pointPath));
        }
        return points;
    }

    private List<EffectSpec> parseEntryEffects(ConfigurationSection trackSection, String base) {
        if (trackSection.isSet("entry-effects") && !trackSection.isList("entry-effects")) {
            throw new IllegalArgumentException(base + ".entry-effects must be a YAML list");
        }
        List<?> raw = trackSection.getList("entry-effects");
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<EffectSpec> effects = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            String path = base + ".entry-effects[" + index + "]";
            ConfigurationSection section = asSection(raw.get(index), path);
            checkKeys(section, path, Set.of("effect", "amplifier", "duration"));
            EffectSpec effect = parseEffect(section, path, false);
            if (effect.action() != EffectAction.ADD) {
                throw new IllegalArgumentException(path + " can only add effects");
            }
            effects.add(effect);
        }
        return List.copyOf(effects);
    }

    private EffectSpec parseEffect(ConfigurationSection section, String path, boolean allowAction) {
        String key = requiredString(section, "effect", path + ".effect");
        PotionEffectType type = resolveEffect(key, path + ".effect");
        EffectAction action = allowAction
                ? EffectAction.parse(optionalString(section, "action", "add"))
                : EffectAction.ADD;
        int amplifier = 0;
        if (section.isSet("amplifier")) {
            amplifier = nonNegativeInt(section, "amplifier", path + ".amplifier", 255);
        }
        int duration = 0;
        if (section.isSet("duration")) {
            duration = nonNegativeInt(section, "duration", path + ".duration", config.maxTrackTicks());
        }
        String canonicalKey = type.getKey().toString();
        return new EffectSpec(type, canonicalKey, action, amplifier, duration);
    }

    private Pose parsePose(ConfigurationSection section, String path) {
        checkKeys(section, path, POSE_KEYS);
        double x = finiteNumber(section, "x", path + ".x");
        double y = finiteNumber(section, "y", path + ".y");
        double z = finiteNumber(section, "z", path + ".z");
        float yaw = (float) finiteNumber(section, "yaw", path + ".yaw", 0.0);
        float pitch = (float) finiteNumber(section, "pitch", path + ".pitch", 0.0);
        if (pitch < -90.0f || pitch > 90.0f) {
            throw new IllegalArgumentException(path + ".pitch must be between -90 and 90");
        }
        return new Pose(x, y, z, yaw, pitch);
    }

    private SwaySettings parseTrackSway(Object value, String path, String trackId) {
        int period = config.defaultSwayPeriod();
        long seed = defaultSeed(trackId);
        SwayPreset preset = config.defaultSwayPreset();
        double x;
        double y;
        double z;
        double yaw;
        double pitch;

        if (value == null) {
            SwaySettings base = SwaySettings.ofPreset(preset, period, seed);
            return base;
        }
        if (value instanceof Boolean bool) {
            // YAML 1.1 parses unquoted off/on as booleans.
            return SwaySettings.ofPreset(bool ? config.defaultSwayPreset() : SwayPreset.OFF, period, seed);
        }
        if (value instanceof String rawPreset) {
            try {
                SwayPreset parsed = SwayPreset.parse(rawPreset);
                SwaySettings base = SwaySettings.ofPreset(parsed == SwayPreset.INHERIT
                        ? config.defaultSwayPreset() : parsed, period, seed);
                return base;
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(path + ": " + exception.getMessage());
            }
        }
        ConfigurationSection section = asSection(value, path);
        checkKeys(section, path, Set.of("preset", "x", "y", "z", "yaw", "pitch", "period", "seed"));
        if (section.isSet("preset")) {
            SwayPreset parsed = parseSwayOverride(section.get("preset"), path + ".preset");
            preset = parsed == SwayPreset.INHERIT ? config.defaultSwayPreset() : parsed;
        }
        SwaySettings base = SwaySettings.ofPreset(preset, period, seed);
        x = section.isSet("x") ? nonNegativeDouble(section, "x", path + ".x", 5.0) : base.x();
        y = section.isSet("y") ? nonNegativeDouble(section, "y", path + ".y", 5.0) : base.y();
        z = section.isSet("z") ? nonNegativeDouble(section, "z", path + ".z", 5.0) : base.z();
        yaw = section.isSet("yaw") ? nonNegativeDouble(section, "yaw", path + ".yaw", 30.0) : base.yaw();
        pitch = section.isSet("pitch") ? nonNegativeDouble(section, "pitch", path + ".pitch", 30.0) : base.pitch();
        if (section.isSet("period")) {
            period = requiredInt(section, "period", path + ".period", 20, config.maxTrackTicks());
        }
        if (section.isSet("seed")) {
            seed = requiredLong(section, "seed", path + ".seed");
        }
        return new SwaySettings(x, y, z, yaw, pitch, period, seed);
    }

    private static SwayPreset parseSwayOverride(Object value, String path) {
        if (value == null) {
            return SwayPreset.INHERIT;
        }
        if (value instanceof Boolean bool) {
            return bool ? SwayPreset.INHERIT : SwayPreset.OFF;
        }
        if (value instanceof String raw) {
            try {
                return SwayPreset.parse(raw);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(path + ": " + exception.getMessage());
            }
        }
        throw new IllegalArgumentException(path + " must be a sway preset");
    }

    private Easing easing(ConfigurationSection section, String path) {
        String raw = optionalString(section, "easing", config.defaultEasing().name());
        try {
            return Easing.parse(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(path + ".easing: " + exception.getMessage());
        }
    }

    private int duration(ConfigurationSection section, String path, int fallback) {
        if (!section.isSet("duration")) {
            return fallback;
        }
        return requiredInt(section, "duration", path + ".duration", 1, config.maxTrackTicks());
    }

    private PotionEffectType resolveEffect(String raw, String path) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        String mapped = EFFECT_ALIASES.getOrDefault(normalized, normalized);
        NamespacedKey key = NamespacedKey.fromString(mapped);
        if (key == null) {
            throw new IllegalArgumentException(path + ": invalid potion effect '" + raw + "'");
        }
        PotionEffectType type = Registry.EFFECT.get(key);
        if (type == null) {
            throw new IllegalArgumentException(path + ": unknown potion effect '" + raw + "'");
        }
        return type;
    }

    private static void validateMiniMessage(String text, String path) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            MINI_MESSAGE.deserialize(text);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(path + ": invalid MiniMessage: " + exception.getMessage());
        }
    }

    private static void checkKeys(ConfigurationSection section, String path, Set<String> allowed) {
        for (String key : section.getKeys(false)) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(path + "." + key + " is not a recognized option; allowed: "
                        + String.join(", ", allowed.stream().sorted().toList()));
            }
        }
    }

    private static ConfigurationSection asSection(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(path + " must be a YAML mapping");
        }
        MemoryConfiguration memory = new MemoryConfiguration();
        memory.createSection("value", castMap(map));
        ConfigurationSection section = memory.getConfigurationSection("value");
        if (section == null) {
            throw new IllegalArgumentException(path + " must be a YAML mapping");
        }
        return section;
    }

    private static Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static ConfigurationSection requiredSection(ConfigurationSection parent, String key, String path) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) {
            throw new IllegalArgumentException(path + " is required and must be a YAML mapping");
        }
        return section;
    }

    private static String requiredString(ConfigurationSection section, String key, String path) {
        String value = section.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(path + " is required");
        }
        return value;
    }

    private static String optionalString(ConfigurationSection section, String key, String fallback) {
        String value = section.getString(key);
        return value == null ? fallback : value;
    }

    private static boolean requiredBoolean(ConfigurationSection section, String key, String path) {
        if (!section.isBoolean(key)) {
            throw new IllegalArgumentException(path + " must be true or false");
        }
        return section.getBoolean(key);
    }

    private static double finiteNumber(ConfigurationSection section, String key, String path) {
        return finiteNumber(section, key, path, Double.NaN);
    }

    private static double finiteNumber(ConfigurationSection section, String key, String path, double fallback) {
        if (!section.isSet(key)) {
            if (Double.isNaN(fallback)) {
                throw new IllegalArgumentException(path + " is required");
            }
            return fallback;
        }
        Object value = section.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be a number");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(path + " must be finite");
        }
        return result;
    }

    private static int requiredInt(ConfigurationSection section, String key, String path, int min, int max) {
        if (!section.isSet(key) || !(section.get(key) instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        double numeric = number.doubleValue();
        int value = number.intValue();
        if (!Double.isFinite(numeric) || numeric != value) {
            throw new IllegalArgumentException(path + " must be a whole number");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static int nonNegativeInt(ConfigurationSection section, String key, String path, int max) {
        return nonNegativeInt(section, key, path, max, 0);
    }

    private static int nonNegativeInt(ConfigurationSection section, String key, String path, int max, int fallback) {
        if (!section.isSet(key)) {
            return fallback;
        }
        if (!(section.get(key) instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        double numeric = number.doubleValue();
        int value = number.intValue();
        if (!Double.isFinite(numeric) || numeric != value) {
            throw new IllegalArgumentException(path + " must be a whole number");
        }
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(path + " must be between 0 and " + max);
        }
        return value;
    }

    private static long requiredLong(ConfigurationSection section, String key, String path) {
        if (!(section.get(key) instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        double numeric = number.doubleValue();
        if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)) {
            throw new IllegalArgumentException(path + " must be a whole number");
        }
        return number.longValue();
    }

    private static double nonNegativeDouble(ConfigurationSection section, String key, String path, double max) {
        if (!(section.get(key) instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be a number");
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0.0 || value > max) {
            throw new IllegalArgumentException(path + " must be between 0 and " + max);
        }
        return value;
    }

    public static long defaultSeed(String trackId) {
        return 0x5DEECE66DL ^ ((long) trackId.hashCode() * 0x9E3779B97F4A7C15L);
    }

    private static Map<String, String> createEffectAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("blindness", "minecraft:blindness");
        aliases.put("darkness", "minecraft:darkness");
        aliases.put("slowness", "minecraft:slowness");
        aliases.put("slow", "minecraft:slowness");
        aliases.put("speed", "minecraft:speed");
        aliases.put("swiftness", "minecraft:speed");
        aliases.put("night_vision", "minecraft:night_vision");
        aliases.put("night-vision", "minecraft:night_vision");
        aliases.put("nausea", "minecraft:nausea");
        aliases.put("confusion", "minecraft:nausea");
        aliases.put("invisibility", "minecraft:invisibility");
        aliases.put("jump_boost", "minecraft:jump_boost");
        aliases.put("regeneration", "minecraft:regeneration");
        aliases.put("resistance", "minecraft:resistance");
        aliases.put("fire_resistance", "minecraft:fire_resistance");
        aliases.put("water_breathing", "minecraft:water_breathing");
        aliases.put("glowing", "minecraft:glowing");
        aliases.put("levitation", "minecraft:levitation");
        aliases.put("slow_falling", "minecraft:slow_falling");
        aliases.put("conduit_power", "minecraft:conduit_power");
        aliases.put("dolphins_grace", "minecraft:dolphins_grace");
        aliases.put("bad_omen", "minecraft:bad_omen");
        aliases.put("hero_of_the_village", "minecraft:hero_of_the_village");
        aliases.put("absorption", "minecraft:absorption");
        aliases.put("haste", "minecraft:haste");
        aliases.put("mining_fatigue", "minecraft:mining_fatigue");
        aliases.put("health_boost", "minecraft:health_boost");
        aliases.put("hunger", "minecraft:hunger");
        aliases.put("poison", "minecraft:poison");
        aliases.put("saturation", "minecraft:saturation");
        aliases.put("strength", "minecraft:strength");
        aliases.put("weakness", "minecraft:weakness");
        aliases.put("wither", "minecraft:wither");
        aliases.put("luck", "minecraft:luck");
        aliases.put("unluck", "minecraft:unluck");
        aliases.put("oozing", "minecraft:oozing");
        aliases.put("weaving", "minecraft:weaving");
        aliases.put("wind_charged", "minecraft:wind_charged");
        aliases.put("infested", "minecraft:infested");
        return Map.copyOf(aliases);
    }
}

