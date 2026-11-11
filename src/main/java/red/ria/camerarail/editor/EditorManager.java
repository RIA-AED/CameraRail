package red.ria.camerarail.editor;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import red.ria.camerarail.api.CameraTrackEndEvent;
import red.ria.camerarail.config.RailConfig;
import red.ria.camerarail.config.TrackParser;
import red.ria.camerarail.config.TrackRepository;
import red.ria.camerarail.message.Messages;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.ExitMode;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;
import red.ria.camerarail.runtime.CameraEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Dialog editor sessions.
 *
 * <p>This class owns the Dialog editor's draft, its undo history and its UI state. It is
 * independent of the stateless command/tool editing surface ({@link EditService}); the only
 * shared state is {@link EditorLocks}, which keeps a track from being edited by both systems at
 * the same time. Every draft change is written through the repository immediately.</p>
 */
public final class EditorManager implements Listener {
    private final Plugin plugin;
    private final TrackRepository repository;
    private final CameraEngine engine;
    private final EditorLocks locks;
    private final Supplier<RailConfig> config;
    private final Supplier<Messages> messages;
    private final Map<UUID, EditorSession> editors = new ConcurrentHashMap<>();

    public EditorManager(Plugin plugin, TrackRepository repository, CameraEngine engine, EditorLocks locks,
                         Supplier<RailConfig> config, Supplier<Messages> messages) {
        this.plugin = plugin;
        this.repository = repository;
        this.engine = engine;
        this.locks = locks;
        this.config = config;
        this.messages = messages;
    }

    public boolean hasEditors() {
        return !editors.isEmpty();
    }

    /** Whether the editor should promise fake-camera-only FOV/effect behaviour. */
    public boolean fakeCameraAvailable() {
        return engine.fakeCameraAvailable();
    }

    /** Whether a Dialog session currently holds this track. */
    public boolean isEditingTrack(String trackId) {
        return locks.locked(trackId);
    }

    public void open(Player player, String rawId) {
        if (editors.containsKey(player.getUniqueId())) {
            messages.get().send(player, "editor.already", "track", editors.get(player.getUniqueId()).draft.id());
            return;
        }
        String id = normalizeId(rawId);
        if (id == null) {
            messages.get().send(player, "track.not-found", "track", rawId);
            return;
        }
        // tryLock is the only acquisition path: a check-then-lock pair would let two players
        // (or two Folia region threads) both create a session for the same track.
        if (!locks.tryLock(id, player.getUniqueId())) {
            messages.get().send(player, "manage.edit-busy", "track", id);
            return;
        }
        boolean keepLock = false;
        try {
            // Read the draft only after the lock is ours. A concurrent command edit either ran
            // before this read (so the draft sees it) or its lock check sees our lock and refuses.
            CameraTrack track = readAtomically(id);
            if (track == null) {
                messages.get().send(player, "track.not-found", "track", rawId);
                return;
            }
            if (!track.worldName().equals(player.getWorld().getName())) {
                messages.get().send(player, "editor.world-mismatch", "world", track.worldName());
                return;
            }
            EditorSession session = new EditorSession(player.getUniqueId(), track);
            editors.put(player.getUniqueId(), session);
            messages.get().send(player, "editor.opened", "track", track.id(), "world", track.worldName());
            keepLock = true;
        } finally {
            if (!keepLock) {
                locks.unlock(id, player.getUniqueId());
            }
        }
    }

    public void create(Player player, String rawId, String displayName) {
        if (editors.containsKey(player.getUniqueId())) {
            messages.get().send(player, "editor.already", "track", editors.get(player.getUniqueId()).draft.id());
            return;
        }
        String id = normalizeId(rawId);
        if (id == null || !TrackRules.isValidId(id)) {
            messages.get().send(player, "manage.invalid-id");
            return;
        }
        if (!locks.tryLock(id, player.getUniqueId())) {
            messages.get().send(player, "manage.edit-busy", "track", id);
            return;
        }
        boolean keepLock = false;
        try {
            if (readAtomically(id) != null) {
                messages.get().send(player, "manage.exists", "track", id);
                return;
            }
            Pose start = eyePose(player);
            if (start == null) {
                return;
            }
            RailConfig cfg = config.get();
            SwaySettings sway = SwaySettings.ofPreset(cfg.defaultSwayPreset(), cfg.defaultSwayPeriod(),
                    TrackParser.defaultSeed(id));
            CameraTrack track = new CameraTrack(id,
                    displayName == null || displayName.isBlank() ? id : displayName,
                    "", player.getWorld().getName(), start, ExitMode.ANIMATION_END, null, sway,
                    cfg.followCamera(), List.of(), List.of(new HoldStep(20, SwayPreset.INHERIT)));
            boolean[] created = {false};
            repository.compute(id, current -> {
                if (current != null) {
                    return current;
                }
                created[0] = true;
                return track;
            });
            if (!created[0]) {
                messages.get().send(player, "manage.exists", "track", id);
                return;
            }
            EditorSession session = new EditorSession(player.getUniqueId(), track);
            editors.put(player.getUniqueId(), session);
            persist(player, repository.saveAsync());
            messages.get().send(player, "editor.created", "track", id);
            keepLock = true;
        } finally {
            if (!keepLock) {
                locks.unlock(id, player.getUniqueId());
            }
        }
    }

    public void close(Player player) {
        EditorSession session = editors.remove(player.getUniqueId());
        if (session == null) {
            messages.get().send(player, "editor.not-editing");
            return;
        }
        locks.unlock(session.draft.id(), player.getUniqueId());
        if (session.previewing && engine.isPlaying(player)) {
            engine.stop(player);
        }
        messages.get().send(player, "editor.closed");
    }

    public void handleQuit(Player player) {
        EditorSession session = editors.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        locks.unlock(session.draft.id(), player.getUniqueId());
        if (session.previewing) {
            engine.stop(player);
        }
    }

    /** Deletes the track of the player's Dialog session. The caller has already confirmed. */
    public void delete(Player player) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null) {
            messages.get().send(player, "editor.not-editing");
            return;
        }
        if (session.previewing && engine.isPlaying(player)) {
            engine.stop(player);
        }
        String trackId = session.draft.id();
        if (repository.delete(trackId)) {
            persist(player, repository.saveAsync());
        }
        editors.remove(player.getUniqueId());
        locks.unlock(trackId, player.getUniqueId());
        messages.get().send(player, "editor.deleted", "track", trackId);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onTrackEnd(CameraTrackEndEvent event) {
        if (!event.preview()) {
            return;
        }
        EditorSession session = editors.get(event.getPlayer().getUniqueId());
        if (session != null && session.previewing) {
            boolean ui = session.uiPreview;
            session.previewing = false;
            session.uiPreview = false;
            if (!ui) {
                messages.get().send(event.getPlayer(), "editor.preview-ended");
            }
        }
    }

    // ------------------------------------------------------------------ dialog editor API

    /**
     * Session owned by the player, or {@code null}. Dialog actions run on the player's
     * scheduler, the same thread that owns the session.
     */
    EditorSession session(Player player) {
        return editors.get(player.getUniqueId());
    }

    /**
     * Replaces the whole draft. Returns {@code null} on success, or a user-facing error.
     * The caller is responsible for running on the player's scheduler.
     */
    String applyDraft(Player player, CameraTrack updated, int selected) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null) {
            return "你当前不在编辑模式.";
        }
        String error = validateDraft(session, updated);
        if (error != null) {
            return error;
        }
        session.remember();
        session.draft = updated;
        session.selected = updated.steps().isEmpty()
                ? -1
                : Math.max(0, Math.min(selected, updated.steps().size() - 1));
        persisted(player, session);
        return null;
    }

    /**
     * Appends a step and selects it. Returns {@code null} on success, or a user-facing error.
     */
    String addStep(Player player, Step step) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null) {
            return "你当前不在编辑模式.";
        }
        if (step == null) {
            return "步骤无效.";
        }
        List<Step> steps = new ArrayList<>(session.draft.steps());
        steps.add(step);
        CameraTrack updated = session.draft.withSteps(steps);
        String error = validateDraft(session, updated);
        if (error != null) {
            return error;
        }
        session.remember();
        session.draft = updated;
        session.selected = steps.size() - 1;
        persisted(player, session);
        return null;
    }

    /**
     * Undoes one edit without chat feedback. Returns false when there is nothing to undo.
     */
    boolean undoQuiet(Player player) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null || !session.restore()) {
            return false;
        }
        persisted(player, session);
        return true;
    }

    /**
     * Starts a UI preview at {@code startIndex} without chat feedback. The end event is
     * handled by {@link EditorUI}, which reopens the editor.
     */
    boolean startPreview(Player player, int startIndex) {
        return startPreview(player, startIndex, -1);
    }

    /**
     * Starts a UI preview of the inclusive interval {@code [startIndex, endIndex]}. A
     * negative {@code endIndex} means "to the end of the track".
     */
    boolean startPreview(Player player, int startIndex, int endIndex) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null || session.previewing || engine.isPlaying(player)) {
            return false;
        }
        if (session.draft.steps().isEmpty()) {
            return false;
        }
        int index = Math.max(0, Math.min(startIndex, session.draft.steps().size() - 1));
        int last = session.draft.steps().size() - 1;
        int end = endIndex < 0 ? last : Math.max(index, Math.min(endIndex, last));
        if (!storeDraft(player, session)) {
            return false;
        }
        persist(player, repository.saveAsync());
        session.previewing = true;
        session.uiPreview = true;
        boolean started;
        try {
            started = engine.playPreview(player, session.draft.id(), index, end);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not start camera preview for " + player.getName(), exception);
            started = false;
        }
        if (!started) {
            session.previewing = false;
            session.uiPreview = false;
            return false;
        }
        // A canceled CameraTrackStartEvent produces no end event. Release the flag so the
        // UI can be reopened instead of leaving the editor locked in "previewing".
        player.getScheduler().execute(plugin, () -> {
            if (editors.get(player.getUniqueId()) == session
                    && session.previewing && !engine.isPlaying(player)) {
                session.previewing = false;
                session.uiPreview = false;
            }
        }, () -> {
        }, 5L);
        return true;
    }

    /**
     * Stops a UI preview. The end event reopens the editor; this method intentionally
     * leaves {@code previewing} set so the normal end handling runs.
     */
    boolean stopPreviewQuiet(Player player) {
        EditorSession session = editors.get(player.getUniqueId());
        if (session == null || !session.previewing) {
            return false;
        }
        session.uiPreview = true;
        return engine.stop(player);
    }

    private String validateDraft(EditorSession session, CameraTrack updated) {
        return TrackRules.validate(session.draft, updated, config.get());
    }

    private Pose eyePose(Player player) {
        if (engine.isPlaying(player)) {
            messages.get().send(player, "editor.preview-active");
            return null;
        }
        Location eye = player.getEyeLocation();
        return new Pose(eye.getX(), eye.getY(), eye.getZ(), player.getYaw(), player.getPitch());
    }

    private static String normalizeId(String rawId) {
        return rawId == null ? null : rawId.toLowerCase(Locale.ROOT);
    }

    /**
     * Reads the current track under the repository write lock. A command edit is either fully
     * before this read or sees the session lock and refuses, so the draft cannot start from a
     * value the command surface is about to replace.
     */
    private CameraTrack readAtomically(String id) {
        return repository.compute(id, current -> current);
    }

    /**
     * Writes the draft into the repository through the atomic compute path. Returns {@code false}
     * when the track no longer exists; the caller must then skip the save instead of writing a
     * stale draft that would resurrect a deleted track.
     */
    private boolean storeDraft(Player player, EditorSession session) {
        CameraTrack stored = repository.compute(session.draft.id(),
                current -> current == null ? null : session.draft);
        if (stored != null) {
            return true;
        }
        if (!session.saveFailed) {
            session.saveFailed = true;
            messages.get().send(player, "track.not-found", "track", session.draft.id());
        }
        return false;
    }

    private void persisted(Player player, EditorSession session) {
        if (!storeDraft(player, session)) {
            return;
        }
        persist(player, repository.saveAsync());
    }

    private void persist(Player player, java.util.concurrent.CompletableFuture<Void> future) {
        EditorSession session = editors.get(player.getUniqueId());
        future.whenComplete((ignored, error) -> {
            if (error == null) {
                if (session != null) {
                    session.saveFailed = false;
                }
                return;
            }
            if (session != null) {
                session.saveFailed = true;
            }
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not save tracks.yml", error);
            if (player.isOnline()) {
                player.getScheduler().execute(plugin,
                        () -> messages.get().send(player, "editor.save-failed"), () -> {
                        }, 0L);
            }
        });
    }
}
