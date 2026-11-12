package red.ria.camerarail.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.Easing;
import red.ria.camerarail.model.ExitMode;
import red.ria.camerarail.model.HoldStep;
import red.ria.camerarail.model.Pose;
import red.ria.camerarail.model.Step;
import red.ria.camerarail.model.SwayPreset;
import red.ria.camerarail.model.SwaySettings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-memory tests for the repository's atomic read-compute-write path. No Bukkit server is
 * needed: the tests never touch tracks.yml, they only exercise the registry and its write lock.
 */
class TrackRepositoryTest {
    private static final Logger LOGGER = Logger.getLogger(TrackRepositoryTest.class.getName());
    private static final RailConfig CONFIG = new RailConfig(100, Easing.EASE_IN_OUT,
            SwayPreset.NATURAL, 160, true, 6000, false, true, true, 1_000L, 256, 128, 72_000, false);

    @TempDir
    Path tempDir;

    private TrackRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TrackRepository(tempDir.resolve("tracks.yml").toFile(), CONFIG, LOGGER);
    }

    @AfterEach
    void tearDown() {
        repository.close();
    }

    private static CameraTrack track(String id) {
        return track(id, List.of());
    }

    private static CameraTrack track(String id, List<Step> steps) {
        return new CameraTrack(id, id, "", "world", new Pose(1, 2, 3, 0, 0),
                ExitMode.ANIMATION_END, null,
                SwaySettings.ofPreset(SwayPreset.NATURAL, 160, 1L), false, List.of(), steps);
    }

    private static CameraTrack track(String id, String displayName) {
        return new CameraTrack(id, displayName, "note", "world", new Pose(1, 2, 3, 0, 0),
                ExitMode.ANIMATION_END, null,
                SwaySettings.ofPreset(SwayPreset.GENTLE, 120, 42L), true, List.of(),
                List.of(new HoldStep(20, SwayPreset.INHERIT)));
    }

    /** A track the writer can serialize back (default sway seed, so it writes a preset name). */
    private static CameraTrack storableTrack(String id) {
        return new CameraTrack(id, id, "", "world", new Pose(1, 2, 3, 0, 0),
                ExitMode.ANIMATION_END, null,
                SwaySettings.ofPreset(SwayPreset.NATURAL, 160, TrackParser.defaultSeed(id)),
                false, List.of(), List.of());
    }

    @Test
    void computeAppliesTheChangeAndKeepsSnapshotsImmutable() {
        repository.upsert(track("login"));
        CameraTrack updated = repository.compute("login", current ->
                current.withSteps(List.of(new HoldStep(20, SwayPreset.INHERIT))));
        assertNotNull(updated);
        assertEquals(1, updated.steps().size());
        assertEquals(1, repository.get("LOGIN").steps().size(), "ids stay case-insensitive");

        Map<String, CameraTrack> snapshot = repository.all();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", track("x")));
    }

    @Test
    void computeCanCreateAndRejectsMissingIds() {
        CameraTrack created = repository.compute("login", current ->
                current == null ? track("login") : current);
        assertNotNull(created);
        assertNotNull(repository.get("login"));

        assertNull(repository.compute(null, current -> track("x")));
        assertNull(repository.compute("  ", current -> track("x")));
        assertSame(repository.get("login"), repository.compute("login", current -> null),
                "a null changer result aborts and keeps the stored value");
        assertNotNull(repository.get("login"), "the aborted change must not remove or replace it");
    }

    @Test
    void unchangedValueIsNotReplaced() {
        repository.upsert(track("login"));
        CameraTrack current = repository.get("login");
        CameraTrack result = repository.compute("login", ignored -> current);
        assertSame(current, result);
        assertSame(current, repository.get("login"));
    }

    @Test
    void deleteReturnsWhetherTheTrackExistedAndNotifiesOnce() {
        List<String> deleted = new ArrayList<>();
        repository.onTrackDeleted(deleted::add);
        repository.upsert(track("login"));

        assertTrue(repository.delete("LOGIN"));
        assertEquals(List.of("login"), deleted);
        assertFalse(repository.delete("login"));
        assertEquals(1, deleted.size());
        assertNull(repository.get("login"));
    }

    @Test
    void renameMovesTheKeyAndNotifiesTheListenerOnce() {
        List<String> renamed = new ArrayList<>();
        repository.onTrackRenamed((oldId, newId) -> renamed.add(oldId + "->" + newId));
        repository.upsert(track("login"));

        TrackRepository.RenameResult result = repository.rename("LOGIN", "Intro");

        assertEquals(TrackRepository.RenameStatus.RENAMED, result.status());
        assertTrue(result.renamed());
        assertEquals("intro", result.track().id());
        assertNull(repository.get("login"));
        assertNotNull(repository.get("intro"));
        assertEquals("intro", repository.get("intro").displayName(),
                "a display name that just mirrored the old id follows the rename");
        assertEquals(List.of("login->intro"), renamed);
    }

    @Test
    void renameKeepsACustomNameAndTheSwaySettings() {
        CameraTrack original = track("login", "登录过场");
        repository.upsert(original);

        CameraTrack renamed = repository.rename("login", "login-v2").track();

        assertEquals("login-v2", renamed.id());
        assertEquals("登录过场", renamed.displayName(), "a custom display name is not rewritten");
        assertEquals(original.sway(), renamed.sway(), "rename keeps the sway seed and amplitudes");
        assertEquals(original.steps(), renamed.steps());
        assertEquals(original.description(), renamed.description());
        assertTrue(renamed.follow());
    }

    @Test
    void renameRejectsMissingSourceSameIdInvalidIdAndTakenTarget() {
        repository.upsert(track("login"));
        repository.upsert(track("intro"));

        assertEquals(TrackRepository.RenameStatus.SOURCE_MISSING,
                repository.rename("missing", "other").status());
        assertEquals(TrackRepository.RenameStatus.SAME_ID,
                repository.rename("LOGIN", "login").status());
        assertEquals(TrackRepository.RenameStatus.INVALID_ID,
                repository.rename("login", "has space").status());
        assertEquals(TrackRepository.RenameStatus.INVALID_ID,
                repository.rename("login", "").status());
        assertEquals(TrackRepository.RenameStatus.TARGET_EXISTS,
                repository.rename("login", "INTRO").status());

        assertNotNull(repository.get("login"), "a refused rename keeps the source track");
        assertNotNull(repository.get("intro"));
    }

    @Test
    void renamePreconditionVetoesWithoutTouchingTheMap() {
        repository.upsert(track("login"));
        List<String> renamed = new ArrayList<>();
        repository.onTrackRenamed((oldId, newId) -> renamed.add(oldId));

        TrackRepository.RenameResult result = repository.rename("login", "intro", track -> false);

        assertEquals(TrackRepository.RenameStatus.REFUSED, result.status());
        assertNull(result.track());
        assertNotNull(repository.get("login"));
        assertNull(repository.get("intro"));
        assertTrue(renamed.isEmpty(), "a refused rename must not notify listeners");
    }

    @Test
    void renameIsWrittenUnderTheNewYamlKey() throws Exception {
        repository.upsert(storableTrack("login"));
        assertEquals(TrackRepository.RenameStatus.RENAMED, repository.rename("login", "intro").status());
        repository.saveAsync().get(10, TimeUnit.SECONDS);

        TrackRepository reloaded = new TrackRepository(repository.file(), CONFIG, LOGGER);
        try {
            LoadReport report = reloaded.loadInitial();
            assertFalse(report.fatal());
            assertTrue(report.errors().isEmpty(), String.valueOf(report.errors()));
            assertNotNull(reloaded.get("intro"));
            assertNull(reloaded.get("login"));
        } finally {
            reloaded.close();
        }
    }

    @Test
    void reloadNotifiesListenerAfterTheMapWasReplaced() throws Exception {
        TrackWriter.write(repository.file().toPath(), Map.of("login", storableTrack("login")), CONFIG);
        repository.loadInitial();
        AtomicInteger notified = new AtomicInteger();
        repository.onReloaded(notified::incrementAndGet);

        LoadReport report = repository.reload();

        assertFalse(report.fatal());
        assertEquals(1, notified.get(), "a successful reload must notify its listener exactly once");
        assertNotNull(repository.get("login"));
    }

    @Test
    void concurrentComputesDoNotLoseUpdates() throws Exception {
        repository.upsert(track("login"));
        int writers = 4;
        int editsPerWriter = 25;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int writer = 0; writer < writers; writer++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int edit = 0; edit < editsPerWriter; edit++) {
                        repository.compute("login", current -> {
                            List<Step> steps = new ArrayList<>(current.steps());
                            steps.add(new HoldStep(1, SwayPreset.INHERIT));
                            return current.withSteps(steps);
                        });
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(writers * editsPerWriter, repository.get("login").steps().size(),
                "every concurrent compute must be applied on top of the previous one");
    }
}
