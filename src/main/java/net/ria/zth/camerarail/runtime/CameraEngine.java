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

    private final Plugin plugin;
    private final TrackRepository tracks;
    private final RecoveryStore recovery;
    private final Supplier<RailConfig> config;
    private final Supplier<Messages> messages;
    private final IntSupplier reloadAction;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public CameraEngine(
            Plugin plugin,
            TrackRepository tracks,
            RecoveryStore recovery,
            Supplier<RailConfig> config,
            Supplier<Messages> messages,
            IntSupplier reloadAction
    ) {
        this.plugin = plugin;
        this.tracks = tracks;
        this.recovery = recovery;
        this.config = config;
        this.messages = messages;
        this.reloadAction = reloadAction;
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
                // Hidden from every player by default; attach() shows it to exactly one
                // player with Player#showEntity. That call pushes the spawn packet to the
                // client *now*, and the order matters: the client silently drops a Set
                // Camera packet for an entity it does not know yet (it only resolves the id
                // in its own level) and never asks for it again. A visible-by-default stand
                // is spawned by the entity tracker on the tracker's own schedule, so the
                // camera packet can overtake it, leaving the client in free spectator mode
                // while the server keeps snapping the player to the stand every tick.
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
            // The client resolves Set Camera against its own entity list, so the spawn has to
            // be on the wire first. showEntity pushes it to this player immediately; the
            // stand is hidden from everybody by default, so nobody else ever receives it.
            player.showEntity(plugin, camera);
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

    private void applyEntryEffects(Session session) {
        if (session.track.entryEffects().isEmpty()) {
            return;
        }
        // Called on the player's scheduler, before the first teleport. Only effects the
        // client reads from the player (nausea/darkness/night_vision) can cover the cut;
        // blindness is read from the camera entity and does nothing once attached.
        // See docs/spectator-effects.md.
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
                session.enterStep(step);
                session.currentPose = cutStep.to();
                session.stepIndex++;
                session.stepTicks = 0;
                continue;
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
        // Teleport while the player is still a flying spectator, then wait one tick before
        // restoring the game mode. The extra tick lets the client apply the new body
        // position/rotation before its camera leaves the armour stand, which removes the
        // visible snap back to the old body position.
        beginInternalTeleport(session, 1000L);
        try {
            player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((success, error) -> {
                        endInternalTeleport(session);
                        player.getScheduler().runDelayed(plugin, task -> {
                            boolean restored = restorePlayerSafely(session, restoreGameMode);
                            if (!restored) {
                                session.keepRecovery = true;
                            }
                            if (error != null || !Boolean.TRUE.equals(success)) {
                                plugin.getLogger().log(Level.WARNING,
                                        "Could not place player at camera exit location: " + player.getName(), error);
                                session.completed = false;
                                session.keepRecovery = true;
                            } else {
                                session.completed = session.endReason == EndReason.FINISHED;
                            }
                            finalizeSession(session, notify);
                        }, () -> finishWithoutPlayer(session), 1L);
                    });
        } catch (RuntimeException exception) {
            endInternalTeleport(session);
            plugin.getLogger().log(Level.WARNING, "Exit teleport threw for " + player.getName(), exception);
            boolean restored = restorePlayerSafely(session, restoreGameMode);
            if (!restored) {
                session.keepRecovery = true;
            }
            finalizeSession(session, notify);
        }
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
        Pose pose = session.currentPose;
        double eyeHeight = session.player.getEyeHeight();
        return new Location(session.world, pose.x(), pose.y() - eyeHeight, pose.z(), pose.yaw(), pose.pitch());
    }

    private boolean restorePlayer(Session session, boolean restoreGameMode) {
        Player player = session.player;
        PlayerStateSnapshot original = session.original;
        if (original == null) {
            return true;
        }
        boolean restored = true;
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
        restoreEffects(session, session.totalTicks);
        return restored;
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

    private void removeCamera(Session session) {
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
     * Effects always target the player, never the camera armor stand: the client only sends a
     * non-player entity's effect packets to its passengers, and a spectator target is not a
     * passenger. Note that this still does not make camera-entity effects (blindness fog, FOV)
     * visible while attached; see {@code docs/spectator-effects.md}.
     */
    private void applyEffectNow(Session session, EffectSpec spec) {
        Player player = session.player;
        if (!session.originalEffects.containsKey(spec.type())) {
            session.originalEffects.put(spec.type(), player.getPotionEffect(spec.type()));
        }
        try {
            if (spec.action() == net.ria.zth.camerarail.model.EffectAction.ADD) {
                player.removePotionEffect(spec.type());
                player.addPotionEffect(spec.toPotionEffect());
            } else {
                player.removePotionEffect(spec.type());
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not apply camera effect " + spec.key(), exception);
        }
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
        // Ignore the packets that were already in flight while the camera was being attached.
        if (session != null && session.attached && !session.ending.get() && session.totalTicks >= 15) {
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
        if (session == null || session.ending.get() || session.camera == null) {
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
        if (!recovery.enabled() || sessions.containsKey(player.getUniqueId())) {
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
