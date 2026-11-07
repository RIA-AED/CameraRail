package red.ria.camerarail;

import red.ria.camerarail.model.Easing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EasingTest {
    @Test
    void allCurvesHaveCorrectEndpoints() {
        for (Easing easing : Easing.values()) {
            assertEquals(0.0, easing.apply(0.0), 1.0e-9, easing.name());
            assertEquals(1.0, easing.apply(1.0), 1.0e-9, easing.name());
        }
    }

    @Test
    void curvesAreMonotonic() {
        for (Easing easing : Easing.values()) {
            double previous = -1.0;
            for (int i = 0; i <= 100; i++) {
                double value = easing.apply(i / 100.0);
                assertTrue(value >= previous - 1.0e-9, easing.name() + " went backwards at " + i);
                assertTrue(value >= -1.0e-9 && value <= 1.0 + 1.0e-9, easing.name());
                previous = value;
            }
        }
    }

    @Test
    void aliasesAndErrors() {
        assertEquals(Easing.EASE_IN_OUT, Easing.parse("smooth"));
        assertEquals(Easing.EASE_IN_OUT, Easing.parse("ease_in_out"));
        assertThrows(IllegalArgumentException.class, () -> Easing.parse("bounce"));
    }
}
