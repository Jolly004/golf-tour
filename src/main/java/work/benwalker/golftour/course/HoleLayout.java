package work.benwalker.golftour.course;

import java.util.List;

import work.benwalker.golftour.physics.Units;
import work.benwalker.golftour.physics.Vec;

/**
 * One hole's design. Horizontal geometry uses {@link Vec} x/z (y unused) in block coordinates.
 *
 * @param line tee centre, dogleg corners, green centre
 * @param greenLong green ellipse radius along {@code greenAngle}
 * @param greenShort green ellipse radius across it
 * @param greenAngle yaw of the green's long axis (Minecraft degrees)
 * @param pin cup position (cell centre, x/z)
 * @param fairwayStart arc length along {@code line} where the fairway begins
 * @param fairwayEnd arc length where it ends
 * @param bunkers each bunker is a union of circles
 * @param water ponds, each a union of circles
 * @param slope the green's virtual height field
 */
public record HoleLayout(int number, int par, List<Vec> line, double greenLong, double greenShort, double greenAngle, Vec pin,
						 double fairwayStart, double fairwayEnd, double fairwayHalfWidth, double widthPhase,
						 List<List<Circle>> bunkers, List<List<Circle>> water, GreenSlope slope, int teeY, int greenY, int waterLevel) {

	public record Circle(double x, double z, double r) {
		public boolean contains(double px, double pz) {
			double dx = px - x, dz = pz - z;
			return dx * dx + dz * dz <= r * r;
		}
	}

	/** Closest point on the centre line: distance and arc length. */
	public record LinePoint(double distance, double along) {
	}

	public static final double TEE_LENGTH = 9;
	public static final double TEE_WIDTH = 6;

	public Vec tee() {
		return line.get(0);
	}

	public Vec green() {
		return line.get(line.size() - 1);
	}

	/** Yaw from the tee towards the first target on the centre line. */
	public float teeYaw() {
		return Vec.yawOf(line.get(1).sub(line.get(0)));
	}

	public double lengthBlocks() {
		double total = 0;
		for (int i = 1; i < line.size(); i++) {
			total += line.get(i).horizontalDistanceTo(line.get(i - 1));
		}
		return total;
	}

	public int yards() {
		return (int) Math.round(Units.toYards(lengthBlocks()));
	}

	public LinePoint nearest(double x, double z) {
		double best = Double.MAX_VALUE, bestAlong = 0, walked = 0;
		for (int i = 1; i < line.size(); i++) {
			Vec a = line.get(i - 1), b = line.get(i);
			double abx = b.x() - a.x(), abz = b.z() - a.z();
			double len2 = abx * abx + abz * abz;
			double len = Math.sqrt(len2);
			double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - a.x()) * abx + (z - a.z()) * abz) / len2));
			double cx = a.x() + abx * t - x, cz = a.z() + abz * t - z;
			double d = Math.sqrt(cx * cx + cz * cz);
			if (d < best) {
				best = d;
				bestAlong = walked + t * len;
			}
			walked += len;
		}
		return new LinePoint(best, bestAlong);
	}

	/** Point at arc length {@code s} along the centre line. */
	public Vec pointAt(double s) {
		double walked = 0;
		for (int i = 1; i < line.size(); i++) {
			Vec a = line.get(i - 1), b = line.get(i);
			double len = a.horizontalDistanceTo(b);
			if (walked + len >= s || i == line.size() - 1) {
				double t = len < 1e-9 ? 0 : Math.max(0, Math.min(1, (s - walked) / len));
				return a.lerp(b, t);
			}
			walked += len;
		}
		return green();
	}

	/** Fairway half width at arc length {@code s}. */
	public double halfWidthAt(double s) {
		return fairwayHalfWidth + 1.5 * Math.sin(s / 21.0 + widthPhase);
	}

	/** Normalised elliptical radius of a point around the green (1 = green edge), optionally padded by {@code pad} blocks. */
	public double greenRadius(double x, double z, double pad) {
		double r = Math.toRadians(greenAngle);
		Vec along = new Vec(-Math.sin(r), 0, Math.cos(r));
		double dx = x - green().x(), dz = z - green().z();
		double u = dx * along.x() + dz * along.z();
		double v = -dx * along.z() + dz * along.x();
		double a = greenLong + pad, b = greenShort + pad;
		return Math.sqrt((u * u) / (a * a) + (v * v) / (b * b));
	}

	public boolean inTeeBox(double x, double z) {
		Vec dir = line.get(1).sub(line.get(0)).horizontal().normalize();
		double dx = x - tee().x(), dz = z - tee().z();
		double u = dx * dir.x() + dz * dir.z();
		double v = -dx * dir.z() + dz * dir.x();
		return Math.abs(u) <= TEE_LENGTH / 2 && Math.abs(v) <= TEE_WIDTH / 2;
	}

	/** Signed distance to the nearest pond edge (negative inside the water), or a large number if the hole is dry. */
	public double waterEdgeDistance(double x, double z) {
		double best = 1e9;
		for (List<Circle> pond : water) {
			for (Circle c : pond) {
				best = Math.min(best, Math.hypot(x - c.x(), z - c.z()) - c.r());
			}
		}
		return best;
	}

	/** Fairway yardage posts at 100, 150 and 200 yards from the centre of the green (right edge of the fairway). */
	public List<Marker> yardageMarkers() {
		List<Marker> out = new java.util.ArrayList<>();
		if (par < 4) {
			return out;
		}
		double len = lengthBlocks();
		for (int yards : new int[] {100, 150, 200}) {
			double s = len - work.benwalker.golftour.physics.Units.fromYards(yards);
			if (s < fairwayStart + 5) {
				continue;
			}
			Vec p = pointAt(s);
			Vec dir = pointAt(s + 1).sub(p).horizontal().normalize();
			Vec right = dir.cross(Vec.UP).normalize();
			out.add(new Marker(p.add(right.scale(halfWidthAt(s) + 1.3)), yards));
		}
		return out;
	}

	public record Marker(Vec pos, int yards) {
	}

	public static boolean inAny(List<List<Circle>> blobs, double x, double z) {
		for (List<Circle> blob : blobs) {
			for (Circle c : blob) {
				if (c.contains(x, z)) {
					return true;
				}
			}
		}
		return false;
	}
}
