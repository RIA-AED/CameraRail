package red.ria.camerarail.model;

/**
 * Effects whose camera-facing rendering depends on the fake-player camera.
 *
 * <p>Without PacketEvents the camera is the armor stand. {@code nausea} and the lightmap
 * still work on the real player, but FOV, blindness fog/sky and the slowness/speed FOV
 * changes need a player camera entity. Admin-facing warnings use this set so a track that
 * only uses {@code nausea} is not flagged.</p>
 */
public final class CameraEffects {
    private CameraEffects() {
    }

    /** Whether this effect needs a player camera entity to render as designed. */
    public static boolean isCameraDependent(String key) {
        if (key == null) {
            return false;
        }
        return switch (key) {
            case "blindness", "darkness", "night_vision", "slowness", "speed" -> true;
            default -> false;
        };
    }

    public static boolean isCameraDependent(EffectSpec effect) {
        return effect != null && isCameraDependent(effect.type().getKey().getKey());
    }

    public static boolean isDarkness(EffectSpec effect) {
        return effect != null && "darkness".equals(effect.type().getKey().getKey());
    }

    /** Whether a track uses any effect that falls back to the armor-stand limitations. */
    public static boolean usesCameraDependentEffects(CameraTrack track) {
        if (track == null) {
            return false;
        }
        for (EffectSpec effect : track.entryEffects()) {
            if (isCameraDependent(effect)) {
                return true;
            }
        }
        for (Step step : track.steps()) {
            if (step instanceof EffectStep effectStep && isCameraDependent(effectStep.effect())) {
                return true;
            }
        }
        return false;
    }
}
