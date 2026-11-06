package net.ria.zth.camerarail.runtime;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.ria.zth.camerarail.api.EndReason;
import net.ria.zth.camerarail.api.PlaybackInfo;
import net.ria.zth.camerarail.model.CameraTrack;
import net.ria.zth.camerarail.model.CameraStep;
import net.ria.zth.camerarail.model.EffectSpec;
import net.ria.zth.camerarail.model.Pose;
import net.ria.zth.camerarail.model.Step;
import net.ria.zth.camerarail.model.SwayPreset;
import net.ria.zth.camerarail.model.WaitStep;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable state for one playback. Most fields are owned by the camera entity scheduler;
 * fields read by commands are volatile. Player-state fields are only touched by the
 * player's scheduler.
 */
final class Session {
    private static final int MAX_QUEUED_ADVANCES = 32;

    final Player player;
    final UUID playerId;
    final CameraTrack track;
    final World world;
    final boolean preview;
    /** First step to run; non-zero when the editor previews from a selected step. */
    final int startIndex;
    /** Last step to run (inclusive); used to preview a single step. */
    final int endIndex;
    /** Pose the camera starts at (the end pose of the steps before {@link #startIndex}). */
    final Pose initialPose;
    /** Sway override active when the session starts. */
    final SwayPreset initialSway;
    final long startedAtMillis;

    final AtomicBoolean ending = new AtomicBoolean();
    final AtomicBoolean closed = new AtomicBoolean();
    final AtomicInteger internalTeleports = new AtomicInteger();
    final AtomicBoolean followPending = new AtomicBoolean();
    final AtomicInteger queuedAdvances = new AtomicInteger();
    final Queue<AdvanceRequest> advances = new ConcurrentLinkedQueue<>();
    final Map<PotionEffectType, PotionEffect> originalEffects = new LinkedHashMap<>();
    /**
     * Effects that are currently active on the client-side camera entity. Effects are
     * applied to the player as well; this map lets a camera view created later replay
     * them, and makes effect removal order-independent.
     */
    final Map<PotionEffectType, EffectSpec> cameraEffects = new ConcurrentHashMap<>();
    /** Absolute tick at which each fake-camera effect expires; Long.MAX_VALUE = infinite. */
    final Map<PotionEffectType, Long> cameraEffectExpiry = new ConcurrentHashMap<>();
    /** Chunks kept loaded for the camera entity (packed chunk coordinates). */
    final Set<Long> cameraChunks = ConcurrentHashMap.newKeySet();

    volatile PlayerStateSnapshot original;
    volatile ArmorStand camera;
    /** Client-only player camera; null when PacketEvents is absent or spawn failed. */
    volatile CameraOverlay.CameraView fakeCamera;
    volatile boolean fakeCameraFailureNotified;
    volatile ScheduledTask tickTask;
    volatile boolean startEventFired;
    volatile boolean internalModeChange;
    volatile boolean attached;
    volatile boolean completed;
    volatile EndReason endReason = EndReason.ERROR;
    volatile boolean recoveryWritten;
    volatile boolean keepRecovery;

    volatile Pose currentPose;
    volatile Pose stepFrom;
    volatile PathCurve pathCurve;
    volatile int pathCurveStep = -1;
    /**
     * Step index whose hard-cut jump has already been applied. The cut is processed again
     * on later ticks (hold after the jump, armor-stand switch), so the jump must not repeat.
     */
    volatile int cutStepApplied = -1;
    /**
     * Armor-stand fallback hard-cut switch state: {@code 0} = idle, positive = settle ticks
     * left before the client camera may be re-attached, negative = a switch task is in flight.
     */
    volatile int cutSwitch;
    volatile String activeStepDescription = "准备中";
    volatile String activeSway = "inherit";
    volatile int stepIndex;
    volatile int stepTicks;
    volatile int totalTicks;
    volatile String waitingSignal;
    volatile int waitTicks;
    volatile int waitTimeoutTicks;
    volatile boolean pendingInstant;
    volatile long teleportGraceUntilMillis;
    volatile boolean cameraTeleportPending;
    volatile org.bukkit.Location pendingCameraTarget;
    volatile double cameraEyeOffset;
    volatile long lastFollowChunk = Long.MIN_VALUE;
    volatile long lastLockHintTick = -100L;
    volatile int attachAttempts;
    /** Set while the plugin re-sends the camera packet, so the spectator events it raises are ours. */
    volatile boolean internalSpectatorChange;
    /** Set when the client moved its own player after the camera settled: the camera did not lock. */
    volatile boolean clientMovedWhileAttached;
    volatile boolean clientMoveWarned;

    final SwayAnimator swayAnimator;

    /** Debug trace state. Only touched by the camera entity's scheduler thread. */
    Pose tracePose;
    int traceTicks;
    int traceDeferred;
    int traceWireSteps;
    double traceWireMax;
    double traceMaxYaw;
    double traceMaxPitch;
    double traceMaxPos;
    boolean traceEntitySeen;
    float traceEntityYaw;
    double traceEntityMax;
    int traceStale;

    Session(Player player, CameraTrack track, World world, boolean preview, int startIndex, int endIndex) {
        this.player = player;
        this.playerId = player.getUniqueId();
        this.track = track;
        this.world = world;
        this.preview = preview;
        this.startIndex = TrackTimeline.clampStep(track, startIndex);
        int size = track.steps().size();
        this.endIndex = size == 0 ? -1 : Math.max(this.startIndex, Math.min(endIndex, size - 1));
        this.initialPose = TrackTimeline.poseAtStepStart(track, this.startIndex);
        this.initialSway = TrackTimeline.swayAtStepStart(track, this.startIndex);
        this.startedAtMillis = System.currentTimeMillis();
        this.currentPose = initialPose;
        this.stepFrom = initialPose;
        this.stepIndex = this.startIndex;
        this.activeSway = initialSway.name();
        this.swayAnimator = new SwayAnimator(track.sway());
    }

    boolean active() {
        return !ending.get() && !closed.get() && player.isOnline();
    }

    /**
     * Whether the camera enters at the track's own start pose ({@link #initialPose} may still
     * be the end pose of earlier steps). Entry effects are tied to the hard cut from the
     * player's body to {@code track.start()}; a preview that begins at a later step spawns
     * the camera at the end pose of the preceding steps, which is not an entry moment in
     * real playback, so the editor's "预览此步" must not trigger them.
     */
    boolean appliesEntryEffects() {
        return startIndex == 0;
    }

    Optional<PlaybackInfo> info() {
        if (closed.get()) {
            return Optional.empty();
        }
        int index = Math.min(stepIndex, Math.max(0, track.steps().size() - 1));
        String description = activeStepDescription;
        Step step = stepIndex >= 0 && stepIndex < track.steps().size() ? track.steps().get(stepIndex) : null;
        if (step != null) {
            description = step.describe();
        } else if (stepIndex >= track.steps().size()) {
            description = "结束";
        }
        return Optional.of(new PlaybackInfo(track.id(), index, track.steps().size(), description,
                waitingSignal, totalTicks, preview));
    }

    boolean queueAdvance(String signal) {
        if (ending.get() || closed.get()) {
            return false;
        }
        if (queuedAdvances.get() >= MAX_QUEUED_ADVANCES) {
            return false;
        }
        advances.add(new AdvanceRequest(signal));
        queuedAdvances.incrementAndGet();
        return true;
    }

    /**
     * Removes the first queued request matching the signal. A null signal matches any
     * wait.
     */
    Optional<AdvanceRequest> pollAdvance(String signal) {
        Iterator<AdvanceRequest> iterator = advances.iterator();
        while (iterator.hasNext()) {
            AdvanceRequest request = iterator.next();
            if (request.signal() == null || signal == null || request.signal().equals(signal)) {
                if (advances.remove(request)) {
                    queuedAdvances.decrementAndGet();
                    return Optional.of(request);
                }
            }
        }
        return Optional.empty();
    }

    boolean hasMatchingAdvance(String signal) {
        for (AdvanceRequest request : advances) {
            if (request.signal() == null || signal == null || request.signal().equals(signal)) {
                return true;
            }
        }
        return false;
    }

    void enterWait(WaitStep wait) {
        waitingSignal = wait.signal();
        waitTicks = 0;
        waitTimeoutTicks = wait.timeoutTicks();
        activeStepDescription = wait.describe();
        activeSway = wait.sway().name();
    }

    void leaveWait() {
        waitingSignal = null;
        waitTicks = 0;
        waitTimeoutTicks = 0;
    }

    void enterStep(Step step) {
        activeStepDescription = step.describe();
        if (step instanceof CameraStep cameraStep) {
            activeSway = cameraStep.sway().name();
        }
        stepTicks = 0;
        stepFrom = currentPose;
        if (step instanceof net.ria.zth.camerarail.model.PathStep) {
            pathCurve = null;
            pathCurveStep = stepIndex;
        }
    }

    void markHint(long tick) {
        lastLockHintTick = tick;
    }

    record AdvanceRequest(String signal) {
    }
}
