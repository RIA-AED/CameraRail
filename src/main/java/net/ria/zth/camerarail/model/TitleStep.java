package net.ria.zth.camerarail.model;

/**
 * Sends an Adventure title. Text is MiniMessage. Timings are in ticks.
 */
public record TitleStep(String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks)
        implements Step {
    public TitleStep {
        title = title == null ? "" : title;
        subtitle = subtitle == null ? "" : subtitle;
        if (fadeInTicks < 0 || stayTicks < 0 || fadeOutTicks < 0) {
            throw new IllegalArgumentException("Title timings cannot be negative");
        }
    }

    @Override
    public StepType type() {
        return StepType.TITLE;
    }

    @Override
    public String describe() {
        String text = title.isBlank() ? subtitle : title;
        return "标题 " + (text.isBlank() ? "(空)" : text);
    }
}
