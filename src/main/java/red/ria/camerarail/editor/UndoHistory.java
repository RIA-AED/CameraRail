package red.ria.camerarail.editor;

import red.ria.camerarail.model.CameraTrack;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-player undo stack for the stateless command/item editing surface.
 *
 * <p>Every entry stores the complete track <em>before</em> and <em>after</em> one command edit.
 * The after snapshot is what makes undo safe: before restoring the before snapshot the caller
 * asks this class for the latest entry of that track, passing the value currently stored in the
 * repository. When the track no longer equals the recorded after snapshot, somebody (another
 * player, the Dialog editor, a reload, or a hand-edited tracks.yml) has changed it since the
 * edit, and the entry is reported as {@link Status#STALE} instead of silently reverting their
 * work.</p>
 *
 * <p>This class is deliberately pure: it touches no Bukkit type, keeps at most
 * {@link #MAX_ENTRIES} entries, and is the only place that mutates the stack. All methods are
 * synchronized so a delete hook running on another thread cannot corrupt the stack while its
 * owner is undoing.</p>
 */
final class UndoHistory {
    /** How many edits one player can undo. */
    static final int MAX_ENTRIES = 30;

    /** One recorded command edit. All fields are immutable snapshots. */
    record Entry(String trackId, CameraTrack before, CameraTrack after) {
    }

    /** The outcome of looking up an undo entry against the repository's current value. */
    enum Status {
        /** The player has no recorded edit for this track. */
        NONE,
        /** There is an entry, but the track changed after that edit. */
        STALE,
        /** The entry matches the current value and can be restored. */
        READY
    }

    /** A lookup result; {@code entry} is non-null unless {@code status} is {@link Status#NONE}. */
    record Lookup(Status status, Entry entry) {
        boolean ready() {
            return status == Status.READY;
        }

        boolean stale() {
            return status == Status.STALE;
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();

    /** Records one edit, dropping the oldest entry once {@link #MAX_ENTRIES} is reached. */
    synchronized void push(String trackId, CameraTrack before, CameraTrack after) {
        entries.addFirst(new Entry(trackId, before, after));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    /**
     * Finds the most recent entry for {@code trackId} and compares its after snapshot with the
     * value currently stored in the repository. The entry is not removed; call
     * {@link #remove(Entry)} after a successful restore.
     */
    synchronized Lookup lookup(String trackId, CameraTrack current) {
        for (Entry entry : entries) {
            if (!entry.trackId().equals(trackId)) {
                continue;
            }
            if (current == null || !current.equals(entry.after())) {
                return new Lookup(Status.STALE, entry);
            }
            return new Lookup(Status.READY, entry);
        }
        return new Lookup(Status.NONE, null);
    }

    /** Removes one entry after it was applied. */
    synchronized boolean remove(Entry entry) {
        return entries.remove(entry);
    }

    /** Forgets every edit of one track (the track was deleted). */
    synchronized void forgetTrack(String trackId) {
        entries.removeIf(entry -> entry.trackId().equals(trackId));
    }

    /** Forgets every edit (tracks.yml was reloaded or the player left). */
    synchronized void clear() {
        entries.clear();
    }

    /** Number of recorded edits, for tests and diagnostics. */
    synchronized int size() {
        return entries.size();
    }
}
