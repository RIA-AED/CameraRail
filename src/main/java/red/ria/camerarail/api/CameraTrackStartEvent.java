package red.ria.camerarail.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Fired before the player is moved into spectator mode. A listener can cancel the start;
 * the player is then left untouched and no end event is fired.
 */
public final class CameraTrackStartEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String trackId;
    private final String displayName;
    private final boolean preview;
    private boolean cancelled;

    public CameraTrackStartEvent(Player player, String trackId, String displayName, boolean preview) {
        super(player);
        this.trackId = trackId;
        this.displayName = displayName;
        this.preview = preview;
    }

    public String trackId() {
        return trackId;
    }

    public String displayName() {
        return displayName;
    }

    public boolean preview() {
        return preview;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
