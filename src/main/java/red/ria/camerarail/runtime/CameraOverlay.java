package red.ria.camerarail.runtime;

import red.ria.camerarail.model.EffectSpec;
import red.ria.camerarail.model.Pose;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Optional client-side camera overlay.
 *
 * <p>The server keeps its hidden armor stand as the authoritative camera anchor: the
 * spectator target, the per-tick snap, chunk tickets and the follow teleports all keep
 * working unchanged. On top of that anchor an overlay may introduce a second, client-only
 * entity that becomes the client's {@code Camera} entity. The overlay is therefore purely
 * visual: it must never replace the server-side spectator target.</p>
 *
 * <p>The PacketEvents implementation is the only production implementation. This
 * interface deliberately contains no PacketEvents types so the core camera engine can
 * still load and fall back to the armor-stand camera when PacketEvents is absent.</p>
 */
public interface CameraOverlay extends AutoCloseable {

    /**
     * Whether this overlay can currently create views. The camera falls back to the armor
     * stand whenever this is false or {@link #create} returns {@code null}.
     */
    boolean available();

    /**
     * Creates and shows the client-only camera entity at {@code eyePose}. The armor stand
     * is the server anchor; {@code eyePose} is where the camera eye must be.
     *
     * @return the view, or {@code null} when the overlay is unavailable or the packets
     * could not be sent
     */
    CameraView create(Player player, ArmorStand anchor, Pose eyePose);

    @Override
    void close();

    /**
     * One client-side camera entity. All methods are safe to call from the camera entity's
     * scheduler; implementations must tolerate a closed connection.
     */
    interface CameraView {

        boolean active();

        int entityId();

        /**
         * Sends the current eye pose. Implementations convert the eye position to the
         * entity's feet because a player camera has a vanilla eye height.
         */
        void sync(Pose eyePose);

        /**
         * Sets the fake player's movement-speed attribute. The client does not apply mob
         * effect attribute modifiers to a client-only entity, so slowness/speed FOV has to
         * be expressed as a real attribute update.
         */
        void setMovementSpeed(double speed);

        /**
         * Re-creates the client-only camera entity at {@code eyePose} and points the client
         * camera at the new entity in the same tick, so a hard cut is a frame jump instead
         * of the client interpolating the previous entity towards the new pose.
         *
         * <p>The new entity must keep the movement-speed attribute and the camera-visible
         * effects that were applied to the previous one, or FOV/blindness would reset.</p>
         */
        void hardCut(Pose eyePose);

        /**
         * Applies a camera-visible effect. Effects that only render on the local player
         * (nausea) are ignored by the overlay.
         */
        void applyEffect(EffectSpec effect);

        void removeEffect(PotionEffectType type);

        /**
         * Stops intercepting Set Camera packets. Called immediately before the server
         * releases its spectator target, so the server's reset packet reaches the client.
         */
        void deactivate();

        /**
         * Removes the client-only entity and its player-info entry. Idempotent.
         */
        void destroy();
    }
}
