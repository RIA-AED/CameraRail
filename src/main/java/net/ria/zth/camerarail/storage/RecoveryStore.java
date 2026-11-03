package net.ria.zth.camerarail.storage;

import net.ria.zth.camerarail.config.RailConfig;
import net.ria.zth.camerarail.runtime.PlayerStateSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small per-player recovery files. They are written before a player is put into spectator
 * mode and removed after a successful cleanup. If the server crashes, the file is used on
 * the next join to undo the takeover.
 */
public final class RecoveryStore {
    private final File directory;
    private final Logger logger;
    private final ExecutorService executor;
    private volatile RailConfig config;

    public RecoveryStore(File dataFolder, RailConfig config, Logger logger) {
        this.directory = new File(dataFolder, "recovery");
        this.config = config;
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ZthCameraRail-Recovery");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void updateConfig(RailConfig newConfig) {
        this.config = newConfig;
    }

    public boolean enabled() {
        return config.recoveryEnabled();
    }

    public CompletableFuture<Void> write(UUID playerId, String trackId, long startedAt, PlayerStateSnapshot state) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        Location location = state.location();
        if (location.getWorld() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Player location has no world"));
        }
        List<EffectData> effects = new ArrayList<>();
        for (PotionEffect effect : state.effects()) {
            effects.add(EffectData.from(effect));
        }
        RecoveryData data = new RecoveryData(
                playerId,
                trackId,
                startedAt,
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                state.gameMode(),
                state.spectatorTarget(),
                state.allowFlight(),
                state.flying(),
                state.walkSpeed(),
                state.flySpeed(),
                effects
        );
        return CompletableFuture.runAsync(() -> writeNow(data), executor);
    }

    public CompletableFuture<Optional<RecoveryData>> read(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> readNow(playerId), executor);
    }

    public void delete(UUID playerId) {
        CompletableFuture.runAsync(() -> {
            try {
                Files.deleteIfExists(file(playerId));
            } catch (IOException exception) {
                logger.log(Level.WARNING, "Could not delete recovery file for " + playerId, exception);
            }
        }, executor);
    }

    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void writeNow(RecoveryData data) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("player", data.playerId().toString());
        yaml.set("track", data.trackId());
        yaml.set("started-at", data.startedAt());
        yaml.set("original.world", data.worldName());
        yaml.set("original.x", data.x());
        yaml.set("original.y", data.y());
        yaml.set("original.z", data.z());
        yaml.set("original.yaw", (double) data.yaw());
        yaml.set("original.pitch", (double) data.pitch());
        yaml.set("original.game-mode", data.gameMode().name());
        if (data.spectatorTarget() != null) {
            yaml.set("original.spectator-target", data.spectatorTarget().toString());
        }
        yaml.set("original.allow-flight", data.allowFlight());
        yaml.set("original.flying", data.flying());
        yaml.set("original.walk-speed", (double) data.walkSpeed());
        yaml.set("original.fly-speed", (double) data.flySpeed());
        List<Map<String, Object>> effects = new ArrayList<>();
        for (EffectData effect : data.effects()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", effect.key());
            map.put("duration", effect.duration());
            map.put("amplifier", effect.amplifier());
            map.put("ambient", effect.ambient());
            map.put("particles", effect.particles());
            map.put("icon", effect.icon());
            effects.add(map);
        }
        yaml.set("effects", effects);
        try {
            Files.createDirectories(directory.toPath());
            Path target = file(data.playerId());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException("Could not write recovery file for "
                    + data.playerId(), exception);
        }
    }

    private Optional<RecoveryData> readNow(UUID playerId) {
        Path file = file(playerId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
            long startedAt = yaml.getLong("started-at", 0L);
            if (startedAt <= 0L || System.currentTimeMillis() - startedAt > config.recoveryMaxAgeMillis()) {
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            String world = yaml.getString("original.world");
            if (world == null || world.isBlank()) {
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            GameMode gameMode;
            try {
                gameMode = GameMode.valueOf(yaml.getString("original.game-mode", "SURVIVAL"));
            } catch (IllegalArgumentException exception) {
                gameMode = GameMode.SURVIVAL;
            }
            List<EffectData> effects = new ArrayList<>();
            for (Map<?, ?> map : yaml.getMapList("effects")) {
                Object type = map.get("type");
                if (type == null) {
                    continue;
                }
                effects.add(new EffectData(
                        String.valueOf(type),
                        intValue(map, "duration", 1),
                        intValue(map, "amplifier", 0),
                        boolValue(map, "ambient", false),
                        boolValue(map, "particles", false),
                        boolValue(map, "icon", false)
                ));
            }
            String target = yaml.getString("original.spectator-target");
            RecoveryData data = new RecoveryData(
                    playerId,
                    yaml.getString("track", "unknown"),
                    startedAt,
                    world,
                    yaml.getDouble("original.x"),
                    yaml.getDouble("original.y"),
                    yaml.getDouble("original.z"),
                    (float) yaml.getDouble("original.yaw"),
                    (float) yaml.getDouble("original.pitch"),
                    gameMode,
                    target == null ? null : parseUuid(target),
                    yaml.getBoolean("original.allow-flight"),
                    yaml.getBoolean("original.flying"),
                    (float) yaml.getDouble("original.walk-speed", 0.2D),
                    (float) yaml.getDouble("original.fly-speed", 0.1D),
                    effects
            );
            return Optional.of(data);
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "Could not read recovery file for " + playerId, exception);
            return Optional.empty();
        }
    }

    private Path file(UUID playerId) {
        return directory.toPath().resolve(playerId + ".yml");
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static int intValue(Map<?, ?> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean boolValue(Map<?, ?> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }
}
