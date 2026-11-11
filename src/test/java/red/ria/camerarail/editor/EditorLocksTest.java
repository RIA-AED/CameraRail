package red.ria.camerarail.editor;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorLocksTest {
    @Test
    void tracksAreHeldByTheDialogOwnerUntilReleased() {
        EditorLocks locks = new EditorLocks();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertFalse(locks.locked("login"));
        assertTrue(locks.tryLock("login", owner));
        assertTrue(locks.locked("login"));
        assertTrue(locks.lockedBy("login", owner));
        assertFalse(locks.lockedBy("login", other));
        assertTrue(locks.lockedByOther("login", other));
        assertFalse(locks.lockedByOther("login", owner));

        locks.unlock("login", other);
        assertTrue(locks.locked("login"), "a stale owner must not release the lock");
        locks.unlock("login", owner);
        assertFalse(locks.locked("login"));
    }

    @Test
    void tryLockLetsExactlyOneOwnerIn() {
        EditorLocks locks = new EditorLocks();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertTrue(locks.tryLock("login", owner));
        assertFalse(locks.tryLock("login", other), "a second player must not steal the track");
        assertFalse(locks.tryLock("login", owner), "the same owner cannot acquire twice either");
        assertEquals(Optional.of(owner), locks.owner("login"));

        locks.unlock("login", other);
        assertEquals(Optional.of(owner), locks.owner("login"));

        locks.unlock("login", owner);
        assertTrue(locks.tryLock("login", other), "a released track can be acquired again");
        assertTrue(locks.lockedBy("login", other));
    }
}
