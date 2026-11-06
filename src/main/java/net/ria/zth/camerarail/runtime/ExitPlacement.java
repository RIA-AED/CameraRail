package net.ria.zth.camerarail.runtime;

import net.ria.zth.camerarail.model.Pose;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

/**
 * Computes where a player body has to be placed when the camera releases control.
 *
 * <p>Camera poses store the <em>eye</em> position; a standing player's body is
 * {@code eyeHeight} below it. That subtraction is not exact: {@code player.getEyeHeight()}
 * is a {@code float} ({@code 1.62f} is {@code 1.6200000047683716} as a double), so
 * {@code 64.62 - 1.62f} is {@code 62.99999999523163}, i.e. 4.8e-9 <em>below</em> the block
 * top the camera ended on. Some client/server collision paths treat such an embedded
 * coordinate as a fresh collision and silently pull the player back when they start
 * walking. Adding 0.1 mm puts the feet unambiguously above the surface while staying
 * invisible.</p>
 *
 * <p>The same embedding can also come from the editor: GUI capture rounds coordinates to
 * six decimals, and a pose captured while sneaking or swimming has a different eye height
 * than the standing player it is restored to. {@link #feet} therefore also lifts the feet
 * onto the top of the block collision shape when the target point lands inside one (slabs,
 * stairs, carpets, snow layers), instead of leaving the body embedded in it.</p>
 */
public final class ExitPlacement {
    /** 0.1 mm: far above the ~5e-9 float error, far below anything visible. */
    private static final double SAFE_EPSILON = 1.0E-4;
    /** A point can overlap only a few stacked collision shapes (e.g. carpet on a slab). */
    private static final int MAX_LIFT_ATTEMPTS = 4;

    private ExitPlacement() {
    }

    /**
     * Feet Y for an eye pose, lifted by the 0.1 mm safety margin.
     *
     * @param pose      the camera eye pose the session ended on
     * @param eyeHeight the eye height of the standing player that is being restored
     */
    public static double feetY(Pose pose, double eyeHeight) {
        return pose.y() - eyeHeight + SAFE_EPSILON;
    }

    /**
     * Lifts feet that are inside {@code [blockMinY, blockMaxY)} just above the block top.
     * A player standing on the shape has feet at {@code blockMaxY}, so anything below it is
     * embedded. Returns {@code feetY} unchanged when it is already above the shape or below
     * it.
     *
     * <p>Separate from {@link #feet} so the pure Y logic can be unit-tested without a world.
     */
    static double liftOutOfBlock(double feetY, double blockMinY, double blockMaxY) {
        if (feetY >= blockMinY && feetY < blockMaxY) {
            return blockMaxY + SAFE_EPSILON;
        }
        return feetY;
    }

    /**
     * Builds the exit location for an eye pose: the standing body position with a 0.1 mm
     * lift, moved above any block collision shape the feet would otherwise be embedded in.
     *
     * <p>The block probe subtracts the epsilon again so it looks at the block the feet
     * would stand on (the surface the camera ended above), not at the air block above it.
     * The loop is bounded because each lift can only reach another, higher shape (carpet on
     * a slab, snow on a slab) and no more than a handful can overlap one point.</p>
     */
    public static Location feet(World world, Pose pose, double eyeHeight) {
        double y = feetY(pose, eyeHeight);
        try {
            for (int attempt = 0; attempt < MAX_LIFT_ATTEMPTS; attempt++) {
                double probeY = y - SAFE_EPSILON;
                Block block = world.getBlockAt(
                        (int) Math.floor(pose.x()),
                        (int) Math.floor(probeY),
                        (int) Math.floor(pose.z()));
                BoundingBox box = block.getBoundingBox();
                // Air has an empty box; only a real collision shape can embed the feet.
                if (box.getVolume() <= 0.0) {
                    break;
                }
                // A block's box can start at its own edge, so the point must be inside the
                // box in X/Z as well before its Y range is relevant (e.g. a neighbouring slab
                // must not lift a point that stands on the floor beside it).
                if (pose.x() < box.getMinX() || pose.x() >= box.getMaxX()
                        || pose.z() < box.getMinZ() || pose.z() >= box.getMaxZ()) {
                    break;
                }
                double lifted = liftOutOfBlock(probeY, box.getMinY(), box.getMaxY());
                if (lifted == probeY) {
                    break;
                }
                y = lifted;
            }
        } catch (RuntimeException exception) {
            // A region-threaded server can refuse a block read when the target chunk belongs
            // to another region. The exit must not fail there: keep the pure Y fix (the part
            // that removes the reported pull-back) and skip only the collision-shape lift.
            y = feetY(pose, eyeHeight);
        }
        return new Location(world, pose.x(), y, pose.z(), pose.yaw(), pose.pitch());
    }
}
