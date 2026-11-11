package red.ria.camerarail.command;

import org.junit.jupiter.api.Test;
import red.ria.camerarail.model.Pose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure argument parsing rules moved out of {@link EditFeedback}. The reported messages are
 * covered by the command layer; these tests pin the parse/fallback semantics the commands keep.
 */
class EditArgumentsTest {
    private static final Pose FALLBACK = new Pose(10, 20, 30, 45, -15);

    @Test
    void integersAndDoublesParseOrReturnNull() {
        assertEquals(42, EditArguments.parseInt("42"));
        assertEquals(-7, EditArguments.parseInt("-7"));
        assertNull(EditArguments.parseInt("x"));
        assertNull(EditArguments.parseInt(""));
        assertNull(EditArguments.parseInt("4.2"));

        assertEquals(1.5, EditArguments.parseDouble("1.5"));
        assertEquals(-2.25, EditArguments.parseDouble("-2.25"));
        assertNull(EditArguments.parseDouble("nope"));
        assertNull(EditArguments.parseDouble("1,5"));
        assertTrue(Double.isNaN(EditArguments.parseDouble("NaN")),
                "the old parser accepted NaN; the pose/model decides what to do with it");
    }

    @Test
    void booleansAcceptTheHistoricalSpellings() {
        assertEquals(Boolean.TRUE, EditArguments.parseBoolean("true"));
        assertEquals(Boolean.TRUE, EditArguments.parseBoolean("ON"));
        assertEquals(Boolean.TRUE, EditArguments.parseBoolean("yes"));
        assertEquals(Boolean.TRUE, EditArguments.parseBoolean("1"));
        assertEquals(Boolean.FALSE, EditArguments.parseBoolean("false"));
        assertEquals(Boolean.FALSE, EditArguments.parseBoolean("off"));
        assertEquals(Boolean.FALSE, EditArguments.parseBoolean("No"));
        assertEquals(Boolean.FALSE, EditArguments.parseBoolean("0"));
        assertNull(EditArguments.parseBoolean("maybe"));
    }

    @Test
    void posesKeepTheFallbackYawAndPitchWhenOmitted() {
        String[] positionOnly = {"edit", "login", "start", "1", "2", "3"};
        Pose pose = EditArguments.parsePose(positionOnly, 3, FALLBACK);
        assertEquals(new Pose(1, 2, 3, FALLBACK.yaw(), FALLBACK.pitch()), pose);

        String[] rotated = {"edit", "login", "start", "1", "2", "3", "90", "-45"};
        assertEquals(new Pose(1, 2, 3, 90, -45), EditArguments.parsePose(rotated, 3, FALLBACK));
    }

    @Test
    void posesRejectMissingAndUnparsableCoordinates() {
        assertNull(EditArguments.parsePose(new String[]{"edit", "login", "start", "1", "2"}, 3, FALLBACK));
        assertNull(EditArguments.parsePose(
                new String[]{"edit", "login", "start", "1", "x", "3"}, 3, FALLBACK));
        assertNull(EditArguments.parsePose(
                new String[]{"edit", "login", "start", "1", "2", "3", "bad"}, 3, FALLBACK));
        assertNull(EditArguments.parsePose(
                new String[]{"edit", "login", "start", "1", "2", "3", "10", "bad"}, 3, FALLBACK));
    }

    @Test
    void extraTokensBeyondPitchAreIgnored() {
        String[] args = {"edit", "login", "start", "1", "2", "3", "10", "20", "ignored", "more"};
        assertEquals(new Pose(1, 2, 3, 10, 20), EditArguments.parsePose(args, 3, FALLBACK));
    }

    @Test
    void firstInvalidNumberReportsTheOffendingTokenInOrder() {
        assertEquals("x", EditArguments.firstInvalidNumber(
                new String[]{"e", "t", "start", "x", "2", "3"}, 3));
        assertEquals("y", EditArguments.firstInvalidNumber(
                new String[]{"e", "t", "start", "1", "y", "3"}, 3));
        assertEquals("bad", EditArguments.firstInvalidNumber(
                new String[]{"e", "t", "start", "1", "2", "3", "bad"}, 3),
                "x/y/z pass, so the first bad optional token is reported");
        assertEquals("1", EditArguments.firstInvalidNumber(
                new String[]{"e", "t", "start", "1", "2", "3"}, 3),
                "all tokens valid: the caller only uses this after parsePose failed, so the fallback is the first coordinate");
    }

    @Test
    void positiveIndexIsOneBased() {
        assertEquals(1, EditArguments.positiveIndex("1"));
        assertEquals(7, EditArguments.positiveIndex("7"));
        assertNull(EditArguments.positiveIndex("0"));
        assertNull(EditArguments.positiveIndex("-1"));
        assertNull(EditArguments.positiveIndex("x"));
    }

    @Test
    void parseIntKeepsNegativeValuesForTheModel() {
        // The command layer always accepted negative effect/title numbers and let the model clamp
        // them or reject the step; requireInt adds the message, not the restriction.
        assertEquals(-5, EditArguments.parseInt("-5"));
        assertEquals(3, EditArguments.parseInt("3"));
        assertNull(EditArguments.parseInt("x"));
    }

}
