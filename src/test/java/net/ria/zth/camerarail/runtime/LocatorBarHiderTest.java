package net.ria.zth.camerarail.runtime;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The locator-bar hider must only ever add or remove its own transient modifier: never the
 * attribute base value, never another plugin's modifier. These tests cover the state
 * transitions that end paths, recovery and the periodic re-check rely on, including a third
 * party clearing our modifier while playback is running.
 */
class LocatorBarHiderTest {
    private static final NamespacedKey KEY = new NamespacedKey("zth_camera_rail", "locator_bar_hidden");
    private static final NamespacedKey OTHER = new NamespacedKey("other_plugin", "receive_range");

    @Test
    void hidingIsIdempotent() {
        FakeHandle handle = new FakeHandle();

        assertTrue(LocatorBarHider.setHidden(handle, KEY, true));
        assertFalse(LocatorBarHider.setHidden(handle, KEY, true));
        assertTrue(handle.hasModifier(KEY));
        assertEquals(1, handle.additions);
    }

    @Test
    void restoringRemovesOnlyOurModifier() {
        FakeHandle handle = new FakeHandle();
        handle.addTransientModifier(KEY);
        handle.addTransientModifier(OTHER);

        assertTrue(LocatorBarHider.setHidden(handle, KEY, false));
        assertFalse(handle.hasModifier(KEY));
        assertTrue(handle.hasModifier(OTHER));
        assertEquals(1, handle.removals);
    }

    @Test
    void restoringWhenNothingIsHiddenIsANoOp() {
        FakeHandle handle = new FakeHandle();

        assertFalse(LocatorBarHider.setHidden(handle, KEY, false));
        assertEquals(0, handle.removals);
    }

    @Test
    void reassertsAfterAThirdPartyClearsOurModifier() {
        FakeHandle handle = new FakeHandle();
        LocatorBarHider.setHidden(handle, KEY, true);

        // e.g. AttributeInstance#removeModifiers() or a third-party cleanup.
        handle.removeModifier(KEY);

        assertTrue(LocatorBarHider.setHidden(handle, KEY, true));
        assertTrue(handle.hasModifier(KEY));
    }

    @Test
    void disablingTheConfigDuringPlaybackOnlyRemovesOurModifier() {
        FakeHandle handle = new FakeHandle();
        LocatorBarHider.setHidden(handle, KEY, true);
        handle.addTransientModifier(OTHER);

        assertTrue(LocatorBarHider.setHidden(handle, KEY, false));
        assertFalse(handle.hasModifier(KEY));
        assertTrue(handle.hasModifier(OTHER));
    }

    @Test
    void aThirdPartyModifierIsNeverMistakenForOurs() {
        FakeHandle handle = new FakeHandle();
        handle.addTransientModifier(OTHER);

        assertFalse(LocatorBarHider.setHidden(handle, KEY, false));
        assertTrue(handle.hasModifier(OTHER));

        assertTrue(LocatorBarHider.setHidden(handle, KEY, true));
        assertTrue(handle.hasModifier(OTHER));
        assertTrue(handle.hasModifier(KEY));
    }

    private static final class FakeHandle implements LocatorBarHider.ModifierHandle {
        private final Map<NamespacedKey, Boolean> modifiers = new HashMap<>();
        private int additions;
        private int removals;

        @Override
        public boolean hasModifier(NamespacedKey key) {
            return modifiers.containsKey(key);
        }

        @Override
        public void addTransientModifier(NamespacedKey key) {
            if (modifiers.put(key, Boolean.TRUE) == null) {
                additions++;
            }
        }

        @Override
        public void removeModifier(NamespacedKey key) {
            if (modifiers.remove(key) != null) {
                removals++;
            }
        }
    }
}
