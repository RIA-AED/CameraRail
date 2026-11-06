package net.ria.zth.camerarail.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraEffectsTest {
    @Test
    void cameraDependentEffectsAreRecognised() {
        assertTrue(CameraEffects.isCameraDependent("blindness"));
        assertTrue(CameraEffects.isCameraDependent("darkness"));
        assertTrue(CameraEffects.isCameraDependent("night_vision"));
        assertTrue(CameraEffects.isCameraDependent("slowness"));
        assertTrue(CameraEffects.isCameraDependent("speed"));
    }

    @Test
    void playerOnlyEffectsDoNotTriggerFallbackWarnings() {
        assertFalse(CameraEffects.isCameraDependent("nausea"));
        assertFalse(CameraEffects.isCameraDependent("regeneration"));
        assertFalse(CameraEffects.isCameraDependent((String) null));
    }

    @Test
    void darknessHintUsesTheVanillaKey() {
        assertTrue(CameraEffects.isCameraDependent("darkness"));
        assertFalse(CameraEffects.isDarkness(null));
    }
}
