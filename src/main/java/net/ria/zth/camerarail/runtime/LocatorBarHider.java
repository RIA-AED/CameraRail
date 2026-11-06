package net.ria.zth.camerarail.runtime;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hides the vanilla locator bar for a single player while a camera session owns them.
 *
 * <p>The locator bar has no dedicated server API. What it renders is the client-side
 * waypoint table, which is fed by the server only while the player's
 * {@code minecraft:waypoint_receive_range} attribute is positive ("receiving waypoints").
 * This class forces that attribute to {@code 0} with a <em>transient</em>
 * {@code ADD_MULTIPLIED_TOTAL(-1)} (Bukkit: {@code MULTIPLY_SCALAR_1}) modifier. On the
 * player's next tick {@code ServerPlayer#onAttributeUpdated} drops all waypoint
 * connections, the client receives {@code removeWaypoint} packets, its waypoint table
 * becomes empty and {@code Gui#nextContextualInfoState} stops selecting the locator bar -
 * the bar disappears entirely rather than turning into an empty bar.</p>
 *
 * <p>State management rules, chosen so third-party code and edge cases cannot leave the
 * player stuck without a locator bar:</p>
 *
 * <ul>
 *   <li>Only this plugin's own <em>transient</em> modifier is added or removed. The base
 *       value is never read back, snapshotted or written, and other plugins' modifiers are
 *       never touched. If another plugin also changes {@code waypoint_receive_range}
 *       (base value, permanent modifier, another transient modifier), our modifier still
 *       pins the effective value to {@code 0} while hidden, and removing our modifier
 *       leaves their change in effect afterwards.</li>
 *   <li>Transient modifiers are not persisted by {@code AttributeInstance#pack()}, so a
 *       crash, kick or relog cannot leave a "locator bar permanently hidden" state behind;
 *       the entity that carried the modifier is gone.</li>
 *   <li>If third-party code removes or replaces the attribute's modifiers during playback
 *       (or the config is reloaded), {@link CameraEngine} re-runs {@link #setHidden(Player,
 *       boolean)} from the camera tick every {@code LOCATOR_BAR_SYNC_TICKS} ticks. That is
 *       the only situation where the bar can briefly reappear (at most that interval).</li>
 *   <li>Every end path - normal finish, stop, external teleport/game-mode change, death,
 *       kick, quit, recovery and plugin disable - removes the modifier again (see the
 *       {@code restoreLocatorBar} call sites in {@link CameraEngine}).</li>
 * </ul>
 *
 * <p>All player-touching methods must run on the player's scheduler/region thread.</p>
 */
final class LocatorBarHider {
    /**
     * Multiplied total {@code -1}: the effective value is {@code base * (1 + ...) * 0 = 0}
     * whatever base value or add/multiply-base modifiers other code has set, and the ranged
     * attribute clamps the result to its {@code [0, 6e7]} range. Vanilla itself uses the same
     * operation for the crouching "stop transmitting my waypoint" modifier.
     */
    static final double HIDE_AMOUNT = -1.0;

    private static final AttributeModifier.Operation HIDE_OPERATION =
            AttributeModifier.Operation.MULTIPLY_SCALAR_1;
    /** Key is scoped to this plugin, so a remove only ever matches our own modifier. */
    private static final String MODIFIER_KEY = "locator_bar_hidden";

    /**
     * The minimal attribute surface this class needs. Kept separate so the state transitions
     * can be unit tested without a running server (and without instantiating Bukkit's
     * {@link Attribute} registry).
     */
    interface ModifierHandle {
        boolean hasModifier(NamespacedKey key);

        void addTransientModifier(NamespacedKey key);

        void removeModifier(NamespacedKey key);
    }

    private final Logger logger;
    private final NamespacedKey key;
    private final AttributeModifier modifier;
    private final AtomicBoolean warned = new AtomicBoolean();

    LocatorBarHider(Plugin plugin, Logger logger) {
        this.logger = logger;
        this.key = new NamespacedKey(plugin, MODIFIER_KEY);
        this.modifier = new AttributeModifier(key, HIDE_AMOUNT, HIDE_OPERATION);
    }

    /**
     * Pure state transition: makes the handle carry our modifier exactly when {@code hidden}
     * is true. Missing or extra third-party modifiers are left alone.
     *
     * @return whether this call changed the handle
     */
    static boolean setHidden(ModifierHandle handle, NamespacedKey key, boolean hidden) {
        boolean present = handle.hasModifier(key);
        if (hidden == present) {
            return false;
        }
        if (hidden) {
            handle.addTransientModifier(key);
        } else {
            handle.removeModifier(key);
        }
        return true;
    }

    /**
     * Applies the desired state to the player. Safe to call repeatedly and from any end path;
     * failures are logged once and never propagate (a missing locator-bar API must not break
     * playback).
     */
    void setHidden(Player player, boolean hidden) {
        if (player == null || !player.isOnline()) {
            return;
        }
        AttributeInstance instance;
        try {
            instance = player.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE);
        } catch (RuntimeException | LinkageError error) {
            warn("resolve the waypoint_receive_range attribute", error);
            return;
        }
        if (instance == null) {
            warn("resolve the waypoint_receive_range attribute", null);
            return;
        }
        try {
            setHidden(new InstanceHandle(instance), key, hidden);
        } catch (RuntimeException exception) {
            warn((hidden ? "hide" : "restore") + " the locator bar", exception);
        }
    }

    private void warn(String action, Throwable error) {
        if (!warned.compareAndSet(false, true)) {
            return;
        }
        String message = "Could not " + action + " for the camera track; the locator bar will"
                + " stay visible during playback.";
        if (error == null) {
            logger.warning(message);
        } else {
            logger.log(Level.WARNING, message, error);
        }
    }

    private final class InstanceHandle implements ModifierHandle {
        private final AttributeInstance instance;

        private InstanceHandle(AttributeInstance instance) {
            this.instance = instance;
        }

        @Override
        public boolean hasModifier(NamespacedKey key) {
            return instance.getModifier(key) != null;
        }

        @Override
        public void addTransientModifier(NamespacedKey key) {
            instance.addTransientModifier(modifier);
        }

        @Override
        public void removeModifier(NamespacedKey key) {
            instance.removeModifier(key);
        }
    }
}
