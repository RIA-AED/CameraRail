package red.ria.camerarail.command;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import red.ria.camerarail.config.TrackRepository;
import red.ria.camerarail.editor.EditTarget;
import red.ria.camerarail.message.Messages;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.runtime.CameraEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Tab completion for the whole command surface.
 *
 * <p>The module owns the suggestion vocabularies and the target-aware completion rules: the root
 * commands, the {@code edit} target/action/value positions, {@code help} pages, the {@code editor}
 * argument and the {@code advance} signals. Permission filtering and {@link CommandHelp} usage are
 * identical to what the help command itself renders, so completion can never offer something the
 * sender is not allowed to see.</p>
 */
final class EditCompletions {
    private static final List<String> EASING_NAMES = List.of("linear", "ease-in", "ease-out",
            "ease-in-out", "smoothstep", "smootherstep", "sine");
    private static final List<String> STEP_SWAY_NAMES = List.of("inherit", "off", "gentle", "natural", "strong");
    private static final List<String> TRACK_SWAY_NAMES = List.of("off", "gentle", "natural", "strong");
    private static final List<String> EFFECT_NAMES = List.of("blindness", "darkness", "slowness",
            "night_vision", "nausea", "speed", "invisibility", "glowing");
    private static final List<String> EXIT_MODE_NAMES = List.of("animation-end", "pre-entry", "custom");
    private static final List<String> ADD_TYPES = List.of("move", "path", "hold", "wait", "cut", "effect", "title");
    private static final List<String> ENTRY_ACTIONS = List.of("list", "add", "set", "remove");

    private final CameraEngine engine;
    private final TrackRepository repository;
    private final Supplier<Messages> messages;

    public EditCompletions(CameraEngine engine, TrackRepository repository, Supplier<Messages> messages) {
        this.engine = engine;
        this.repository = repository;
        this.messages = messages;
    }

    /** The completion list for {@code args}; an empty list means "nothing to suggest here". */
    public List<String> complete(CommandSender sender, String[] args, String alias) {
        if (!sender.hasPermission("camerarail.command")) {
            return List.of();
        }
        // The alias is only used for rendering; names() (all this module reads) does not use it.
        CommandHelp help = new CommandHelp(messages.get(), alias, sender::hasPermission);
        if (args.length == 1) {
            List<String> names = new ArrayList<>();
            names.add("help");
            names.addAll(help.names(CommandHelp.Scope.ROOT));
            return partial(args[0], names);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("edit")) {
            return sender.hasPermission("camerarail.edit") ? editCompletion(args) : List.of();
        }
        if (args.length == 2) {
            return switch (action) {
                case "play", "info", "delete" -> partial(args[1], trackIds());
                case "stop" -> partial(args[1], playerNames());
                case "advance" -> partial(args[1], advanceSuggestions(sender));
                case "help" -> partial(args[1], help.names(CommandHelp.Scope.ROOT));
                case "editor" -> partial(args[1], editorArguments());
                default -> List.of();
            };
        }
        if (args.length == 3) {
            if (action.equals("play") || action.equals("stop") || action.equals("advance")) {
                return partial(args[2], playerNames());
            }
            if (action.equals("delete")) {
                return partial(args[2], List.of("confirm"));
            }
            if (action.equals("help") && args[1].equalsIgnoreCase("edit")) {
                return partial(args[2], help.names(CommandHelp.Scope.EDITOR));
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------------ edit targets

    private List<String> editCompletion(String[] args) {
        String token = args.length >= 2 ? args[1] : "";
        if (args.length == 2) {
            if (token.contains(":")) {
                return targetSuffixes(token, trackOf(token));
            }
            // Offer "id:" next to an exact track id so the client can continue into step numbers.
            List<String> candidates = new ArrayList<>(trackIds());
            for (String id : trackIds()) {
                if (id.equalsIgnoreCase(token)) {
                    candidates.add(id + ":");
                }
            }
            return partial(token, candidates);
        }
        EditTarget target = EditTarget.parse(token);
        if (target == null) {
            return List.of();
        }
        CameraTrack track = repository.get(target.trackId());
        if (track == null) {
            return List.of();
        }
        if (target.hasPoint()) {
            // Only offer point actions when the target really addresses an existing path point.
            PathStep path = StepFields.pathStep(track, target.stepIndex());
            if (path == null || target.pointIndex() >= path.points().size()) {
                return List.of();
            }
            return args.length == 3 ? partial(args[2], EditActions.POINT_ACTIONS) : List.of();
        }
        if (target.hasStep()) {
            if (target.stepIndex() >= track.steps().size()) {
                return List.of();
            }
            Step step = track.steps().get(target.stepIndex());
            List<String> actions = EditActions.actions(step);
            if (args.length == 3) {
                return partial(args[2], actions);
            }
            if (!actions.contains(args[2].toLowerCase(Locale.ROOT))) {
                return List.of();
            }
            return stepCompletion(step, args);
        }
        if (args.length == 3) {
            return partial(args[2], EditActions.TRACK_ACTIONS);
        }
        if (!EditActions.TRACK_ACTIONS.contains(args[2].toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        return trackCompletion(track, args);
    }

    /** The track named before the first {@code :} of the token, or null. */
    private CameraTrack trackOf(String token) {
        int first = token.indexOf(':');
        return repository.get(token.substring(0, first));
    }

    private List<String> stepCompletion(Step step, String[] args) {
        String action = args[2].toLowerCase(Locale.ROOT);
        int current = args.length - 1;
        return switch (action) {
            case "easing" -> current == 3 ? partial(args[current], EASING_NAMES) : List.of();
            case "sway" -> current == 3 ? partial(args[current], STEP_SWAY_NAMES) : List.of();
            case "point" -> current == 3 ? partial(args[current], List.of("list", "add")) : List.of();
            case "effect" -> {
                if (current == 3) {
                    yield partial(args[current], List.of("add", "remove"));
                }
                if (current == 4) {
                    yield partial(args[current], EFFECT_NAMES);
                }
                yield List.of();
            }
            case "signal" -> current == 3 ? partial(args[current], allWaitSignals()) : List.of();
            default -> List.of();
        };
    }

    private List<String> trackCompletion(CameraTrack track, String[] args) {
        String action = args[2].toLowerCase(Locale.ROOT);
        int current = args.length - 1;
        return switch (action) {
            case "sway" -> current == 3 ? partial(args[current], TRACK_SWAY_NAMES) : List.of();
            case "exit-mode" -> current == 3 ? partial(args[current], EXIT_MODE_NAMES) : List.of();
            case "follow" -> current == 3 ? partial(args[current], List.of("true", "false")) : List.of();
            case "id" -> current == 4 ? partial(args[current], List.of("confirm")) : List.of();
            case "add" -> addCompletion(args);
            case "entry" -> entryCompletion(track, args);
            default -> List.of();
        };
    }

    private List<String> addCompletion(String[] args) {
        int current = args.length - 1;
        if (current == 3) {
            return partial(args[current], ADD_TYPES);
        }
        String type = args[3].toLowerCase(Locale.ROOT);
        return switch (type) {
            case "effect" -> {
                if (current == 4) {
                    yield partial(args[current], List.of("add", "remove"));
                }
                if (current == 5) {
                    yield partial(args[current], EFFECT_NAMES);
                }
                yield List.of();
            }
            case "wait" -> current == 4 ? partial(args[current], allWaitSignals()) : List.of();
            default -> List.of();
        };
    }

    private List<String> entryCompletion(CameraTrack track, String[] args) {
        int current = args.length - 1;
        if (current == 3) {
            return partial(args[current], ENTRY_ACTIONS);
        }
        String sub = args[3].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "add" -> current == 4 ? partial(args[current], EFFECT_NAMES) : List.of();
            case "set" -> {
                if (current == 4) {
                    yield partial(args[current], indexNumbers(track.entryEffects().size()));
                }
                yield current == 5 ? partial(args[current], EFFECT_NAMES) : List.of();
            }
            case "remove" -> current == 4
                    ? partial(args[current], indexNumbers(track.entryEffects().size())) : List.of();
            default -> List.of();
        };
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Completes {@code track:} with step numbers and {@code track:step:} with point numbers.
     * Package-private and track-passed so it can be tested without a repository.
     */
    static List<String> targetSuffixes(String token, CameraTrack track) {
        if (track == null) {
            return List.of();
        }
        int first = token.indexOf(':');
        int second = token.indexOf(':', first + 1);
        if (second < 0) {
            String prefix = token.substring(0, first + 1);
            List<String> numbers = new ArrayList<>();
            for (int i = 1; i <= track.steps().size(); i++) {
                numbers.add(prefix + i);
            }
            return partial(token, numbers);
        }
        EditTarget stepTarget = EditTarget.parse(token.substring(0, second));
        PathStep path = stepTarget == null ? null : StepFields.pathStep(track, stepTarget.stepIndex());
        if (path == null) {
            return List.of();
        }
        String prefix = token.substring(0, second + 1);
        List<String> numbers = new ArrayList<>();
        for (int i = 1; i <= path.points().size(); i++) {
            numbers.add(prefix + i);
        }
        return partial(token, numbers);
    }

    private static List<String> indexNumbers(int count) {
        List<String> numbers = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            numbers.add(String.valueOf(i));
        }
        return numbers;
    }

    private List<String> trackIds() {
        return repository.all().keySet().stream().sorted().toList();
    }

    /** Track ids plus {@code close} for {@code /camerarail editor [<轨道>|close]}. */
    private List<String> editorArguments() {
        List<String> values = new ArrayList<>(trackIds());
        values.add("close");
        return values;
    }

    private List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
    }

    private List<String> advanceSuggestions(CommandSender sender) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("*");
        if (sender instanceof Player player) {
            engine.waitingSignal(player).ifPresent(signals -> {
                if (!suggestions.contains(signals)) {
                    suggestions.add(signals);
                }
            });
        }
        for (String signal : allWaitSignals()) {
            if (!suggestions.contains(signal)) {
                suggestions.add(signal);
            }
        }
        return suggestions;
    }

    private List<String> allWaitSignals() {
        List<String> signals = new ArrayList<>();
        for (CameraTrack track : repository.all().values()) {
            signals.addAll(track.waitSignals());
        }
        signals.sort(String::compareTo);
        return signals;
    }

    /** Case-insensitive prefix filter; package-private so the ordering rule is unit-testable. */
    static List<String> partial(String input, List<String> options) {
        String normalized = input.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(normalized))
                .toList();
    }
}
