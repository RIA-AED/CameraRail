package red.ria.camerarail.command;

import red.ria.camerarail.config.RailConfig;
import red.ria.camerarail.config.TrackRepository;
import red.ria.camerarail.editor.EditService;
import red.ria.camerarail.message.Messages;

import java.util.function.Supplier;

/**
 * The dependencies one edit command invocation shares between the layer handlers.
 *
 * <p>Deliberately small: the services a handler may call, the repository/config it validates
 * against, the message source and the single {@link EditFeedback} built for this invocation (so
 * every hint and click uses the alias the player typed). Adding unrelated dependencies here would
 * turn the context into a god object; handlers that need something else should take it as an
 * argument or compute it locally.</p>
 */
record EditContext(EditService service, TrackRepository repository, Supplier<RailConfig> config,
                   Supplier<Messages> messages, EditFeedback feedback, String label) {
}
