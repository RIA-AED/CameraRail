package red.ria.camerarail.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Pixel-aware text layout for the vanilla chat font.
 *
 * <p>Minecraft lays chat out in pixels, not in characters: an {@code i} advances two pixels
 * while a CJK glyph advances nine, so counting characters cannot keep columns aligned. The
 * table below follows the vanilla default font closely enough for chat layout, and columns are
 * padded with spaces (four pixels each), so a column can drift by a few pixels instead of
 * misaligning by a whole word.</p>
 */
public final class ChatText {
    /** Width of the vanilla chat area in GUI pixels. */
    public static final int CHAT_WIDTH = 320;
    /** Advance of a space in the vanilla default font. */
    public static final int SPACE_WIDTH = 4;

    private static final int DEFAULT_WIDTH = 6;
    private static final int WIDE_WIDTH = 9;
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private ChatText() {
    }

    /** Width of a component's plain text, ignoring colors, clicks and hovers. */
    public static int width(Component component) {
        return width(PLAIN.serialize(component));
    }

    /** Width of a plain string in GUI pixels. */
    public static int width(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += charWidth(text.charAt(i));
        }
        return width;
    }

    /**
     * Appends spaces so the component's plain text reaches {@code column}. The result may end a
     * few pixels past the column because a space is four pixels wide.
     */
    public static Component padTo(Component component, int column) {
        return component.append(Component.text(spacesTo(width(component), column)));
    }

    /** Spaces needed to move from {@code current} to at least {@code column}. */
    public static String spacesTo(int current, int column) {
        int delta = column - current;
        if (delta <= 0) {
            return "";
        }
        return " ".repeat((delta + SPACE_WIDTH - 1) / SPACE_WIDTH);
    }

    /**
     * Wraps plain text to lines no wider than {@code maxWidth}. Breaks after spaces and CJK
     * punctuation when the rest of the line still fits, and between characters otherwise, so
     * Chinese text does not break in the middle of a short word.
     */
    public static List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        int lastBreak = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                addLine(lines, line.toString());
                line.setLength(0);
                lineWidth = 0;
                lastBreak = -1;
                continue;
            }
            int width = charWidth(c);
            if (lineWidth + width > maxWidth && !line.isEmpty()) {
                String tail = lastBreak > 0 ? line.substring(lastBreak) : "";
                if (lastBreak > 0 && width(tail) + width <= maxWidth) {
                    addLine(lines, line.substring(0, lastBreak));
                    line.setLength(0);
                    line.append(tail);
                    lineWidth = width(tail);
                } else {
                    addLine(lines, line.toString());
                    line.setLength(0);
                    lineWidth = 0;
                    if (c == ' ') {
                        lastBreak = -1;
                        continue;
                    }
                }
                lastBreak = lastBreakIndex(line);
            }
            line.append(c);
            lineWidth += width;
            if (isBreakAfter(c)) {
                lastBreak = line.length();
            }
        }
        addLine(lines, line.toString());
        return lines;
    }

    private static void addLine(List<String> lines, String line) {
        String stripped = line.stripTrailing();
        if (!stripped.isEmpty()) {
            lines.add(stripped);
        }
    }

    /** Index just past the last space or CJK punctuation in {@code line}, or -1. */
    private static int lastBreakIndex(CharSequence line) {
        for (int i = line.length() - 1; i >= 0; i--) {
            if (isBreakAfter(line.charAt(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    private static boolean isBreakAfter(char c) {
        return c == ' ' || c == '，' || c == '。' || c == '、' || c == '；' || c == '：'
                || c == '！' || c == '？';
    }

    private static int charWidth(char c) {
        if (c < 32) {
            return 0;
        }
        if (c < 127) {
            return switch (c) {
                case ' ' -> SPACE_WIDTH;
                case '!', ',', '.', ':', ';', '|', 'i' -> 2;
                case '\'', '`', 'l' -> 3;
                case '[', ']', 'I', 't' -> 4;
                case '"', '(', ')', '*', '<', '>', 'f', 'k', 'r', '{', '}' -> 5;
                case '@', '~' -> 7;
                default -> DEFAULT_WIDTH;
            };
        }
        return WIDE_WIDTH;
    }
}
