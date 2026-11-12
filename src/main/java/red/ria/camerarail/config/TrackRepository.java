package red.ria.camerarail.config;

import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.TrackId;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
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
    /**
     * Guards every read-compute-write cycle on {@link #tracks} as well as the whole-map
     * replacement done by the reload path. The map itself is an immutable snapshot published
     * through the volatile field, so reads stay lock-free.
     */
    private final Object writeLock = new Object();
    private volatile TrackParser parser;
    private volatile RailConfig config;
    private volatile Map<String, CameraTrack> tracks = Map.of();
    /** Told when a track is deleted, outside the write lock. */
    private volatile Consumer<String> trackDeletedListener;
    /** Told after a successful rename, outside the write lock: {@code (oldId, newId)}. */
    private volatile BiConsumer<String, String> trackRenamedListener;
    /**
     * Told after a non-fatal tracks.yml reload, inside the write lock (so listeners run in the
     * same order as writes: write lock, then their own state).
     */
    private volatile Runnable reloadListener;
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
            Thread thread = new Thread(runnable, "CameraRail-IO");
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
     *
     * <p>The whole read-replace section runs under the same write lock as
     * {@link #compute(String, UnaryOperator)}: a command edit can either finish before the file is
     * read (and is therefore reloaded too) or wait until the new map is published. Without that
     * lock an edit slipping between the read and the swap would be undone in memory while its
     * queued save could still write the older file content.</p>
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
        synchronized (writeLock) {
            LoadReport report = read();
            applyReport(report, tracks);
            writeBlocked = report.fatal() || !report.errors().isEmpty();
            logReport(report);
            if (!report.fatal()) {
                // The map may have been replaced (or partially updated); listeners that cache
                // state derived from it must forget it now, while no write can interleave.
                notifyReloaded();
            }
            return report;
        }
    }

    /**
     * Registers the listener invoked inside the write lock after a non-fatal reload. Used by
     * {@code EditService} to drop undo snapshots that predate the reload. The previous listener is
     * replaced.
     */
    public void onReloaded(Runnable listener) {
        this.reloadListener = listener;
    }

    private void notifyReloaded() {
        Runnable listener = reloadListener;
        if (listener == null) {
            return;
        }
        try {
            listener.run();
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Reload listener failed", exception);
        }
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

    /**
     * Atomically applies {@code changer} to the track stored under {@code id} and stores the
     * result. The whole read-compute-write cycle runs under one lock, so edits coming from
     * different Folia region threads (commands, item tools, Dialog sessions) cannot clobber each
     * other by read-copy-swap races.
     *
     * <p>{@code changer} receives the current track, or {@code null} when the id is unknown.
     * Returning {@code null} means "abort and keep the stored value"; returning an equal track is
     * a no-op. The method returns the value now stored under {@code id}, or {@code null} when the
     * id is unknown and nothing was created. The returned track is immutable, like every value
     * in this repository.</p>
     */
    public CameraTrack compute(String id, UnaryOperator<CameraTrack> changer) {
        if (id == null || id.isBlank() || changer == null) {
            return null;
        }
        String key = id.toLowerCase(Locale.ROOT);
        synchronized (writeLock) {
            CameraTrack current = tracks.get(key);
            CameraTrack updated = changer.apply(current);
            if (updated == null || updated.equals(current)) {
                return current;
            }
            Map<String, CameraTrack> next = new LinkedHashMap<>(tracks);
            next.put(key, updated);
            tracks = Map.copyOf(next);
            return updated;
        }
    }

    public void upsert(CameraTrack track) {
        if (track == null) {
            throw new IllegalArgumentException("Track is required");
        }
        compute(track.id(), ignored -> track);
    }

    /**
     * Removes the track from the in-memory registry. Returns whether it existed. The
     * {@linkplain #onTrackDeleted(Consumer) delete listener} runs outside the write lock.
     */
    public boolean delete(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        synchronized (writeLock) {
            if (!tracks.containsKey(normalized)) {
                return false;
            }
            Map<String, CameraTrack> next = new LinkedHashMap<>(tracks);
            next.remove(normalized);
            tracks = Map.copyOf(next);
        }
        notifyTrackDeleted(normalized);
        return true;
    }

    /**
     * Registers the listener told when a track is deleted. {@code EditService} uses it to drop
     * undo entries of the removed track. The previous listener is replaced.
     */
    public void onTrackDeleted(Consumer<String> listener) {
        this.trackDeletedListener = listener;
    }

    private void notifyTrackDeleted(String trackId) {
        Consumer<String> listener = trackDeletedListener;
        if (listener == null) {
            return;
        }
        try {
            listener.accept(trackId);
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Track-deleted listener failed for '" + trackId + "'", exception);
        }
    }

    /** What happened to one {@link #rename} call. */
    public enum RenameStatus {
        RENAMED,
        /** The source id is unknown. */
        SOURCE_MISSING,
        /** Another track already uses the target id. */
        TARGET_EXISTS,
        /** The target id does not satisfy {@link TrackId#isValid(String)}. */
        INVALID_ID,
        /** The source and target ids are the same. */
        SAME_ID,
        /** The caller's precondition vetoed the move (e.g. the track is locked). */
        REFUSED
    }

    /** The outcome of a rename; {@code track} is the renamed track only when {@code RENAMED}. */
    public record RenameResult(RenameStatus status, CameraTrack track) {
        public boolean renamed() {
            return status == RenameStatus.RENAMED;
        }
    }

    /**
     * Atomically moves a track from {@code oldId} to {@code newId}: checks the source, the target
     * and the optional {@code precondition} and rewrites the map under one write lock, so two
     * callers cannot both claim the same target id. The precondition runs under the same lock and
     * may vet the move against external state (the Dialog editor's {@code EditorLocks}); returning
     * {@code false} leaves the map untouched and reports {@link RenameStatus#REFUSED}.
     *
     * <p>The rename listener is called outside the write lock after a successful move, in the same
     * way as the delete listener.</p>
     */
    public RenameResult rename(String oldId, String newId, Predicate<CameraTrack> precondition) {
        String from = oldId == null ? "" : oldId.trim().toLowerCase(Locale.ROOT);
        String to = TrackId.normalize(newId);
        if (!TrackId.isValid(to)) {
            return new RenameResult(RenameStatus.INVALID_ID, null);
        }
        if (from.isEmpty()) {
            return new RenameResult(RenameStatus.SOURCE_MISSING, null);
        }
        if (from.equals(to)) {
            return new RenameResult(RenameStatus.SAME_ID, null);
        }
        CameraTrack renamed;
        synchronized (writeLock) {
            CameraTrack current = tracks.get(from);
            if (current == null) {
                return new RenameResult(RenameStatus.SOURCE_MISSING, null);
            }
            if (tracks.containsKey(to)) {
                return new RenameResult(RenameStatus.TARGET_EXISTS, null);
            }
            if (precondition != null && !precondition.test(current)) {
                return new RenameResult(RenameStatus.REFUSED, null);
            }
            renamed = current.withId(to);
            Map<String, CameraTrack> next = new LinkedHashMap<>(tracks);
            next.remove(from);
            next.put(to, renamed);
            tracks = Map.copyOf(next);
        }
        notifyTrackRenamed(from, to);
        return new RenameResult(RenameStatus.RENAMED, renamed);
    }

    /** Convenience overload for callers that do not need a precondition. */
    public RenameResult rename(String oldId, String newId) {
        return rename(oldId, newId, null);
    }

    /**
     * Registers the listener told after a successful rename, outside the write lock. This is how
     * undo histories and per-player tool selections stop pointing at the old id. The previous
     * listener is replaced.
     */
    public void onTrackRenamed(BiConsumer<String, String> listener) {
        this.trackRenamedListener = listener;
    }

    private void notifyTrackRenamed(String oldId, String newId) {
        BiConsumer<String, String> listener = trackRenamedListener;
        if (listener == null) {
            return;
        }
        try {
            listener.accept(oldId, newId);
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Track-renamed listener failed for '" + oldId + "' -> '"
                    + newId + "'", exception);
        }
    }

    public CompletableFuture<Void> saveAsync() {
        if (writeBlocked) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "tracks.yml has malformed sections; fix them and run /camerarail reload before saving"));
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
        synchronized (writeLock) {
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
