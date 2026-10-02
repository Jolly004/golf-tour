package work.benwalker.golftour.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import work.benwalker.golftour.net.HitBallPayload;
import work.benwalker.golftour.physics.Club;
import work.benwalker.golftour.physics.ShotCalculator;
import work.benwalker.golftour.physics.ShotType;
import work.benwalker.golftour.registry.GolfSounds;

/**
 * PGA 2K-style mouse swing (the "swing stick"). Hold right-click at the ball, pull the mouse back (towards you) for
 * the backswing, then push it forward through the ball. How far back you pull sets the power, and the club follows
 * the mouse; the strike happens as the mouse passes its starting point.
 * <p>
 * The shot's shape comes from how straight the stroke was: straight back and straight through is pure, pushing
 * forward and to the right pushes or slices, forward and to the left pulls or hooks. A crooked takeaway counts
 * half as much as the forward stroke. Tempo matters too: a dawdling downswing loses distance, a snatched one
 * exaggerates any miss. Letting go of right-click before impact cancels the swing.
 */
public final class SwingMeter {
	public enum Phase {
		IDLE, BACKSWING, DOWNSWING
	}

	/** Result of the last swing, shown on the HUD for a few seconds. */
	public record Feedback(double backDegrees, double throughDegrees, double tempoSeconds, String tempo, int tempoColor, int at) {
	}

	/** Mouse travel for a full (100%) backswing, as a fraction of the window height. */
	public static final double FULL_SWING = 0.30;
	/** How far forward from the top (power units) the mouse must come before the downswing has started. */
	private static final double TRANSITION = 0.04;
	/** Meter accuracy per degree of stroke angle (see {@link ShotCalculator.Swing#accuracy}). */
	private static final double ACCURACY_PER_DEGREE = 0.0055;
	/** Largest miss a crooked stroke can produce; stays clear of a duff. */
	private static final double MAX_MISS = 0.22;

	private static Phase phase = Phase.IDLE;
	/** Current backswing depth (power units: 1 = full swing) and its deepest point. */
	private static double back, peak;
	/** Sideways mouse position (same units) now and at the top of the backswing. */
	private static double x, xTop;
	private static long startNanos, topNanos;
	private static double lockedPower;
	private static Club club;
	private static ShotType type;
	private static double plannedPower;
	private static final List<double[]> trail = new ArrayList<>();
	private static Feedback last;

	private SwingMeter() {
	}

	public static Phase phase() {
		return phase;
	}

	/** True while the mouse is driving the club (it doesn't turn the camera). */
	public static boolean capturing() {
		return phase != Phase.IDLE;
	}

	public static Club club() {
		return club;
	}

	/** Power of the swing: the depth of the backswing once the downswing has started. */
	public static double lockedPower() {
		return phase == Phase.DOWNSWING ? lockedPower : peak;
	}

	/** Club position on the meter: how far back the mouse is right now. */
	public static double needle() {
		return phase == Phase.IDLE ? 0 : back;
	}

	/** The mouse path of the current (or last) swing as (sideways, depth) points. */
	public static List<double[]> trail() {
		return trail;
	}

	public static Feedback feedback() {
		return last;
	}

	public static void reset() {
		phase = Phase.IDLE;
	}

	public static void cancel() {
		phase = Phase.IDLE;
	}

	public static double plannedPower() {
		return phase == Phase.IDLE ? ShotPlanner.plannedPower() : plannedPower;
	}

	public static ShotType type() {
		return type;
	}

	/** Right-click pressed at the ball: the mouse now drives the club. */
	public static void begin() {
		club = ClientGolf.heldClub();
		if (club == null) {
			return;
		}
		type = ClientGolf.shotTypeFor(club);
		plannedPower = ShotPlanner.plannedPower();
		back = peak = x = xTop = 0;
		lockedPower = 0;
		trail.clear();
		trail.add(new double[] {0, 0});
		startNanos = topNanos = System.nanoTime();
		phase = Phase.BACKSWING;
		play(GolfSounds.UI_METER_TICK, 0.9f);
	}

	/** Right-click released: before impact that cancels the swing. */
	public static void release() {
		if (phase != Phase.IDLE) {
			cancel();
			ClientGolf.notice("SWING CANCELLED", 0xC0C0C0);
		}
	}

	/** Raw mouse movement in window pixels (y grows downwards, so pulling the mouse back is +dy). */
	public static void onMouse(double dx, double dy, double windowHeight) {
		if (phase == Phase.IDLE || windowHeight <= 0) {
			return;
		}
		double unit = windowHeight * FULL_SWING;
		x += dx / unit;
		double dBack = dy / unit;
		long now = System.nanoTime();
		if (phase == Phase.BACKSWING) {
			back = Math.max(0, Math.min(ShotCalculator.MAX_POWER, back + dBack));
			if (back >= peak) {
				peak = back;
				xTop = x;
				topNanos = now;
			} else if (peak > 0.03 && back < peak - TRANSITION) {
				phase = Phase.DOWNSWING;
				lockedPower = Math.max(0.02, Math.min(ShotCalculator.MAX_POWER, peak));
				play(GolfSounds.UI_METER_LOCK, 1.0f);
			}
		} else {
			back = Math.min(peak, back + dBack);
		}
		if (trail.size() < 600) {
			trail.add(new double[] {x, back});
		}
		if (phase == Phase.DOWNSWING && back <= 0) {
			strike(now);
		}
	}

	/** Per client tick: drop the swing if the player can no longer make it, or it stalls. */
	public static void tick() {
		if (phase == Phase.IDLE) {
			return;
		}
		if (!ClientGolf.canSwing() || ClientGolf.heldClub() != club) {
			cancel();
			return;
		}
		long now = System.nanoTime();
		if (phase == Phase.BACKSWING && (now - startNanos) / 1e9 > 8) {
			cancel();
			ClientGolf.notice("SWING TIMED OUT · PULL BACK, THEN PUSH THROUGH", 0xFFB060);
		} else if (phase == Phase.DOWNSWING && (now - topNanos) / 1e9 > 3) {
			cancel();
			ClientGolf.notice("SWING TIMED OUT · PUSH THROUGH THE BALL", 0xFFB060);
		}
	}

	private static void strike(long now) {
		phase = Phase.IDLE;
		boolean putter = club.isPutter();
		double tempo = (now - topNanos) / 1e9;
		double depth = Math.max(peak, 1e-3);
		double backDegrees = Math.toDegrees(Math.atan2(xTop, depth));
		double throughDegrees = Math.toDegrees(Math.atan2(x - xTop, Math.max(peak - back, 1e-3)));
		double path = throughDegrees + 0.5 * backDegrees;

		double power = lockedPower;
		double fast = putter ? 0.12 : 0.08, slow = putter ? 1.3 : 0.6;
		double amplify = 1.0;
		String label;
		int color;
		if (tempo < fast) {
			label = "FAST";
			color = 0xFFFF9933;
			amplify = 1.5;
		} else if (tempo > slow) {
			label = "SLOW";
			color = 0xFF66B3FF;
			power *= Math.max(putter ? 0.9 : 0.85, 1 - (tempo - slow) * 0.2);
		} else {
			label = "GOOD";
			color = 0xFF55FF55;
		}
		double accuracy = Math.max(-MAX_MISS, Math.min(MAX_MISS, -path * ACCURACY_PER_DEGREE * amplify));
		last = new Feedback(backDegrees, throughDegrees, tempo, label, color, ClientGolf.ticks);

		ClubAnimator.onStrike(power);
		ShotPlanner.Aim aim = ShotPlanner.aim();
		if (aim == null) {
			return;
		}
		ClientPlayNetworking.send(new HitBallPayload(club.ordinal(), type.ordinal(), (float) power, (float) accuracy, (float) ClientGolf.shape,
			(float) ClientGolf.spin, aim.yaw(), (float) aim.puttTarget()));
	}

	private static void play(net.minecraft.sounds.SoundEvent sound, float pitch) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.playSound(sound, 0.6f, pitch);
		}
	}
}
