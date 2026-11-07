package red.ria.camerarail.model;

/**
 * Applies or removes a potion effect.
 */
public record EffectStep(EffectSpec effect) implements Step {
    public EffectStep {
        if (effect == null) {
            throw new IllegalArgumentException("Effect specification is required");
        }
    }

    @Override
    public StepType type() {
        return StepType.EFFECT;
    }

    @Override
    public String describe() {
        return "效果 " + effect.describe();
    }
}
