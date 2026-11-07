package red.ria.camerarail.api;

/**
 * Why a camera session ended.
 */
public enum EndReason {
    FINISHED,
    STOPPED,
    QUIT,
    KICKED,
    TIMEOUT,
    LIMIT,
    CAMERA_LOST,
    EXTERNAL_TELEPORT,
    GAME_MODE_CHANGE,
    WORLD_CHANGE,
    DEATH,
    ERROR,
    RECOVERED
}
