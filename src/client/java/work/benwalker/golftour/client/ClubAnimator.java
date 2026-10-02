package work.benwalker.golftour.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import work.benwalker.golftour.physics.Club;

/**
 * First-person club animation, gun-mod style: the 3D club is held at address with a gentle waggle, rises
 * with the swing meter on the backswing, drops back as the needle falls, whips through impact and holds a
 * high follow-through before settling back to address. The swing plane is a roll around the view axis,
 * pivoting at the hands.
 */
public final class ClubAnimator {
	private static long strikeNanos = -1;
	private static double strikePower;

	private ClubAnimator() {
	}

	public static void onStrike(double power) {
		strikeNanos = System.nanoTime();
		strikePower = power;
	}

	/** Seconds since the last strike (large if none). */
	public static double sinceStrike() {
		return strikeNanos < 0 ? 1e9 : (System.nanoTime() - strikeNanos) / 1e9;
	}

	/** Swing progress for the third-person golfer: +1 top of backswing, 0 address/impact, -1 full finish. */
	public static double swingFraction(Club club) {
		double maxBack = club.isPutter() ? 34 : 150;
		double follow = club.isPutter() ? -24 : -128;
		double theta = theta(club);
		return theta >= 0 ? theta / maxBack : -theta / follow;
	}

	private static double theta(Club club) {
		boolean putter = club.isPutter();
		double maxBack = putter ? 34 : 150;
		double follow = putter ? -24 : -128;
		double t = System.nanoTime() / 1e9;
		double theta;
		double age = strikeNanos < 0 ? 1e9 : (System.nanoTime() - strikeNanos) / 1e9;
		if (age < 1.7) {
			double reach = follow * (putter ? Math.min(1.2, strikePower) : Math.min(1.0, 0.55 + strikePower * 0.45));
			if (age < 0.32) {
				theta = reach * easeOut(age / 0.32);
			} else if (age < 1.05) {
				theta = reach;
			} else {
				theta = reach * (1 - easeInOut((age - 1.05) / 0.65));
			}
		} else {
			double needle = SwingMeter.needle();
			theta = switch (SwingMeter.phase()) {
				case BACKSWING -> maxBack * easeOut(Math.min(1.1, needle) / 1.1);
				case DOWNSWING -> needle >= 0 ? maxBack * Math.min(1.1, needle) / 1.1 : follow * 0.25 * Math.min(1, -needle / 0.25);
				default -> 2.5 * Math.sin(t * 2.3) + 1.2 * Math.sin(t * 0.9);
			};
		}
		return theta;
	}

	public static void apply(PoseStack pose, Club club) {
		boolean putter = club.isPutter();
		double theta = theta(club);

		// Hands low and right in view; the shaft runs down and away towards the ball.
		pose.translate(0.30, -0.40, -0.60);
		pose.rotateDegrees(Axis.YP, 10f);
		pose.rotateDegrees(Axis.ZP, (float) theta);
		pose.rotateDegrees(Axis.XP, putter ? -28f : -38f);
		pose.rotateDegrees(Axis.ZP, -24f);
		float s = putter ? 0.55f : 0.62f;
		pose.scale(s, s, s);
		// Put the grip (model y = 28/16, minus the half-block centring) on the pivot.
		pose.translate(0, -1.25, 0);
	}

	private static double easeOut(double x) {
		x = Math.max(0, Math.min(1, x));
		return 1 - (1 - x) * (1 - x);
	}

	private static double easeInOut(double x) {
		x = Math.max(0, Math.min(1, x));
		return x * x * (3 - 2 * x);
	}
}
