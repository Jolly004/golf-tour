package work.benwalker.golftour.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import work.benwalker.golftour.physics.Club;
import work.benwalker.golftour.physics.Vec;

/**
 * The golfer in third person: where they stand (beside the ball, facing it, target off their left shoulder)
 * and how far through the swing they are, which drives the arm, torso and club animation.
 */
public final class GolferPose {
	public enum CameraMode {
		/** Behind the golfer down the target line, for aiming and the whole swing. */
		BROADCAST,
		/** Classic first person with the animated club in view. */
		FIRST_PERSON
	}

	public static CameraMode mode = CameraMode.BROADCAST;

	private GolferPose() {
	}

	/** True while the local player is set up over the ball in the broadcast camera. */
	public static boolean active() {
		return mode == CameraMode.BROADCAST && ClientGolf.atBall() && club() != null;
	}

	public static Club club() {
		return ClientGolf.heldClub();
	}

	/** Yaw the shot is aimed along: the way the player (and the camera behind them) faces. */
	public static float aimYaw() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? 0 : mc.player.getYRot();
	}

	/** Direction the golfer faces: square to the target line, with the target on their left. */
	public static float facingYaw() {
		return aimYaw() + 90f;
	}

	/** Where the golfer's feet go for the current aim. */
	public static Vec stance() {
		return stanceFor(aimYaw());
	}

	/** Where the golfer's feet go for an aim along {@code aimYaw}. */
	public static Vec stanceFor(float aimYaw) {
		Club club = club();
		double off = club == null ? 0.95 : club.isPutter() ? 0.72 : club.category == Club.Category.DRIVER ? 1.05 : 0.95;
		Vec facing = Vec.fromYaw(aimYaw + 90f);
		Vec target = Vec.fromYaw(aimYaw);
		Vec ball = ClientGolf.ball();
		Vec g = ball.sub(facing.scale(off)).sub(target.scale(0.08));
		double y = ClientGolf.course() != null ? ClientGolf.course().heightAt(g.x(), g.z()) : ball.y();
		if (Math.abs(y - ball.y()) > 1.2) {
			y = ball.y();
		}
		return g.withY(y);
	}

	private static double lastX, lastZ;
	private static int settleUntil;

	/** Keeps the golfer planted in their stance as the aim swings around. */
	public static void tick() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			// After a teleport (go to ball, end of a flyover) give the client half a second to settle in before
			// stepping into the stance; stepping straight away gets rejected by the server's movement check.
			double jx = player.getX() - lastX, jz = player.getZ() - lastZ;
			if (jx * jx + jz * jz > 16) {
				settleUntil = ClientGolf.ticks + 10;
			}
			lastX = player.getX();
			lastZ = player.getZ();
		}
		if (!active() || ClientGolf.ticks < settleUntil) {
			return;
		}
		Vec g = stance();
		double dx = g.x() - player.getX(), dz = g.z() - player.getZ();
		// Wait for the ground there to arrive (just after a teleport); stepping into missing chunks gets rejected.
		boolean loaded = player.level().hasChunksAt((int) Math.floor(g.x()) - 1, (int) Math.floor(g.z()) - 1,
			(int) Math.floor(g.x()) + 1, (int) Math.floor(g.z()) + 1);
		if (loaded && dx * dx + dz * dz > 1e-4) {
			// Step into the stance with real collision (NoCubes' smooth ground included) and let gravity set the
			// height. The server re-runs every move against its own collision, and a raw setPos onto a slope can
			// land inside the ground, which it rejects by pulling the player back (rubber-banding).
			float moveDist = player.moveDist; // no footstep sounds while the stance shifts with the aim
			player.move(MoverType.SELF, new Vec3(dx, 0, dz));
			player.moveDist = moveDist;
		}
		player.setDeltaMovement(0, Math.min(0, player.getDeltaMovement().y), 0);
		float body = facingYaw();
		player.yBodyRot = body;
		player.yBodyRotO = body;
	}

	/** True from the start of the backswing until the finish has been held. */
	public static boolean swinging() {
		return mode == CameraMode.BROADCAST && ClientGolf.active()
			&& (SwingMeter.phase() != SwingMeter.Phase.IDLE || ClubAnimator.sinceStrike() < 1.6);
	}

	/**
	 * Swing progress: 0 at address and impact, up to +1 at the top of the backswing, down to -1 at the end
	 * of the follow-through.
	 */
	public static double swing() {
		Club club = club();
		if (club == null) {
			return 0;
		}
		return ClubAnimator.swingFraction(club);
	}
}
