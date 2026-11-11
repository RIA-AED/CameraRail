package red.ria.camerarail.command;

import org.bukkit.entity.Player;
import red.ria.camerarail.editor.EditService;
import red.ria.camerarail.editor.EditTarget;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.PathStep;
import red.ria.camerarail.model.Pose;

/**
 * Path-point-level edit actions: one point of one path step.
 */
final class PointEditCommands {
    void handle(Player player, EditTarget target, String action, String[] args, EditContext context) {
        CameraTrack track = context.repository().get(target.trackId());
        if (track == null) {
            context.messages().get().send(player, "track.not-found", "track", target.trackId());
            return;
        }
        PathStep path = StepFields.pathStep(track, target.stepIndex());
        if (path == null || target.pointIndex() >= path.points().size()) {
            context.messages().get().send(player, "editor.point-missing", "target", target.display());
            return;
        }
        switch (action) {
            case "" -> context.feedback().statusPoint(player, target, path.points().get(target.pointIndex()));
            case "here" -> {
                EditService.Result result = context.service().capturePose(player, target);
                if (context.feedback().applied(player, result)) {
                    context.messages().get().send(player, "editor.here-updated", "target", target.display());
                }
            }
            case "pos" -> {
                Pose pose = context.feedback().requirePose(player, target, args, 3,
                        path.points().get(target.pointIndex()),
                        "pos <x> <y> <z> [yaw] [pitch]");
                if (pose == null) {
                    return;
                }
                EditService.Result result = context.service().setPathPointPose(player, target, pose);
                if (context.feedback().applied(player, result)) {
                    context.messages().get().send(player, "editor.pose-set",
                            "target", target.display(), "pose", context.feedback().poseShort(pose));
                }
            }
            case "remove" -> {
                EditService.Result result = context.service().removePathPoint(player, target);
                if (context.feedback().applied(player, result)) {
                    context.messages().get().send(player, "editor.path-point-removed",
                            "target", target.display(),
                            "count", String.valueOf(StepFields.pathCount(result.track(), target.stepIndex())));
                }
            }
            case "up" -> movePoint(player, target, -1, context);
            case "down" -> movePoint(player, target, 1, context);
            case "insert" -> {
                EditService.Result result = context.service().insertPathPoint(player, target);
                if (context.feedback().applied(player, result)) {
                    context.messages().get().send(player, "editor.path-point-inserted",
                            "target", target.display(),
                            "count", String.valueOf(StepFields.pathCount(result.track(), target.stepIndex())));
                }
            }
            case "stop" -> EditPreview.stop(context, player);
            default -> context.feedback().wrongAction(player, target, action, EditActions.POINT_ACTIONS);
        }
    }

    private void movePoint(Player player, EditTarget target, int delta, EditContext context) {
        EditService.Result result = context.service().movePathPoint(player, target, delta);
        if (context.feedback().applied(player, result)) {
            int to = target.pointIndex() + 1 + delta;
            context.messages().get().send(player, "editor.path-point-moved",
                    "target", target.trackId() + ":" + (target.stepIndex() + 1) + ":" + to);
        }
    }

}
