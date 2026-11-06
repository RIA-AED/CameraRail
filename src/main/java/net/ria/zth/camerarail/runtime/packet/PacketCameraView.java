package net.ria.zth.camerarail.runtime.packet;

import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.potion.PotionType;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.kyori.adventure.text.Component;
import net.ria.zth.camerarail.model.EffectSpec;
import net.ria.zth.camerarail.model.Pose;
import net.ria.zth.camerarail.runtime.CameraOverlay;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * One client-only PLAYER entity used as the visual camera.
 *
 * <p>Only the client sees this entity: it has no server entity, no chunk ticket and no
 * connection. Its position and body rotation are pushed from the armor stand's eye pose
 * every third camera tick, matching the server tracker's precise position cadence, while
 * the head rotation is pushed on every tick where the packed value changes. The spectator
 * camera reads the entity's <em>head</em> yaw, so the position-only sync alone would leave
 * the view frozen at the spawn rotation; see {@link CameraEntitySync}. The server's armor
 * stand keeps providing chunk loading, the spectator snap and follow teleports.</p>
 */
final class PacketCameraView implements CameraOverlay.CameraView {
    /** Vanilla standing-player eye height, used to convert the eye pose to entity feet. */
    private static final double PLAYER_EYE_HEIGHT = 1.62;
    private static final byte INVISIBLE_FLAG = 0x20;

    private final PacketCameraOverlay overlay;
    private final Player player;
    private final int entityId;
    private final int anchorEntityId;
    private final int playerEntityId;
    private final User user;
    private final java.util.UUID profileId;
    private final String profileName;
    private final Pose initialPose;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean destroyed = new AtomicBoolean();
    /** Decides the position cadence and the packed head-yaw dedup for every camera tick. */
    private final CameraEntitySync entitySync = new CameraEntitySync();
    private volatile double movementSpeed = 0.1;
    private volatile boolean sendFailureLogged;

    PacketCameraView(
            PacketCameraOverlay overlay,
            Player player,
            org.bukkit.entity.ArmorStand anchor,
            User user,
            Pose eyePose
    ) {
        this.overlay = overlay;
        this.player = player;
        this.user = user;
        this.entityId = SpigotReflectionUtil.generateEntityId();
        this.anchorEntityId = anchor.getEntityId();
        this.playerEntityId = player.getEntityId();
        this.profileId = java.util.UUID.randomUUID();
        this.profileName = "ZthCam" + String.format(java.util.Locale.ROOT, "%06X",
                ThreadLocalRandom.current().nextInt(0x1000000));
        this.initialPose = eyePose;
    }

    /**
     * Sends everything the client needs before the first Set Camera packet. Player info is
     * first so the client can construct a RemotePlayer; then the entity, its metadata and
     * its movement-speed attribute.
     */
    void spawn() {
        UserProfile profile = new UserProfile(profileId, profileName);
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
                new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                        profile,
                        false,
                        0,
                        GameMode.SPECTATOR,
                        Component.empty(),
                        null);
        EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = EnumSet.of(
                WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME);
        user.sendPacket(new WrapperPlayServerPlayerInfoUpdate(actions, List.of(info)));

        double feetY = eyeY() - PLAYER_EYE_HEIGHT;
        user.sendPacket(new WrapperPlayServerSpawnEntity(
                entityId,
                profileId,
                EntityTypes.PLAYER,
                new Location(eyeX(), feetY, eyeZ(), eyeYaw(), eyePitch()),
                eyeYaw(),
                0,
                Vector3d.zero()));
        // Spectator players can still see invisible entities, but a camera entity is not
        // rendered when it is the camera. Marking it invisible keeps it harmless if a client
        // version decides to render the camera entity anyway.
        user.sendPacket(new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(0, EntityDataTypes.BYTE, INVISIBLE_FLAG))));
        // The client creates RemotePlayer with the vanilla default walking speed (0.1).
        // Effects such as slowness do not modify a client-only entity's attributes on the
        // client, so FOV is driven by this explicit attribute update instead.
        sendMovementSpeed(0.1);
        // The server is about to send Set Camera(anchor); the listener rewrites it. Sending
        // the fake target here as well means a late or dropped server packet cannot leave
        // the client in free spectator mode, and it establishes the target before the first
        // position sync.
        user.sendPacket(new WrapperPlayServerCamera(entityId));
        active.set(true);
    }

    @Override
    public boolean active() {
        return active.get() && !destroyed.get();
    }

    @Override
    public int entityId() {
        return entityId;
    }

    @Override
    public void sync(Pose eyePose) {
        if (!active()) {
            return;
        }
        CameraEntitySync.SyncPlan plan = entitySync.tick(eyePose.yaw());
        if (plan.sendPosition()) {
            try {
                // The tracker uses a three-tick cadence for precise entity positions and
                // lets the client interpolate between them. Sending every tick would
                // retarget the client interpolator every tick and add a constant lag.
                double feetY = eyePose.y() - PLAYER_EYE_HEIGHT;
                EntityPositionData data = new EntityPositionData(
                        new Vector3d(eyePose.x(), feetY, eyePose.z()),
                        Vector3d.zero(),
                        eyePose.yaw(),
                        eyePose.pitch());
                user.sendPacket(new WrapperPlayServerEntityPositionSync(entityId, data, false));
            } catch (RuntimeException exception) {
                logSendFailure("sync the fake camera player", exception);
            }
        }
        if (plan.sendHeadLook()) {
            try {
                // The spectator camera reads LivingEntity#getViewYRot, i.e. the head yaw,
                // and the position sync above only updates the body yaw/pitch. The spawn
                // packet's head yaw is therefore the last one the client ever sees unless
                // every changed head yaw is sent explicitly.
                user.sendPacket(new WrapperPlayServerEntityHeadLook(entityId, plan.headYaw()));
            } catch (RuntimeException exception) {
                // Keep the position sync independent: a failed head-look send must not
                // stop the camera from following its track.
                logSendFailure("sync the fake camera player's head rotation", exception);
            }
        }
    }

    @Override
    public synchronized void setMovementSpeed(double speed) {
        if (!active() || !Double.isFinite(speed)) {
            return;
        }
        double clamped = Math.max(0.0, Math.min(2.0, speed));
        if (Math.abs(clamped - movementSpeed) < 1.0E-9) {
            return;
        }
        movementSpeed = clamped;
        sendMovementSpeed(clamped);
    }

    private void sendMovementSpeed(double speed) {
        try {
            user.sendPacket(new WrapperPlayServerUpdateAttributes(entityId, List.of(
                    new WrapperPlayServerUpdateAttributes.Property(
                            Attributes.MOVEMENT_SPEED, speed, List.of()))));
        } catch (RuntimeException exception) {
            logSendFailure("update camera movement speed", exception);
        }
    }

    @Override
    public void applyEffect(EffectSpec effect) {
        if (!active()) {
            return;
        }
        PotionType potion = cameraPotion(effect.type());
        if (potion == null) {
            return;
        }
        int duration = effect.durationTicks() == 0 ? -1 : effect.durationTicks();
        try {
            user.sendPacket(new WrapperPlayServerEntityEffect(
                    entityId, potion, effect.amplifier(), duration, (byte) 0));
        } catch (RuntimeException exception) {
            logSendFailure("apply camera effect " + effect.key(), exception);
        }
    }

    @Override
    public void removeEffect(PotionEffectType type) {
        if (!active()) {
            return;
        }
        PotionType potion = cameraPotion(type);
        if (potion == null) {
            return;
        }
        try {
            user.sendPacket(new WrapperPlayServerRemoveEntityEffect(entityId, potion));
        } catch (RuntimeException exception) {
            logSendFailure("remove camera effect " + type.getKey(), exception);
        }
    }

    /**
     * Maps only effects the client actually reads from the camera entity. Nausea is
     * intentionally absent: it is rendered from the local player and must stay on the real
     * player, not on this fake entity.
     */
    private static PotionType cameraPotion(PotionEffectType type) {
        if (type == null) {
            return null;
        }
        return switch (type.getKey().getKey()) {
            case "blindness" -> PotionTypes.BLINDNESS;
            case "darkness" -> PotionTypes.DARKNESS;
            case "night_vision" -> PotionTypes.NIGHT_VISION;
            // Slowness/speed are intentionally not sent as effects to the client-only
            // entity: FOV is driven by the explicit movement-speed attribute instead.
            default -> null;
        };
    }

    @Override
    public void deactivate() {
        active.set(false);
    }

    @Override
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        active.set(false);
        overlay.remove(player.getUniqueId(), this);
        try {
            // Abnormal end paths (abandoned session, plugin disable) may destroy the fake
            // entity without a preceding server-side Set Camera reset. A duplicate reset
            // after a normal end is harmless.
            user.sendPacket(new WrapperPlayServerCamera(playerEntityId));
            user.sendPacket(new WrapperPlayServerDestroyEntities(entityId));
            user.sendPacket(new WrapperPlayServerPlayerInfoRemove(List.of(profileId)));
        } catch (RuntimeException exception) {
            // The connection is already gone (quit/kick); there is nothing left to clean up
            // on the client.
            if (!sendFailureLogged) {
                sendFailureLogged = true;
                overlay.plugin().getLogger().log(Level.FINE,
                        "Could not remove the fake camera player for " + player.getName(), exception);
            }
        }
    }

    boolean matchesCameraPacket(int cameraId) {
        return cameraId == anchorEntityId || cameraId == playerEntityId;
    }

    private void logSendFailure(String action, RuntimeException exception) {
        if (sendFailureLogged) {
            return;
        }
        sendFailureLogged = true;
        overlay.plugin().getLogger().log(Level.FINE,
                "Could not " + action + " for " + player.getName()
                        + "; the armor-stand camera remains authoritative", exception);
    }

    // The spawn packet is built once, from the pose the session attached at.
    private double eyeX() {
        return initialPose.x();
    }

    private double eyeY() {
        return initialPose.y();
    }

    private double eyeZ() {
        return initialPose.z();
    }

    private float eyeYaw() {
        return initialPose.yaw();
    }

    private float eyePitch() {
        return initialPose.pitch();
    }
}
