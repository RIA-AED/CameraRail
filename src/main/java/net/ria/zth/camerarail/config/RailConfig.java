package net.ria.zth.camerarail.config;

import net.ria.zth.camerarail.model.Easing;
import net.ria.zth.camerarail.model.SwayPreset;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Logger;

/**
 * Global configuration. Track-specific settings live in tracks.yml.
 */
public record RailConfig(
        int defaultMoveDuration,
        Easing defaultEasing,
        SwayPreset defaultSwayPreset,
        int defaultSwayPeriod,
        boolean followCamera,
        int defaultWaitTimeoutTicks,
        boolean allowSneakExit,
        boolean recoveryEnabled,
        long recoveryMaxAgeMillis,
        int maxSteps,
        int maxPathPoints,
        int maxTrackTicks,
        boolean debug
) {
    public static final int MIN_TRACK_TICKS = 1;
    public static final int MAX_TRACK_TICKS = 24 * 60 * 60 * 20;

    public static RailConfig load(FileConfiguration config, Logger logger) {
        int moveDuration = intValue(config, "defaults.move-duration", 100, 1, 20 * 60 * 10, logger);
        Easing easing = easingValue(config, logger);
        SwayPreset sway = swayValue(config, logger);
        int swayPeriod = intValue(config, "defaults.sway-period", 160, 20, 20 * 60 * 10, logger);

        boolean followCamera = config.getBoolean("session.follow-camera", true);
        int waitTimeout = intValue(config, "session.wait-timeout", 6000, 0, Integer.MAX_VALUE, logger);
        boolean allowSneakExit = config.getBoolean("session.allow-sneak-exit", false);
        boolean recovery = config.getBoolean("session.recovery", true);
        long recoveryHours = longValue(config, "session.recovery-max-age-hours", 168L, 1L, 24L * 365L, logger);

        int maxSteps = intValue(config, "limits.max-steps", 256, 1, 4096, logger);
        int maxPathPoints = intValue(config, "limits.max-path-points", 128, 1, 4096, logger);
        int maxTrackTicks = intValue(config, "limits.max-track-ticks", 72000, MIN_TRACK_TICKS, MAX_TRACK_TICKS, logger);
        boolean debug = config.getBoolean("debug", false);

        return new RailConfig(
                moveDuration,
                easing,
                sway,
                swayPeriod,
                followCamera,
                waitTimeout,
                allowSneakExit,
                recovery,
                recoveryHours * 60L * 60L * 1000L,
                maxSteps,
                maxPathPoints,
                maxTrackTicks,
                debug
        );
    }

    private static Easing easingValue(FileConfiguration config, Logger logger) {
        String raw = config.getString("defaults.easing", "ease-in-out");
        try {
            return Easing.parse(raw);
        } catch (IllegalArgumentException exception) {
            logger.warning("Invalid defaults.easing, using ease-in-out: " + exception.getMessage());
            return Easing.EASE_IN_OUT;
        }
    }

    private static SwayPreset swayValue(FileConfiguration config, Logger logger) {
        String raw = config.getString("defaults.sway", "natural");
        try {
            SwayPreset preset = SwayPreset.parse(raw);
            return preset == SwayPreset.INHERIT ? SwayPreset.NATURAL : preset;
        } catch (IllegalArgumentException exception) {
            logger.warning("Invalid defaults.sway, using natural: " + exception.getMessage());
            return SwayPreset.NATURAL;
        }
    }

    private static int intValue(FileConfiguration config, String path, int fallback, int min, int max, Logger logger) {
        int value = config.getInt(path, fallback);
        if (value < min || value > max) {
            logger.warning("Config value " + path + " is outside [" + min + ", " + max + "], using " + fallback);
            return fallback;
        }
        return value;
    }

    private static long longValue(FileConfiguration config, String path, long fallback, long min, long max, Logger logger) {
        long value = config.getLong(path, fallback);
        if (value < min || value > max) {
            logger.warning("Config value " + path + " is outside [" + min + ", " + max + "], using " + fallback);
            return fallback;
        }
        return value;
    }
}
