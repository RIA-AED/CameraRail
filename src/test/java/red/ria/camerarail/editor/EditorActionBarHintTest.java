package red.ria.camerarail.editor;

import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #2: the editing tools describe a held item on the ActionBar, but a spectator can neither
 * see nor use that item - the editor preview switches the player into spectator. The readout must
 * stay silent then (and must never clear the ActionBar, which belongs to the engine or another
 * plugin); every other game mode keeps the previous behaviour.
 */
class EditorActionBarHintTest {
    @Test
    void spectatorSeesNoHeldToolHint() {
        assertFalse(EditorTools.showsToolActionBar(GameMode.SPECTATOR));
    }

    @Test
    void everyOtherGameModeKeepsTheHint() {
        for (GameMode mode : GameMode.values()) {
            if (mode == GameMode.SPECTATOR) {
                continue;
            }
            assertTrue(EditorTools.showsToolActionBar(mode), "mode " + mode);
        }
    }

    @Test
    void unknownGameModeFallsBackToShowingTheHint() {
        assertTrue(EditorTools.showsToolActionBar(null));
    }
}
