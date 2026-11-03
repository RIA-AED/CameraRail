package net.ria.zth.camerarail.storage;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Portable potion effect data used by the crash-recovery file.
 */
public record EffectData(String key, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
    public static EffectData from(PotionEffect effect) {
        return new EffectData(effect.getType().getKey().toString(), effect.getDuration(), effect.getAmplifier(),
                effect.isAmbient(), effect.hasParticles(), effect.hasIcon());
    }

    public PotionEffect toPotionEffect() {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        if (namespacedKey == null) {
            return null;
        }
        PotionEffectType type = Registry.EFFECT.get(namespacedKey);
        if (type == null) {
            return null;
        }
        return new PotionEffect(type, duration, amplifier, ambient, particles, icon);
    }
}
