package net.ria.zth.camerarail.editor;

import net.ria.zth.camerarail.model.CameraTrack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Per-player editor state. Only the command thread mutates it; preview status is also read
 * from the player scheduler.
 */
final class EditorSession {
    private static final int MAX_UNDO = 30;

    final UUID playerId;
    final String worldName;
    final Deque<UndoEntry> undo = new ArrayDeque<>();
    CameraTrack draft;
    int selected;
    /** Screen to reopen after Esc / a preview, so closing the UI to fly does not lose the place. */
    UiScreen uiScreen = UiScreen.OVERVIEW;
    int uiPage;
    int uiPoint;
    int uiEffect;
    volatile boolean previewing;
    volatile boolean saveFailed;
    /** Preview started from the Dialog editor: suppress chat feedback and reopen the UI later. */
    volatile boolean uiPreview;

    EditorSession(UUID playerId, CameraTrack draft) {
        this.playerId = playerId;
        this.worldName = draft.worldName();
        this.draft = draft;
        this.selected = draft.steps().isEmpty() ? -1 : 0;
    }

    void remember() {
        undo.push(new UndoEntry(draft, selected));
        while (undo.size() > MAX_UNDO) {
            undo.removeLast();
        }
    }

    boolean restore() {
        UndoEntry entry = undo.poll();
        if (entry == null) {
            return false;
        }
        draft = entry.track();
        selected = entry.selected();
        return true;
    }

    record UndoEntry(CameraTrack track, int selected) {
    }

    enum UiScreen {
        OVERVIEW,
        STEPS,
        STEP_FORM,
        PATH_POINTS,
        PATH_POINT_FORM,
        TRACK_SETTINGS,
        EXIT_MODE,
        POSE_START,
        POSE_EXIT,
        ENTRY_EFFECTS,
        ENTRY_EFFECT_FORM
    }
}
