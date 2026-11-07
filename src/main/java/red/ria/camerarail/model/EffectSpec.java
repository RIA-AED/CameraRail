package red.ria.camerarail.model;

import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * A single potion-effect operation. {@code durationTicks == 0} means "until removed or the
 * track ends". Amplifier is zero-based, matching Bukkit.
 *
 * <p>Effects are applied to the spectating player, never to the camera armor stand. When the
 * PacketEvents fake-player camera is active, the camera-visible subset is also sent to that
 * client-only player entity, which makes blindness/darkness fog and slowness/speed FOV work;
 * the player copy still drives nausea, the lightmap and the HUD state. See
 * {@code docs/spectator-effects.md}.
 */
public record EffectSpec(
        PotionEffectType type,
        String key,
        EffectAction action,
        int amplifier,
        int durationTicks
) {
    public EffectSpec {
        if (type == null) {
            throw new IllegalArgumentException("Potion effect type is required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Potion effect key is required");
        }
        if (action == null) {
            throw new IllegalArgumentException("Effect action is required");
        }
        if (amplifier < 0 || amplifier > 255) {
            throw new IllegalArgumentException("Effect amplifier must be between 0 and 255");
        }
        if (durationTicks < 0) {
            throw new IllegalArgumentException("Effect duration cannot be negative");
        }
    }

    public static EffectSpec add(PotionEffectType type, String key, int amplifier) {
        return new EffectSpec(type, key, EffectAction.ADD, amplifier, 0);
    }

    public boolean isInfinite() {
        return durationTicks == 0;
    }

    /**
     * Creates the effect applied to the player. Ambient and icon are hidden so the effect
     * does not become visual clutter in the HUD.
     */
    public PotionEffect toPotionEffect() {
        int duration = durationTicks == 0 ? PotionEffect.INFINITE_DURATION : durationTicks;
        return new PotionEffect(type, duration, amplifier, true, false, false);
    }

    /**
     * Human-readable description used by the editor and list output.
     */
    public String describe() {
        if (action == EffectAction.REMOVE) {
            return "移除 " + key;
        }
        String suffix = isInfinite() ? "持续" : durationTicks + "t";
        return "施加 " + key + " Lv" + amplifier + " " + suffix;
    }
}
