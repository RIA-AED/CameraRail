package red.ria.camerarail.message;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * The strict {@link MiniMessage} instance shared by everything that validates or renders
 * MiniMessage text.
 *
 * <p>Strict parsing rejects an unbalanced tag instead of letting it reach a client, and sharing
 * one instance keeps the YAML parser, the structural rules and the editors on the same rules.</p>
 */
public final class MiniMessages {
    private static final MiniMessage STRICT = MiniMessage.builder().strict(true).build();

    private MiniMessages() {
    }

    /** The strict parser used for titles, subtitles and any other MiniMessage text. */
    public static MiniMessage strict() {
        return STRICT;
    }
}
