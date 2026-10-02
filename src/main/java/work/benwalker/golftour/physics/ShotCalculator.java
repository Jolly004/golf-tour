package work.benwalker.golftour.physics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a swing (club, shot type, meter power and timing, strike adjustments, lie) into a {@link Launch}.
 * Shared by the server (real shot) and the client (preview), so both agree exactly.
 */
public final class ShotCalculator {
	/** Needle position (meter units) at which a missed third click becomes a duff. */
	public static final double DUFF_ACCURACY = -0.24;
	/** Overswing limit of the meter. */
	public static final double MAX_POWER = 1.1;

	public enum Quality {
		PERFECT("PERFECT", 0x55FF55),
		GREAT("GREAT", 0xA8FF60),
		GOOD("GOOD", 0xFFFF55),
		HOOK("HOOK", 0xFF9933),
		SLICE("SLICE", 0xFF9933),
		PULL("PULL", 0xFF9933),
		PUSH("PUSH", 0xFF9933),
		DUFF("DUFFED", 0xFF5555);

		public final String label;
		public final int color;

		Quality(String label, int color) {
			this.label = label;
			this.color = color;
		}
	}

	/**
	 * @param power meter power, 0..{@link #MAX_POWER}; for full swings it is the fraction of the club's carry,
	 *              for putts the fraction of {@code puttTarget}
	 * @param accuracy needle position at the third click: 0 is the sweet spot, positive early, negative late
	 * @param shape -1 (draw) .. 1 (fade)
	 * @param spinAdjust -1 (topspin) .. 1 (extra backspin)
	 * @param aimYaw Minecraft yaw towards the target
	 * @param puttTarget flat-green putt length in blocks that 100% power rolls (putter only)
	 */
	public record Swing(Club club, ShotType type, double power, double accuracy, double shape, double spinAdjust, float aimYaw,
						Surface lie, double puttTarget) {
		public Swing {
			if (!type.allowedFor(club)) {
				type = ShotType.defaultFor(club);
			}
			power = clamp(power, 0, MAX_POWER);
			accuracy = clamp(accuracy, DUFF_ACCURACY, 1.0);
			shape = clamp(shape, -1, 1);
			spinAdjust = clamp(spinAdjust, -1, 1);
			puttTarget = clamp(puttTarget, 0.2, 80);
		}

		public static Swing perfect(Club club, ShotType type, double power, double shape, double spinAdjust, float aimYaw, Surface lie, double puttTarget) {
			return new Swing(club, type, power, 0, shape, spinAdjust, aimYaw, lie, puttTarget);
		}
	}

	public record Result(Launch launch, Quality quality, double targetCarry) {
	}

	private ShotCalculator() {
	}

	/** Width (meter units, either side of zero) of the sweet spot for this swing. */
	public static double sweetSpot(Club club, ShotType type, double power) {
		double window = 0.035 * club.forgiveness * type.windowMul;
		return power > 1.0 ? window * 0.6 : window;
	}

	/** Carry multiplier for playing {@code club} from {@code lie}. */
	public static double lieFactor(Club club, Surface lie) {
		if (club.isPutter()) {
			return 1.0;
		}
		if (lie == Surface.BUNKER) {
			return switch (club.category) {
				case DRIVER, WOOD -> 0.55;
				case HYBRID -> 0.70;
				case IRON -> 0.80;
				case WEDGE -> club == Club.SAND_WEDGE || club == Club.LOB_WEDGE ? 0.92 : 0.86;
				case PUTTER -> 1.0;
			};
		}
		if (club == Club.DRIVER && lie != Surface.TEE) {
			return lie.lieDistance * 0.9;
		}
		return lie.lieDistance;
	}

	public static Result compute(Swing s) {
		Club club = s.club();
		double window = sweetSpot(club, s.type(), s.power());
		double acc = s.accuracy();
		boolean duff = acc <= DUFF_ACCURACY + 1e-9;
		double excess = Math.max(0, Math.abs(acc) - window);
		double err = Math.signum(acc) * excess * (s.power() > 1.0 ? 1.5 : 1.0);

		Quality quality;
		if (duff) {
			quality = Quality.DUFF;
		} else if (excess == 0) {
			quality = Math.abs(acc) <= window * 0.5 ? Quality.PERFECT : Quality.GREAT;
		} else if (excess < 0.03) {
			quality = Quality.GOOD;
		} else if (club.isPutter()) {
			quality = err > 0 ? Quality.PULL : Quality.PUSH;
		} else {
			quality = err > 0 ? Quality.HOOK : Quality.SLICE;
		}

		if (club.isPutter()) {
			double yaw = s.aimYaw() - err * 25.0;
			double distance = s.puttTarget() * s.power() * (duff ? 0.4 : 1.0);
			double speed = Math.sqrt(2 * Surface.GREEN.rollDecel * Math.max(0.01, distance));
			return new Result(Launch.putt(yaw, speed), quality, distance);
		}

		double fraction = Math.min(s.power(), 1.0) + Math.max(0, s.power() - 1.0) * 0.6;
		double missFactor = duff ? 0.3 : 1 - Math.min(0.5, 2.0 * excess);
		double targetCarry = club.carryBlocks() * s.type().distanceMul * fraction * lieFactor(club, s.lie()) * missFactor;
		double launchDeg = launchAngle(club, s.type(), s.spinAdjust(), duff);
		double spin = spinRpm(club, s.type(), s.lie(), s.spinAdjust(), fraction);
		double tilt = s.shape() * 14.0 - err * 170.0;
		double yaw = s.aimYaw() - s.shape() * 2.5 - err * 30.0 + (duff ? 6.0 : 0.0);
		double speed = speedForCarry(launchDeg, spin, targetCarry);
		return new Result(Launch.fullSwing(yaw, launchDeg, speed, spin, tilt), quality, targetCarry);
	}

	static double launchAngle(Club club, ShotType type, double spinAdjust, boolean duff) {
		double angle = club.launchDegrees * type.launchMul + spinAdjust * 1.5;
		return duff ? angle * 0.5 : Math.max(2.0, angle);
	}

	static double spinRpm(Club club, ShotType type, Surface lie, double spinAdjust, double fraction) {
		return club.spinRpm * type.spinMul * lie.lieSpin * (1 + 0.45 * spinAdjust) * (0.6 + 0.4 * Math.min(1, fraction));
	}

	// ---------------------------------------------------------------- calibration

	private static final Map<Long, Double> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Double> eldest) {
			return size() > 4096;
		}
	};
	private static final BallWorld FLAT = BallWorld.flat(Surface.FAIRWAY, 0);

	/** Launch speed (blocks/s) that carries {@code carryBlocks} over flat ground with no wind. */
	public static double speedForCarry(double launchDeg, double spinRpm, double carryBlocks) {
		if (carryBlocks <= 0.05) {
			return 0.5;
		}
		long key = Math.round(launchDeg * 20) * 1_000_003L * 1_000_003L + Math.round(spinRpm / 25) * 1_000_003L + Math.round(carryBlocks * 50);
		synchronized (CACHE) {
			Double cached = CACHE.get(key);
			if (cached != null) {
				return cached;
			}
		}
		double lo = 0.5, hi = 140;
		for (int i = 0; i < 26; i++) {
			double mid = (lo + hi) / 2;
			if (carry(launchDeg, spinRpm, mid) < carryBlocks) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		double speed = (lo + hi) / 2;
		synchronized (CACHE) {
			CACHE.put(key, speed);
		}
		return speed;
	}

	/** Flat-ground carry in blocks for a straight shot. */
	public static double carry(double launchDeg, double spinRpm, double speed) {
		Vec start = new Vec(0.5, 0.0, 0.5);
		Launch launch = Launch.fullSwing(0, launchDeg, speed, spinRpm, 0);
		return BallSimulator.simulate(FLAT, start, launch, BallSimulator.Options.carryOnly()).carryFrom(start);
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
