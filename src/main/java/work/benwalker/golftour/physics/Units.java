package work.benwalker.golftour.physics;

/**
 * The course is built at half scale: one block is two yards. Distances shown to the player are real golf
 * yards and feet, and the physics runs in blocks with gravity and aerodynamics scaled to match, so a
 * 260-yard drive flies 130 blocks with a realistic shape and hang time.
 */
public final class Units {
	public static final double YARDS_PER_BLOCK = 2.0;
	public static final double METERS_PER_BLOCK = YARDS_PER_BLOCK * 0.9144;
	public static final double FEET_PER_BLOCK = YARDS_PER_BLOCK * 3.0;
	/** Gravity in blocks per second squared. */
	public static final double GRAVITY = 9.81 / METERS_PER_BLOCK;
	public static final double TICK_SECONDS = 0.05;

	private Units() {
	}

	public static double toYards(double blocks) {
		return blocks * YARDS_PER_BLOCK;
	}

	public static double toFeet(double blocks) {
		return blocks * FEET_PER_BLOCK;
	}

	public static double fromYards(double yards) {
		return yards / YARDS_PER_BLOCK;
	}

	public static double mphToBlocksPerSecond(double mph) {
		return mph * 0.44704 / METERS_PER_BLOCK;
	}

	public static double blocksPerSecondToMph(double bps) {
		return bps * METERS_PER_BLOCK / 0.44704;
	}

	public static double rpmToRadPerSecond(double rpm) {
		return rpm * 2 * Math.PI / 60.0;
	}

	public static double radPerSecondToRpm(double rad) {
		return rad * 60.0 / (2 * Math.PI);
	}
}
