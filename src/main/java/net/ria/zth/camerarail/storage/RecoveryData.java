package net.ria.zth.camerarail.storage;

import org.bukkit.GameMode;

import java.util.List;
import java.util.UUID;

/**
 * Immutable on-disk snapshot of a session that was active when the server stopped
 * unexpectedly.
 */
public record RecoveryData(
        UUID playerId,
        String trackId,
        long startedAt,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        GameMode gameMode,
        UUID spectatorTarget,
        boolean allowFlight,
        boolean flying,
        float walkSpeed,
        float flySpeed,
        List<EffectData> effects
) {
    public RecoveryData {
        effects = List.copyOf(effects);
    }
}
