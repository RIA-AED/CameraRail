package net.ria.zth.camerarail.api;

/**
 * Read-only snapshot of a player's current camera session.
 *
 * @param trackId track id
 * @param stepIndex zero-based index of the active step
 * @param stepCount number of steps in the track
 * @param stepDescription short description of the active step
 * @param waitingSignal signal currently waited on, or null
 * @param elapsedTicks ticks since the camera was attached
 * @param preview true for an editor preview
 */
public record PlaybackInfo(
        String trackId,
        int stepIndex,
        int stepCount,
        String stepDescription,
        String waitingSignal,
        int elapsedTicks,
        boolean preview
) {
}
