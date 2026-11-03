package net.ria.zth.camerarail.config;

import net.ria.zth.camerarail.model.CameraTrack;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of reading tracks.yml. Individual bad tracks do not prevent other tracks from
 * loading.
 *
 * @param tracks parsed tracks, keyed by lowercase id
 * @param errors human-readable errors, with exact YAML paths
 * @param failedIds ids that failed in this attempt (old version is kept on reload)
 * @param fatal true when the file itself could not be read/parsed
 */
public record LoadReport(
        Map<String, CameraTrack> tracks,
        List<String> errors,
        Set<String> failedIds,
        boolean fatal
) {
    public static LoadReport fatal(String error) {
        return new LoadReport(Map.of(), List.of(error), Set.of(), true);
    }
}
