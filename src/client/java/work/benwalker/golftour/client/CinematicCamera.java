package work.benwalker.golftour.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import work.benwalker.golftour.course.Flyover;
import work.benwalker.golftour.game.GolfRound;
import work.benwalker.golftour.net.GolfActionPayload;
import work.benwalker.golftour.physics.Vec;

/**
 * The camera director, in priority order: hole flyover, the swing hold (from the backswing through the
 * follow-through), ball chase camera, and the down-the-line camera behind the golfer while aiming.
 * The swing is shot from behind like the aim view, so the camera never swings round to the side.
 */
public final class CinematicCamera {
	private static long flyoverStart = -1;
	private static boolean overriding;
	private static boolean hideCrosshair;
	private static Vec3 pos;
	private static float yaw, pitch;

	private CinematicCamera() {
	}

	public static void startFlyover() {
		flyoverStart = System.nanoTime();
	}

	public static void stopFlyover() {
		flyoverStart = -1;
	}

	/** True if the camera was overridden on the last frame (hand hidden, player model shown). */
	public static boolean overriding() {
		return overriding;
	}

	/** True when the crosshair should be hidden (cinematic shots; the aim camera keeps it). */
	public static boolean hideCrosshair() {
		return overriding && hideCrosshair;
	}

	public static boolean flyoverActive() {
		return flyoverStart >= 0 && ClientGolf.active() && ClientGolf.roundState() == GolfRound.State.FLYOVER;
	}

	/** A click during the flyover skips it. */
	public static void skipFlyover() {
		if (flyoverActive()) {
			stopFlyover();
			ClientPlayNetworking.send(new GolfActionPayload(GolfActionPayload.SKIP_FLYOVER));
		}
	}

	/** Seconds after a strike during which the camera holds behind the golfer on the follow-through. */
	private static final double FOLLOW_THROUGH_HOLD = 0.6;

	private static boolean swingCamActive() {
		if (GolferPose.mode != GolferPose.CameraMode.BROADCAST || !ClientGolf.active()) {
			return false;
		}
		boolean swinging = SwingMeter.phase() != SwingMeter.Phase.IDLE && GolferPose.active();
		boolean finishing = ClubAnimator.sinceStrike() < FOLLOW_THROUGH_HOLD && (ClientGolf.atAddress() || ClientGolf.inFlight());
		return swinging || finishing;
	}

	/** Computes this frame's override, if any. {@code normal} is where vanilla put the camera. */
	public static boolean update(Vec3 normal, float partialTicks) {
		hideCrosshair = true;
		if (flyoverActive()) {
			double t = ((System.nanoTime() - flyoverStart) / 1e9 - Flyover.LEAD_IN * 0.05) / (Flyover.TICKS * 0.05);
			t = Math.max(0, t);
			if (t <= 1.0) {
				Flyover.Pose pose = Flyover.at(ClientGolf.course(), ClientGolf.hole(), t);
				return set(new Vec3(pose.pos().x(), pose.pos().y(), pose.pos().z()), pose.yaw(), pose.pitch());
			}
		}
		if (swingCamActive() && behindPos != null) {
			// Hold the down-the-line shot from address, so the whole swing is seen from behind.
			return set(behindPos, behindYaw, behindPitch);
		}
		if (GolferPose.mode == GolferPose.CameraMode.BROADCAST && ClientGolf.inFlight() && ClientGolf.lastWasPutt && behindPos != null) {
			// Watch the putt roll from behind the ball, like the TV putting view.
			return set(behindPos, behindYaw, behindPitch);
		}
		if (BallCam.active()) {
			BallCam.update(pos != null && overriding ? pos : normal, partialTicks);
			return set(BallCam.position(), BallCam.yaw(), BallCam.pitch());
		}
		if (GolferPose.active()) {
			// Down the line: behind the golfer and ball, looking where the player aims.
			LocalPlayer player = Minecraft.getInstance().player;
			Vec eye = addressEye(player.getYRot(), GolferPose.club().isPutter());
			hideCrosshair = false;
			behindPos = new Vec3(eye.x(), eye.y(), eye.z());
			behindYaw = player.getYRot();
			behindPitch = player.getXRot();
			return set(behindPos, behindYaw, behindPitch);
		}
		behindPos = null;
		overriding = false;
		return false;
	}

	/**
	 * Where the down-the-line camera sits for an aim along {@code yaw}. Full shots frame golfer and ball from
	 * behind them both; putts sit low and right behind the ball, so the crosshair is exactly on the putting line.
	 */
	public static Vec addressEye(float yaw, boolean putter) {
		Vec back = Vec.fromYaw(yaw);
		Vec ball = ClientGolf.ball();
		if (putter) {
			return ball.sub(back.scale(3.2)).add(0, 1.3, 0);
		}
		Vec mid = GolferPose.stanceFor(yaw).lerp(ball, 0.5);
		return mid.sub(back.scale(3.7)).add(0, 1.75, 0);
	}

	/** The last down-the-line camera at address, held through the swing. */
	private static Vec3 behindPos;
	private static float behindYaw, behindPitch;

	private static boolean set(Vec3 p, float newYaw, float newPitch) {
		pos = p;
		yaw = newYaw;
		pitch = newPitch;
		overriding = true;
		return true;
	}

	public static Vec3 position() {
		return pos;
	}

	public static float yaw() {
		return yaw;
	}

	public static float pitch() {
		return pitch;
	}
}
