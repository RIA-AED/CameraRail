package red.ria.camerarail.command;

import org.junit.jupiter.api.Test;
import red.ria.camerarail.model.EffectAction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The branchy {@code add} argument rules. These are pure, so every edge of the old parser is
 * pinned here instead of needing a Bukkit player (the duration wording test is the regression
 * that triggered Phase H).
 */
class AddStepArgumentsTest {
    @Test
    void durationReportsNormalizedNumberBelowMinimum() {
        assertDurationInvalid("00", "0");
        assertDurationInvalid("+0", "0");
        assertDurationInvalid("000", "0");
        assertDurationInvalid("0", "0");
        assertDurationInvalid("-1", "-1");
        assertDurationInvalid("x", "x");
    }

    @Test
    void durationAcceptsValidValuesAndFallsBackWhenAbsent() {
        AddStepArguments.Duration valid = AddStepArguments.duration(args("40"), 4, 100, 1);
        assertEquals(40, valid.value());
        assertNull(valid.invalidToken());

        AddStepArguments.Duration zeroCut = AddStepArguments.duration(args("0"), 4, 0, 0);
        assertEquals(0, zeroCut.value());
        assertNull(zeroCut.invalidToken());

        AddStepArguments.Duration absent = AddStepArguments.duration(new String[]{"a", "b", "c", "d"}, 4, 100, 1);
        assertEquals(100, absent.value());
        assertNull(absent.invalidToken());
    }

    @Test
    void durationRejectsFallingBackBelowMinimum() {
        // The old parser validated the fallback too, and reported its normalized number.
        AddStepArguments.Duration absent = AddStepArguments.duration(new String[]{"a", "b", "c", "d"}, 4, 0, 1);
        assertNull(absent.value());
        assertEquals("0", absent.invalidToken());
    }

    @Test
    void effectChoiceOmitsTheActionByDefault() {
        AddStepArguments.EffectChoice choice = AddStepArguments.effectChoice(
                new String[]{"e", "t", "add", "effect", "speed"});
        assertEquals(EffectAction.ADD, choice.action());
        assertEquals("speed", choice.key());
        assertEquals(5, choice.restStart());

        AddStepArguments.EffectChoice noKey = AddStepArguments.effectChoice(
                new String[]{"e", "t", "add", "effect"});
        assertEquals(EffectAction.ADD, noKey.action());
        assertEquals("darkness", noKey.key());
        assertEquals(5, noKey.restStart());
    }

    @Test
    void effectChoiceAcceptsExplicitActionAndMovesTheRestStart() {
        AddStepArguments.EffectChoice add = AddStepArguments.effectChoice(
                new String[]{"e", "t", "add", "effect", "add", "speed", "2", "100"});
        assertEquals(EffectAction.ADD, add.action());
        assertEquals("speed", add.key());
        assertEquals(6, add.restStart());

        AddStepArguments.EffectChoice remove = AddStepArguments.effectChoice(
                new String[]{"e", "t", "add", "effect", "remove", "speed"});
        assertEquals(EffectAction.REMOVE, remove.action());
        assertEquals("speed", remove.key());
        assertEquals(6, remove.restStart());

        AddStepArguments.EffectChoice noKey = AddStepArguments.effectChoice(
                new String[]{"e", "t", "add", "effect", "remove"});
        assertEquals(EffectAction.REMOVE, noKey.action());
        assertEquals("darkness", noKey.key());
        assertEquals(6, noKey.restStart());
    }

    @Test
    void effectChoiceTreatsAnInvalidActionTokenAsTheKey() {
        // `add effect bogus` is a key lookup (and reports invalid-effect), not an invalid value.
        AddStepArguments.EffectChoice choice = AddStepArguments.effectChoice(
                new String[]{"e", "t", "add", "effect", "not-an-action"});
        assertEquals(EffectAction.ADD, choice.action());
        assertEquals("not-an-action", choice.key());
        assertEquals(5, choice.restStart());
    }

    @Test
    void titleTextFormKeepsTheDefaultFades() {
        AddStepArguments.TitleChoice title = AddStepArguments.title(
                new String[]{"e", "t", "add", "title", "hello"});
        assertEquals(20, title.fadeIn());
        assertEquals(60, title.stay());
        assertEquals(20, title.fadeOut());
        assertEquals("hello", title.raw());
        assertNull(title.invalidToken());

        AddStepArguments.TitleChoice multiWord = AddStepArguments.title(
                new String[]{"e", "t", "add", "title", "hello", "world|sub"});
        assertEquals("hello world|sub", multiWord.raw());
        assertNull(multiWord.invalidToken());
    }

    @Test
    void titleNumericFormReadsThreeNumbersAndTheText() {
        AddStepArguments.TitleChoice full = AddStepArguments.title(
                new String[]{"e", "t", "add", "title", "10", "20", "30", "hello|sub"});
        assertEquals(10, full.fadeIn());
        assertEquals(20, full.stay());
        assertEquals(30, full.fadeOut());
        assertEquals("hello|sub", full.raw());
        assertNull(full.invalidToken());

        AddStepArguments.TitleChoice partial = AddStepArguments.title(
                new String[]{"e", "t", "add", "title", "10"});
        assertEquals(10, partial.fadeIn());
        assertEquals(60, partial.stay());
        assertEquals(20, partial.fadeOut());
        assertEquals("", partial.raw());
        assertNull(partial.invalidToken());

        AddStepArguments.TitleChoice defaults = AddStepArguments.title(
                new String[]{"e", "t", "add", "title"});
        assertEquals(20, defaults.fadeIn());
        assertEquals(60, defaults.stay());
        assertEquals(20, defaults.fadeOut());
        assertEquals("", defaults.raw());
        assertNull(defaults.invalidToken());
    }

    @Test
    void titleReportsTheFirstInvalidNumericToken() {
        AddStepArguments.TitleChoice badStay = AddStepArguments.title(
                new String[]{"e", "t", "add", "title", "10", "x"});
        assertEquals("", badStay.raw());
        assertEquals("x", badStay.invalidToken());

        AddStepArguments.TitleChoice badFadeOut = AddStepArguments.title(
                new String[]{"e", "t", "add", "title", "10", "20", "y", "text"});
        assertEquals("y", badFadeOut.invalidToken());
    }

    @Test
    void waitSignalNamesAreUniquePerTrack() {
        assertEquals("wait", AddStepArguments.nextWaitSignal(List.of()));
        assertEquals("wait2", AddStepArguments.nextWaitSignal(List.of("wait")));
        assertEquals("wait4", AddStepArguments.nextWaitSignal(List.of("wait", "wait2", "wait3")));
        assertEquals("wait2", AddStepArguments.nextWaitSignal(List.of("other", "wait")));
    }

    private static void assertDurationInvalid(String token, String expectedInvalidToken) {
        AddStepArguments.Duration duration = AddStepArguments.duration(args(token), 4, 100, 1);
        assertNull(duration.value(), token + " should be rejected");
        assertEquals(expectedInvalidToken, duration.invalidToken());
    }

    private static String[] args(String token) {
        return new String[]{"a", "b", "c", "d", token};
    }
}
