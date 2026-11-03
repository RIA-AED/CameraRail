package net.ria.zth.camerarail.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Fired after the session has released the player. At this point the player is no longer
 * spectating the camera; effects and game mode have been restored.
 */
public final class CameraTrackEndEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String trackId;
    private final EndReason reason;
    private final boolean completed;
    private final boolean preview;

    public CameraTrackEndEvent(Player player, String trackId, EndReason reason, boolean completed, boolean preview) {
        super(player);
        this.trackId = trackId;
        this.reason = reason;
        this.completed = completed;
        this.preview = preview;
    }

    public String trackId() {
        return trackId;
    }

    public EndReason reason() {
        return reason;
    }

    /**
     * True only when the track reached its natural end and the player was placed at the
     * configured exit.
     */
    public boolean completed() {
        return completed;
    }

    public boolean preview() {
        return preview;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
