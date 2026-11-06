package net.ria.zth.camerarail.runtime.packet;

import net.ria.zth.camerarail.model.Pose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the fake camera's per-tick packet planning.
 *
 * <p>{@link CameraEntitySync} exists so these decisions can be tested without an
 * initialized PacketEvents API: the wrappers themselves need it and cannot be constructed
 * in a plain unit test.</p>
 */
class CameraEntitySyncTest {
    /** Half of one packed head-yaw step: 1.40625 / 2 degrees. */
    private static final float HALF_HEAD_YAW_STEP_DEGREES = 0.703125f;

    @Test
    void firstTickSendsPositionAndHeadLook() {
        CameraEntitySync sync = new CameraEntitySync();
        CameraEntitySync.SyncPlan plan = sync.tick(0.0f);
        assertTrue(plan.sendPosition(), "the first tick must send the position");
        assertTrue(plan.sendHeadLook(), "the first tick must send the head rotation");
        assertEquals(0.0f, plan.headYaw(), "the packet must carry the swayed camera yaw");
        assertEquals((byte) 0, plan.packedHeadYaw());
    }

    @Test
    void headLookIsSentOnlyOnceWhileThePackedYawStaysTheSame() {
        CameraEntitySync sync = new CameraEntitySync();
        assertTrue(sync.tick(10.0f).sendHeadLook());
        // 10.0 and 10.5 degrees both pack to byte 7 (10 * 0.7111111 = 7.11 and
        // 10.5 * 0.7111111 = 7.46). The client starts a three-tick interpolation toward
        // the byte it receives, so a packet carrying the same byte would set the same
        // target: the client keeps interpolating toward it and nothing needs to be sent.
        for (int tick = 0; tick < 40; tick++) {
            CameraEntitySync.SyncPlan plan = sync.tick(10.5f);
            assertFalse(plan.sendHeadLook(), "unchanged packed head yaw at tick " + tick);
            assertEquals((byte) 7, plan.packedHeadYaw());
        }
    }

    @Test
    void headLookIsSentAgainWhenThePackedYawChanges() {
        CameraEntitySync sync = new CameraEntitySync();
        CameraEntitySync.SyncPlan first = sync.tick(10.0f);
        assertTrue(first.sendHeadLook());
        assertEquals((byte) 7, first.packedHeadYaw());
        assertFalse(sync.tick(10.5f).sendHeadLook(), "a sub-step sway sends nothing");
        CameraEntitySync.SyncPlan changed = sync.tick(12.0f);
        assertTrue(changed.sendHeadLook(), "a new packed byte sends the head rotation");
        assertEquals(12.0f, changed.headYaw());
        assertEquals((byte) 8, changed.packedHeadYaw());
        assertFalse(sync.tick(12.5f).sendHeadLook(), "12.5 still packs to byte 8");
        assertTrue(sync.tick(-12.0f).sendHeadLook(), "a change in the other direction sends too");
    }

    @Test
    void headLookChangeIsSentEvenWhenPositionSyncIsSkipped() {
        CameraEntitySync sync = new CameraEntitySync();
        assertTrue(sync.tick(10.0f).sendPosition());
        CameraEntitySync.SyncPlan plan = sync.tick(12.0f);
        assertFalse(plan.sendPosition(), "the second tick is not a position tick");
        assertTrue(plan.sendHeadLook(),
                "a changed head yaw is sent immediately, not on the position cadence");
    }

    @Test
    void positionSyncKeepsTheVanillaThreeTickCadence() {
        CameraEntitySync sync = new CameraEntitySync();
        for (int tick = 0; tick < 12; tick++) {
            CameraEntitySync.SyncPlan plan = sync.tick(0.0f);
            assertEquals(tick % 3 == 0, plan.sendPosition(), "position packet at tick " + tick);
            // The yaw never changes here, so only the first tick sends a head rotation.
            assertEquals(tick == 0, plan.sendHeadLook(), "head-look packet at tick " + tick);
        }
    }

    @Test
    void quantizedBoundaryYawsMatchThePacketEventsWriter() {
        // (byte)((int)(yaw * 0.7111111f)), the expression PacketEvents' head-look writer
        // uses: -180 and 180 both wrap to Byte.MIN_VALUE, which decodes to -180 degrees.
        assertEquals((byte) 0, CameraEntitySync.quantizeHeadYaw(0.0f));
        assertEquals(Byte.MIN_VALUE, CameraEntitySync.quantizeHeadYaw(-180.0f));
        assertEquals(Byte.MIN_VALUE, CameraEntitySync.quantizeHeadYaw(180.0f));
        assertEquals((byte) 64, CameraEntitySync.quantizeHeadYaw(90.0f));
        assertEquals((byte) -64, CameraEntitySync.quantizeHeadYaw(-90.0f));
        // The angles above sit exactly on the 1.40625-degree grid, where truncation toward
        // zero and floor agree, so they cannot tell the PacketEvents writer apart from a
        // floor. These off-grid negative angles can: PacketEvents writes
        // (int)(yaw * 0.7111111f), so -179 * 0.7111111 = -127.28 truncates to -127 (a
        // floor, like vanilla Mth.packDegrees, would give -128), and -1 * 0.7111111 =
        // -0.71 truncates to 0 (a floor would give -1).
        assertEquals((byte) -127, CameraEntitySync.quantizeHeadYaw(-179.0f));
        assertEquals((byte) 0, CameraEntitySync.quantizeHeadYaw(-1.0f));
    }

    @Test
    void decodedBoundaryYawsRoundTripWithinHalfAPackedStep() {
        float[] boundaryYaws = {0.0f, -180.0f, 180.0f, 90.0f, -90.0f};
        for (float yaw : boundaryYaws) {
            byte packed = CameraEntitySync.quantizeHeadYaw(yaw);
            float decoded = CameraEntitySync.decodeHeadYaw(packed);
            // Compare circularly: 180 and -180 are the same direction.
            double error = Math.abs(Pose.shortestYawDelta(yaw, decoded));
            assertTrue(error <= HALF_HEAD_YAW_STEP_DEGREES,
                    "decoded " + decoded + " degrees is " + error + " degrees from " + yaw);
        }
    }

    @Test
    void decodedHeadYawLandsExactlyOnThePackedStepGrid() {
        // Packing and decoding are inverses on the 1.40625-degree grid the byte can
        // represent, which is what makes the packed byte a faithful "same interpolation
        // target" key for the dedup in tick(float).
        for (int step = Byte.MIN_VALUE; step <= Byte.MAX_VALUE; step++) {
            float yaw = step * CameraEntitySync.HEAD_YAW_STEP_DEGREES;
            byte packed = CameraEntitySync.quantizeHeadYaw(yaw);
            assertEquals((byte) step, packed, "packed grid point " + yaw);
            assertEquals(yaw, CameraEntitySync.decodeHeadYaw(packed), 0.0f,
                    "decoded grid point " + yaw);
        }
    }

    @Test
    void decodedHeadYawStaysWithinOnePackedStepAcrossTheWholeTurn() {
        // The writer truncates toward zero instead of rounding, so the exact guarantee is
        // one full packed step (1.40625 degrees), not half a step. What matters for the
        // camera is that the byte used for dedup is also the byte the client decodes,
        // which quantizeHeadYaw and decodeHeadYaw mirror from PacketEvents.
        float worstError = 0.0f;
        for (float yaw = -180.0f; yaw < 180.0f; yaw += 0.25f) {
            byte packed = CameraEntitySync.quantizeHeadYaw(yaw);
            float decoded = CameraEntitySync.decodeHeadYaw(packed);
            float error = (float) Math.abs(Pose.shortestYawDelta(yaw, decoded));
            worstError = Math.max(worstError, error);
        }
        assertTrue(worstError <= CameraEntitySync.HEAD_YAW_STEP_DEGREES,
                "worst round-trip error was " + worstError + " degrees");
    }
}
