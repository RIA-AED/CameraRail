package net.ria.zth.camerarail.model;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * An immutable camera track. All positions belong to {@link #worldName()}.
 */
public record CameraTrack(
        String id,
        String displayName,
        String description,
        String worldName,
        Pose start,
        Pose exit,
        SwaySettings sway,
        boolean follow,
        List<EffectSpec> entryEffects,
        List<Step> steps
) {
    public CameraTrack {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Track id is required");
        }
        id = id.toLowerCase(java.util.Locale.ROOT);
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        description = description == null ? "" : description;
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("Track world is required");
        }
        if (start == null) {
            throw new IllegalArgumentException("Track start pose is required");
        }
        if (sway == null) {
            throw new IllegalArgumentException("Track sway settings are required");
        }
        entryEffects = List.copyOf(entryEffects);
        steps = List.copyOf(steps);
        for (EffectSpec entryEffect : entryEffects) {
            if (entryEffect.action() != EffectAction.ADD) {
                throw new IllegalArgumentException("Entry effects can only add potion effects");
            }
        }
    }

    public int timedDurationTicks() {
        int total = 0;
        for (Step step : steps) {
            if (step instanceof CameraStep cameraStep) {
                total += cameraStep.timedDurationTicks();
            }
        }
        return total;
    }

    public boolean hasWait() {
        return !waitSignals().isEmpty();
    }

    public Set<String> waitSignals() {
        Set<String> signals = new TreeSet<>();
        for (Step step : steps) {
            if (step instanceof WaitStep wait) {
                signals.add(wait.signal());
            }
        }
        return java.util.Collections.unmodifiableSet(signals);
    }

    public CameraTrack withSteps(List<Step> newSteps) {
        return new CameraTrack(id, displayName, description, worldName, start, exit, sway, follow, entryEffects, newSteps);
    }

    public CameraTrack withStart(Pose newStart) {
        return new CameraTrack(id, displayName, description, worldName, newStart, exit, sway, follow, entryEffects, steps);
    }

    public CameraTrack withExit(Pose newExit) {
        return new CameraTrack(id, displayName, description, worldName, start, newExit, sway, follow, entryEffects, steps);
    }

    public CameraTrack withDisplayName(String newDisplayName) {
        return new CameraTrack(id, newDisplayName, description, worldName, start, exit, sway, follow, entryEffects, steps);
    }

    public CameraTrack withDescription(String newDescription) {
        return new CameraTrack(id, displayName, newDescription, worldName, start, exit, sway, follow, entryEffects, steps);
    }

    public CameraTrack withSway(SwaySettings newSway) {
        return new CameraTrack(id, displayName, description, worldName, start, exit, newSway, follow, entryEffects, steps);
    }

    public CameraTrack withFollow(boolean newFollow) {
        return new CameraTrack(id, displayName, description, worldName, start, exit, sway, newFollow, entryEffects, steps);
    }

    public CameraTrack withEntryEffects(List<EffectSpec> newEntryEffects) {
        return new CameraTrack(id, displayName, description, worldName, start, exit, sway, follow,
                newEntryEffects, steps);
    }
}
