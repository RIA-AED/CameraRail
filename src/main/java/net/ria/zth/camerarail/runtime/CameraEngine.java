package net.ria.zth.camerarail.runtime;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.ria.zth.camerarail.api.CameraService;
import net.ria.zth.camerarail.api.CameraTrackEndEvent;
import net.ria.zth.camerarail.api.CameraTrackStartEvent;
import net.ria.zth.camerarail.api.CameraWaitEvent;
import net.ria.zth.camerarail.api.EndReason;
import net.ria.zth.camerarail.api.PlaybackInfo;
import net.ria.zth.camerarail.config.RailConfig;
import net.ria.zth.camerarail.config.TrackRepository;
import net.ria.zth.camerarail.message.Messages;
import net.ria.zth.camerarail.model.CameraEffects;
import net.ria.zth.camerarail.model.CameraStep;
import net.ria.zth.camerarail.model.CameraTrack;
import net.ria.zth.camerarail.model.CutStep;
import net.ria.zth.camerarail.model.EffectSpec;
import net.ria.zth.camerarail.model.EffectStep;
import net.ria.zth.camerarail.model.HoldStep;
import net.ria.zth.camerarail.model.MoveStep;
import net.ria.zth.camerarail.model.PathStep;
import net.ria.zth.camerarail.model.Pose;
import net.ria.zth.camerarail.model.Step;
import net.ria.zth.camerarail.model.SwayPreset;
import net.ria.zth.camerarail.model.SwaySettings;
import net.ria.zth.camerarail.model.TitleStep;
import net.ria.zth.camerarail.model.WaitStep;
import net.ria.zth.camerarail.storage.EffectData;
import net.ria.zth.camerarail.storage.RecoveryData;
import net.ria.zth.camerarail.storage.RecoveryStore;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The runtime. A session is driven by the camera ArmorStand's entity scheduler, which
 * follows the entity across Folia regions. All player mutations are marshalled to the
 * player's entity scheduler.
 */
public final class CameraEngine implements CameraService {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final long LOCK_HINT_INTERVAL_TICKS = 80L;
    private static final boolean FOLIA = detectFolia();
    /**
     * Ticks to wait after the armor-stand anchor reached the cut pose before re-attaching
     * the client camera. The client interpolates the anchor over roughly three ticks; the
     * view already shows the cut pose from the player's body during this window.
     */
    private static final int CUT_SWITCH_SETTLE_TICKS = 4;
    /**
     * How often a running session re-checks the player's locator-bar modifier. Third-party
     * plugins may drop modifiers, and {@code /zc reload} may flip
     * {@code session.hide-locator-bar} while a shot is running; a re-check every second
     * repairs both without writing the attribute every tick.
     */
    private static final int LOCATOR_BAR_SYNC_TICKS = 20;

    private final Plugin plugin;
    private final TrackRepository tracks;
    private final RecoveryStore recovery;
    private final Supplier<RailConfig> config;
    private final Supplier<Messages> messages;
    private final IntSupplier reloadAction;
    private final CameraOverlay overlay;
    private final LocatorBarHider locatorBarHider;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<String> fallbackEffectWarnedTracks = ConcurrentHashMap.newKeySet();

    public CameraEngine(
            Plugin plugin,
            TrackRepository tracks,
            RecoveryStore recovery,
            Supplier<RailConfig> config,
            Supplier<Messages> messages,
            IntSupplier reloadAction,
            CameraOverlay overlay
    ) {
        this.plugin = plugin;
        this.tracks = tracks;
        this.recovery = recovery;
        this.config = config;
        this.messages = messages;
        this.reloadAction = reloadAction;
        this.overlay = overlay;
        this.locatorBarHider = new LocatorBarHider(plugin, plugin.getLogger());
    }

    @Override
    public Set<String> trackIds() {
        return java.util.Collections.unmodifiableSet(new TreeSet<>(tracks.ids()));
    }

    @Override
    public boolean isPlaying(Player player) {
        return player != null && sessions.containsKey(player.getUniqueId());
    }

    @Override
    public Optional<PlaybackInfo> playback(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        Session session = sessions.get(player.getUniqueId());
        return session == null ? Optional.empty() : session.info();
    }

    @Override
    public Optional<String> waitingSignal(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return Optional.empty();
        }
        String signal = session.waitingSignal;
        return signal == null ? Optional.empty() : Optional.of(signal);
    }

    @Override
    public boolean play(Player player, String trackId) {
        return playInternal(player, trackId, false, 0, Integer.MAX_VALUE);
    }

    /**
     * Editor preview. The player is returned to the pre-preview location on both natural
     * finish and abort.
     */
    public boolean playPreview(Player player, String trackId) {
        return playInternal(player, trackId, true, 0, Integer.MAX_VALUE);
    }

    /**
     * Editor preview starting at {@code startIndex}. The camera spawns at the pose the
     * preceding steps end at; effect and title steps before the index are not replayed.
     */
    public boolean playPreview(Player player, String trackId, int startIndex) {
        return playInternal(player, trackId, true, startIndex, Integer.MAX_VALUE);
    }

    /**
     * Editor preview of the inclusive interval {@code [startIndex, endIndex]}.
     */
    public boolean playPreview(Player player, String trackId, int startIndex, int endIndex) {
        return playInternal(player, trackId, true, startIndex, endIndex);
    }

    private boolean playInternal(Player player, String trackId, boolean preview, int startIndex, int endIndex) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        CameraTrack track = tracks.get(trackId);
        if (track == null) {
            return false;
        }
        World world = Bukkit.getWorld(track.worldName());
        if (world == null) {
            messages.get().send(player, "track.world-missing", "world", track.worldName());
            return false;
        }
        if (!player.getWorld().equals(world)) {
            // The start teleport would raise PlayerChangedWorldEvent and be mistaken for
            // an external world change before the session even attaches.
            messages.get().send(player, "editor.world-mismatch", "world", track.worldName());
            return false;
        }
        Session session = new Session(player, track, world, preview, startIndex, endIndex);
        Session existing = sessions.putIfAbsent(player.getUniqueId(), session);
        if (existing != null) {
            return false;
        }
        warnFallbackEffects(player, track);
        boolean scheduled = player.getScheduler().execute(plugin,
                () -> begin(session),
                () -> abandon(session),
                0L);
        if (!scheduled) {
            sessions.remove(player.getUniqueId(), session);
            return false;
        }
        return true;
    }

    /**
     * Whether the PacketEvents fake-player camera is currently usable. The editor uses this
     * to avoid promising FOV/blindness effects in fallback mode.
     */
    public boolean fakeCameraAvailable() {
        return overlay != null && overlay.available();
    }

    private void warnFallbackEffects(Player player, CameraTrack track) {
        if (fakeCameraAvailable() || !CameraEffects.usesCameraDependentEffects(track)) {
            return;
        }
        messages.get().send(player, "camera.fake-camera-unavailable");
        if (fallbackEffectWarnedTracks.add(track.id())) {
            plugin.getLogger().warning("Track " + track.id() + " uses camera-dependent effects but"
                    + " PacketEvents is unavailable; using the armor-stand fallback (FOV, blindness"
                    + " and slowness/speed effects will not render).");
        }
    }

    @Override
    public boolean stop(Player player) {
        if (player == null) {
            return false;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.ending.get()) {
            return false;
        }
        requestEnd(session, EndReason.STOPPED, RestoreMode.ORIGINAL, true, true);
        return true;
    }

    @Override
    public boolean advance(Player player, String signal) {
        if (player == null || signal == null || signal.isBlank()) {
            return false;
        }
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.queueAdvance(signal);
    }

    @Override
    public boolean advance(Player player) {
        if (player == null) {
            return false;
        }
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.queueAdvance(null);
    }

    @Override
    public int reload() {
        return reloadAction.getAsInt();
    }

    // ------------------------------------------------------------------ locator bar

    /**
     * Applies the configured locator-bar state to one player. Called only from the player's
     * scheduler/region thread.
     */
    private void syncLocatorBar(Player player) {
        locatorBarHider.setHidden(player, config.get().hideLocatorBar());
    }

    /**
     * Removes this plugin's locator-bar modifier. Never reads, snapshots or restores the
     * attribute base value and never touches other plugins' modifiers: if third-party code
     * changed {@code waypoint_receive_range} while the shot was running, its change stays in
     * effect after we remove ours. Safe to call on every end/recovery path, when nothing was
     * hidden, or when the locator-bar attribute is unavailable.
     */
    private void restoreLocatorBar(Player player) {
        locatorBarHider.setHidden(player, false);
    }

    /**
     * Re-runs the locator-bar check on the player's scheduler. The camera tick task runs on
     * the camera entity's scheduler, so this hops back to the player; a retired player
     * scheduler simply skips the check (the session ends on the next tick).
     */
    private void scheduleLocatorBarSync(Session session) {
        Player player = session.player;
        boolean scheduled = player.getScheduler().execute(plugin, () -> {
            if (isCurrent(session) && !session.ending.get() && !session.closed.get() && player.isOnline()) {
                syncLocatorBar(player);
            }
        }, () -> {
        }, 0L);
        if (!scheduled && config.get().debug()) {
            plugin.getLogger().fine("Could not re-check the locator bar for " + player.getName());
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private void begin(Session session) {
        if (!isCurrent(session) || !session.player.isOnline()) {
            abandon(session);
            return;
        }
        Player player = session.player;
        try {
            session.original = PlayerStateSnapshot.capture(player);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not snapshot camera session for " + player.getName(), exception);
            removeSession(session);
            return;
        }

        CameraTrackStartEvent event = new CameraTrackStartEvent(player, session.track.id(),
                session.track.displayName(), session.preview);
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "CameraTrackStartEvent listener failed", exception);
            removeSession(session);
            return;
        }
        if (event.isCancelled()) {
            messages.get().send(player, "track.start-cancelled");
            removeSession(session);
            return;
        }
        if (session.ending.get()) {
            // A listener may have called stop() from the start event.
            abandon(session);
            return;
        }
        session.startEventFired = true;

        if (!recovery.enabled()) {
            continueStart(session);
            return;
        }
        recovery.write(player.getUniqueId(), session.track.id(), session.startedAtMillis, session.original)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Recovery file could not be written; starting anyway",
                                error);
                    } else {
                        session.recoveryWritten = true;
                    }
                    if (!isCurrent(session) || !player.isOnline()) {
                        abandon(session);
                        return;
                    }
                    player.getScheduler().execute(plugin, () -> continueStart(session),
                            () -> abandon(session), 0L);
                });
    }

    private void continueStart(Session session) {
        if (!isCurrent(session) || session.ending.get() || !session.player.isOnline()) {
            abandon(session);
            return;
        }
        // Loading the start chunk can take a while. Apply the covering effects only once
        // the camera is actually in place, so the player does not stare at a blind screen
        // while the server generates terrain at the destination.
        // If the region task or the client never attaches, do not leave the player in a
        // strange state forever.
        session.player.getScheduler().runDelayed(plugin, task -> {
            if (isCurrent(session) && !session.ending.get() && !session.attached) {
                plugin.getLogger().warning("Camera attach timed out for " + session.player.getName());
                requestEnd(session, EndReason.ERROR, RestoreMode.ORIGINAL, true, true);
            }
        }, () -> {
        }, 300L);
        Location start = session.initialPose.toLocation(session.world);
        Bukkit.getRegionScheduler().execute(plugin, start, () -> spawnCamera(session));
    }

    private void spawnCamera(Session session) {
        if (!isCurrent(session) || session.ending.get() || !session.player.isOnline()) {
            abandon(session);
            return;
        }
        Location start = session.initialPose.toLocation(session.world);
        // The start can be far outside the player's loaded area. Without a chunk ticket
        // the freshly spawned marker exists in an unloaded chunk and every teleport
        // (even teleportAsync) fails as "cancelled".
        ensureCameraChunk(session, start);
        ArmorStand camera;
        try {
            camera = session.world.spawn(start, ArmorStand.class, CreatureSpawnEvent.SpawnReason.CUSTOM, false, stand -> {
                // A marker stand is camera-targeted at its origin on the client while Bukkit
                // still reports a non-zero eye height; that made the whole shot one eye-height
                // too low. A normal invisible stand uses eye height on both sides.
                stand.setMarker(false);
                stand.setInvisible(true);
                stand.setSmall(true);
                stand.setVisible(false);
                stand.setBasePlate(false);
                stand.setArms(false);
                stand.setGravity(false);
                stand.setInvulnerable(true);
                stand.setSilent(true);
                stand.setCollidable(false);
                stand.setPersistent(false);
                // Hidden from every player by default. The fake-player path never shows it
                // to the spectator: spectators can see invisible entities, so an armor stand
                // sent to the client would appear as a translucent model at the camera. Only
                // the armor-stand fallback calls Player#showEntity; there the spawn must be on
                // the wire before Set Camera, otherwise the client drops the camera packet.
                // setVisible(false) already hides the stand's model.
                stand.setVisibleByDefault(false);
                stand.setCustomNameVisible(false);
                stand.setCanPickupItems(false);
                stand.setRotation(session.initialPose.yaw(), session.initialPose.pitch());
                stand.addScoreboardTag("zth_camera_rail");
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not spawn camera entity in " + session.world.getName(), exception);
            requestEnd(session, EndReason.ERROR, RestoreMode.ORIGINAL, true, true);
            return;
        }
        if (camera == null) {
            plugin.getLogger().warning("Camera entity spawn was refused in " + session.world.getName());
            requestEnd(session, EndReason.ERROR, RestoreMode.ORIGINAL, true, true);
            return;
        }
        session.camera = camera;
        // Full-precision rotation; see PreciseCameraSync for why the default byte-quantised
        // entity tracking is visible during slow pans.
        PreciseCameraSync.enable(camera, plugin.getLogger());
        // Marker armor stands are targeted by the client at the entity position, not at
        // ArmorStand#getEyeHeight(); a normal invisible stand uses its eye height on both
        // sides, so spawn it at pose - eye height and keep that offset when moving.
        double eyeOffset = camera.getEyeHeight();
        session.cameraEyeOffset = Double.isFinite(eyeOffset) ? eyeOffset : 0.0;
        Location cameraPosition = start.clone().subtract(0.0, session.cameraEyeOffset, 0.0);
        cameraPosition.setYaw(session.initialPose.yaw());
        cameraPosition.setPitch(session.initialPose.pitch());
        // teleportAsync is used on Paper as well as Folia: the start chunk (and, later,
        // chunks along the route) is often outside the player's loaded area, and a
        // synchronous Entity#teleport silently fails with "cancelled" when the target
        // chunk is not loaded. teleportAsync loads the target chunk first.
        try {
            camera.teleportAsync(cameraPosition, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((success, error) -> camera.getScheduler().execute(plugin, () -> {
                        if (error != null || !Boolean.TRUE.equals(success)) {
                            plugin.getLogger().log(Level.WARNING, "Could not position camera entity",
                                    error);
                            requestEnd(session, EndReason.CAMERA_LOST, RestoreMode.ORIGINAL, true, true);
                            return;
                        }
                        session.player.getScheduler().execute(plugin, () -> teleportToStart(session),
                                () -> abandon(session), 0L);
                    }, () -> abandon(session), 0L));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not initialise camera entity", exception);
            requestEnd(session, EndReason.CAMERA_LOST, RestoreMode.ORIGINAL, true, true);
        }
    }

    private void teleportToStart(Session session) {
        if (!isCurrent(session) || session.ending.get() || !session.player.isOnline() || session.camera == null) {
            abandon(session);
            return;
        }
        Player player = session.player;
        ArmorStand camera = session.camera;
        Location target = camera.getLocation().clone();
        target.setYaw(session.initialPose.yaw());
        target.setPitch(session.initialPose.pitch());
        applyEntryEffects(session);
        beginInternalTeleport(session, 1500L);
        try {
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((success, error) -> {
                        endInternalTeleport(session);
                        player.getScheduler().execute(plugin, () -> {
                            if (error != null || !Boolean.TRUE.equals(success)) {
                                plugin.getLogger().log(Level.WARNING, "Start teleport failed for " + player.getName(),
                                        error);
                                requestEnd(session, EndReason.ERROR, RestoreMode.ORIGINAL, true, true);
                                return;
                            }
                            attach(session);
                        }, () -> abandon(session), 0L);
                    });
        } catch (RuntimeException exception) {
            endInternalTeleport(session);
            plugin.getLogger().log(Level.WARNING, "Start teleport threw for " + player.getName(), exception);
            requestEnd(session, EndReason.ERROR, RestoreMode.ORIGINAL, true, true);
        }
    }

    private void attach(Session session) {
        if (!isCurrent(session) || session.ending.get() || !session.player.isOnline()) {
            abandon(session);
            return;
        }
        Player player = session.player;
        ArmorStand camera = session.camera;
        if (camera == null || !camera.isValid()) {
            requestEnd(session, EndReason.CAMERA_LOST, RestoreMode.ORIGINAL, true, true);
            return;
        }
        if (!player.getWorld().equals(session.world)) {
            requestEnd(session, EndReason.WORLD_CHANGE, RestoreMode.NONE, true, true);
            return;
        }
        try {
            if (player.getGameMode() != GameMode.SPECTATOR) {
                session.internalModeChange = true;
                try {
                    player.setGameMode(GameMode.SPECTATOR);
                } finally {
                    session.internalModeChange = false;
                }
            }
            // The player's own body is snapped to the camera by the server every tick. Keep
            // it able to fly so that it does not drop out of the world if the client ever
            // takes control of it again (a camera packet the client did not honour).
            player.setAllowFlight(true);
            player.setFlying(true);
            CameraOverlay.CameraView fakeCamera = session.fakeCamera;
            if (fakeCamera != null && !fakeCamera.active()) {
                fakeCamera.destroy();
                session.fakeCamera = null;
                fakeCamera = null;
            }
            if (fakeCamera == null && overlay != null && overlay.available()) {
                try {
                    fakeCamera = overlay.create(player, camera, session.initialPose);
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING,
                            "Could not create the client-side camera player for " + player.getName()
                                    + "; using the armor-stand camera", exception);
                    fakeCamera = null;
                }
                if (fakeCamera == null) {
                    if (!session.fakeCameraFailureNotified && overlay != null && overlay.available()) {
                        session.fakeCameraFailureNotified = true;
                        messages.get().send(player, "camera.fake-camera-failed");
                    }
                    if (config.get().debug()) {
                        plugin.getLogger().info("Client-side camera unavailable for " + player.getName()
                                + "; continuing with the armor-stand camera");
                    }
                } else {
                    session.fakeCamera = fakeCamera;
                    if (config.get().debug()) {
                        plugin.getLogger().info("Fake camera created for " + player.getName()
                                + " entityId=" + fakeCamera.entityId());
                    }
                    // Entry effects are applied before the camera entity exists. Replay them
                    // once the fake player has been spawned so blindness/FOV cover the cut.
                    // A slow start can outlive a short entry effect; do not resurrect it on
                    // the client-only entity after the player copy has already expired.
                    for (EffectSpec effect : List.copyOf(session.cameraEffects.values())) {
                        Long expiry = session.cameraEffectExpiry.get(effect.type());
                        if (expiry == null || session.totalTicks < expiry) {
                            fakeCamera.applyEffect(effect);
                        }
                    }
                    updateFakeMovementSpeed(session);
                }
            }
            if (fakeCamera == null) {
                // Fallback camera: the client resolves Set Camera against its own entity
                // list, so the stand must be on the wire first. showEntity pushes it to this
                // player immediately; setVisibleByDefault(false) keeps it from everybody else.
                //
                // Do NOT show it when the fake-player camera is active. Spectators can see
                // invisible entities (Entity#isInvisibleTo returns false for spectators), so
                // a normally invisible armor stand would still appear semi-transparent at
                // the camera position and block the shot.
                player.showEntity(plugin, camera);
            }
            // The server-side spectator target stays the armor stand. With the fake camera,
            // PacketEvents rewrites the Set Camera packet to the fake player; the client
            // never needs the armor stand entity at all.
            player.setSpectatorTarget(camera);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not attach spectator camera for " + player.getName(), exception);
            requestEnd(session, EndReason.ERROR, RestoreMode.ORIGINAL, true, true);
            return;
        }
        if (player.getSpectatorTarget() != camera) {
            session.attachAttempts++;
            if (session.attachAttempts < 5) {
                player.getScheduler().execute(plugin, () -> attach(session), () -> abandon(session), 1L);
            } else {
                requestEnd(session, EndReason.ERROR, RestoreMode.ORIGINAL, true, true);
            }
            return;
        }

        session.attached = true;
        session.clientMovedWhileAttached = false;
        // Hide the vanilla locator bar only once the camera actually owns the player: from
        // here until release the client sees a cutscene HUD, and the player is already a
        // spectator (a spectator's waypoint is ignored by other players anyway).
        if (!session.ending.get()) {
            syncLocatorBar(player);
        }
        // Repairing a camera packet the client dropped is not possible through
        // getSpectatorTarget(): the field already points at the camera, and
        // ServerPlayer#setCamera only sends a packet when the target *changes*. Re-send it
        // explicitly a couple of times while the client settles (it is idempotent and
        // invisible when the first packet was honoured), then check whether the client
        // really stopped moving its own player.
        scheduleCameraResend(session, 2L);
        scheduleCameraResend(session, 10L);
        scheduleCameraCheck(session, 45L);
        session.teleportGraceUntilMillis = Math.max(session.teleportGraceUntilMillis,
                System.currentTimeMillis() + 500L);
        session.stepIndex = session.startIndex;
        session.stepTicks = 0;
        session.totalTicks = 0;
        session.currentPose = session.initialPose;
        session.stepFrom = session.initialPose;
        session.pathCurve = null;
        session.pathCurveStep = -1;
        session.activeSway = session.initialSway.name();
        session.activeStepDescription = "准备中";
        session.tickTask = camera.getScheduler().runAtFixedRate(plugin,
                task -> tick(session),
                () -> onCameraRetired(session),
                1L,
                1L);
        if (session.tickTask == null) {
            plugin.getLogger().warning("Camera tick task could not be scheduled for " + player.getName());
            requestEnd(session, EndReason.CAMERA_LOST, RestoreMode.ORIGINAL, true, true);
            return;
        }
        if (config.get().debug()) {
            plugin.getLogger().info("Camera started: player=" + player.getName() + " track=" + session.track.id()
                    + " world=" + session.world.getName() + " preview=" + session.preview
                    + " precise-sync=" + PreciseCameraSync.isAvailable());
        }
    }

    /**
     * Re-sends the camera packet after {@code delayTicks}. The client drops a Set Camera
     * packet whose entity id it cannot resolve yet, and because the server-side target never
     * changes, {@code Player#getSpectatorTarget()} cannot tell a locked client from a client
     * that ignored the packet - so nothing would ever send it again.
     */
    private void scheduleCameraResend(Session session, long delayTicks) {
        session.player.getScheduler().runDelayed(plugin, task -> {
            if (isCurrent(session) && !session.ending.get() && session.player.isOnline()) {
                resendCameraPacket(session);
            }
        }, () -> {
        }, delayTicks);
    }

    /**
     * Toggling through null is the only Bukkit-API way to force {@code ServerPlayer#setCamera}
     * to send a fresh Set Camera packet; re-selecting the current target is a no-op. Both
     * packets are handled in the same client tick, so the view does not flicker.
     */
    private void resendCameraPacket(Session session) {
        Player player = session.player;
        ArmorStand camera = session.camera;
        if (camera == null || !camera.isValid() || player.getGameMode() != GameMode.SPECTATOR
                || !player.getWorld().equals(camera.getWorld())) {
            return;
        }
        session.internalSpectatorChange = true;
        beginInternalTeleport(session, 300L);
        try {
            player.setSpectatorTarget(null);
            player.setSpectatorTarget(camera);
        } catch (RuntimeException exception) {
            // A listener vetoed one of the calls; a later resend or tick retries.
            if (config.get().debug()) {
                plugin.getLogger().log(Level.FINE, "Camera re-send failed for " + player.getName(), exception);
            }
        } finally {
            session.internalSpectatorChange = false;
            endInternalTeleport(session);
        }
    }

    /**
     * Whether the client is really looking through the camera cannot be read from the server:
     * the camera is client-side state. What is observable is the client's own movement. While
     * the client is camera-locked {@code LocalPlayer#sendPosition} sends nothing at all, so a
     * movement packet after the attach means the client still drives its own body - the camera
     * packet was lost, and from here on the server snaps the player to the stand every tick.
     */
    private void scheduleCameraCheck(Session session, long delayTicks) {
        session.player.getScheduler().runDelayed(plugin, task -> {
            if (!isCurrent(session) || session.ending.get() || session.closed.get()
                    || !session.clientMovedWhileAttached || session.clientMoveWarned) {
                return;
            }
            session.clientMoveWarned = true;
            plugin.getLogger().warning("Spectator camera did not lock for " + session.player.getName()
                    + " (track=" + session.track.id() + "): the client kept sending movement packets."
                    + " Check its version and any protocol translator in front of it.");
            resendCameraPacket(session);
        }, () -> {
        }, delayTicks);
    }

    // ------------------------------------------------------------------ hard cuts

    /**
     * Applies a hard cut on the fake-player camera by re-creating the client-only camera
     * entity at the target pose. A camera-target switch is instant on the client, while the
     * entity was only ever tracked with interpolated positions and a three-tick head-yaw
     * lerp, so teleporting it would show the cut as a tween.
     *
     * @return {@code true} when the fake camera carried the cut; {@code false} when the
     *         armor-stand switch in {@link #beginCutSwitch} has to do it
     */
    private boolean hardCutFakeCamera(Session session, Pose target) {
        CameraOverlay.CameraView fakeCamera = session.fakeCamera;
        if (fakeCamera == null || !fakeCamera.active()) {
            return false;
        }
        try {
            fakeCamera.hardCut(target);
            if (config.get().debug()) {
                plugin.getLogger().info("hard cut: re-created the fake camera entity for "
                        + session.player.getName() + " entityId=" + fakeCamera.entityId()
                        + " at tick " + session.totalTicks);
            }
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not hard-cut the client camera for "
                    + session.player.getName() + "; continuing with the armor-stand camera",
                    exception);
            destroyFakeCamera(session);
            session.fakeCameraFailureNotified = true;
            return false;
        }
    }

    /**
     * Armor-stand fallback for a hard cut. The client camera is the stand, and the stand is
     * moved with the normal entity position sync, which the client interpolates over three
     * ticks - a teleport would therefore glide. Instead the plugin detaches the camera,
     * snaps the player's body onto the cut pose (the client camera is the body while it is
     * detached, so the view already shows the new shot immediately), moves the stand, and
     * only re-attaches once the client had time to interpolate the stand to its new pose.
     */
    private void beginCutSwitch(Session session, Pose target) {
        session.cutSwitch = -1;
        if (config.get().debug()) {
            plugin.getLogger().info("hard cut: detaching the armor-stand camera for "
                    + session.player.getName() + " at tick " + session.totalTicks);
        }
        Player player = session.player;
        boolean scheduled = player.getScheduler().execute(plugin, () -> detachForCut(session, target),
                () -> session.cutSwitch = 0, 0L);
        if (!scheduled) {
            session.cutSwitch = 0;
        }
    }

    /** Player scheduler: drop the camera and put the body (and therefore the view) on the cut pose. */
    private void detachForCut(Session session, Pose target) {
        if (!isCurrent(session) || session.ending.get() || session.closed.get() || !session.player.isOnline()) {
            session.cutSwitch = 0;
            return;
        }
        Player player = session.player;
        session.internalSpectatorChange = true;
        try {
            player.setSpectatorTarget(null);
        } catch (RuntimeException exception) {
            // The client may already have dropped the camera; the body snap below still
            // shows the cut pose.
            if (config.get().debug()) {
                plugin.getLogger().log(Level.FINE, "Could not detach the camera for a hard cut", exception);
            }
        } finally {
            session.internalSpectatorChange = false;
        }
        Location feet = ExitPlacement.feet(session.world, target, player.getEyeHeight());
        feet.setYaw(target.yaw());
        feet.setPitch(target.pitch());
        beginInternalTeleport(session, 1000L);
        try {
            player.teleportAsync(feet, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) -> {
                endInternalTeleport(session);
                if (error != null || !Boolean.TRUE.equals(success)) {
                    session.cutSwitch = 0;
                    plugin.getLogger().log(Level.WARNING,
                            "Could not place the player for a hard cut: " + player.getName(), error);
                    requestEnd(session, EndReason.ERROR, RestoreMode.ORIGINAL, true, true);
                    return;
                }
                ArmorStand camera = session.camera;
                if (camera == null) {
                    session.cutSwitch = 0;
                    return;
                }
                camera.getScheduler().execute(plugin, () -> moveCameraForCut(session, camera, target),
                        () -> session.cutSwitch = 0, 0L);
            });
        } catch (RuntimeException exception) {
            endInternalTeleport(session);
            session.cutSwitch = 0;
            plugin.getLogger().log(Level.WARNING, "Hard-cut body teleport threw for " + player.getName(), exception);
            requestEnd(session, EndReason.ERROR, RestoreMode.ORIGINAL, true, true);
        }
    }

    /** Camera scheduler: move the anchor, then start the settle countdown before re-attaching. */
    private void moveCameraForCut(Session session, ArmorStand camera, Pose target) {
        if (!isCurrent(session) || session.ending.get() || session.closed.get()) {
            session.cutSwitch = 0;
            return;
        }
        Location location = new Location(session.world,
                target.x(), target.y() - session.cameraEyeOffset, target.z(),
                target.yaw(), target.pitch());
        ensureCameraChunk(session, location);
        try {
            camera.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((success, error) -> camera.getScheduler().execute(plugin, () -> {
                        if (!isCurrent(session) || session.ending.get() || session.closed.get()) {
                            session.cutSwitch = 0;
                            return;
                        }
                        if (error != null || !Boolean.TRUE.equals(success)) {
                            plugin.getLogger().log(Level.WARNING,
                                    "Could not move the camera for a hard cut: " + session.player.getName(),
                                    error);
                            session.cutSwitch = 0;
                            requestEnd(session, EndReason.CAMERA_LOST, RestoreMode.ORIGINAL, true, true);
                            return;
                        }
                        session.cutSwitch = CUT_SWITCH_SETTLE_TICKS;
                    }, () -> session.cutSwitch = 0, 0L));
        } catch (RuntimeException exception) {
            session.cutSwitch = 0;
            plugin.getLogger().log(Level.WARNING,
                    "Camera hard-cut teleport threw for " + session.player.getName(), exception);
            requestEnd(session, EndReason.CAMERA_LOST, RestoreMode.ORIGINAL, true, true);
        }
    }

    /** Camera scheduler: count the settle ticks down, then re-attach on the player scheduler. */
    private void advanceCutSwitch(Session session) {
        int phase = session.cutSwitch;
        if (phase < 0) {
            // A detach/move/re-attach task is in flight; its callback clears the state.
            return;
        }
        if (phase > 1) {
            session.cutSwitch = phase - 1;
            return;
        }
        session.cutSwitch = -1;
        boolean scheduled = session.player.getScheduler().execute(plugin, () -> finishCutSwitch(session),
                () -> session.cutSwitch = 0, 0L);
        if (!scheduled) {
            session.cutSwitch = 0;
        }
    }

    /** Player scheduler: lock the camera again once the client has caught up with the anchor. */
    private void finishCutSwitch(Session session) {
        session.cutSwitch = 0;
        if (!isCurrent(session) || session.ending.get() || session.closed.get() || !session.player.isOnline()) {
            return;
        }
        Player player = session.player;
        ArmorStand camera = session.camera;
        if (camera == null || !camera.isValid()) {
            requestEnd(session, EndReason.CAMERA_LOST, RestoreMode.ORIGINAL, true, true);
            return;
        }
        if (!player.getWorld().equals(session.world)) {
            requestEnd(session, EndReason.WORLD_CHANGE, RestoreMode.NONE, true, true);
            return;
        }
        if (session.fakeCamera == null) {
            // Fallback camera: the client resolves Set Camera against its own entity list.
            player.showEntity(plugin, camera);
        }
        session.internalSpectatorChange = true;
        try {
            player.setSpectatorTarget(camera);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not re-attach the camera after a hard cut for " + player.getName(), exception);
            requestEnd(session, EndReason.ERROR, RestoreMode.ORIGINAL, true, true);
            return;
        } finally {
            session.internalSpectatorChange = false;
        }
        if (player.getSpectatorTarget() != camera) {
            scheduleCameraResend(session, 1L);
        }
        session.clientMovedWhileAttached = false;
        if (config.get().debug()) {
            plugin.getLogger().info("hard cut: re-attached the armor-stand camera for "
                    + player.getName() + " at tick " + session.totalTicks);
        }
        // Same idempotent re-send as a fresh attach: the client drops a Set Camera packet it
        // cannot resolve yet, and the server-side target cannot reveal that.
        scheduleCameraResend(session, 2L);
        session.teleportGraceUntilMillis = Math.max(session.teleportGraceUntilMillis,
                System.currentTimeMillis() + 500L);
    }

    private void applyEntryEffects(Session session) {
        if (session.track.entryEffects().isEmpty() || !session.appliesEntryEffects()) {
            return;
        }
        // Called on the player's scheduler, before the first teleport. The player copy covers
        // nausea, the lightmap and the HUD. The fake-player camera is created later, after
        // the start teleport; attach() replays this map onto it so blindness fog and FOV
        // cover the cut as well. See docs/spectator-effects.md.
        for (EffectSpec effect : session.track.entryEffects()) {
            applyEffectNow(session, effect);
        }
    }

    // ------------------------------------------------------------------ ticking

    private void tick(Session session) {
        if (!isCurrent(session) || session.ending.get() || session.closed.get()) {
            return;
        }
        if (!session.player.isOnline()) {
            requestEnd(session, EndReason.QUIT, RestoreMode.NONE, false, false);
            return;
        }
        ArmorStand camera = session.camera;
        if (camera == null || !camera.isValid()) {
            requestEnd(session, EndReason.CAMERA_LOST, RestoreMode.ORIGINAL, true, true);
            return;
        }
        RailConfig currentConfig = config.get();
        session.totalTicks++;
        if (session.totalTicks % LOCATOR_BAR_SYNC_TICKS == 0) {
            scheduleLocatorBarSync(session);
        }
        if (session.totalTicks > currentConfig.maxTrackTicks()) {
            requestEnd(session, EndReason.LIMIT, RestoreMode.ORIGINAL, true, true);
            return;
        }

        // Instant actions (effects/titles) run on the player scheduler. Hold the camera
        // until they complete so that a final effect cannot be lost during cleanup.
        if (session.pendingInstant) {
            updateCamera(session);
            return;
        }

        // A hard cut on the armor-stand camera has to detach the client camera for a few
        // ticks (see beginCutSwitch); step processing pauses until it is re-attached.
        if (session.cutSwitch != 0) {
            updateCamera(session);
            advanceCutSwitch(session);
            return;
        }

        if (session.waitingSignal != null) {
            session.waitTicks++;
            Optional<Session.AdvanceRequest> advance = session.pollAdvance(session.waitingSignal);
            if (advance.isPresent()) {
                session.leaveWait();
                session.stepIndex++;
                session.stepTicks = 0;
                processSteps(session);
                return;
            }
            if (session.waitTimeoutTicks > 0 && session.waitTicks >= session.waitTimeoutTicks) {
                requestEnd(session, EndReason.TIMEOUT, RestoreMode.ORIGINAL, true, true);
                return;
            }
            updateCamera(session);
            return;
        }

        processSteps(session);
    }

    private void processSteps(Session session) {
        int guard = session.track.steps().size() + 4;
        while (isCurrent(session) && !session.ending.get()) {
            if (guard-- <= 0) {
                plugin.getLogger().warning("Camera step processing guard tripped for " + session.player.getName());
                requestEnd(session, EndReason.ERROR, RestoreMode.ORIGINAL, true, true);
                return;
            }
            if (session.endIndex >= 0 && session.stepIndex > session.endIndex) {
                requestEnd(session, EndReason.FINISHED,
                        session.preview ? RestoreMode.ORIGINAL : RestoreMode.EXIT, true, false);
                return;
            }
            if (session.stepIndex >= session.track.steps().size()) {
                requestEnd(session, EndReason.FINISHED,
                        session.preview ? RestoreMode.ORIGINAL : RestoreMode.EXIT, true, false);
                return;
            }
            Step step = session.track.steps().get(session.stepIndex);
            if (step instanceof WaitStep wait) {
                if (session.waitingSignal == null) {
                    session.enterWait(wait);
                    fireWaitEvent(session, wait);
                }
                updateCamera(session);
                return;
            }
            if (step instanceof EffectStep effectStep) {
                session.enterStep(step);
                session.activeStepDescription = step.describe();
                queueInstant(session, () -> applyEffectNow(session, effectStep.effect()));
                return;
            }
            if (step instanceof TitleStep titleStep) {
                session.enterStep(step);
                session.activeStepDescription = step.describe();
                queueInstant(session, () -> sendTitleNow(session, titleStep));
                return;
            }
            if (step instanceof CutStep cutStep) {
                Pose cutTarget = cutStep.to();
                if (session.cutStepApplied != session.stepIndex) {
                    session.enterStep(step);
                    session.currentPose = cutTarget;
                    session.cutStepApplied = session.stepIndex;
                    if (!hardCutFakeCamera(session, cutTarget)) {
                        // The armor-stand camera is what the client renders, so the jump
                        // has to go through a short detach: the client cannot interpolate a
                        // camera target it no longer looks through.
                        beginCutSwitch(session, cutTarget);
                        return;
                    }
                }
                // The jump itself has no transition. A positive duration then holds the new
                // pose (sway included) for that many ticks; duration 0 stays a pure cut that
                // occupies no ticks, so the following step starts on this very tick.
                if (cutStep.durationTicks() == 0) {
                    session.stepIndex++;
                    session.stepTicks = 0;
                    continue;
                }
                session.stepTicks++;
                updateCamera(session);
                if (session.stepTicks >= cutStep.durationTicks()) {
                    session.stepIndex++;
                    session.stepTicks = 0;
                }
                return;
            }
            if (step instanceof CameraStep) {
                if (session.stepTicks == 0) {
                    session.enterStep(step);
                }
                session.stepTicks++;
                boolean finished = tickCameraStep(session, step);
                updateCamera(session);
                if (finished) {
                    session.stepIndex++;
                    session.stepTicks = 0;
                    session.pathCurve = null;
                }
                return;
            }
            plugin.getLogger().warning("Unknown step implementation " + step.getClass().getName());
            session.stepIndex++;
            session.stepTicks = 0;
        }
    }

    /**
     * @return true when the step has completed on this tick
     */
    private boolean tickCameraStep(Session session, Step step) {
        if (step instanceof MoveStep move) {
            double progress = Math.min(1.0, session.stepTicks / (double) move.durationTicks());
            session.currentPose = session.stepFrom.lerp(move.to(), move.easing().apply(progress));
            if (progress >= 1.0) {
                session.currentPose = move.to();
                return true;
            }
            return false;
        }
        if (step instanceof PathStep path) {
            if (session.pathCurve == null || session.pathCurveStep != session.stepIndex) {
                List<Pose> keyframes = new ArrayList<>(path.points().size() + 1);
                keyframes.add(session.stepFrom);
                keyframes.addAll(path.points());
                session.pathCurve = PathCurve.through(keyframes);
                session.pathCurveStep = session.stepIndex;
            }
            double progress = Math.min(1.0, session.stepTicks / (double) path.durationTicks());
            session.currentPose = session.pathCurve.at(path.easing().apply(progress));
            if (progress >= 1.0) {
                session.currentPose = path.points().getLast();
                return true;
            }
            return false;
        }
        if (step instanceof HoldStep hold) {
            return session.stepTicks >= hold.durationTicks();
        }
        if (step instanceof WaitStep wait) {
            session.enterWait(wait);
            return false;
        }
        return true;
    }

    private void updateCamera(Session session) {
        SwaySettings target = resolveSway(session);
        SwayAnimator.SwayOffset offset = session.swayAnimator.tick(target, session.totalTicks);
        Pose pose = session.currentPose.sway(offset.x(), offset.y(), offset.z(), offset.yaw(), offset.pitch());
        ArmorStand camera = session.camera;
        if (config.get().debug() && camera != null) {
            tracePose(session, pose, target, camera);
        }
        if (camera == null || !camera.isValid()) {
            return;
        }
        // Some server/entity combinations reset the metadata flag while the marker is
        // moved across chunks. Keep it pinned; a falling camera would drag the client
        // view down between async teleports and trigger movement warnings.
        camera.setGravity(false);
        Location location = new Location(session.world,
                pose.x(), pose.y() - session.cameraEyeOffset, pose.z(), pose.yaw(), pose.pitch());
        moveCameraEntity(session, camera, location);
        CameraOverlay.CameraView fakeCamera = session.fakeCamera;
        if (fakeCamera != null && fakeCamera.active()) {
            // The fake player is the client's visual camera; the armor stand above remains
            // the server-side anchor. Both are fed from the same unswayed/swayed pose.
            fakeCamera.sync(pose);
            updateFakeMovementSpeed(session);
        }
        ensureFollowing(session, location);
    }

    /**
     * Debug-only trace of the pose stream itself: once a second, log the largest per-tick
     * step and how many teleports were deferred. A twitch that shows on screen while this
     * line stays flat is not coming from the server-side camera pose; the client (entity
     * interpolation, packet cadence) or the transport is then the place to look.
     *
     * <p>When the precise flag is not verifiably on, the tracker may be packing yaw/pitch into
     * a byte (1/256 turn = 1.40625 degrees). Sub-degree sway then never reaches the client as
     * motion: it stays still until the accumulated angle crosses a step and jumps. The line
     * reports those packed steps, which is exactly what the client would see.</p>
     */
    private void tracePose(Session session, Pose pose, SwaySettings sway, ArmorStand camera) {
        Pose previous = session.tracePose;
        session.tracePose = pose;
        Boolean preciseFlag = PreciseCameraSync.readFlag(camera);
        String precise = !PreciseCameraSync.isAvailable() ? "off"
                : preciseFlag == null ? "on(unverified)"
                : preciseFlag ? "on" : "on-but-reset";
        boolean quantised = !"on".equals(precise);
        float appliedYaw = camera.getLocation().getYaw();
        if (session.traceEntitySeen) {
            double entityStep = Math.abs(Pose.shortestYawDelta(session.traceEntityYaw, appliedYaw));
            session.traceEntityMax = Math.max(session.traceEntityMax, entityStep);
            if (entityStep == 0.0 && previous != null && pose.yaw() != previous.yaw()) {
                session.traceStale++;
            }
        }
        session.traceEntitySeen = true;
        session.traceEntityYaw = appliedYaw;
        if (previous != null) {
            session.traceMaxYaw = Math.max(session.traceMaxYaw,
                    Math.abs(Pose.shortestYawDelta(previous.yaw(), pose.yaw())));
            session.traceMaxPitch = Math.max(session.traceMaxPitch,
                    Math.abs(pose.pitch() - previous.pitch()));
            session.traceMaxPos = Math.max(session.traceMaxPos, pose.distance(previous));
            if (quantised) {
                double step = Math.abs(Pose.shortestYawDelta(
                        (float) packedDegrees(previous.yaw()), (float) packedDegrees(pose.yaw())));
                if (step > 1.0) {
                    session.traceWireSteps++;
                }
                session.traceWireMax = Math.max(session.traceWireMax, step);
            }
        }
        if (++session.traceTicks < 20) {
            return;
        }
        String wire = quantised
                ? String.format(Locale.ROOT,
                " | packed yaw steps>1deg=%d max=%.3f deg", session.traceWireSteps, session.traceWireMax)
                : "";
        plugin.getLogger().info(String.format(Locale.ROOT,
                "camera trace %s tick=%d amp yaw=%.2f deg precise=%s | max/tick yaw=%.3f deg"
                        + " pitch=%.3f deg pos=%.4f blocks | deferred teleports %d/20"
                        + " | entity yaw step max=%.3f deg stale=%d/20%s",
                session.track.id(), session.totalTicks, sway.yaw(), precise,
                session.traceMaxYaw, session.traceMaxPitch, session.traceMaxPos,
                session.traceDeferred, session.traceEntityMax, session.traceStale, wire));
        session.traceTicks = 0;
        session.traceDeferred = 0;
        session.traceWireSteps = 0;
        session.traceWireMax = 0.0;
        session.traceMaxYaw = 0.0;
        session.traceMaxPitch = 0.0;
        session.traceMaxPos = 0.0;
        session.traceEntityMax = 0.0;
        session.traceStale = 0;
    }

    /**
     * Keeps the chunk under the camera loaded for the whole shot. The client only gets
     * chunks around the player, but the camera entity itself also needs a valid chunk;
     * a marker spawned in an unloaded chunk cannot be teleported, even asynchronously.
     */
    private void ensureCameraChunk(Session session, Location location) {
        if (session.ending.get() || session.closed.get()) {
            return;
        }
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        if (!session.cameraChunks.add(key)) {
            return;
        }
        if (config.get().debug()) {
            plugin.getLogger().info("camera chunk " + chunkX + "," + chunkZ + " ticketed at tick "
                    + session.totalTicks + " for " + session.player.getName());
        }
        try {
            session.world.addPluginChunkTicket(chunkX, chunkZ, plugin);
        } catch (RuntimeException exception) {
            session.cameraChunks.remove(key);
            plugin.getLogger().log(Level.WARNING, "Could not keep camera chunk loaded at "
                    + chunkX + "," + chunkZ + " for " + session.player.getName(), exception);
        }
    }

    private void releaseCameraChunks(Session session) {
        for (long key : List.copyOf(session.cameraChunks)) {
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            try {
                session.world.removePluginChunkTicket(chunkX, chunkZ, plugin);
            } catch (RuntimeException exception) {
                if (config.get().debug()) {
                    plugin.getLogger().log(Level.FINE, "Could not release camera chunk ticket at "
                            + chunkX + "," + chunkZ, exception);
                }
            } finally {
                session.cameraChunks.remove(key);
            }
        }
    }

    private void moveCameraEntity(Session session, ArmorStand camera, Location location) {
        // teleportAsync may complete a bit later than one tick. Keep only the newest
        // target and let the client interpolate between updates instead of flooding
        // the scheduler with overlapping teleports. It also loads chunks along the
        // route, which a synchronous teleport does not.
        ensureCameraChunk(session, location);
        session.pendingCameraTarget = location;
        if (session.cameraTeleportPending) {
            if (config.get().debug()) {
                session.traceDeferred++;
            }
            return;
        }
        issueCameraTeleport(session, camera);
    }

    private void issueCameraTeleport(Session session, ArmorStand camera) {
        if (!isCurrent(session) || session.ending.get() || !camera.isValid()) {
            session.cameraTeleportPending = false;
            return;
        }
        Location target = session.pendingCameraTarget;
        if (target == null) {
            session.cameraTeleportPending = false;
            return;
        }
        session.pendingCameraTarget = null;
        session.cameraTeleportPending = true;
        try {
            camera.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((success, error) -> camera.getScheduler().execute(plugin, () -> {
                        session.cameraTeleportPending = false;
                        if (!isCurrent(session) || session.ending.get()) {
                            return;
                        }
                        if (error != null || !Boolean.TRUE.equals(success)) {
                            plugin.getLogger().log(Level.WARNING, "Camera teleport failed for "
                                    + session.player.getName(), error);
                            requestEnd(session, EndReason.CAMERA_LOST, RestoreMode.ORIGINAL, true, true);
                            return;
                        }
                        if (session.pendingCameraTarget != null) {
                            issueCameraTeleport(session, camera);
                        }
                    }, () -> session.cameraTeleportPending = false, 0L));
        } catch (RuntimeException exception) {
            session.cameraTeleportPending = false;
            plugin.getLogger().log(Level.WARNING, "Camera teleport threw for "
                    + session.player.getName(), exception);
            requestEnd(session, EndReason.CAMERA_LOST, RestoreMode.ORIGINAL, true, true);
        }
    }

    /** The 1/256 turn byte the vanilla entity tracker packs an angle into. */
    private static double packedDegrees(float degrees) {
        return ((byte) (int) Math.floor(degrees * 256.0 / 360.0)) * 360.0 / 256.0;
    }

    private SwaySettings resolveSway(Session session) {
        String active = session.activeSway;
        SwayPreset preset;
        try {
            preset = SwayPreset.valueOf(active);
        } catch (IllegalArgumentException exception) {
            preset = SwayPreset.INHERIT;
        }
        return preset.resolve(session.track.sway());
    }

    private void ensureFollowing(Session session, Location cameraLocation) {
        if (!session.track.follow() || !config.get().followCamera()) {
            return;
        }
        if (session.cutSwitch != 0) {
            // The body was deliberately placed on the cut pose: while the camera is
            // detached the client camera IS the body, so following the anchor now would
            // move the view off the cut pose and make the re-attach jump. The first normal
            // tick follows again; by then the client looks through the camera and cannot
            // see the body move.
            return;
        }
        int chunkX = cameraLocation.getBlockX() >> 4;
        int chunkZ = cameraLocation.getBlockZ() >> 4;
        long packed = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        if (packed == session.lastFollowChunk) {
            return;
        }
        session.lastFollowChunk = packed;
        if (!session.followPending.compareAndSet(false, true)) {
            return;
        }
        Location target = cameraLocation.clone();
        // The player's body rotation is invisible while spectating; avoiding a cross-thread
        // player read here keeps this Folia-safe.
        target.setYaw(cameraLocation.getYaw());
        target.setPitch(cameraLocation.getPitch());
        boolean scheduled = session.player.getScheduler().execute(plugin,
                () -> followOnPlayer(session, target),
                () -> session.followPending.set(false),
                0L);
        if (!scheduled) {
            session.followPending.set(false);
        }
    }

    private void followOnPlayer(Session session, Location target) {
        if (!isCurrent(session) || session.ending.get() || !session.player.isOnline()) {
            session.followPending.set(false);
            return;
        }
        Player player = session.player;
        if (!player.getWorld().equals(target.getWorld())) {
            session.followPending.set(false);
            requestEnd(session, EndReason.WORLD_CHANGE, RestoreMode.NONE, true, true);
            return;
        }
        int chunkX = target.getBlockX() >> 4;
        int chunkZ = target.getBlockZ() >> 4;
        Location current = player.getLocation();
        if ((current.getBlockX() >> 4) == chunkX && (current.getBlockZ() >> 4) == chunkZ) {
            session.followPending.set(false);
            return;
        }
        if (config.get().debug()) {
            plugin.getLogger().info("camera follow: player " + player.getName() + " -> chunk "
                    + chunkX + "," + chunkZ + " at tick " + session.totalTicks);
        }
        beginInternalTeleport(session, 500L);
        try {
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((success, error) -> {
                        endInternalTeleport(session);
                        player.getScheduler().execute(plugin, () -> {
                            session.followPending.set(false);
                            if (error != null || !Boolean.TRUE.equals(success)) {
                                if (config.get().debug()) {
                                    plugin.getLogger().log(Level.FINE, "Chunk-follow teleport failed for "
                                            + player.getName(), error);
                                }
                                return;
                            }
                            if (isCurrent(session) && !session.ending.get() && session.camera != null
                                    && session.cutSwitch == 0
                                    && player.getSpectatorTarget() != session.camera) {
                                try {
                                    player.setSpectatorTarget(session.camera);
                                } catch (RuntimeException ignored) {
                                    // A later tick retries.
                                }
                            }
                        }, () -> session.followPending.set(false), 0L);
                    });
        } catch (RuntimeException exception) {
            endInternalTeleport(session);
            session.followPending.set(false);
            plugin.getLogger().log(Level.FINE, "Chunk-follow teleport threw for " + player.getName(), exception);
        }
    }

    // ------------------------------------------------------------------ ends

    private enum RestoreMode {
        EXIT,
        ORIGINAL,
        NONE
    }

    private void requestEnd(
            Session session,
            EndReason reason,
            RestoreMode mode,
            boolean restoreGameMode,
            boolean notify
    ) {
        if (!session.ending.compareAndSet(false, true)) {
            return;
        }
        session.endReason = reason;
        ScheduledTask task = session.tickTask;
        if (task != null) {
            task.cancel();
        }
        Player player = session.player;
        boolean scheduled = player.getScheduler().execute(plugin,
                () -> endOnPlayer(session, mode, restoreGameMode, notify),
                () -> finishWithoutPlayer(session),
                0L);
        if (!scheduled) {
            finishWithoutPlayer(session);
        }
    }

    private void endOnPlayer(Session session, RestoreMode mode, boolean restoreGameMode, boolean notify) {
        Player player = session.player;
        if (!player.isOnline()) {
            finishWithoutPlayer(session);
            return;
        }
        Location destination = switch (mode) {
            case EXIT -> exitLocation(session);
            case ORIGINAL -> session.original == null ? null : session.original.location();
            case NONE -> null;
        };
        if (destination == null) {
            boolean restored = restorePlayerSafely(session, restoreGameMode);
            if (!restored) {
                session.keepRecovery = true;
            }
            finalizeSession(session, notify);
            return;
        }
        Location bodyBefore = player.getLocation();
        // Restore control *before* the exit teleport. While the player is still camera-locked
        // or a spectator, the exit teleport races the server's own Set Camera(self) teleport
        // (ServerPlayer#setCamera) triggered by setSpectatorTarget(null): two absolute-position
        // packets with interleaved ack ids and justTeleported/awaiting state can leave the
        // client's position phase wrong, which is what makes walking get silently teleported
        // back.
        boolean restored = restorePlayerSafely(session, restoreGameMode);
        if (!restored) {
            session.keepRecovery = true;
        }
        if (!player.isOnline() || !isCurrent(session)) {
            finishWithoutPlayer(session);
            return;
        }
        // The restored body may carry spectator/movement residue; a clean placement starts
        // from zero velocity and no falling distance.
        clearMotion(player);
        beginInternalTeleport(session, 1000L);
        try {
            player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((success, error) -> {
                        endInternalTeleport(session);
                        if (error != null || !Boolean.TRUE.equals(success)) {
                            plugin.getLogger().log(Level.WARNING,
                                    "Could not place player at camera exit location: " + player.getName(), error);
                            session.completed = false;
                            session.keepRecovery = true;
                            // Never leave the player camera-locked in a half-ended session;
                            // finalize after a tick so the failed teleport has settled.
                            player.getScheduler().runDelayed(plugin, task -> {
                                logExitDiagnostics(session, destination, bodyBefore, false, null);
                                finalizeSession(session, notify);
                            }, () -> finishWithoutPlayer(session), 1L);
                            return;
                        }
                        // The exit teleport is in; repeat it at the same coordinates one tick
                        // later (see resyncExitTeleport) and only then finalize.
                        player.getScheduler().runDelayed(plugin,
                                task -> resyncExitTeleport(session, destination, bodyBefore, notify),
                                () -> finishWithoutPlayer(session), 1L);
                    });
        } catch (RuntimeException exception) {
            endInternalTeleport(session);
            plugin.getLogger().log(Level.WARNING, "Exit teleport threw for " + player.getName(), exception);
            session.completed = false;
            session.keepRecovery = true;
            logExitDiagnostics(session, destination, bodyBefore, false, null);
            finalizeSession(session, notify);
        }
    }

    /**
     * Repeats the exit teleport once at the same destination, one tick after the first one.
     *
     * <p>A second teleport sends a fresh {@code ClientboundPlayerPositionPacket} id/ack,
     * which resets the client's awaiting-teleport/last-good-position state
     * ({@code justTeleported}/{@code awaitingPosition}) after an exit that overlapped a
     * camera release; without it the client can keep validating against the pre-exit
     * position and silently pull the next steps back. The first teleport already succeeded,
     * so a failure here is not fatal: the player is at the destination either way.</p>
     */
    private void resyncExitTeleport(Session session, Location destination, Location bodyBefore, boolean notify) {
        Player player = session.player;
        if (!player.isOnline() || !isCurrent(session)) {
            finishWithoutPlayer(session);
            return;
        }
        // Movement in the tick between the two teleports must not leak into the re-sync.
        clearMotion(player);
        beginInternalTeleport(session, 1000L);
        try {
            player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((success, error) -> {
                        endInternalTeleport(session);
                        player.getScheduler().runDelayed(plugin, task -> {
                            boolean resynced = error == null && Boolean.TRUE.equals(success);
                            if (!resynced) {
                                plugin.getLogger().log(config.get().debug() ? Level.WARNING : Level.FINE,
                                        "Could not re-sync camera exit teleport for " + player.getName(), error);
                            }
                            session.completed = session.endReason == EndReason.FINISHED;
                            logExitDiagnostics(session, destination, bodyBefore, true, resynced);
                            finalizeSession(session, notify);
                        }, () -> finishWithoutPlayer(session), 1L);
                    });
        } catch (RuntimeException exception) {
            endInternalTeleport(session);
            plugin.getLogger().log(Level.WARNING, "Camera exit re-sync teleport threw for "
                    + player.getName(), exception);
            session.completed = session.endReason == EndReason.FINISHED;
            logExitDiagnostics(session, destination, bodyBefore, true, false);
            finalizeSession(session, notify);
        }
    }

    private static void clearMotion(Player player) {
        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0.0f);
    }

    /**
     * Debug-only one-liner for user reports: where the exit teleport went, where the body was
     * before/after it, and whether the first teleport and the same-position re-sync worked.
     */
    private void logExitDiagnostics(
            Session session,
            Location destination,
            Location bodyBefore,
            boolean teleported,
            Boolean resynced
    ) {
        if (!config.get().debug()) {
            return;
        }
        Player player = session.player;
        String bodyAfter = player.isOnline() ? coordinates(player.getLocation()) : "offline";
        plugin.getLogger().info("Camera exit: track=" + session.track.id()
                + " destination=" + coordinates(destination)
                + " body-before=" + coordinates(bodyBefore)
                + " body-after=" + bodyAfter
                + " teleported=" + teleported
                + " resynced=" + (resynced == null ? "skipped" : resynced));
    }

    private static String coordinates(Location location) {
        // Six decimals so a user report can show the sub-millimetre placements this exit
        // workaround is about (0.1 mm = 0.0001 blocks).
        return String.format(Locale.ROOT, "%.6f/%.6f/%.6f",
                location.getX(), location.getY(), location.getZ());
    }

    private boolean restorePlayerSafely(Session session, boolean restoreGameMode) {
        try {
            return restorePlayer(session, restoreGameMode);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not restore camera state for "
                    + session.player.getName(), exception);
            return false;
        }
    }

    private Location exitLocation(Session session) {
        if (session.preview) {
            return session.original == null ? null : session.original.location();
        }
        CameraTrack track = session.track;
        return switch (track.exitMode()) {
            case CUSTOM -> track.exit().toLocation(session.world);
            case PRE_ENTRY -> session.original == null ? null : session.original.location();
            case ANIMATION_END -> animationEndLocation(session);
        };
    }

    /**
     * Uses the camera entity's actual final eye position and rotation, including the last
     * sway/pose tick, so the player body can be placed exactly under the client's camera.
     */
    private Location animationEndLocation(Session session) {
        // Use the unswayed track pose, not the last rendered frame: the editor saved the
        // base pose, so exiting there keeps the final coordinates exact. Natural sway is
        // only ~0.06 blocks / 0.6 degrees, so dropping that last-frame offset is invisible.
        // ExitPlacement turns the eye pose into feet without the float eye-height underflow
        // and without embedding the body in a slab/stairs collision shape.
        return ExitPlacement.feet(session.world, session.currentPose, session.player.getEyeHeight());
    }

    private boolean restorePlayer(Session session, boolean restoreGameMode) {
        Player player = session.player;
        // Let the server's upcoming Set Camera(player) through. If the fake camera stayed
        // active, the listener would rewrite that reset back to the fake entity and the
        // client would keep looking through a camera that is about to be destroyed.
        deactivateFakeCamera(session);
        PlayerStateSnapshot original = session.original;
        boolean restored = true;
        try {
            if (original == null) {
                return true;
            }
            if (restoreGameMode) {
                try {
                    if (player.getGameMode() == GameMode.SPECTATOR) {
                        player.setSpectatorTarget(null);
                    }
                } catch (RuntimeException ignored) {
                    // The target may already be gone; game mode change below still works.
                }
                session.internalModeChange = true;
                try {
                    if (player.getGameMode() != original.gameMode()) {
                        player.setGameMode(original.gameMode());
                    }
                    // A listener can cancel PlayerGameModeChangeEvent. Do not report a
                    // successful restore (and delete the recovery file) if the player is still
                    // stuck in spectator.
                    restored = player.getGameMode() == original.gameMode();
                    player.setAllowFlight(original.allowFlight());
                    if (original.allowFlight()) {
                        player.setFlying(original.flying());
                    }
                    player.setWalkSpeed(original.walkSpeed());
                    player.setFlySpeed(original.flySpeed());
                    player.setFallDistance(0.0f);
                } finally {
                    session.internalModeChange = false;
                }
                if (original.gameMode() == GameMode.SPECTATOR && original.spectatorTarget() != null) {
                    Entity target = Bukkit.getEntity(original.spectatorTarget());
                    if (target != null && target.isValid() && target.getWorld().equals(player.getWorld())) {
                        try {
                            player.setSpectatorTarget(target);
                        } catch (RuntimeException ignored) {
                            // Old target no longer accepts spectators.
                        }
                    }
                }
            }
            return restored;
        } finally {
            // An exception in a game-mode/teleport restore must not strand an infinite
            // blindness/darkness effect on the player. Effect cleanup is independent of the
            // camera restore and is cheap, so always run it.
            restoreEffects(session, session.totalTicks);
            // Same for the locator-bar modifier: unconditionally drop our own transient
            // modifier, including the original == null early return above and all throw paths.
            restoreLocatorBar(player);
        }
    }

    private void restoreEffects(Session session, int elapsedTicks) {
        Player player = session.player;
        for (Map.Entry<PotionEffectType, PotionEffect> entry : new LinkedHashMap<>(session.originalEffects).entrySet()) {
            PotionEffect original = entry.getValue();
            try {
                if (original == null) {
                    player.removePotionEffect(entry.getKey());
                    continue;
                }
                if (original.isInfinite()) {
                    player.removePotionEffect(entry.getKey());
                    player.addPotionEffect(original);
                    continue;
                }
                int remaining = original.getDuration() - elapsedTicks;
                if (remaining > 0) {
                    player.removePotionEffect(entry.getKey());
                    player.addPotionEffect(original.withDuration(remaining));
                } else {
                    player.removePotionEffect(entry.getKey());
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not restore effect "
                        + entry.getKey().getKey(), exception);
            }
        }
        session.originalEffects.clear();
    }

    private void finalizeSession(Session session, boolean notify) {
        if (!session.closed.compareAndSet(false, true)) {
            return;
        }
        sessions.remove(session.playerId, session);
        removeCamera(session);
        Player player = session.player;
        if (player.isOnline() && !session.keepRecovery) {
            recovery.delete(session.playerId);
        } else {
            // Leave the recovery file for the next join.
            session.keepRecovery = true;
        }
        if (session.startEventFired && player.isOnline()) {
            try {
                Bukkit.getPluginManager().callEvent(new CameraTrackEndEvent(player, session.track.id(),
                        session.endReason, session.completed, session.preview));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "CameraTrackEndEvent listener failed", exception);
            }
        }
        if (notify && player.isOnline()) {
            notifyEnd(session);
        }
        if (config.get().debug()) {
            plugin.getLogger().info("Camera ended: player=" + player.getName() + " track=" + session.track.id()
                    + " reason=" + session.endReason + " completed=" + session.completed);
        }
    }

    private void notifyEnd(Session session) {
        if (!session.player.isOnline()) {
            return;
        }
        switch (session.endReason) {
            case TIMEOUT -> messages.get().send(session.player, "camera.timeout");
            case LIMIT -> messages.get().send(session.player, "camera.limit");
            case EXTERNAL_TELEPORT -> messages.get().send(session.player, "camera.external-teleport");
            case GAME_MODE_CHANGE -> messages.get().send(session.player, "camera.game-mode");
            case WORLD_CHANGE -> messages.get().send(session.player, "camera.world-change");
            case CAMERA_LOST -> messages.get().send(session.player, "camera.camera-lost");
            case ERROR -> messages.get().send(session.player, "camera.error");
            default -> {
            }
        }
    }

    private void finishWithoutPlayer(Session session) {
        session.ending.set(true);
        if (!session.closed.compareAndSet(false, true)) {
            return;
        }
        sessions.remove(session.playerId, session);
        removeCamera(session);
        // Keep the recovery file: the player is offline or their scheduler retired.
        session.keepRecovery = true;
    }

    private void deactivateFakeCamera(Session session) {
        CameraOverlay.CameraView fakeCamera = session.fakeCamera;
        if (fakeCamera != null) {
            fakeCamera.deactivate();
        }
    }

    private void destroyFakeCamera(Session session) {
        CameraOverlay.CameraView fakeCamera = session.fakeCamera;
        session.fakeCamera = null;
        if (fakeCamera != null) {
            fakeCamera.destroy();
        }
    }

    private void removeCamera(Session session) {
        destroyFakeCamera(session);
        ArmorStand camera = session.camera;
        session.camera = null;
        // Release tickets before the entity is retired. The retired callback must not
        // change ticket levels, and EntityScheduler#execute returns false for an already
        // retired entity without invoking that callback.
        releaseCameraChunks(session);
        if (camera == null) {
            return;
        }
        try {
            camera.getScheduler().execute(plugin, () -> {
                if (camera.isValid()) {
                    camera.remove();
                }
            }, () -> {
            }, 0L);
        } catch (RuntimeException ignored) {
            // Entity already retired or server is stopping.
        }
    }

    private void abandon(Session session) {
        if (session.closed.get()) {
            return;
        }
        session.ending.set(true);
        if (!session.closed.compareAndSet(false, true)) {
            return;
        }
        sessions.remove(session.playerId, session);
        removeCamera(session);
        if (session.startEventFired && session.player.isOnline()) {
            try {
                Bukkit.getPluginManager().callEvent(new CameraTrackEndEvent(session.player, session.track.id(),
                        EndReason.ERROR, false, session.preview));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "CameraTrackEndEvent listener failed on abandon", exception);
            }
        }
    }

    // ------------------------------------------------------------------ effects/titles

    private void queueInstant(Session session, Runnable action) {
        if (session.pendingInstant) {
            return;
        }
        session.pendingInstant = true;
        Player player = session.player;
        boolean scheduled = player.getScheduler().execute(plugin, () -> {
            try {
                if (isCurrent(session) && !session.ending.get() && player.isOnline()) {
                    action.run();
                    session.stepIndex++;
                    session.stepTicks = 0;
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Camera instant action failed for " + player.getName(),
                        exception);
                if (isCurrent(session) && !session.ending.get()) {
                    requestEnd(session, EndReason.ERROR, RestoreMode.ORIGINAL, true, true);
                }
            } finally {
                session.pendingInstant = false;
            }
        }, () -> session.pendingInstant = false, 0L);
        if (!scheduled) {
            session.pendingInstant = false;
            requestEnd(session, EndReason.ERROR, RestoreMode.ORIGINAL, true, true);
        }
    }

    /**
     * Effects are applied to the player (HUD, nausea shake, lightmap) and, when PacketEvents
     * provides a fake-player camera, also to that client-only camera entity. The fake player
     * is what makes blindness fog, darkness/night-vision fog and the FOV calculation see the
     * effect; the real player remains the source of the screen-space effects. The armor stand
     * itself is never an effect target.
     */
    private void applyEffectNow(Session session, EffectSpec spec) {
        Player player = session.player;
        if (!session.originalEffects.containsKey(spec.type())) {
            session.originalEffects.put(spec.type(), player.getPotionEffect(spec.type()));
        }
        try {
            if (spec.action() == net.ria.zth.camerarail.model.EffectAction.ADD) {
                player.removePotionEffect(spec.type());
                if (!player.addPotionEffect(spec.toPotionEffect())) {
                    plugin.getLogger().warning("The server refused effect " + spec.key()
                            + " for " + player.getName() + "; the camera copy will be cleared.");
                    session.cameraEffects.remove(spec.type());
                    session.cameraEffectExpiry.remove(spec.type());
                    return;
                }
                session.cameraEffects.put(spec.type(), spec);
                session.cameraEffectExpiry.put(spec.type(), spec.durationTicks() == 0
                        ? Long.MAX_VALUE
                        : session.totalTicks + spec.durationTicks());
                CameraOverlay.CameraView fakeCamera = session.fakeCamera;
                if (fakeCamera != null && fakeCamera.active()) {
                    fakeCamera.applyEffect(spec);
                }
            } else {
                player.removePotionEffect(spec.type());
                session.cameraEffects.remove(spec.type());
                session.cameraEffectExpiry.remove(spec.type());
                CameraOverlay.CameraView fakeCamera = session.fakeCamera;
                if (fakeCamera != null && fakeCamera.active()) {
                    fakeCamera.removeEffect(spec.type());
                }
            }
            updateFakeMovementSpeed(session);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not apply camera effect " + spec.key(), exception);
        }
    }

    /**
     * The client does not apply a mob effect's attribute modifiers to a client-only entity,
     * so slowness/speed FOV is expressed as the fake player's movement-speed attribute. The
     * attribute is recomputed from the active effects, which also handles natural expiry.
     */
    private void updateFakeMovementSpeed(Session session) {
        CameraOverlay.CameraView fakeCamera = session.fakeCamera;
        if (fakeCamera == null || !fakeCamera.active()) {
            return;
        }
        long now = session.totalTicks;
        for (Map.Entry<PotionEffectType, EffectSpec> entry : session.cameraEffects.entrySet()) {
            Long expiry = session.cameraEffectExpiry.get(entry.getKey());
            // Expiry is tracked here so natural expiry also removes the camera copy. The
            // player check covers milk, death and external /effect clear: never leave a
            // client-only effect (especially darkness fog) behind when the player copy is gone.
            if ((expiry != null && now >= expiry) || !session.player.hasPotionEffect(entry.getKey())) {
                if (session.cameraEffects.remove(entry.getKey(), entry.getValue())) {
                    session.cameraEffectExpiry.remove(entry.getKey());
                    fakeCamera.removeEffect(entry.getKey());
                }
            }
        }
        EffectSpec slowness = session.cameraEffects.get(PotionEffectType.SLOWNESS);
        EffectSpec speedEffect = session.cameraEffects.get(PotionEffectType.SPEED);
        fakeCamera.setMovementSpeed(CameraFov.movementSpeed(
                slowness == null ? CameraFov.ABSENT : slowness.amplifier(),
                speedEffect == null ? CameraFov.ABSENT : speedEffect.amplifier()));
    }

    private void sendTitleNow(Session session, TitleStep step) {
        Player player = session.player;
        try {
            net.kyori.adventure.text.Component title = net.kyori.adventure.text.Component.text("");
            net.kyori.adventure.text.Component subtitle = net.kyori.adventure.text.Component.text("");
            if (!step.title().isBlank()) {
                title = MINI_MESSAGE.deserialize(step.title());
            }
            if (!step.subtitle().isBlank()) {
                subtitle = MINI_MESSAGE.deserialize(step.subtitle());
            }
            Title.Times times = Title.Times.times(
                    Duration.ofMillis(step.fadeInTicks() * 50L),
                    Duration.ofMillis(step.stayTicks() * 50L),
                    Duration.ofMillis(step.fadeOutTicks() * 50L));
            player.showTitle(Title.title(title, subtitle, times));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Invalid camera title at runtime", exception);
        }
    }

    private void fireWaitEvent(Session session, WaitStep wait) {
        Player player = session.player;
        player.getScheduler().execute(plugin, () -> {
            if (!isCurrent(session) || session.ending.get() || !player.isOnline()) {
                return;
            }
            try {
                Bukkit.getPluginManager().callEvent(new CameraWaitEvent(player, session.track.id(),
                        wait.signal(), wait.timeoutTicks(), session.preview));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "CameraWaitEvent listener failed", exception);
            }
        }, () -> {
        }, 0L);
        if (config.get().debug()) {
            plugin.getLogger().info("Camera waiting: player=" + player.getName() + " signal=" + wait.signal());
        }
    }

    // ------------------------------------------------------------------ listeners

    public void handleQuit(Player player) {
        handleQuit(player, EndReason.QUIT);
    }

    public void handleKick(Player player) {
        handleQuit(player, EndReason.KICKED);
    }

    private void handleQuit(Player player, EndReason reason) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.closed.get()) {
            return;
        }
        session.ending.set(true);
        ScheduledTask task = session.tickTask;
        if (task != null) {
            task.cancel();
        }
        boolean restored = true;
        try {
            if (session.original != null) {
                restorePlayer(session, true);
            }
        } catch (RuntimeException exception) {
            restored = false;
            plugin.getLogger().log(Level.WARNING, "Could not restore camera state on quit for " + player.getName(),
                    exception);
        }
        // Covers a session whose snapshot was missing; normal restores already did this.
        restoreLocatorBar(player);
        sessions.remove(player.getUniqueId(), session);
        removeCamera(session);
        // Keep the recovery file even after restoring the game mode: the player's logout
        // position is wherever the camera was. On the next join it is moved back to the
        // pre-session location.
        session.keepRecovery = true;
        session.closed.set(true);
        if (session.startEventFired) {
            try {
                Bukkit.getPluginManager().callEvent(new CameraTrackEndEvent(player, session.track.id(),
                        restored ? reason : EndReason.ERROR, false, session.preview));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "CameraTrackEndEvent listener failed on quit", exception);
            }
        }
    }

    public void handleExternalGameModeChange(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.ending.get() || session.internalModeChange) {
            return;
        }
        requestEnd(session, EndReason.GAME_MODE_CHANGE, RestoreMode.NONE, false, true);
    }

    public void handleExternalTeleport(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.ending.get() || session.internalTeleports.get() > 0) {
            return;
        }
        // Paper can deliver the PlayerTeleportEvent for an async teleport after its future
        // has completed. A short grace window prevents our own movement from being
        // mistaken for another plugin's teleport.
        if (System.currentTimeMillis() < session.teleportGraceUntilMillis) {
            if (config.get().debug()) {
                plugin.getLogger().info("Ignored own camera teleport for " + player.getName());
            }
            return;
        }
        requestEnd(session, EndReason.EXTERNAL_TELEPORT, RestoreMode.NONE, true, true);
    }

    /**
     * A camera-locked client sends no movement at all; any accepted movement after the
     * session settled means the camera packet never took effect. Movements in the first few
     * ticks are ignored: they were already in flight while the camera was attached.
     */
    public void handleClientMove(Player player) {
        Session session = sessions.get(player.getUniqueId());
        // Ignore the packets that were already in flight while the camera was being attached,
        // and those sent while a hard cut has the camera detached on purpose.
        if (session != null && session.attached && !session.ending.get()
                && session.totalTicks >= 15 && session.cutSwitch == 0) {
            session.clientMovedWhileAttached = true;
        }
    }

    /**
     * True while the plugin itself is re-sending the camera packet, so the spectator events it
     * triggers are not mistaken for the player trying to leave the camera.
     */
    public boolean isInternalSpectatorChange(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.internalSpectatorChange;
    }

    private void beginInternalTeleport(Session session, long graceMillis) {
        session.internalTeleports.incrementAndGet();
        session.teleportGraceUntilMillis = Math.max(session.teleportGraceUntilMillis,
                System.currentTimeMillis() + graceMillis);
    }

    private void endInternalTeleport(Session session) {
        session.internalTeleports.updateAndGet(value -> Math.max(0, value - 1));
        session.teleportGraceUntilMillis = Math.max(session.teleportGraceUntilMillis,
                System.currentTimeMillis() + 250L);
    }

    public void handleWorldChange(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.ending.get()) {
            return;
        }
        if (session.internalTeleports.get() > 0
                || System.currentTimeMillis() < session.teleportGraceUntilMillis) {
            return;
        }
        requestEnd(session, EndReason.WORLD_CHANGE, RestoreMode.NONE, true, true);
    }

    public void handleDeath(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.ending.get()) {
            return;
        }
        // Restore the game mode even on death; otherwise the player respawns as a
        // spectator while the recovery file is deleted as if cleanup succeeded.
        requestEnd(session, EndReason.DEATH, RestoreMode.NONE, true, true);
    }

    public void handleSneakExit(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.ending.get()) {
            return;
        }
        if (canSneakExit(session)) {
            requestEnd(session, EndReason.STOPPED, RestoreMode.ORIGINAL, true, false);
        } else {
            hintLocked(session);
        }
    }

    /**
     * Previews are always escapable with Shift; real playback is locked unless the
     * server explicitly enables {@code session.allow-sneak-exit}.
     */
    public boolean canSneakExit(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && canSneakExit(session);
    }

    private boolean canSneakExit(Session session) {
        return !session.ending.get() && (session.preview || config.get().allowSneakExit());
    }

    /**
     * True while the spectator target should be protected from player input.
     */
    public boolean shouldLockSpectator(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.attached && !session.ending.get() && !session.closed.get()
                && session.camera != null;
    }

    public boolean isCameraEntity(Player player, Entity entity) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.camera != null && session.camera.equals(entity);
    }

    public void hintLocked(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            hintLocked(session);
        }
    }

    public void reassertSpectator(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.ending.get() || session.camera == null || session.cutSwitch != 0) {
            // Re-attaching in the middle of a hard-cut switch would re-introduce the
            // interpolation the switch exists to avoid; finishCutSwitch locks it again.
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR && player.getSpectatorTarget() != session.camera) {
            player.getScheduler().execute(plugin, () -> {
                if (isCurrent(session) && !session.ending.get() && session.camera != null) {
                    try {
                        player.setSpectatorTarget(session.camera);
                    } catch (RuntimeException ignored) {
                        // The next camera tick or stop will settle it.
                    }
                }
            }, () -> {
            }, 0L);
        }
    }

    public void hintLocked(Session session) {
        if (!session.player.isOnline()) {
            return;
        }
        long now = session.totalTicks;
        if (now - session.lastLockHintTick < LOCK_HINT_INTERVAL_TICKS) {
            return;
        }
        session.markHint(now);
        messages.get().actionBar(session.player, "camera.locked");
    }

    // ------------------------------------------------------------------ recovery/shutdown

    public void recoverOnJoin(Player player) {
        if (sessions.containsKey(player.getUniqueId())) {
            return;
        }
        // A plugin reload keeps player entities (and their attributes) alive but drops this
        // plugin's in-memory bookkeeping. Clear a stale modifier even when recovery is off;
        // transient modifiers do not survive a real relog, so this is normally a no-op.
        player.getScheduler().execute(plugin, () -> {
            if (player.isOnline() && !sessions.containsKey(player.getUniqueId())) {
                restoreLocatorBar(player);
            }
        }, () -> {
        }, 0L);
        if (!recovery.enabled()) {
            return;
        }
        recovery.read(player.getUniqueId()).thenAccept(optional -> optional.ifPresent(recoveryData -> {
            if (!player.isOnline()) {
                return;
            }
            player.getScheduler().execute(plugin, () -> applyRecovery(player, recoveryData), () -> {
            }, 1L);
        }));
    }

    private void applyRecovery(Player player, RecoveryData data) {
        if (!player.isOnline() || sessions.containsKey(player.getUniqueId())) {
            return;
        }
        // Idempotent cleanup for a plugin reload that left our transient modifier on a live
        // player; the player may also have set the attribute deliberately, which we never undo.
        restoreLocatorBar(player);
        World world = Bukkit.getWorld(data.worldName());
        if (world == null) {
            // Do not touch effects/game mode and do not delete the file: the world may be
            // loaded later, and a deleted world would otherwise leave a half-restored player.
            messages.get().send(player, "camera.recovery-world-missing", "world", data.worldName());
            return;
        }
        try {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                player.setSpectatorTarget(null);
            }
            player.clearActivePotionEffects();
            for (EffectData effectData : data.effects()) {
                PotionEffect effect = effectData.toPotionEffect();
                if (effect != null) {
                    player.addPotionEffect(effect);
                }
            }
            player.setGameMode(data.gameMode());
            player.setAllowFlight(data.allowFlight());
            if (data.allowFlight()) {
                player.setFlying(data.flying());
            }
            player.setWalkSpeed(data.walkSpeed());
            player.setFlySpeed(data.flySpeed());
            player.setFallDistance(0.0f);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not apply recovery state for " + player.getName(), exception);
            messages.get().send(player, "camera.recovery-failed");
            return;
        }
        Location target = new Location(world, data.x(), data.y(), data.z(), data.yaw(), data.pitch());
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
                .whenComplete((success, error) -> player.getScheduler().execute(plugin, () -> {
                    if (error != null || !Boolean.TRUE.equals(success)) {
                        messages.get().send(player, "camera.recovery-failed");
                        return;
                    }
                    if (data.gameMode() == GameMode.SPECTATOR && data.spectatorTarget() != null) {
                        Entity entity = Bukkit.getEntity(data.spectatorTarget());
                        if (entity != null && entity.isValid()) {
                            try {
                                player.setSpectatorTarget(entity);
                            } catch (RuntimeException ignored) {
                                // The old target is gone.
                            }
                        }
                    }
                    recovery.delete(player.getUniqueId());
                    messages.get().send(player, "camera.recovered");
                    try {
                        Bukkit.getPluginManager().callEvent(new CameraTrackEndEvent(player, data.trackId(),
                                EndReason.RECOVERED, false, false));
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(Level.WARNING, "CameraTrackEndEvent listener failed on recovery",
                                exception);
                    }
                    if (config.get().debug()) {
                        plugin.getLogger().info("Recovered camera session for " + player.getName()
                                + " track=" + data.trackId());
                    }
                }, () -> {
                }, 0L));
    }

    /**
     * Best-effort cleanup on plugin disable. Recovery files are intentionally kept so a
     * graceful restart can repair the player on next join.
     */
    public void shutdown() {
        for (Session session : new ArrayList<>(sessions.values())) {
            session.ending.set(true);
            ScheduledTask task = session.tickTask;
            if (task != null) {
                task.cancel();
            }
            Player player = session.player;
            if (!isFolia() && player.isOnline()) {
                try {
                    if (session.original != null) {
                        restorePlayer(session, true);
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "Could not restore camera on shutdown for "
                            + player.getName(), exception);
                }
                // On Paper (non-Folia) the shutdown runs on the main thread, so the attribute
                // may be touched directly even if the session had no snapshot to restore.
                restoreLocatorBar(player);
            } else if (player.isOnline()) {
                // On Folia this runs on the player's current region when possible.
                player.getScheduler().execute(plugin, () -> {
                    try {
                        if (session.original != null) {
                            restorePlayer(session, true);
                        }
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(Level.WARNING, "Could not restore camera on shutdown for "
                                + player.getName(), exception);
                    }
                    restoreLocatorBar(player);
                }, () -> {
                }, 0L);
            }
            removeCamera(session);
            session.closed.set(true);
            sessions.remove(player.getUniqueId(), session);
        }
        sessions.clear();
    }

    public boolean isRecoveryEnabled() {
        return recovery.enabled();
    }

    private void removeSession(Session session) {
        sessions.remove(session.playerId, session);
        removeCamera(session);
        session.closed.set(true);
    }

    private boolean isCurrent(Session session) {
        return !session.closed.get() && sessions.get(session.playerId) == session;
    }

    private void onCameraRetired(Session session) {
        if (isCurrent(session) && !session.ending.get()) {
            requestEnd(session, EndReason.CAMERA_LOST, RestoreMode.ORIGINAL, true, true);
        }
    }

    private static boolean isFolia() {
        return FOLIA;
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
