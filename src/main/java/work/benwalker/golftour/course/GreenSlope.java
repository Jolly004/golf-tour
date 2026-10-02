package work.benwalker.golftour.course;

import java.util.List;

import work.benwalker.golftour.physics.Vec;

/**
 * A green's virtual height field: an overall tilt plus a few gentle humps and hollows. The blocks stay flat;
 * the slope only exists for the rolling ball and the green-reading overlay.
 */
public record GreenSlope(double centerX, double centerZ, double tiltX, double tiltZ, List<Bump> bumps) {
	/** Steepest slope allowed anywhere, so a ball can always come to rest. */
	public static final double MAX_SLOPE = 0.035;

	public record Bump(double x, double z, double amplitude, double sigma) {
	}

	/** Height of the virtual surface relative to the green centre, in blocks. */
	public double height(double x, double z) {
		double h = tiltX * (x - centerX) + tiltZ * (z - centerZ);
		for (Bump b : bumps) {
			double dx = x - b.x(), dz = z - b.z();
			h += b.amplitude() * Math.exp(-(dx * dx + dz * dz) / (2 * b.sigma() * b.sigma()));
		}
		return h;
	}

	/** Gradient (dh/dx, 0, dh/dz), clamped to {@link #MAX_SLOPE}. */
	public Vec gradient(double x, double z) {
		double gx = tiltX, gz = tiltZ;
		for (Bump b : bumps) {
			double dx = x - b.x(), dz = z - b.z();
			double s2 = b.sigma() * b.sigma();
			double e = b.amplitude() * Math.exp(-(dx * dx + dz * dz) / (2 * s2));
			gx += -dx / s2 * e;
			gz += -dz / s2 * e;
		}
		double mag = Math.sqrt(gx * gx + gz * gz);
		if (mag > MAX_SLOPE) {
			gx *= MAX_SLOPE / mag;
			gz *= MAX_SLOPE / mag;
		}
		return new Vec(gx, 0, gz);
	}
}
