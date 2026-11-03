package net.ria.zth.camerarail.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTextTest {
    @Test
    void measuresAsciiWithTheVanillaWidths() {
        assertEquals(0, ChatText.width(""));
        assertEquals(4, ChatText.width(" "));
        assertEquals(2, ChatText.width("i"));
        assertEquals(3, ChatText.width("l"));
        assertEquals(4, ChatText.width("t"));
        assertEquals(6, ChatText.width("a"));
        assertEquals(18, ChatText.width("abc"));
        assertEquals(9, ChatText.width("镜"));
        assertEquals(18, ChatText.width("镜头"));
    }

    @Test
    void padToReachesTheColumnWithinOneSpace() {
        Component padded = ChatText.padTo(Component.text("abc"), 40);
        int width = ChatText.width(padded);
        assertTrue(width >= 40, "padded width " + width);
        assertTrue(width < 40 + ChatText.SPACE_WIDTH, "padded width " + width);
    }

    @Test
    void padToNeverShortensLongerText() {
        Component text = Component.text("a long command", NamedTextColor.WHITE);
        assertEquals(ChatText.width(text), ChatText.width(ChatText.padTo(text, 10)));
    }

    @Test
    void spacesToIsEmptyWhenAlreadyPastTheColumn() {
        assertEquals("", ChatText.spacesTo(50, 40));
        assertEquals("  ", ChatText.spacesTo(32, 40));
    }

    @Test
    void wrapStaysInsideTheWidthAndKeepsEveryCharacter() {
        String text = "显示世界、步骤、总时长、摇摆、区块跟随和等待信号，以及每个等待步骤的信号名.";
        List<String> lines = ChatText.wrap(text, 120);
        assertFalse(lines.isEmpty());
        for (String line : lines) {
            assertTrue(ChatText.width(line) <= 120, line);
        }
        assertEquals(text, String.join("", lines));
    }

    @Test
    void wrapPrefersSpacesAndKeepsWordsWhole() {
        List<String> lines = ChatText.wrap("alpha beta gamma delta epsilon", 70);
        for (String line : lines) {
            assertTrue(ChatText.width(line) <= 70, line);
            assertFalse(line.startsWith(" ") || line.endsWith(" "), line);
        }
        assertEquals("alpha beta gamma delta epsilon", String.join(" ", lines));
    }
}
