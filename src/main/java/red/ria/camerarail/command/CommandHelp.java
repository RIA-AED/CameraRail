package red.ria.camerarail.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import red.ria.camerarail.message.ChatText;
import red.ria.camerarail.message.Messages;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Draws {@code /camerarail help} and the editor's command reference.
 *
 * <p>Wording lives in {@code messages.yml}; the layout lives here so every command gets the same
 * shape: a body rail, an aligned command column, a summary column, and click-to-fill on each
 * entry. Lines stay inside the vanilla chat width, and only the commands the sender may use are
 * listed.</p>
 */
public final class CommandHelp {
    /** Where a help page belongs: the root command or the in-game editor. */
    public enum Scope {
        ROOT("root"),
        EDITOR("editor");

        private final String key;

        Scope(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private enum Group {
        TRACK("track"),
        PLAYBACK("playback"),
        EDITOR("editor"),
        MANAGE("manage"),
        SETTINGS("settings"),
        ENTRY("entry"),
        STEP("step"),
        MOVE("move"),
        PATH("path"),
        HOLD("hold"),
        WAIT("wait"),
        CUT("cut"),
        EFFECT("effect"),
        TITLE("title"),
        POINT("point"),
        STEP_OR_POINT("step-point"),
        PREVIEW("preview"),
        SAVE("save");

        private final String key;

        Group(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** What a stateless editor command addresses, used to render the usage line. */
    private enum Level {
        NONE,
        TRACK,
        STEP,
        POINT,
        TRACK_OR_STEP,
        STEP_OR_POINT,
        ANY
    }

    private record Entry(String name, String syntax, Group group, List<String> permissions,
                         List<String> aliases, Level level, String usage) {
        Entry(String name, String syntax, Group group, List<String> permissions) {
            this(name, syntax, group, permissions, List.of(), Level.NONE, null);
        }

        Entry(String name, String syntax, Group group, List<String> permissions, Level level) {
            this(name, syntax, group, permissions, List.of(), level, null);
        }

        Entry(String name, String syntax, Group group, List<String> permissions, List<String> aliases) {
            this(name, syntax, group, permissions, aliases, Level.NONE, null);
        }

        /** @param usage the part after the target, when the overview {@code syntax} is not a command */
        Entry(String name, String syntax, Group group, List<String> permissions, Level level, String usage) {
            this(name, syntax, group, permissions, List.of(), level, usage);
        }

        boolean matches(String token) {
            return name.equalsIgnoreCase(token)
                    || aliases.stream().anyMatch(alias -> alias.equalsIgnoreCase(token));
        }
    }

    private static final List<String> COMMAND = List.of("camerarail.command");
    private static final List<String> PLAY = List.of("camerarail.play");
    private static final List<String> STOP = List.of("camerarail.stop");
    private static final List<String> ADVANCE = List.of("camerarail.advance");
    private static final List<String> EDIT = List.of("camerarail.edit");
    private static final List<String> MANAGE = List.of("camerarail.manage", "camerarail.edit");
    private static final List<String> RELOAD = List.of("camerarail.reload");

    /** Shown in this order; a new group starts a new row. */
    private static final List<Entry> ROOT_ENTRIES = List.of(
            new Entry("list", "list", Group.TRACK, COMMAND),
            new Entry("info", "info <id>", Group.TRACK, COMMAND),
            new Entry("play", "play <id> [玩家]", Group.PLAYBACK, PLAY),
            new Entry("stop", "stop [玩家]", Group.PLAYBACK, STOP),
            new Entry("advance", "advance <信号|*> [玩家]", Group.PLAYBACK, ADVANCE),
            new Entry("edit", "edit <目标> <动作> [参数]", Group.EDITOR, EDIT),
            new Entry("editor", "editor [<轨道>|close]", Group.EDITOR, EDIT),
            new Entry("tools", "tools", Group.EDITOR, EDIT),
            new Entry("create", "create <id> [名称]", Group.MANAGE, MANAGE),
            new Entry("delete", "delete <id> confirm", Group.MANAGE, MANAGE),
            new Entry("reload", "reload", Group.MANAGE, RELOAD)
    );

    /**
     * The stateless editor commands, grouped exactly like the Dialog screens: track settings,
     * entry effects, the step collection, one row per step type, path points, then the session
     * actions. The target prefix is explained once in the overview instead of on every row.
     */
    private static final List<Entry> EDITOR_ENTRIES = List.of(
            new Entry("display", "display <名称>", Group.SETTINGS, EDIT, Level.TRACK),
            new Entry("description", "description [说明]", Group.SETTINGS, EDIT, Level.TRACK),
            new Entry("start", "start [x y z [yaw pitch]]", Group.SETTINGS, EDIT, Level.TRACK),
            new Entry("exit", "exit [x y z [yaw pitch]]", Group.SETTINGS, EDIT, Level.TRACK),
            new Entry("exit-mode", "exit-mode <模式>", Group.SETTINGS, EDIT, Level.TRACK),
            new Entry("sway", "sway <预设> [周期]", Group.SETTINGS, EDIT, Level.TRACK),
            new Entry("follow", "follow <true|false>", Group.SETTINGS, EDIT, Level.TRACK),
            new Entry("id", "id <新 id> confirm", Group.MANAGE, MANAGE, Level.TRACK),
            new Entry("entry", "entry <list|add|set|remove>", Group.ENTRY, EDIT, Level.TRACK),
            new Entry("list", "list", Group.STEP, EDIT, Level.TRACK),
            new Entry("add", "add <类型> [参数]", Group.STEP, EDIT, Level.TRACK),
            new Entry("remove", "remove", Group.STEP, EDIT, Level.STEP_OR_POINT),
            new Entry("duplicate", "duplicate", Group.STEP, EDIT, Level.STEP),
            new Entry("up", "up", Group.STEP, EDIT, Level.STEP_OR_POINT),
            new Entry("down", "down", Group.STEP, EDIT, Level.STEP_OR_POINT),
            new Entry("move", "move: here|pos|duration|easing|sway", Group.MOVE, EDIT, Level.STEP,
                    "<here|pos|duration|easing|sway>"),
            new Entry("path", "path: duration|easing|sway|point", Group.PATH, EDIT, Level.STEP,
                    "<duration|easing|sway|point>"),
            new Entry("hold", "hold: duration|sway", Group.HOLD, EDIT, Level.STEP,
                    "<duration|sway>"),
            new Entry("wait", "wait: signal|timeout|sway", Group.WAIT, EDIT, Level.STEP,
                    "<signal|timeout|sway>"),
            new Entry("cut", "cut: here|pos|duration|sway", Group.CUT, EDIT, Level.STEP,
                    "<here|pos|duration|sway>"),
            new Entry("effect", "effect <add|remove> <效果>", Group.EFFECT, EDIT, Level.STEP),
            new Entry("title", "title|subtitle|fade", Group.TITLE, EDIT, Level.STEP,
                    "<title [文本]|subtitle [文本]|fade>"),
            new Entry("point", "point <list|add>", Group.POINT, EDIT, Level.STEP),
            new Entry("here", "here", Group.STEP_OR_POINT, EDIT, Level.STEP_OR_POINT),
            new Entry("pos", "pos <x> <y> <z> [yaw] [pitch]", Group.STEP_OR_POINT, EDIT, Level.STEP_OR_POINT),
            new Entry("insert", "insert", Group.POINT, EDIT, Level.POINT),
            new Entry("preview", "preview", Group.PREVIEW, EDIT, Level.TRACK_OR_STEP),
            new Entry("stop", "stop", Group.PREVIEW, EDIT, Level.ANY),
            new Entry("undo", "undo", Group.SAVE, EDIT, Level.TRACK),
            new Entry("save", "save", Group.SAVE, EDIT, Level.TRACK)
    );

    /**
     * Step fields that get their own detail page but are never a top-level command row: they
     * always take a step target ({@code edit <轨道:步骤> duration 120}). They are reachable
     * through {@code /camerarail help edit <字段>} and tab completion, but not the overview.
     */
    private static final List<Entry> EDITOR_TOPICS = List.of(
            new Entry("duration", "duration <tick>", Group.STEP, EDIT, Level.STEP),
            new Entry("easing", "easing <曲线>", Group.STEP, EDIT, Level.STEP),
            new Entry("signal", "signal <信号>", Group.STEP, EDIT, Level.STEP),
            new Entry("timeout", "timeout <tick>", Group.STEP, EDIT, Level.STEP),
            new Entry("subtitle", "subtitle [文本]", Group.TITLE, EDIT, Level.STEP),
            new Entry("fade", "fade <淡入> <停留> <淡出>", Group.TITLE, EDIT, Level.STEP)
    );

    private static final Component RAIL = Component.text("▏", NamedTextColor.DARK_GRAY);
    private static final Component SEPARATOR = Component.text(" · ", NamedTextColor.DARK_GRAY);
    private static final int RAIL_WIDTH = ChatText.width("▏");
    private static final int BODY_PADDING = 4;
    private static final int GROUP_GAP = 6;
    private static final int COLUMN_GAP = 12;
    private static final int MAX_USAGE_COLUMN = 150;
    /** Content width before the chat edge; spaces can overshoot a column by up to three pixels. */
    private static final int MAX_LINE_WIDTH = ChatText.CHAT_WIDTH - BODY_PADDING;
    private static final List<String> DETAIL_LABELS =
            List.of("usage", "args", "detail", "permission", "related");

    private final Messages messages;
    private final String label;
    private final Predicate<String> permissions;
    private final Component indent;
    private final int bodyStart;

    public CommandHelp(Messages messages, String label, Predicate<String> permissions) {
        this.messages = messages;
        this.label = label;
        this.permissions = permissions;
        int prefixWidth = ChatText.width(messages.prefixComponent());
        this.indent = Component.text(ChatText.spacesTo(0, prefixWidth + 2));
        this.bodyStart = ChatText.width(indent) + RAIL_WIDTH + ChatText.SPACE_WIDTH;
    }

    /** Command names (including aliases) the sender may use, for tab completion. */
    public List<String> names(Scope scope) {
        List<String> names = new ArrayList<>();
        for (Entry entry : visible(scope)) {
            names.add(entry.name());
            names.addAll(entry.aliases());
        }
        if (scope == Scope.EDITOR) {
            for (Entry topic : EDITOR_TOPICS) {
                if (permitted(topic)) {
                    names.add(topic.name());
                    names.addAll(topic.aliases());
                }
            }
        }
        return names;
    }

    public void sendOverview(CommandSender sender, Scope scope) {
        for (Component line : overview(scope)) {
            sender.sendMessage(line);
        }
    }

    public void sendDetail(CommandSender sender, Scope scope, String name) {
        List<Component> lines = detail(scope, name);
        if (lines.isEmpty()) {
            messages.send(sender, "help.not-found", "command", name, "usage", commandRoot(scope) + " help");
            return;
        }
        for (Component line : lines) {
            sender.sendMessage(line);
        }
    }

    // ------------------------------------------------------------------ overview

    List<Component> overview(Scope scope) {
        List<Component> lines = new ArrayList<>();
        lines.add(messages.prefixed("help.title",
                "scope", text("help.scope." + scope.key()),
                "command", commandRoot(scope)));
        if (scope == Scope.EDITOR) {
            // The stateless actions all take the same target prefix, so it is explained once.
            lines.add(body(messages.component("help.target-hint")));
            lines.add(body(messages.component("help.target-hint-escape")));
        }
        List<Entry> visible = visible(scope);
        if (visible.isEmpty()) {
            lines.add(body(messages.component("help.no-commands")));
            return lines;
        }
        if (scope == Scope.ROOT) {
            table(visible, lines);
        } else {
            packed(visible, lines);
        }
        lines.add(body(messages.component("help.footer." + scope.key(), "label", label)));
        return lines;
    }

    /** One command per row with an aligned summary column; used for the few root commands. */
    private void table(List<Entry> entries, List<Component> lines) {
        int labelWidth = labelWidth(entries);
        int usageColumn = usageColumn(entries);
        int usageStart = bodyStart + labelWidth + GROUP_GAP;
        int summaryStart = usageStart + usageColumn + COLUMN_GAP;
        Group current = null;
        for (Entry entry : entries) {
            Component line = lineStart();
            if (entry.group() != current) {
                line = line.append(groupLabel(entry.group()));
                current = entry.group();
            }
            line = ChatText.padTo(line, usageStart);
            Component usage = Component.text(entry.syntax(), NamedTextColor.WHITE);
            line = line.append(usage);
            if (ChatText.width(usage) > usageColumn) {
                line = line.append(Component.text("  "));
            } else {
                line = ChatText.padTo(line, summaryStart);
            }
            line = line.append(summary(Scope.ROOT, entry));
            lines.add(decorate(line, Scope.ROOT, entry));
        }
    }

    /** Commands packed into rows of {@code command · command}; used for the many editor commands. */
    private void packed(List<Entry> entries, List<Component> lines) {
        int labelWidth = labelWidth(entries);
        int itemsStart = bodyStart + labelWidth + GROUP_GAP;
        int budget = MAX_LINE_WIDTH - itemsStart - ChatText.SPACE_WIDTH;
        int separatorWidth = ChatText.width(SEPARATOR);
        Group current = null;
        Component line = null;
        int used = 0;
        for (Entry entry : entries) {
            int itemWidth = ChatText.width(entry.syntax());
            boolean newGroup = entry.group() != current;
            if (line == null || newGroup) {
                if (line != null) {
                    lines.add(line);
                }
                line = lineStart();
                if (newGroup) {
                    line = line.append(groupLabel(entry.group()));
                }
                line = ChatText.padTo(line, itemsStart).append(usageItem(Scope.EDITOR, entry));
                used = itemWidth;
                current = entry.group();
            } else if (used + separatorWidth + itemWidth > budget) {
                lines.add(line);
                line = ChatText.padTo(lineStart(), itemsStart).append(usageItem(Scope.EDITOR, entry));
                used = itemWidth;
            } else {
                line = line.append(SEPARATOR).append(usageItem(Scope.EDITOR, entry));
                used += separatorWidth + itemWidth;
            }
        }
        if (line != null) {
            lines.add(line);
        }
    }

    // ------------------------------------------------------------------ detail

    List<Component> detail(Scope scope, String name) {
        Entry entry = find(scope, name);
        if (entry == null) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(messages.prefixed("help.detail.title",
                "name", entry.name(),
                "summary", text(summaryKey(scope, entry))));
        int labelWidth = detailLabelWidth();
        int valueWidth = MAX_LINE_WIDTH - bodyStart - labelWidth - GROUP_GAP - ChatText.SPACE_WIDTH;
        if (!permitted(entry)) {
            lines.add(body(messages.component("help.detail.denied")));
        }
        boolean first = true;
        for (String usage : ChatText.wrap(fullUsage(scope, entry), valueWidth)) {
            Component line = Component.text(usage, NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.suggestCommand(suggestion(scope, entry)))
                    .hoverEvent(HoverEvent.showText(messages.component("help.hover.fill")));
            lines.add(detailLine(labelWidth, first ? "usage" : null, line));
            first = false;
        }
        first = true;
        for (String arg : messages.strings(argsKey(scope, entry), "label", label)) {
            for (String wrapped : ChatText.wrap(arg, valueWidth)) {
                lines.add(detailLine(labelWidth, first ? "args" : null,
                        Component.text(wrapped, NamedTextColor.GRAY)));
                first = false;
            }
        }
        String detail = messages.string(detailKey(scope, entry), "label", label);
        if (detail != null && !detail.isBlank()) {
            first = true;
            for (String wrapped : ChatText.wrap(detail, valueWidth)) {
                lines.add(detailLine(labelWidth, first ? "detail" : null,
                        Component.text(wrapped, NamedTextColor.GRAY)));
                first = false;
            }
        }
        lines.add(detailLine(labelWidth, "permission", Component.text(
                String.join(" + ", entry.permissions()), NamedTextColor.DARK_GRAY)));
        Component related = related(scope, entry);
        if (related != null) {
            lines.add(detailLine(labelWidth, "related", related));
        }
        return lines;
    }

    private Component related(Scope scope, Entry entry) {
        Component joined = Component.empty();
        int count = 0;
        for (Entry other : visible(scope)) {
            if (other == entry || other.group() != entry.group() || count >= 4) {
                continue;
            }
            if (count > 0) {
                joined = joined.append(SEPARATOR);
            }
            joined = joined.append(Component.text(other.name(), NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.suggestCommand(suggestion(scope, other)))
                    .hoverEvent(HoverEvent.showText(hover(scope, other))));
            count++;
        }
        return count == 0 ? null : joined;
    }

    private Component detailLine(int labelWidth, String labelKey, Component value) {
        Component line = lineStart();
        if (labelKey != null) {
            line = line.append(messages.component("help.labels." + labelKey).colorIfAbsent(NamedTextColor.GRAY));
        }
        return ChatText.padTo(line, bodyStart + labelWidth + GROUP_GAP).append(value);
    }

    // ------------------------------------------------------------------ pieces

    private Component lineStart() {
        return Component.empty().append(indent).append(RAIL).append(Component.text(" "));
    }

    private Component body(Component content) {
        return lineStart().append(content.colorIfAbsent(NamedTextColor.GRAY));
    }

    private Component groupLabel(Group group) {
        return messages.component("help.group." + group.key()).colorIfAbsent(NamedTextColor.GRAY);
    }

    private Component summary(Scope scope, Entry entry) {
        return messages.component(summaryKey(scope, entry)).colorIfAbsent(NamedTextColor.GRAY);
    }

    private Component usageItem(Scope scope, Entry entry) {
        return decorate(Component.text(entry.syntax(), NamedTextColor.WHITE), scope, entry);
    }

    private Component decorate(Component component, Scope scope, Entry entry) {
        return decorateDeep(component,
                ClickEvent.suggestCommand(suggestion(scope, entry)),
                HoverEvent.showText(hover(scope, entry)));
    }

    /** Applies the interaction to every text run, since clients do not always inherit events. */
    private static Component decorateDeep(Component component, ClickEvent click, HoverEvent<?> hover) {
        List<Component> children = new ArrayList<>(component.children().size());
        for (Component child : component.children()) {
            children.add(decorateDeep(child, click, hover));
        }
        return component.children(children).clickEvent(click).hoverEvent(hover);
    }

    private Component hover(Scope scope, Entry entry) {
        return summary(scope, entry)
                .append(Component.newline())
                .append(Component.text(fullUsage(scope, entry), NamedTextColor.DARK_GRAY))
                .append(Component.newline())
                .append(messages.component("help.hover.fill").colorIfAbsent(NamedTextColor.DARK_GRAY));
    }

    private int labelWidth(List<Entry> entries) {
        int width = 0;
        Group current = null;
        for (Entry entry : entries) {
            if (entry.group() != current) {
                width = Math.max(width, ChatText.width(groupLabel(entry.group())));
                current = entry.group();
            }
        }
        return width;
    }

    private int usageColumn(List<Entry> entries) {
        int width = 0;
        for (Entry entry : entries) {
            width = Math.max(width, ChatText.width(entry.syntax()));
        }
        return Math.min(width, MAX_USAGE_COLUMN);
    }

    private int detailLabelWidth() {
        int width = 0;
        for (String label : DETAIL_LABELS) {
            width = Math.max(width, ChatText.width(messages.component("help.labels." + label)));
        }
        return width;
    }

    private List<Entry> visible(Scope scope) {
        List<Entry> visible = new ArrayList<>();
        for (Entry entry : entries(scope)) {
            if (permitted(entry)) {
                visible.add(entry);
            }
        }
        return visible;
    }

    private boolean permitted(Entry entry) {
        return entry.permissions().stream().allMatch(permissions);
    }

    private Entry find(Scope scope, String name) {
        for (Entry entry : entries(scope)) {
            if (entry.matches(name)) {
                return entry;
            }
        }
        if (scope == Scope.EDITOR) {
            for (Entry topic : EDITOR_TOPICS) {
                if (topic.matches(name)) {
                    return topic;
                }
            }
        }
        return null;
    }

    private String commandRoot(Scope scope) {
        return "/" + label + (scope == Scope.EDITOR ? " edit" : "");
    }

    private String fullUsage(Scope scope, Entry entry) {
        String syntax = entry.usage() == null ? entry.syntax() : entry.usage();
        if (scope == Scope.EDITOR && entry.level() != Level.NONE) {
            return commandRoot(scope) + " " + targetHint(entry.level()) + " " + syntax;
        }
        return commandRoot(scope) + " " + syntax;
    }

    private String suggestion(Scope scope, Entry entry) {
        // Stop before the first argument: the player types it instead of deleting a placeholder.
        // Stateless editor commands start with their target, so the click only fills the root.
        if (scope == Scope.EDITOR && entry.level() != Level.NONE) {
            return commandRoot(scope) + " ";
        }
        return commandRoot(scope) + " " + entry.name() + " ";
    }

    private static String targetHint(Level level) {
        return switch (level) {
            case TRACK -> "<轨道>";
            case STEP -> "<轨道:步骤>";
            case POINT -> "<轨道:步骤:路径点>";
            case TRACK_OR_STEP -> "<轨道[:步骤]>";
            case STEP_OR_POINT -> "<轨道:步骤[:路径点]>";
            case ANY -> "<轨道[:步骤[:路径点]]>";
            case NONE -> "";
        };
    }

    private String summaryKey(Scope scope, Entry entry) {
        return "help.commands." + scope.key() + "." + entry.name() + ".summary";
    }

    private String detailKey(Scope scope, Entry entry) {
        return "help.commands." + scope.key() + "." + entry.name() + ".detail";
    }

    private String argsKey(Scope scope, Entry entry) {
        return "help.commands." + scope.key() + "." + entry.name() + ".args";
    }

    private String text(String path) {
        String value = messages.string(path);
        return value == null ? path : value;
    }

    private static List<Entry> entries(Scope scope) {
        return scope == Scope.ROOT ? ROOT_ENTRIES : EDITOR_ENTRIES;
    }
}
