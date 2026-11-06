package net.ria.zth.camerarail.listener;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import net.ria.zth.camerarail.runtime.CameraEngine;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

/**
 * All player-driven ways out of the spectator camera are funnelled here. The default
 * policy is to keep the shot locked; external teleports/game-mode changes end the session
 * instead of fighting the other plugin.
 */
public final class CameraListener implements Listener {
    private final CameraEngine engine;

    public CameraListener(CameraEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        engine.recoverOnJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(org.bukkit.event.player.PlayerKickEvent event) {
        engine.handleKick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        engine.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking() || !engine.shouldLockSpectator(event.getPlayer())) {
            return;
        }
        if (!engine.canSneakExit(event.getPlayer())) {
            event.setCancelled(true);
            engine.hintLocked(event.getPlayer());
            engine.reassertSpectator(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStopSpectating(PlayerStopSpectatingEntityEvent event) {
        Player player = event.getPlayer();
        if (engine.isInternalSpectatorChange(player) || !engine.shouldLockSpectator(player)) {
            return;
        }
        // A release the player did not cause (the server-side sneak flag is clear) must not be
        // run through the Shift exit path. Folia replaces the camera entity when it is handed
        // to another region and clears the stale target first; on Paper the same event comes
        // from a lost camera entity, a server teleport that resets the camera, or another
        // plugin. Keep the shot silently.
        if (!engine.isPlayerInitiatedCameraExit(player)) {
            event.setCancelled(true);
            engine.handleServerCameraRelease(player);
            return;
        }
        if (engine.canSneakExit(player)) {
            engine.handleSneakExit(player);
            return;
        }
        event.setCancelled(true);
        engine.hintLocked(player);
        engine.reassertSpectator(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStartSpectating(PlayerStartSpectatingEntityEvent event) {
        Player player = event.getPlayer();
        if (!engine.shouldLockSpectator(player)) {
            return;
        }
        if (!engine.isCameraEntity(player, event.getNewSpectatorTarget())) {
            event.setCancelled(true);
            engine.hintLocked(player);
            engine.reassertSpectator(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        engine.handleExternalGameModeChange(event.getPlayer());
    }

    /**
     * Vanilla lets a spectator teleport to another player with the number keys (or the
     * spectator menu). That arrives as a body teleport with {@link PlayerTeleportEvent.TeleportCause#SPECTATE}
     * and, without this veto, would be treated as an external teleport and end the shot.
     * The shot stays locked instead; other teleport causes keep the existing "external
     * teleport ends the session" policy.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectatorTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE
                || !engine.shouldLockSpectator(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        engine.hintLocked(event.getPlayer());
        engine.reassertSpectator(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        engine.handleExternalTeleport(event.getPlayer());
    }

    /**
     * The only server-observable sign that the client is driving its own body instead of the
     * camera: a camera-locked client stops sending movement packets entirely.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        engine.handleClientMove(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        engine.handleWorldChange(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        engine.handleDeath(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        engine.handleWorldChange(event.getPlayer());
    }
}
