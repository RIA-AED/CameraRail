package red.ria.camerarail.model;

/**
 * A step in a camera track. Steps are executed in order; camera steps take time, while
 * effect/title steps are instantaneous.
 */
public sealed interface Step permits CameraStep, EffectStep, TitleStep {
    StepType type();

    /**
     * Short description used in command output.
     */
    String describe();
}
