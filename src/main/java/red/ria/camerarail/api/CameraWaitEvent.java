package red.ria.camerarail.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Fired when a track enters a wait step. Integration plugins can use this to confirm that
 * the camera is ready for authentication or a minigame transition.
 */
public final class CameraWaitEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String trackId;
    private final String signal;
    private final int timeoutTicks;
    private final boolean preview;

    public CameraWaitEvent(Player player, String trackId, String signal, int timeoutTicks, boolean preview) {
        super(player);
        this.trackId = trackId;
        this.signal = signal;
        this.timeoutTicks = timeoutTicks;
        this.preview = preview;
    }

    public String trackId() {
        return trackId;
    }

    public String signal() {
        return signal;
    }

    /**
     * Zero means no timeout.
     */
    public int timeoutTicks() {
        return timeoutTicks;
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
