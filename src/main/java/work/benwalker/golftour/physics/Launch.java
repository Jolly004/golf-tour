package work.benwalker.golftour.physics;

/**
 * How the ball leaves the club face.
 *
 * @param velocity initial velocity in blocks per second
 * @param spinRpm total spin rate
 * @param spinAxis unit spin axis; pure backspin points to the right of the flight direction, tilting it curves the ball
 * @param rolling true for putts: the ball starts on the ground already rolling
 */
public record Launch(Vec velocity, double spinRpm, Vec spinAxis, boolean rolling) {
	/** Builds a full-swing launch. Positive {@code tiltDegrees} curves the ball right (fade), negative left (draw). */
	public static Launch fullSwing(double yawDegrees, double launchDegrees, double speed, double spinRpm, double tiltDegrees) {
		Vec forward = Vec.fromYaw(yawDegrees);
		double l = Math.toRadians(launchDegrees);
		Vec velocity = forward.scale(Math.cos(l) * speed).add(0, Math.sin(l) * speed, 0);
		Vec right = forward.cross(Vec.UP).normalize();
		Vec axis = right.rotateAround(forward, Math.toRadians(tiltDegrees)).normalize();
		return new Launch(velocity, spinRpm, axis, false);
	}

	public static Launch putt(double yawDegrees, double speed) {
		Vec forward = Vec.fromYaw(yawDegrees);
		return new Launch(forward.scale(speed), 0, forward.cross(Vec.UP).normalize(), true);
	}
}
