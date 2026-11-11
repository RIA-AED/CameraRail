package red.ria.camerarail.api;

import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.Set;

/**
 * Public integration point, registered in Bukkit's ServicesManager.
 *
 * <pre>{@code
 * CameraService cameras = Bukkit.getServicesManager().load(CameraService.class);
 * cameras.play(player, "login");
 * cameras.advance(player, "authme:authenticated");
 * }</pre>
 *
 * <p>Playback methods are thread-safe and may be called from any thread. {@link #reload()}
 * touches plugin configuration and the registry, so call it from the server thread (a
 * command callback is fine). The boolean returned by {@link #play} means the request was
 * accepted, not that the first frame has been rendered yet. Actual completion is signalled
 * by {@link CameraTrackEndEvent}.</p>
 */
public interface CameraService {
    /**
     * All loaded track ids, sorted.
     */
    Set<String> trackIds();

    /**
     * Whether a session is currently active for the player.
     */
    boolean isPlaying(Player player);

    /**
     * Snapshot of the active session, if any.
     */
    Optional<PlaybackInfo> playback(Player player);

    /**
     * The signal the player's camera is currently waiting on, if any.
     */
    Optional<String> waitingSignal(Player player);

    /**
     * Starts a track for the player. Rejects a second session for the same player.
     */
    boolean play(Player player, String trackId);

    /**
     * Aborts the active session and restores the player's pre-session state.
     */
    boolean stop(Player player);

    /**
     * Delivers a named signal. If the signal has not been reached yet, it is queued once;
     * if it is not the currently waited signal, it remains queued for a later matching
     * wait. Each call can advance one wait.
     */
    boolean advance(Player player, String signal);

    /**
     * Advances the wait the player is currently on, regardless of its signal.
     */
    boolean advance(Player player);

    /**
     * Reloads tracks.yml, config.yml and messages.yml. Returns the number of loaded tracks, or
     * -1 when the reload was refused (a Dialog editor session is open) or tracks.yml reported any
     * error. A refused or fatal reload leaves the in-memory tracks unchanged; when only some
     * tracks fail to parse, the others are reloaded, the failing ones keep their previous version,
     * and the command-side undo history is cleared.
     */
    int reload();
}
