package red.ria.camerarail.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import red.ria.camerarail.message.ChatText;
import red.ria.camerarail.support.TestMessages;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandHelpTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Predicate<String> ADMIN = permission -> true;
    private static final Predicate<String> VIEWER = "camerarail.command"::equals;
    private static final int MAX_LINE_WIDTH = ChatText.CHAT_WIDTH - 4;
    private static final List<String> EDITOR_COMMANDS = List.of(
            "display", "description", "start", "exit", "exit-mode", "sway", "follow", "id",
            "entry", "list", "add", "remove", "duplicate", "up", "down",
            "move", "path", "hold", "wait", "cut", "effect", "title",
            "point", "here", "pos", "insert", "preview", "stop", "undo", "save");
    /** Detail-only field topics: not listed in the overview but reachable via help edit <字段>. */
    private static final List<String> EDITOR_TOPICS = List.of(
            "duration", "easing", "signal", "timeout", "subtitle", "fade");

    @Test
    void overviewsAndDetailsStayInsideTheChatWidth() {
        for (String label : List.of("camerarail", "camera-rail")) {
            for (Predicate<String> permissions : List.of(ADMIN, VIEWER, ADMIN.negate())) {
                CommandHelp help = help(label, permissions);
                List<Component> lines = new ArrayList<>();
                for (CommandHelp.Scope scope : CommandHelp.Scope.values()) {
                    lines.addAll(help.overview(scope));
                    for (String name : help.names(scope)) {
                        lines.addAll(help.detail(scope, name));
                    }
                }
                for (Component line : lines) {
                    assertTrue(ChatText.width(line) <= MAX_LINE_WIDTH,
                            "line too wide (" + ChatText.width(line) + "): " + PLAIN.serialize(line));
                    String text = PLAIN.serialize(line);
                    assertFalse(text.endsWith(" "), "trailing space: |" + text + "|");
                    assertFalse(text.isBlank(), "blank line");
                }
            }
        }
    }

    @Test
    void rootOverviewListsEveryRootCommand() {
        List<String> lines = plain(help("camerarail", ADMIN).overview(CommandHelp.Scope.ROOT));
        assertEquals(13, lines.size());
        assertTrue(lines.get(0).contains("CameraRail"));
        assertTrue(lines.get(0).contains("/camerarail"));
        for (String syntax : List.of("list", "info <id>", "play <id> [玩家]", "stop [玩家]",
                "advance <信号|*> [玩家]", "edit <目标> <动作> [参数]", "editor [<轨道>|close]", "tools", "create <id> [名称]", "delete <id> confirm", "reload")) {
            assertTrue(lines.stream().anyMatch(line -> line.contains(syntax)), syntax);
        }
        assertTrue(lines.get(lines.size() - 1).contains("/camerarail help"));
    }

    @Test
    void rootOverviewAlignsTheSummaryColumn() {
        List<Component> lines = help("camerarail", ADMIN).overview(CommandHelp.Scope.ROOT);
        List<String> summaries = List.of("列出全部轨道", "查看轨道详情", "为玩家播放一条轨道", "结束正在播放的镜头",
                "发送信号，推进 wait 步骤", "按目标层级编辑轨道", "打开 Dialog 图形编辑器", "打开编辑工具箱子",
                "以当前视角创建一条轨道", "删除一条轨道", "重载轨道、配置与消息");
        int column = -1;
        for (String summary : summaries) {
            String line = lines.stream().map(PLAIN::serialize)
                    .filter(candidate -> candidate.contains(summary)).findFirst().orElseThrow();
            int start = line.indexOf(summary);
            int width = ChatText.width(line.substring(0, start));
            if (column < 0) {
                column = width;
            }
            assertTrue(Math.abs(column - width) < ChatText.SPACE_WIDTH,
                    "summary column of " + summary + " (" + width + " vs " + column + ")");
        }
    }

    @Test
    void overviewOnlyListsCommandsTheSenderMayUse() {
        CommandHelp help = help("camerarail", VIEWER);
        List<String> lines = plain(help.overview(CommandHelp.Scope.ROOT));
        assertTrue(lines.stream().anyMatch(line -> line.contains("list")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("info <id>")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("play")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("reload")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("create <id>")));

        List<String> none = plain(help("camerarail", ADMIN.negate()).overview(CommandHelp.Scope.ROOT));
        assertEquals(2, none.size());
        assertTrue(none.get(1).contains("没有可用的子命令"));
    }

    @Test
    void detailPageExplainsSyntaxArgumentsPermissionAndRelatedCommands() {
        List<String> lines = plain(help("camerarail", ADMIN).detail(CommandHelp.Scope.ROOT, "play"));
        assertTrue(lines.get(0).contains("play"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("用法") && line.contains("/camerarail play <id> [玩家]")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("参数") && line.contains("<id>")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("[玩家]") && line.contains("目标玩家")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("说明") && line.contains("只有一条轨道")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("权限") && line.contains("camerarail.play")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("相关") && line.contains("stop")));
    }

    @Test
    void detailPagesResolveAliasesAndRejectUnknownNames() {
        assertTrue(help("camerarail", ADMIN).detail(CommandHelp.Scope.ROOT, "nope").isEmpty());
        assertTrue(help("camerarail", ADMIN).detail(CommandHelp.Scope.EDITOR, "play").isEmpty());
        assertTrue(help("camerarail", ADMIN).detail(CommandHelp.Scope.EDITOR, "tools").isEmpty());
    }

    @Test
    void detailPageWarnsWhenTheSenderLacksThePermission() {
        List<String> lines = plain(help("camerarail", VIEWER).detail(CommandHelp.Scope.ROOT, "play"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("没有使用这个命令的权限")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("camerarail.play")));
    }

    @Test
    void editorDetailPagesRenderTheTargetLevelOfEachAction() {
        CommandHelp help = help("camerarail", ADMIN);
        List<String> trackAction = plain(help.detail(CommandHelp.Scope.EDITOR, "start"));
        assertTrue(trackAction.stream().anyMatch(line ->
                line.contains("用法") && line.contains("/camerarail edit <轨道> start")));
        List<String> stepAction = plain(help.detail(CommandHelp.Scope.EDITOR, "title"));
        String titleUsage = String.join("\n", stepAction);
        assertTrue(titleUsage.contains("/camerarail edit <轨道:步骤>"), titleUsage);
        assertTrue(titleUsage.contains("<title [文本]|subtitle"), titleUsage);
        assertTrue(titleUsage.contains("[文本]|fade>"), titleUsage);
        // Field groups must not render a fake runnable command such as `edit <轨道:步骤> title|subtitle|fade`.
        assertTrue(stepAction.stream().noneMatch(line -> line.contains("title|subtitle|fade")), titleUsage);
        List<String> moveGroup = plain(help.detail(CommandHelp.Scope.EDITOR, "move"));
        String moveUsage = String.join("\n", moveGroup);
        assertTrue(moveUsage.contains("/camerarail edit <轨道:步骤>"), moveUsage);
        assertTrue(moveUsage.contains("<here|pos|duration|easing|sway>"), moveUsage);
        assertTrue(moveGroup.stream().noneMatch(line -> line.contains("move: here")), moveUsage);
        List<String> pointAction = plain(help.detail(CommandHelp.Scope.EDITOR, "insert"));
        assertTrue(pointAction.stream().anyMatch(line ->
                line.contains("/camerarail edit <轨道:步骤:路径点> insert")));
        String overview = String.join("\n", plain(help.overview(CommandHelp.Scope.EDITOR)));
        assertTrue(overview.contains("目标:"));
        assertTrue(overview.contains("Dialog 占用轨道时拒绝修改"));
        assertFalse(overview.contains("@轨道"));
        assertFalse(overview.contains("tools"));
    }

    @Test
    void stepTypeHelpPagesListTheirFields() {
        CommandHelp help = help("camerarail", ADMIN);
        assertTrue(plain(help.detail(CommandHelp.Scope.EDITOR, "title"))
                .stream().anyMatch(line -> line.contains("title [文本]")));
        assertTrue(plain(help.detail(CommandHelp.Scope.EDITOR, "title"))
                .stream().anyMatch(line -> line.contains("fade <淡入> <停留> <淡出>")));
        assertTrue(plain(help.detail(CommandHelp.Scope.EDITOR, "wait"))
                .stream().anyMatch(line -> line.contains("signal <信号>")));
        assertTrue(plain(help.detail(CommandHelp.Scope.EDITOR, "move"))
                .stream().anyMatch(line -> line.contains("duration <tick>")));
        assertTrue(plain(help.detail(CommandHelp.Scope.EDITOR, "entry"))
                .stream().anyMatch(line -> line.contains("add <效果>")));
    }

    @Test
    void addHelpListsEveryStepTypeAndItsArguments() {
        String text = String.join("\n", plain(help("camerarail", ADMIN).detail(CommandHelp.Scope.EDITOR, "add")));
        for (String type : List.of("move", "path", "hold", "wait", "cut", "effect", "title")) {
            assertTrue(text.contains(type), type);
        }
        assertTrue(text.contains("wait [信号] [超时]"), text);
        assertTrue(text.contains("effect [add|remove] [效果] [等级] [时长]"), text);
        assertTrue(text.contains("title [淡入 停留 淡出] <标题>[|<副标题>]"), text);
        assertTrue(text.contains("省略参数时取与"), text);
        assertTrue(text.contains("相同的默认值"), text);
    }

    @Test
    void editNamespaceOnlyContainsTargetFirstActions() {
        CommandHelp help = help("camerarail", ADMIN);
        List<String> editDetail = plain(help.detail(CommandHelp.Scope.ROOT, "edit"));
        assertTrue(editDetail.stream().anyMatch(line ->
                line.contains("用法") && line.contains("/camerarail edit <目标> <动作> [参数]")));
        Set<String> editorNames = new TreeSet<>(help.names(CommandHelp.Scope.EDITOR));
        assertFalse(editorNames.contains("gui"));
        assertFalse(editorNames.contains("tools"));
        assertFalse(editorNames.contains("help"));
        assertFalse(editorNames.contains("done"));
        assertFalse(editorNames.contains("close"));
        assertFalse(editorNames.contains("track-sway"));
        assertTrue(editorNames.contains("start"));
        assertTrue(editorNames.contains("entry"));
        assertTrue(editorNames.contains("title"));
        assertTrue(editorNames.contains("move"));
    }

    @Test
    void editorOverviewListsEveryEditorCommand() {
        CommandHelp help = help("camerarail", ADMIN);
        Set<String> names = new TreeSet<>(help.names(CommandHelp.Scope.EDITOR));
        Set<String> expected = new TreeSet<>(EDITOR_COMMANDS);
        expected.addAll(EDITOR_TOPICS);
        assertEquals(expected, names);
        String text = String.join("\n", plain(help.overview(CommandHelp.Scope.EDITOR)));
        for (String name : EDITOR_COMMANDS) {
            assertTrue(text.contains(name), name);
        }
        assertTrue(text.contains("/camerarail edit"));
    }

    @Test
    void fieldTopicsHaveTheirOwnHelpPages() {
        CommandHelp help = help("camerarail", ADMIN);
        for (String topic : EDITOR_TOPICS) {
            List<String> lines = plain(help.detail(CommandHelp.Scope.EDITOR, topic));
            assertFalse(lines.isEmpty(), topic);
            assertTrue(lines.stream().noneMatch(line -> line.contains("help.commands")), topic);
        }
        String duration = String.join("\n", plain(help.detail(CommandHelp.Scope.EDITOR, "duration")));
        assertTrue(duration.contains("/camerarail edit <轨道:步骤> duration <tick>"), duration);
        String subtitle = String.join("\n", plain(help.detail(CommandHelp.Scope.EDITOR, "subtitle")));
        assertTrue(subtitle.contains("/camerarail edit <轨道:步骤> subtitle [文本]"), subtitle);
    }

    @Test
    void editorOverviewUsesOptionalSyntaxAndTheStepPointGroup() {
        String overview = String.join("\n", plain(help("camerarail", ADMIN).overview(CommandHelp.Scope.EDITOR)));
        assertTrue(overview.contains("description [说明]"), overview);
        assertTrue(overview.contains("步骤/路径点"), overview);
        String description = String.join("\n",
                plain(help("camerarail", ADMIN).detail(CommandHelp.Scope.EDITOR, "description")));
        assertTrue(description.contains("/camerarail edit <轨道> description [说明]"), description);
    }

    @Test
    void overviewEntriesSuggestTheirCommand() {
        List<Component> lines = help("camerarail", ADMIN).overview(CommandHelp.Scope.ROOT);
        List<String> suggestions = new ArrayList<>();
        for (Component line : lines.subList(1, lines.size() - 1)) {
            Set<String> lineSuggestions = new LinkedHashSet<>(clickCommands(line));
            assertEquals(1, lineSuggestions.size(), "one suggestion per row: " + lineSuggestions);
            suggestions.addAll(lineSuggestions);
        }
        assertEquals(List.of("/camerarail list ", "/camerarail info ", "/camerarail play ",
                "/camerarail stop ", "/camerarail advance ", "/camerarail edit ",
                "/camerarail editor ", "/camerarail tools ", "/camerarail create ",
                "/camerarail delete ", "/camerarail reload "), suggestions);
    }

    @Test
    void overviewEntriesHoverWithSyntaxAndSummary() {
        Component line = help("camerarail", ADMIN).overview(CommandHelp.Scope.ROOT).get(1);
        HoverEvent<?> hover = line.hoverEvent();
        assertNotNull(hover);
        String text = PLAIN.serialize((Component) hover.value());
        assertTrue(text.contains("列出全部轨道"), text);
        assertTrue(text.contains("/camerarail list"), text);
    }

    @Test
    void everyHelpStringComesFromMessages() {
        for (String label : List.of("camerarail", "camera-rail")) {
            for (CommandHelp.Scope scope : CommandHelp.Scope.values()) {
                CommandHelp help = help(label, ADMIN);
                List<Component> lines = new ArrayList<>(help.overview(scope));
                for (String name : help.names(scope)) {
                    lines.addAll(help.detail(scope, name));
                }
                for (Component line : lines) {
                    String text = PLAIN.serialize(line);
                    assertFalse(text.contains("help."), "missing message key leaked: " + text);
                    assertFalse(text.contains("{"), "unsubstituted placeholder: " + text);
                }
            }
        }
    }

    private static CommandHelp help(String label, Predicate<String> permissions) {
        return new CommandHelp(TestMessages.bundled(), label, permissions);
    }

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(PLAIN::serialize).toList();
    }

    private static List<String> clickCommands(Component component) {
        List<String> commands = new ArrayList<>();
        ClickEvent click = component.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.SUGGEST_COMMAND
                && click.payload() instanceof ClickEvent.Payload.Text text) {
            commands.add(text.value());
        }
        for (Component child : component.children()) {
            commands.addAll(clickCommands(child));
        }
        return commands;
    }
}
