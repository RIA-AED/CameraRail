package red.ria.camerarail.editor;

import org.junit.jupiter.api.Test;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.ExitMode;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the per-player undo stack and its stale-snapshot guard. The Bukkit-facing part
 * of {@link EditService#undo} (finding the player's history and calling it) is a thin wrapper
 * around this class; the matching logic itself is covered here.
 */
class UndoHistoryTest {
    private static CameraTrack track(String id, String version) {
        return new CameraTrack(id, version, "", "world", new Pose(0, 0, 0, 0, 0),
                ExitMode.ANIMATION_END, null,
                SwaySettings.ofPreset(SwayPreset.NATURAL, 160, 1L), false, List.of(), List.of());
    }

    @Test
    void readyWhenCurrentValueStillEqualsTheRecordedAfterSnapshot() {
        UndoHistory history = new UndoHistory();
        CameraTrack before = track("login", "v1");
        CameraTrack after = track("login", "v2");
        history.push("login", before, after);

        UndoHistory.Lookup lookup = history.lookup("login", after);
        assertEquals(UndoHistory.Status.READY, lookup.status());
        assertTrue(lookup.ready());
        assertSame(before, lookup.entry().before());
        assertSame(after, lookup.entry().after());
    }

    @Test
    void staleWhenTheTrackChangedAfterTheEdit() {
        UndoHistory history = new UndoHistory();
        history.push("login", track("login", "v1"), track("login", "v2"));

        UndoHistory.Lookup lookup = history.lookup("login", track("login", "v3"));
        assertEquals(UndoHistory.Status.STALE, lookup.status());
        assertTrue(lookup.stale());
        assertEquals(1, history.size(), "a refused undo keeps the entry for a later attempt");
    }

    @Test
    void staleWhenTheTrackWasDeleted() {
        UndoHistory history = new UndoHistory();
        history.push("login", track("login", "v1"), track("login", "v2"));
        assertEquals(UndoHistory.Status.STALE, history.lookup("login", null).status());
    }

    @Test
    void lookupPicksTheMostRecentEntryOfThatTrackOnly() {
        UndoHistory history = new UndoHistory();
        history.push("login", track("login", "v1"), track("login", "v2"));
        history.push("other", track("other", "o1"), track("other", "o2"));
        history.push("login", track("login", "v2"), track("login", "v3"));

        UndoHistory.Lookup login = history.lookup("login", track("login", "v3"));
        assertEquals(UndoHistory.Status.READY, login.status());
        assertEquals("v2", login.entry().before().displayName());
        assertEquals(UndoHistory.Status.READY, history.lookup("other", track("other", "o2")).status());
    }

    @Test
    void emptyOrUnrelatedHistoryIsNone() {
        UndoHistory history = new UndoHistory();
        assertEquals(UndoHistory.Status.NONE, history.lookup("login", track("login", "v1")).status());
        history.push("login", track("login", "v1"), track("login", "v2"));
        assertEquals(UndoHistory.Status.NONE, history.lookup("other", track("other", "v1")).status());
    }

    @Test
    void stackKeepsTheThirtyMostRecentEntries() {
        UndoHistory history = new UndoHistory();
        for (int i = 0; i < 35; i++) {
            history.push("t" + i, track("t" + i, "v" + (i - 1)), track("t" + i, "v" + i));
        }
        assertEquals(UndoHistory.MAX_ENTRIES, history.size());
        assertEquals(UndoHistory.Status.NONE, history.lookup("t0", track("t0", "v0")).status());
        assertEquals(UndoHistory.Status.NONE, history.lookup("t4", track("t4", "v4")).status());
        assertEquals(UndoHistory.Status.READY, history.lookup("t5", track("t5", "v5")).status());
        assertEquals(UndoHistory.Status.READY, history.lookup("t34", track("t34", "v34")).status());
    }

    @Test
    void removeDropsExactlyThatEntry() {
        UndoHistory history = new UndoHistory();
        history.push("login", track("login", "v1"), track("login", "v2"));
        UndoHistory.Lookup lookup = history.lookup("login", track("login", "v2"));
        assertTrue(history.remove(lookup.entry()));
        assertFalse(history.remove(lookup.entry()));
        assertEquals(UndoHistory.Status.NONE, history.lookup("login", track("login", "v2")).status());
    }

    @Test
    void forgetTrackLeavesOtherTracksAlone() {
        UndoHistory history = new UndoHistory();
        history.push("login", track("login", "v1"), track("login", "v2"));
        history.push("intro", track("intro", "i1"), track("intro", "i2"));

        history.forgetTrack("login");
        assertEquals(UndoHistory.Status.NONE, history.lookup("login", track("login", "v2")).status());
        assertEquals(UndoHistory.Status.READY, history.lookup("intro", track("intro", "i2")).status());
    }

    @Test
    void clearForgetsEverything() {
        UndoHistory history = new UndoHistory();
        history.push("a", track("a", "v1"), track("a", "v2"));
        history.push("b", track("b", "v1"), track("b", "v2"));
        history.clear();
        assertEquals(0, history.size());
        assertEquals(UndoHistory.Status.NONE, history.lookup("a", track("a", "v2")).status());
    }
}
