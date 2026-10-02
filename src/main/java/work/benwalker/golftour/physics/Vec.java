package work.benwalker.golftour.physics;

/**
 * Immutable 3D vector in world units (1 unit = 1 block). Deliberately independent of Minecraft so the
 * ball physics can be unit tested without bootstrapping the game.
 */
public record Vec(double x, double y, double z) {
	public static final Vec ZERO = new Vec(0, 0, 0);
	public static final Vec UP = new Vec(0, 1, 0);

	public Vec add(Vec o) {
		return new Vec(x + o.x, y + o.y, z + o.z);
	}

	public Vec add(double dx, double dy, double dz) {
		return new Vec(x + dx, y + dy, z + dz);
	}

	public Vec sub(Vec o) {
		return new Vec(x - o.x, y - o.y, z - o.z);
	}

	public Vec scale(double s) {
		return new Vec(x * s, y * s, z * s);
	}

	public double dot(Vec o) {
		return x * o.x + y * o.y + z * o.z;
	}

	public Vec cross(Vec o) {
		return new Vec(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
	}

	public double length() {
		return Math.sqrt(x * x + y * y + z * z);
	}

	public double lengthSqr() {
		return x * x + y * y + z * z;
	}

	public double horizontalLength() {
		return Math.sqrt(x * x + z * z);
	}

	public Vec horizontal() {
		return new Vec(x, 0, z);
	}

	public Vec withY(double newY) {
		return new Vec(x, newY, z);
	}

	public Vec normalize() {
		double len = length();
		return len < 1e-9 ? ZERO : new Vec(x / len, y / len, z / len);
	}

	public double distanceTo(Vec o) {
		return sub(o).length();
	}

	public double horizontalDistanceTo(Vec o) {
		double dx = x - o.x, dz = z - o.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	public Vec lerp(Vec o, double t) {
		return new Vec(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
	}

	/** Turns around the vertical axis like adding {@code degrees} to a Minecraft yaw (positive = turn right). */
	public Vec rotateYaw(double degrees) {
		double r = Math.toRadians(degrees);
		double c = Math.cos(r), s = Math.sin(r);
		return new Vec(x * c - z * s, y, x * s + z * c);
	}

	/** Rotates this vector around {@code axis} (unit length) by {@code radians}, Rodrigues' formula. */
	public Vec rotateAround(Vec axis, double radians) {
		double c = Math.cos(radians), s = Math.sin(radians);
		return scale(c).add(axis.cross(this).scale(s)).add(axis.scale(axis.dot(this) * (1 - c)));
	}

	/** Horizontal unit direction for a Minecraft yaw in degrees (0 = south/+z, 90 = west/-x). */
	public static Vec fromYaw(double yawDegrees) {
		double r = Math.toRadians(yawDegrees);
		return new Vec(-Math.sin(r), 0, Math.cos(r));
	}

	/** Minecraft yaw in degrees for a direction's horizontal component. */
	public static float yawOf(Vec dir) {
		return (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
	}
}
