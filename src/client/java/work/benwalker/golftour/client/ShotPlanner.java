package work.benwalker.golftour.client;

import java.util.Objects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import work.benwalker.golftour.physics.BallSimulator;
import work.benwalker.golftour.physics.Club;
import work.benwalker.golftour.physics.ShotCalculator;
import work.benwalker.golftour.physics.ShotType;
import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.physics.Vec;
import work.benwalker.golftour.world.LevelBallWorld;

/**
 * Aiming and the shot preview. The shot goes the way the camera faces (the mouse turns it); the crosshair only
 * sets how far along that line, clamped to the club's reach. The aim never depends on where the crosshair meets
 * the ground, so the golfer, camera and aim can't chase each other round the ball. The planned power is solved so
 * a perfect strike lands on the target, and the preview runs the real ball simulation (without wind) so the arc,
 * landing ring and roll-out match what the server will do.
 */
public final class ShotPlanner {
	/**
	 * @param target where the player is aiming (landing point for full shots, roll target for putts)
	 * @param power swing power that lands a perfect strike on {@code target}
	 * @param puttTarget putt length in blocks for 100% power
	 * @param preview simulated perfect shot
	 * @param maxCarry the club's full carry from this lie, in blocks
	 */
	public record Aim(Club club, ShotType type, Vec target, float yaw, double power, double puttTarget, BallSimulator.Result preview, double maxCarry) {
		public double carryBlocks() {
			return preview.carryFrom(start());
		}

		public double totalBlocks() {
			return preview.rest().horizontalDistanceTo(start());
		}

		public Vec start() {
			return preview.path().get(0);
		}
	}

	private static Aim current;
	private static Object cacheKey;

	private ShotPlanner() {
	}

	public static Aim aim() {
		return current;
	}

	public static double plannedPower() {
		return current == null ? 1.0 : current.power();
	}

	public static void invalidate() {
		cacheKey = null;
	}

	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Club club = ClientGolf.heldClub();
		if (!ClientGolf.addressed() || player == null || mc.level == null || club == null) {
			current = null;
			return;
		}
		// While swinging, keep the aim the player committed to.
		if (SwingMeter.phase() != SwingMeter.Phase.IDLE && current != null) {
			return;
		}
		ShotType type = ClientGolf.shotTypeFor(club);
		Vec ball = ClientGolf.ball();
		Surface lie = ClientGolf.lie();

		// Aim from wherever the camera is (behind the golfer in the broadcast view) so the crosshair is the target.
		Vec3 eye = CinematicCamera.overriding() && !CinematicCamera.hideCrosshair() && CinematicCamera.position() != null
			? CinematicCamera.position() : player.getEyePosition();
		Vec3 look = player.getViewVector(1f);
		HitResult hit = mc.level.clip(new ClipContext(eye, eye.add(look.scale(400)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
		Vec hitPoint = hit.getType() == HitResult.Type.MISS ? null : new Vec(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z);

		float yaw = player.getYRot();
		Vec dir = Vec.fromYaw(yaw);
		// How far along the aim line the crosshair points (negative: behind the ball, or at the sky).
		double along = hitPoint == null ? -1 : (hitPoint.x() - ball.x()) * dir.x() + (hitPoint.z() - ball.z()) * dir.z();
		double targetY = hitPoint == null ? ball.y() : hitPoint.y();
		double maxCarry;
		double length;
		if (club.isPutter()) {
			maxCarry = 70;
			length = along >= 0.3 ? Math.min(along, maxCarry) : Math.max(0.3, ball.horizontalDistanceTo(ClientGolf.cup()));
		} else {
			maxCarry = club.carryBlocks() * type.distanceMul * ShotCalculator.lieFactor(club, lie);
			length = along >= 3 && along <= maxCarry ? along : maxCarry;
		}
		Vec target = ball.add(dir.scale(length)).withY(targetY);

		Object key = Objects.hash(club, type, Math.round(yaw * 10), Math.round(target.horizontalDistanceTo(ball) * 4), Math.round(target.y() * 2),
			Math.round(ClientGolf.shape * 100), Math.round(ClientGolf.spin * 100), ball, lie);
		if (key.equals(cacheKey) && current != null) {
			return;
		}
		cacheKey = key;

		LevelBallWorld world = new LevelBallWorld(mc.level, ClientGolf.course());
		double puttTarget = target.horizontalDistanceTo(ball);
		double power;
		if (club.isPutter()) {
			// 100% (the marker) rolls the ball to the target, slope and all.
			puttTarget = solvePuttLength(world, ball, type, lie, yaw, length);
			power = 1.0;
		} else if (target.horizontalDistanceTo(ball) >= maxCarry - 0.01) {
			power = 1.0;
		} else {
			power = solvePower(world, ball, club, type, lie, yaw, target.horizontalDistanceTo(ball));
		}
		ShotCalculator.Swing swing = ShotCalculator.Swing.perfect(club, type, power, ClientGolf.shape, ClientGolf.spin, yaw, lie, puttTarget);
		BallSimulator.Result preview = BallSimulator.simulate(world, ball, ShotCalculator.compute(swing).launch(), BallSimulator.Options.preview(ClientGolf.cup()));
		current = new Aim(club, type, target, yaw, power, puttTarget, preview, maxCarry);
	}

	/**
	 * The flat-green putt length (what 100% means) that makes a pure putt along {@code yaw} stop {@code length}
	 * blocks down the line on this green, so uphill putts play longer and downhill ones shorter.
	 */
	private static double solvePuttLength(LevelBallWorld world, Vec ball, ShotType type, Surface lie, float yaw, double length) {
		Vec dir = Vec.fromYaw(yaw);
		double lo = 0.05, hi = Math.min(80, Math.max(1.0, length * 4));
		for (int i = 0; i < 16; i++) {
			double mid = (lo + hi) / 2;
			ShotCalculator.Swing swing = ShotCalculator.Swing.perfect(Club.PUTTER, type, 1.0, 0, 0, yaw, lie, mid);
			BallSimulator.Result r = BallSimulator.simulate(world, ball, ShotCalculator.compute(swing).launch(), BallSimulator.Options.preview(ClientGolf.cup()));
			Vec rest = r.rest();
			double along = (rest.x() - ball.x()) * dir.x() + (rest.z() - ball.z()) * dir.z();
			// Dropping in counts as getting there: the softest putt that holes is the one to show.
			if (r.outcome() != BallSimulator.Outcome.HOLED && along < length) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return (lo + hi) / 2;
	}

	/** Power that makes a perfect strike first touch down {@code distance} blocks away, terrain included. */
	private static double solvePower(LevelBallWorld world, Vec ball, Club club, ShotType type, Surface lie, float yaw, double distance) {
		double lo = 0.02, hi = 1.0;
		for (int i = 0; i < 14; i++) {
			double mid = (lo + hi) / 2;
			ShotCalculator.Swing swing = ShotCalculator.Swing.perfect(club, type, mid, ClientGolf.shape, ClientGolf.spin, yaw, lie, 1);
			BallSimulator.Result r = BallSimulator.simulate(world, ball, ShotCalculator.compute(swing).launch(), BallSimulator.Options.carryOnly());
			double carry = r.firstLanding() == null ? Double.MAX_VALUE : r.carryFrom(ball);
			if (carry < distance) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return (lo + hi) / 2;
	}
}
