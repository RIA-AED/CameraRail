package red.ria.camerarail.runtime;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.UUID;

/**
 * Everything the plugin changes on a player is captured here before the first change.
 * Not all of this is persisted for crash recovery, but the in-memory copy is exact.
 */
public record PlayerStateSnapshot(
        Location location,
        GameMode gameMode,
        UUID spectatorTarget,
        boolean allowFlight,
        boolean flying,
        float walkSpeed,
        float flySpeed,
        UUID vehicle,
        float fallDistance,
        List<PotionEffect> effects
) {
    public PlayerStateSnapshot {
        location = location.clone();
        effects = List.copyOf(effects);
    }

    public static PlayerStateSnapshot capture(Player player) {
        UUID target = player.getSpectatorTarget() == null ? null : player.getSpectatorTarget().getUniqueId();
        UUID vehicle = player.getVehicle() == null ? null : player.getVehicle().getUniqueId();
        return new PlayerStateSnapshot(
                player.getLocation(),
                player.getGameMode(),
                target,
                player.getAllowFlight(),
                player.isFlying(),
                player.getWalkSpeed(),
                player.getFlySpeed(),
                vehicle,
                player.getFallDistance(),
                List.copyOf(player.getActivePotionEffects())
        );
    }
}
