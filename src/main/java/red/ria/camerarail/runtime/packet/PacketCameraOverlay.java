package red.ria.camerarail.runtime.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.PacketHandler;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.runtime.CameraOverlay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * PacketEvents-backed fake-player camera.
 *
 * <p>The server-side camera remains the hidden armor stand. This overlay spawns a second,
 * client-only PLAYER entity and rewrites the outbound {@code SET_CAMERA} packet from the
 * armor stand to that entity. The client therefore renders through a real
 * {@code AbstractClientPlayer}, which makes the vanilla FOV calculation (movement speed,
 * blindness, darkness, night vision) run against the camera entity, while the server still
 * owns the spectator target and the world anchor.</p>
 */
public final class PacketCameraOverlay implements CameraOverlay, PacketListener {
    private final Plugin plugin;
    private final PacketEventsAPI<?> api;
    private final Map<UUID, PacketCameraView> views = new ConcurrentHashMap<>();
    private final PacketListenerCommon listener;
    private volatile boolean closed;

    public PacketCameraOverlay(Plugin plugin) {
        this.plugin = plugin;
        PacketEventsAPI<?> packetEvents = PacketEvents.getAPI();
        if (packetEvents == null || !packetEvents.isInitialized()) {
            throw new IllegalStateException("PacketEvents is present but its API is not initialized yet");
        }
        this.api = packetEvents;
        this.listener = packetEvents.getEventManager()
                .registerListener(this, PacketListenerPriority.NORMAL);
    }

    @Override
    public boolean available() {
        return !closed && api.isInitialized();
    }

    @Override
    public CameraView create(Player player, ArmorStand anchor, Pose eyePose) {
        if (!available() || player == null || anchor == null || eyePose == null) {
            return null;
        }
        User user;
        try {
            user = api.getPlayerManager().getUser(player);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "PacketEvents has no user for " + player.getName(), exception);
            return null;
        }
        if (user == null) {
            return null;
        }
        PacketCameraView view = new PacketCameraView(this, player, anchor, user, eyePose);
        try {
            view.spawn();
            views.put(player.getUniqueId(), view);
            return view;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not spawn the client-side camera player for " + player.getName()
                            + "; falling back to the armor-stand camera", exception);
            view.destroy();
            return null;
        }
    }

    /**
     * Rewrites only the two camera targets the session itself produces: the armor stand and
     * the player's own body (the temporary target used when the plugin re-sends Set Camera).
     * A camera change made by another plugin is left alone and is handled by the spectator
     * event listener instead.
     */
    @PacketHandler
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.CAMERA) {
            return;
        }
        User user = event.getUser();
        if (user == null) {
            return;
        }
        PacketCameraView view = views.get(user.getUUID());
        if (view == null || !view.active()) {
            return;
        }
        WrapperPlayServerCamera camera = new WrapperPlayServerCamera(event);
        if (!view.matchesCameraPacket(camera.getCameraId())) {
            return;
        }
        camera.setCameraId(view.entityId());
        event.markForReEncode(true);
    }

    void remove(UUID playerId, PacketCameraView view) {
        views.remove(playerId, view);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            api.getEventManager().unregisterListener(listener);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Could not unregister the PacketEvents camera listener", exception);
        }
        for (PacketCameraView view : new ArrayList<>(views.values())) {
            view.destroy();
        }
        views.clear();
    }

    Plugin plugin() {
        return plugin;
    }
}
