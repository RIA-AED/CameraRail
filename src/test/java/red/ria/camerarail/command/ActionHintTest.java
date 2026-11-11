package red.ria.camerarail.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import red.ria.camerarail.message.ChatText;
import red.ria.camerarail.message.Messages;
import red.ria.camerarail.support.TestMessages;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionHintTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void actionChipsWrapInsideTheWidthAndFillTheCommand() {
        List<String> actions = List.of("remove", "duplicate", "up", "down", "preview", "stop");
        List<Component> lines = CameraCommand.actionLines(
                Component.text("可用动作: "),
                Component.text(" 详情见 /camerarail help edit"),
                "camerarail", "login:2", actions, 160);
        assertTrue(lines.size() > 1, "expected the line to wrap: " + text(lines));
        List<String> clicks = new ArrayList<>();
        for (Component line : lines) {
            assertTrue(ChatText.width(line) <= 160, "line too wide: " + PLAIN.serialize(line));
            collectClicks(line, clicks);
        }
        for (String action : actions) {
            assertTrue(clicks.contains("/camerarail edit login:2 " + action + " "),
                    action + " should be clickable, got " + clicks);
        }
        assertTrue(text(lines).contains("详情见 /camerarail help edit"));
    }

    @Test
    void shortActionLinesStayOnOneLine() {
        List<Component> lines = CameraCommand.actionLines(
                Component.text("可用动作: "), Component.empty(),
                "camerarail", "login", List.of("add", "preview"), 400);
        assertEquals(1, lines.size(), text(lines));
        String clicks = String.join("\n", clickPayloads(lines));
        assertTrue(clicks.contains("/camerarail edit login add "), clicks);
        assertTrue(clicks.contains("/camerarail edit login preview "), clicks);
        assertFalse(text(lines).contains("详情见"), "empty tail should stay empty");
    }

    @Test
    void helpTailKeepsTheActionPlaceholderLiteral() {
        // The tail is rendered through MiniMessage; "<动作>" must survive as literal text.
        Messages messages = TestMessages.bundled();
        String text = PLAIN.serialize(messages.component("editor.actions-hint-tail",
                "usage", "/camerarail help edit <动作>"));
        assertTrue(text.contains("/camerarail help edit <动作>"), text);
    }

    private static void collectClicks(Component component, List<String> sinks) {
        ClickEvent click = component.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.SUGGEST_COMMAND
                && click.payload() instanceof ClickEvent.Payload.Text text) {
            sinks.add(text.value());
        }
        for (Component child : component.children()) {
            collectClicks(child, sinks);
        }
    }

    private static List<String> clickPayloads(List<Component> lines) {
        List<String> clicks = new ArrayList<>();
        for (Component line : lines) {
            collectClicks(line, clicks);
        }
        return clicks;
    }

    private static String text(List<Component> lines) {
        return String.join("\n", lines.stream().map(PLAIN::serialize).toList());
    }
}
