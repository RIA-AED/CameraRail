package red.ria.camerarail.runtime.packet;

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
import red.ria.camerarail.model.EffectSpec;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.runtime.CameraOverlay;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p>A {@code cut} step cannot use that cadence: the client interpolates a player entity's
 * position and head yaw over about three ticks, so a sync would make the cut glide. The
 * view therefore re-creates the entity at the cut pose with {@link #hardCut(Pose)} and
 * points Set Camera at the new entity in the same tick; a camera-target switch is applied
 * immediately and the fresh entity has no interpolation state to glide from.</p>
 */
final class PacketCameraView implements CameraOverlay.CameraView {
    /** Vanilla standing-player eye height, used to convert the eye pose to entity feet. */
    private static final double PLAYER_EYE_HEIGHT = 1.62;
    private static final byte INVISIBLE_FLAG = 0x20;
    /** The walking speed the client gives every fresh RemotePlayer; FOV is driven from here. */
    private static final double DEFAULT_MOVEMENT_SPEED = 0.1;

    private final PacketCameraOverlay overlay;
    private final Player player;
    private final int anchorEntityId;
    private final int playerEntityId;
    private final User user;
    /** The eye pose the session attached at; only the initial spawn uses it. */
    private final Pose initialPose;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean destroyed = new AtomicBoolean();
    /** Decides the position cadence and the packed head-yaw dedup for every camera tick. */
    private final CameraEntitySync entitySync = new CameraEntitySync();
    /**
     * Camera-visible effects currently applied to the fake entity, so a hard cut can re-send
     * them to the re-created entity. Nausea is never in here: it must stay on the real player.
     */
    private final Map<PotionType, EffectSpec> cameraEffects = new ConcurrentHashMap<>();
    /**
     * The id of the current client-only entity. Mutable and {@code volatile} because a hard
     * cut replaces the entity while the overlay's packet listener reads the id from the
     * netty thread to rewrite Set Camera packets.
     */
    private volatile int entityId;
    /** Profile of the current client-only entity; replaced together with {@link #entityId}. */
    private java.util.UUID profileId;
    private String profileName;
    private volatile double movementSpeed = DEFAULT_MOVEMENT_SPEED;
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
        this.profileName = newProfileName();
        this.initialPose = eyePose;
    }

    /**
     * Sends everything the client needs before the first Set Camera packet. Player info is
     * first so the client can construct a RemotePlayer; then the entity, its metadata and
     * its movement-speed attribute.
     */
    void spawn() {
        sendPlayerInfo(profileId, profileName);
        sendEntitySpawn(entityId, profileId, initialPose, DEFAULT_MOVEMENT_SPEED);
        // The server is about to send Set Camera(anchor); the listener rewrites it. Sending
        // the fake target here as well means a late or dropped server packet cannot leave
        // the client in free spectator mode, and it establishes the target before the first
        // position sync.
        sendCamera(entityId);
        active.set(true);
    }

    /**
     * Re-creates the client-only camera entity at {@code eyePose} and points the client
     * camera at it in the same tick, so a hard cut is a frame jump instead of a glide.
     *
     * <p>The client interpolates a player entity's position and head yaw over about three
     * ticks, so moving the existing entity would make the cut slide. A camera-target switch
     * is applied immediately instead, and a freshly spawned entity has no interpolation
     * state to slide from. The old entity and its player-info entry are both removed, so the
     * tab list and entity tracker do not accumulate one fake player per cut.</p>
     */
    @Override
    public void hardCut(Pose eyePose) {
        if (eyePose == null || !active()) {
            return;
        }
        int previousEntityId = entityId;
        java.util.UUID previousProfileId = profileId;
        int newEntityId = SpigotReflectionUtil.generateEntityId();
        java.util.UUID newProfileId = java.util.UUID.randomUUID();
        String replacementProfileName = newProfileName();
        // Publish the new identity before sending any packet: the overlay's listener runs on
        // the netty thread and must rewrite a concurrent server Set Camera packet to the new
        // entity, not to the one that is about to be destroyed.
        entityId = newEntityId;
        profileId = newProfileId;
        profileName = replacementProfileName;
        try {
            user.sendPacket(new WrapperPlayServerDestroyEntities(previousEntityId));
            user.sendPacket(new WrapperPlayServerPlayerInfoRemove(List.of(previousProfileId)));
            sendPlayerInfo(newProfileId, replacementProfileName);
            sendEntitySpawn(newEntityId, newProfileId, eyePose, movementSpeed);
            sendCamera(newEntityId);
        } catch (RuntimeException exception) {
            // The connection is most likely gone; destroy() still cleans up the new ids.
            logSendFailure("re-create the fake camera player for a hard cut", exception);
            return;
        }
        // The new entity has received neither a position nor a head-look packet and has no
        // interpolation state on the client, so the per-entity sync cadence starts over.
        entitySync.reset();
        // The client builds the replacement entity without the old one's effects, so
        // blindness/darkness/night-vision fog would otherwise be lost on every cut.
        for (Map.Entry<PotionType, EffectSpec> entry : cameraEffects.entrySet()) {
            try {
                sendEffect(newEntityId, entry.getKey(), entry.getValue());
            } catch (RuntimeException exception) {
                logSendFailure("restore camera effect " + entry.getValue().key(), exception);
            }
        }
    }

    /**
     * Sends the player-info entry the client needs to construct a RemotePlayer for the
     * client-only camera entity.
     */
    private void sendPlayerInfo(java.util.UUID id, String name) {
        UserProfile profile = new UserProfile(id, name);
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
    }

    /**
     * Spawns the client-only PLAYER entity {@code id} for {@code eyePose}. The spectator
     * camera sits {@link #PLAYER_EYE_HEIGHT} above a player entity, so the eye pose is
     * converted to feet here. Metadata and the movement-speed attribute follow the spawn:
     * the invisible flag keeps the entity harmless if a client decides to render it anyway,
     * and the attribute carries the FOV (slowness/speed are not sent as effects, since the
     * client does not apply effect modifiers to a client-only entity).
     */
    private void sendEntitySpawn(int id, java.util.UUID profile, Pose eyePose, double speed) {
        double feetY = eyePose.y() - PLAYER_EYE_HEIGHT;
        user.sendPacket(new WrapperPlayServerSpawnEntity(
                id,
                profile,
                EntityTypes.PLAYER,
                new Location(eyePose.x(), feetY, eyePose.z(), eyePose.yaw(), eyePose.pitch()),
                eyePose.yaw(),
                0,
                Vector3d.zero()));
        // Spectator players can still see invisible entities, but a camera entity is not
        // rendered when it is the camera. Marking it invisible keeps it harmless if a client
        // version decides to render the camera entity anyway.
        user.sendPacket(new WrapperPlayServerEntityMetadata(id, List.of(
                new EntityData<>(0, EntityDataTypes.BYTE, INVISIBLE_FLAG))));
        // A fresh RemotePlayer starts with the vanilla default walking speed; the FOV must
        // not depend on setMovementSpeed having observed a change.
        sendMovementSpeed(id, speed);
    }

    private void sendCamera(int id) {
        user.sendPacket(new WrapperPlayServerCamera(id));
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
        sendMovementSpeed(entityId, clamped);
    }

    /** Sends the FOV-driving movement-speed attribute to the client-only entity {@code id}. */
    private void sendMovementSpeed(int id, double speed) {
        try {
            user.sendPacket(new WrapperPlayServerUpdateAttributes(id, List.of(
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
        // Remember the effect so a hard cut can replay it on the replacement entity.
        cameraEffects.put(potion, effect);
        try {
            sendEffect(entityId, potion, effect);
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
        cameraEffects.remove(potion);
        try {
            user.sendPacket(new WrapperPlayServerRemoveEntityEffect(entityId, potion));
        } catch (RuntimeException exception) {
            logSendFailure("remove camera effect " + type.getKey(), exception);
        }
    }

    /** Sends one camera-visible effect to the client-only entity {@code id}. */
    private void sendEffect(int id, PotionType potion, EffectSpec effect) {
        // EffectSpec duration 0 means "until removed or the track ends", which the
        // entity-effect packet spells as -1 (infinite).
        int duration = effect.durationTicks() == 0 ? -1 : effect.durationTicks();
        user.sendPacket(new WrapperPlayServerEntityEffect(
                id, potion, effect.amplifier(), duration, (byte) 0));
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

    /**
     * A fresh name for a client-only camera entity: it only has to look like a player name in
     * the tab list and stay distinct between the initial spawn and every hard cut.
     */
    private static String newProfileName() {
        return "CamRail" + String.format(java.util.Locale.ROOT, "%06X",
                ThreadLocalRandom.current().nextInt(0x1000000));
    }
}
