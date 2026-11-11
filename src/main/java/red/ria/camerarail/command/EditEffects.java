package red.ria.camerarail.command;

import org.bukkit.potion.PotionEffectType;
import red.ria.camerarail.model.CameraEffects;
import red.ria.camerarail.model.EffectAction;
import red.ria.camerarail.model.EffectSpec;

/**
 * Turns the parsed effect arguments into an {@link EffectSpec}: resolves the registry effect and
 * clamps the ADD amplifier/duration to the configured track length.
 *
 * <p>Unlike the rest of the command argument handling this is Bukkit-bound (the registry lookup
 * needs a running server registry), so it is deliberately not unit-tested as a pure function; the
 * surrounding parsing is covered by {@link AddStepArgumentsTest} and the callers report the
 * unknown-effect message via {@link EditFeedback#invalidEffect}.</p>
 */
final class EditEffects {
    private EditEffects() {
    }

    /** The resolved spec, or {@code null} when {@code key} is not a known effect. */
    static EffectSpec resolve(EffectAction action, String key, int amplifier, int duration, int maxTrackTicks) {
        PotionEffectType type = CameraEffects.resolveEffect(key);
        if (type == null) {
            return null;
        }
        int safeAmplifier;
        int safeDuration;
        if (action == EffectAction.ADD) {
            safeAmplifier = Math.max(0, Math.min(255, amplifier));
            safeDuration = Math.max(0, Math.min(maxTrackTicks, duration));
        } else {
            safeAmplifier = 0;
            safeDuration = 0;
        }
        return new EffectSpec(type, type.getKey().toString(), action, safeAmplifier, safeDuration);
    }
}
