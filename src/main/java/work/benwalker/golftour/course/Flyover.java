package work.benwalker.golftour.course;

import java.util.ArrayList;
import java.util.List;

import work.benwalker.golftour.physics.Vec;

/**
 * The broadcast-style hole flyover shown before each tee shot: the camera rises behind the tee, sweeps down
 * the hole and settles over the green looking at the pin. Shared by the client (smooth camera) and the server
 * (which moves the player along the same path so the terrain ahead streams in).
 */
public final class Flyover {
	/** Ticks the camera holds on the opening shot while the hole streams in. */
	public static final int LEAD_IN = 30;
	public static final int TICKS = 150;

	public record Pose(Vec pos, float yaw, float pitch) {
	}

	private Flyover() {
	}

	public static Pose at(CourseLayout course, HoleLayout hole, double t) {
		double e = smooth(Math.max(0, Math.min(1, t)));
		List<Vec> pts = controlPoints(course, hole);
		Vec pos = spline(pts, e);

		double len = hole.lengthBlocks();
		double ahead = Math.min(len, e * len + 70);
		Vec aheadPoint = hole.pointAt(ahead);
		aheadPoint = aheadPoint.withY(ground(course, aheadPoint) + 1);
		Vec pin = new Vec(hole.pin().x(), hole.greenY() + 1.5, hole.pin().z());
		double toPin = smoothstep(0.45, 0.85, e);
		Vec look = aheadPoint.lerp(pin, toPin).sub(pos);
		float yaw = Vec.yawOf(look);
		float pitch = (float) -Math.toDegrees(Math.atan2(look.y(), look.horizontalLength()));
		return new Pose(pos, yaw, pitch);
	}

	private static List<Vec> controlPoints(CourseLayout course, HoleLayout hole) {
		double len = hole.lengthBlocks();
		Vec first = hole.line().get(1).sub(hole.tee()).horizontal().normalize();
		Vec last = hole.green().sub(hole.line().get(hole.line().size() - 2)).horizontal().normalize();
		List<Vec> pts = new ArrayList<>();
		pts.add(lift(course, hole.tee().sub(first.scale(18)), 10));
		pts.add(lift(course, hole.tee().sub(first.scale(6)), 13));
		pts.add(lift(course, hole.pointAt(len * 0.3), 24));
		pts.add(lift(course, hole.pointAt(len * 0.62), 26));
		pts.add(lift(course, hole.green().sub(last.scale(26)), 15));
		pts.add(lift(course, hole.green().sub(last.scale(14)), 9));
		return pts;
	}

	private static Vec lift(CourseLayout course, Vec p, double up) {
		return p.withY(ground(course, p) + up);
	}

	private static double ground(CourseLayout course, Vec p) {
		return course.heightAt(p.x(), p.z());
	}

	/** Centripetal-ish Catmull-Rom through the points, parameterised uniformly over segments. */
	private static Vec spline(List<Vec> p, double t) {
		int segments = p.size() - 1;
		double s = t * segments;
		int i = Math.min(segments - 1, (int) Math.floor(s));
		double u = s - i;
		Vec p0 = p.get(Math.max(0, i - 1)), p1 = p.get(i), p2 = p.get(i + 1), p3 = p.get(Math.min(p.size() - 1, i + 2));
		double u2 = u * u, u3 = u2 * u;
		return p0.scale(-0.5 * u3 + u2 - 0.5 * u)
			.add(p1.scale(1.5 * u3 - 2.5 * u2 + 1))
			.add(p2.scale(-1.5 * u3 + 2 * u2 + 0.5 * u))
			.add(p3.scale(0.5 * u3 - 0.5 * u2));
	}

	private static double smooth(double t) {
		return t * t * (3 - 2 * t);
	}

	private static double smoothstep(double a, double b, double x) {
		double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}
}
