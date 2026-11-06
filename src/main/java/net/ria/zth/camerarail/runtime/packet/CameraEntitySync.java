package net.ria.zth.camerarail.runtime.packet;

/**
 * Packet planning for the client-only fake player that acts as the client's camera.
 *
 * <p>The fake camera is a client-only PLAYER entity. The spectator camera reads
 * {@code LivingEntity#getViewYRot()}, which is the entity's <em>head</em> yaw
 * ({@code yHeadRot}), while the position sync packet only carries the body yaw and pitch.
 * The head rotation therefore has to be pushed as a separate
 * {@code ClientboundRotateHeadPacket} on every camera tick where it changes; without it the
 * client keeps the head yaw it received in the spawn packet and the view never turns.</p>
 *
 * <p>A head-look packet stores the head yaw as one signed byte: 256 steps per full turn, so
 * one step is {@code 360 / 256 = 1.40625} degrees. PacketEvents writes the byte as
 * {@code (int) (yaw * 0.7111111f)} ({@code 256 / 360}) truncated toward zero. The Minecraft
 * client decodes it with {@code Mth.unpackDegrees} ({@code byte * 360 / 256}), while
 * PacketEvents' reader divides by {@code 0.7111111f} instead; the two values agree to
 * within {@code 1e-5} degrees. The client does not snap the head yaw to the packet:
 * {@code ClientboundRotateHeadPacket} goes through
 * {@code ClientPacketListener#handleRotateMob} to {@code LivingEntity#lerpHeadTo}, which
 * stores a three-tick interpolation target, and {@code RemotePlayer#aiStep} approaches it
 * with {@code Mth.rotLerp} each tick. Two ticks that produce the same byte would therefore
 * set the same target, so skipping the packet is lossless: the client keeps interpolating
 * toward the rotation it already has. That makes the packed byte a faithful "did the target
 * head rotation change" test, which is why it is checked on <em>every</em> tick but only sent
 * when it differs: a sway-off hold sends nothing, while a slow eased pan sends one packet at
 * each step as soon as the accumulated angle crosses it.</p>
 *
 * <p>The position/body-rotation sync keeps the vanilla server tracker cadence of one packet
 * per three ticks, with the client interpolating between them. Head yaw changes are sent
 * immediately instead: each packet starts a fresh three-tick interpolation target, so
 * delaying it to the position cadence would add lag to every turn.</p>
 *
 * <p>This class deliberately depends on neither Bukkit nor PacketEvents: the cadence and
 * the packing have to be testable without an initialized PacketEvents API (constructing a
 * {@code PacketWrapper} in a plain unit test fails). {@link PacketCameraView} turns the
 * returned plan into actual packets.</p>
 */
final class CameraEntitySync {
    /** One precise position packet every three ticks, matching the vanilla server tracker. */
    static final int POSITION_SYNC_INTERVAL_TICKS = 3;
    /**
     * Yaw units per degree of the packed head yaw: one byte covers 1/256 turn, i.e.
     * {@code 256 / 360 = 0.7111111}. PacketEvents' {@code WrapperPlayServerEntityHeadLook}
     * writes the byte with this factor and its reader divides by it; the Minecraft client's
     * {@code Mth.unpackDegrees} multiplies by {@code 360 / 256} instead, which agrees to
     * within {@code 1e-5} degrees.
     */
    static final float HEAD_YAW_PACK_SCALE = 0.7111111f;
    /** One packed head-yaw step: {@code 360 / 256 = 1.40625} degrees. */
    static final float HEAD_YAW_STEP_DEGREES = 1.40625f;

    private int ticks;
    private boolean headLookSent;
    private byte lastPackedHeadYaw;

    /**
     * The packets one camera tick needs, decided before any packet is built.
     *
     * @param sendPosition      whether the position/body-rotation packet is due
     * @param sendHeadLook      whether the head rotation changed and must be sent
     * @param headYaw           the swayed camera yaw of this tick, to write into the packet
     * @param packedHeadYaw     the byte the client will decode from that packet
     */
    record SyncPlan(boolean sendPosition, boolean sendHeadLook, float headYaw, byte packedHeadYaw) {
    }

    /**
     * Records one camera tick and returns the packets it needs.
     *
     * @param headYaw the swayed camera yaw of this tick; the client camera uses it as the
     *                fake player's head yaw
     */
    SyncPlan tick(float headYaw) {
        int currentTick = ticks++;
        boolean sendPosition = currentTick % POSITION_SYNC_INTERVAL_TICKS == 0;
        byte packedHeadYaw = quantizeHeadYaw(headYaw);
        // The spawn packet already carries the initial head yaw, but resending it on the
        // first sync tick is cheap and makes the client start interpolating from this
        // session's yaw; headLookSent starts false so the first tick is always sent.
        boolean sendHeadLook = !headLookSent || packedHeadYaw != lastPackedHeadYaw;
        if (sendHeadLook) {
            headLookSent = true;
            lastPackedHeadYaw = packedHeadYaw;
        }
        return new SyncPlan(sendPosition, sendHeadLook, headYaw, packedHeadYaw);
    }

    /**
     * Packs a yaw exactly like the PacketEvents head-look writer.
     *
     * <p>The writer computes {@code (int) (yaw * 0.7111111f)} and writes the low byte, so
     * both casts matter: {@code (int)} truncates toward zero rather than rounding, which is
     * what the client actually receives, including for negative angles. Vanilla's
     * {@code Mth.packDegrees} floors instead; PacketEvents is what writes this packet, so
     * the truncating expression is the one that matters here.</p>
     */
    static byte quantizeHeadYaw(float headYaw) {
        return (byte) ((int) (headYaw * HEAD_YAW_PACK_SCALE));
    }

    /**
     * Decodes a packed head yaw like PacketEvents' reader, for tests and documentation:
     * {@code packedHeadYaw / 0.7111111f} (each step is 1.40625 degrees). The Minecraft
     * client itself decodes with {@code Mth.unpackDegrees} ({@code byte * 360 / 256}); that
     * value agrees with this one to within {@code 1e-5} degrees.
     */
    static float decodeHeadYaw(byte packedHeadYaw) {
        return packedHeadYaw / HEAD_YAW_PACK_SCALE;
    }
}
