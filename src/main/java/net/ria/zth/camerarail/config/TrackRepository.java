package net.ria.zth.camerarail.config;

import net.ria.zth.camerarail.model.CameraTrack;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe in-memory track registry plus an asynchronous single-writer persistence
 * queue. Commands and the editor update the map immediately; disk writes happen off the
 * server thread.
 */
public final class TrackRepository {
    private final Logger logger;
    private final File file;
    private final ExecutorService writerExecutor;
    private volatile TrackParser parser;
    private volatile RailConfig config;
    private volatile Map<String, CameraTrack> tracks = Map.of();
    /**
     * True when the last read found malformed hand-written sections. Writing the in-memory
     * map would silently delete those sections, so persistence is blocked until the file
     * is fixed and reloaded successfully.
     */
    private volatile boolean writeBlocked;

    public TrackRepository(File file, RailConfig config, Logger logger) {
        this.file = file;
        this.config = config;
        this.logger = logger;
        this.parser = new TrackParser(config);
        this.writerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ZthCameraRail-IO");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Reads the file synchronously. Safe during plugin enable.
     */
    public LoadReport loadInitial() {
        LoadReport report = read();
        applyReport(report, Map.of());
        writeBlocked = report.fatal() || !report.errors().isEmpty();
        logReport(report);
        return report;
    }

    /**
     * Reads the file and keeps the old version of any track that fails to parse.
     */
    public LoadReport reload() {
        // A delete/create may still be queued on the writer. Flush before reading so a
        // reload cannot resurrect a deleted track or lose a just-created one.
        try {
            flush().get(3, TimeUnit.SECONDS);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Timed out waiting for pending tracks.yml writes before reload",
                    exception);
        }
        LoadReport report = read();
        applyReport(report, tracks);
        writeBlocked = report.fatal() || !report.errors().isEmpty();
        logReport(report);
        return report;
    }

    public CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(() -> {
        }, writerExecutor);
    }

    public boolean writeBlocked() {
        return writeBlocked;
    }

    public void updateConfig(RailConfig newConfig) {
        this.config = newConfig;
        this.parser = new TrackParser(newConfig);
    }

    public Map<String, CameraTrack> all() {
        return tracks;
    }

    public CameraTrack get(String id) {
        if (id == null) {
            return null;
        }
        return tracks.get(id.toLowerCase(Locale.ROOT));
    }

    public Set<String> ids() {
        return tracks.keySet();
    }

    public void upsert(CameraTrack track) {
        Map<String, CameraTrack> next = new LinkedHashMap<>(tracks);
        next.put(track.id(), track);
        tracks = Map.copyOf(next);
    }

    public boolean delete(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        if (!tracks.containsKey(normalized)) {
            return false;
        }
        Map<String, CameraTrack> next = new LinkedHashMap<>(tracks);
        next.remove(normalized);
        tracks = Map.copyOf(next);
        return true;
    }

    public CompletableFuture<Void> saveAsync() {
        if (writeBlocked) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "tracks.yml has malformed sections; fix them and run /zth-camera reload before saving"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                TrackWriter.write(file.toPath(), tracks, config);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not write " + file.getName() + ": " + exception.getMessage(), exception);
            }
        }, writerExecutor);
    }

    public File file() {
        return file;
    }

    public void close() {
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writerExecutor.shutdownNow();
        }
    }

    private LoadReport read() {
        if (!file.isFile()) {
            return LoadReport.fatal("Missing file " + file.getAbsolutePath());
        }
        YamlConfiguration yaml;
        try {
            yaml = YamlConfiguration.loadConfiguration(file);
        } catch (RuntimeException exception) {
            return LoadReport.fatal("Could not parse " + file.getName() + ": " + exception.getMessage());
        }
        for (String key : yaml.getKeys(false)) {
            if (!key.equals("schema-version") && !key.equals("tracks")) {
                logger.warning("tracks.yml: unknown top-level key '" + key + "' ignored");
            }
        }
        int schema = yaml.getInt("schema-version", 1);
        if (schema != 1) {
            return LoadReport.fatal("Unsupported tracks.yml schema-version " + schema + " (expected 1)");
        }
        ConfigurationSection root = yaml.getConfigurationSection("tracks");
        if (root == null) {
            return LoadReport.fatal("tracks.yml must contain a 'tracks' mapping");
        }
        Map<String, CameraTrack> loaded = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        Set<String> failed = new LinkedHashSet<>();
        for (String rawId : root.getKeys(false)) {
            String normalized = rawId.toLowerCase(Locale.ROOT);
            if (loaded.containsKey(normalized)) {
                String error = "Duplicate track id '" + rawId + "' (ids are case-insensitive)";
                errors.add(error);
                failed.add(normalized);
                continue;
            }
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) {
                errors.add("tracks." + rawId + " must be a YAML mapping");
                failed.add(normalized);
                continue;
            }
            try {
                loaded.put(normalized, parser.parseTrack(normalized, section));
                failed.remove(normalized);
            } catch (RuntimeException exception) {
                errors.add(rootMessage(exception));
                failed.add(normalized);
            }
        }
        return new LoadReport(Map.copyOf(loaded), List.copyOf(errors), Set.copyOf(failed), false);
    }

    private void applyReport(LoadReport report, Map<String, CameraTrack> previous) {
        if (report.fatal()) {
            if (previous.isEmpty()) {
                tracks = Map.of();
            }
            return;
        }
        Map<String, CameraTrack> next = new LinkedHashMap<>(report.tracks());
        for (String failedId : report.failedIds()) {
            CameraTrack old = previous.get(failedId);
            if (old != null) {
                next.put(failedId, old);
            }
        }
        tracks = Map.copyOf(next);
    }

    private void logReport(LoadReport report) {
        for (String error : report.errors()) {
            logger.severe("tracks.yml: " + error);
        }
        if (report.fatal()) {
            return;
        }
        if (report.errors().isEmpty()) {
            logger.info("Loaded " + report.tracks().size() + " camera track(s).");
        } else {
            logger.warning("Loaded " + report.tracks().size() + " camera track(s) with "
                    + report.errors().size() + " error(s).");
        }
        if (config.debug()) {
            for (CameraTrack track : report.tracks().values()) {
                logger.info("Track " + track.id() + ": world=" + track.worldName()
                        + " steps=" + track.steps().size() + " ticks=" + track.timedDurationTicks());
            }
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /**
     * Logs and returns a save failure to the caller. Used by the editor.
     */
    public CompletableFuture<Void> saveAsyncLogged() {
        return saveAsync().whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                logger.log(Level.SEVERE, "Failed to save tracks.yml", throwable);
            }
        });
    }
}
