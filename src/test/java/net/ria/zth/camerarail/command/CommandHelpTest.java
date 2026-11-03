package net.ria.zth.camerarail.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.ria.zth.camerarail.message.ChatText;
import net.ria.zth.camerarail.support.TestMessages;
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
    private static final Predicate<String> VIEWER = "zth.camera.command"::equals;
    private static final int MAX_LINE_WIDTH = ChatText.CHAT_WIDTH - 4;
    private static final List<String> EDITOR_COMMANDS = List.of(
            "gui", "status", "list", "help", "done", "close", "open", "create", "delete", "start", "exit",
            "exit-mode", "display", "track-sway", "follow", "add", "cut", "path", "point", "unpoint", "hold", "wait",
            "effect", "title", "here", "select", "duration", "easing", "sway", "up", "down", "remove",
            "undo", "preview", "stop", "save");

    @Test
    void overviewsAndDetailsStayInsideTheChatWidth() {
        for (String label : List.of("zc", "zth-camera")) {
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
        List<String> lines = plain(help("zc", ADMIN).overview(CommandHelp.Scope.ROOT));
        assertEquals(11, lines.size());
        assertTrue(lines.get(0).contains("ZthCameraRail"));
        assertTrue(lines.get(0).contains("/zc"));
        for (String syntax : List.of("list", "info <id>", "play <id> [玩家]", "stop [玩家]",
                "advance <信号|*> [玩家]", "edit", "create <id> [名称]", "delete <id> confirm", "reload")) {
            assertTrue(lines.stream().anyMatch(line -> line.contains(syntax)), syntax);
        }
        assertTrue(lines.get(lines.size() - 1).contains("/zc help"));
    }

    @Test
    void rootOverviewAlignsTheSummaryColumn() {
        List<Component> lines = help("zc", ADMIN).overview(CommandHelp.Scope.ROOT);
        List<String> summaries = List.of("列出全部轨道", "查看轨道详情", "为玩家播放一条轨道", "结束正在播放的镜头",
                "发送信号，推进 wait 步骤", "打开图形编辑器或编辑命令", "以当前视角创建一条轨道", "删除一条轨道",
                "重载轨道、配置与消息");
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
        CommandHelp help = help("zc", VIEWER);
        List<String> lines = plain(help.overview(CommandHelp.Scope.ROOT));
        assertTrue(lines.stream().anyMatch(line -> line.contains("list")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("info <id>")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("play")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("reload")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("create <id>")));

        List<String> none = plain(help("zc", ADMIN.negate()).overview(CommandHelp.Scope.ROOT));
        assertEquals(2, none.size());
        assertTrue(none.get(1).contains("没有可用的子命令"));
    }

    @Test
    void detailPageExplainsSyntaxArgumentsPermissionAndRelatedCommands() {
        List<String> lines = plain(help("zc", ADMIN).detail(CommandHelp.Scope.ROOT, "play"));
        assertTrue(lines.get(0).contains("play"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("用法") && line.contains("/zc play <id> [玩家]")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("参数") && line.contains("<id>")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("[玩家]") && line.contains("目标玩家")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("说明") && line.contains("只有一条轨道")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("权限") && line.contains("zth.camera.play")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("相关") && line.contains("stop")));
    }

    @Test
    void detailPagesResolveAliasesAndRejectUnknownNames() {
        List<String> done = plain(help("zc", ADMIN).detail(CommandHelp.Scope.EDITOR, "close"));
        assertFalse(done.isEmpty());
        assertTrue(done.get(0).contains("done"));
        assertTrue(help("zc", ADMIN).detail(CommandHelp.Scope.ROOT, "nope").isEmpty());
        assertTrue(help("zc", ADMIN).detail(CommandHelp.Scope.EDITOR, "play").isEmpty());
    }

    @Test
    void detailPageWarnsWhenTheSenderLacksThePermission() {
        List<String> lines = plain(help("zc", VIEWER).detail(CommandHelp.Scope.ROOT, "play"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("没有使用这个命令的权限")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("zth.camera.play")));
    }

    @Test
    void editorOverviewListsEveryEditorCommand() {
        CommandHelp help = help("zc", ADMIN);
        Set<String> names = new TreeSet<>(help.names(CommandHelp.Scope.EDITOR));
        assertEquals(new TreeSet<>(EDITOR_COMMANDS), names);
        String text = String.join("\n", plain(help.overview(CommandHelp.Scope.EDITOR)));
        for (String name : EDITOR_COMMANDS) {
            assertTrue(text.contains(name), name);
        }
        assertTrue(text.contains("/zc edit"));
    }

    @Test
    void overviewEntriesSuggestTheirCommand() {
        List<Component> lines = help("zc", ADMIN).overview(CommandHelp.Scope.ROOT);
        List<String> suggestions = new ArrayList<>();
        for (Component line : lines.subList(1, lines.size() - 1)) {
            Set<String> lineSuggestions = new LinkedHashSet<>(clickCommands(line));
            assertEquals(1, lineSuggestions.size(), "one suggestion per row: " + lineSuggestions);
            suggestions.addAll(lineSuggestions);
        }
        assertEquals(List.of("/zc list ", "/zc info ", "/zc play ", "/zc stop ", "/zc advance ", "/zc edit ",
                "/zc create ", "/zc delete ", "/zc reload "), suggestions);
    }

    @Test
    void overviewEntriesHoverWithSyntaxAndSummary() {
        Component line = help("zc", ADMIN).overview(CommandHelp.Scope.ROOT).get(1);
        HoverEvent<?> hover = line.hoverEvent();
        assertNotNull(hover);
        String text = PLAIN.serialize((Component) hover.value());
        assertTrue(text.contains("列出全部轨道"), text);
        assertTrue(text.contains("/zc list"), text);
    }

    @Test
    void everyHelpStringComesFromMessages() {
        for (String label : List.of("zc", "zth-camera")) {
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
