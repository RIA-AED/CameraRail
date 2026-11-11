package red.ria.camerarail.editor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which tracks are currently held by a Dialog editor session.
 *
 * <p>The Dialog editor and the command/item editing surface are two independent systems: the
 * Dialog owns a draft, the command surface writes straight to the repository. They must not
 * mutate the same track at the same time, so this small registry is the only shared state. Both
 * sides depend on it instead of depending on each other.</p>
 *
 * <p>{@link #tryLock(String, UUID)} is the only acquisition path used by the Dialog editor: a
 * check-then-{@link #lock(String, UUID)} pair would let two players (or two Folia region
 * threads) both believe they own the same track. The command surface only reads these ownership
 * records; its check-to-write window is closed by {@code TrackRepository.compute}, which
 * serialises the check and the write, and by the Dialog opening its session with a lock acquired
 * before it reads the draft.</p>
 */
public final class EditorLocks {
    private final Map<String, UUID> owners = new ConcurrentHashMap<>();

    /**
     * Acquires the track for {@code owner} or reports that somebody else already holds it.
     * Atomic via {@code ConcurrentHashMap.putIfAbsent}.
     *
     * @return {@code true} when the caller now owns the track
     */
    public boolean tryLock(String trackId, UUID owner) {
        return owners.putIfAbsent(trackId, owner) == null;
    }

    /**
     * Releases only when {@code owner} still holds the lock, so a stale close cannot steal it.
     */
    public void unlock(String trackId, UUID owner) {
        owners.remove(trackId, owner);
    }

    public boolean locked(String trackId) {
        return owners.containsKey(trackId);
    }

    public boolean lockedBy(String trackId, UUID playerId) {
        return playerId != null && playerId.equals(owners.get(trackId));
    }

    public boolean lockedByOther(String trackId, UUID playerId) {
        UUID owner = owners.get(trackId);
        return owner != null && !owner.equals(playerId);
    }

    public Optional<UUID> owner(String trackId) {
        return Optional.ofNullable(owners.get(trackId));
    }
}
