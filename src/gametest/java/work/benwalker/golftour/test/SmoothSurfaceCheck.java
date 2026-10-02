package work.benwalker.golftour.test;

import io.github.cadiboo.nocubes.NoCubes;
import io.github.cadiboo.nocubes.config.NoCubesConfig;
import io.github.cadiboo.nocubes.util.Area;
import io.github.cadiboo.nocubes.util.Face;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import work.benwalker.golftour.course.CourseLayout;
import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.world.LevelBallWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Measures how far the ball's contact surface ({@link LevelBallWorld#surfaceHeight}) is from the terrain NoCubes
 * actually draws and collides with, by running NoCubes' mesher over a patch of course and intersecting vertical
 * rays with its faces. Server thread only.
 */
final class SmoothSurfaceCheck {

	record Result(String name, int samples, double mean, double max) {
		@Override
		public String toString() {
			return String.format(Locale.ROOT, "%-14s samples %4d  mean %.3f  max %.3f blocks", name, samples, mean, max);
		}
	}

	private SmoothSurfaceCheck() {
	}

	static Result measure(String name, ServerLevel level, CourseLayout course, double cx, double cz, int radius) {
		int x0 = (int) Math.floor(cx) - radius, z0 = (int) Math.floor(cz) - radius, size = radius * 2;
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		for (int x = x0; x < x0 + size; x++) {
			for (int z = z0; z < z0 + size; z++) {
				level.getChunk(x >> 4, z >> 4);
				int g = course.column(x, z).groundY();
				minY = Math.min(minY, g);
				maxY = Math.max(maxY, g);
			}
		}
		List<float[]> triangles = new ArrayList<>();
		var start = new BlockPos(x0, minY - 3, z0);
		var mesher = NoCubesConfig.Server.mesher;
		try (var area = new Area(level, start, new BlockPos(size, maxY - minY + 7, size), mesher)) {
			mesher.generateGeometry(area, NoCubes::isSmoothable, (pos, face) -> {
				addTriangles(triangles, face, area.start);
				return true;
			});
		}

		var ball = new LevelBallWorld(level, course);
		double sum = 0, max = 0;
		int n = 0;
		for (double x = x0 + 2.125; x < x0 + size - 2; x += 0.5) {
			for (double z = z0 + 2.125; z < z0 + size - 2; z += 0.5) {
				var c = course.column((int) Math.floor(x), (int) Math.floor(z));
				if (c.waterTop() != Integer.MIN_VALUE || c.surface() == Surface.OUT || c.surface() == Surface.DEEP_ROUGH) {
					continue; // under water or under trees
				}
				double expected = ball.surfaceHeight(x, z);
				double best = Double.NaN;
				for (float[] t : triangles) {
					double y = rayHeight(t, x, z);
					if (!Double.isNaN(y) && (Double.isNaN(best) || Math.abs(y - expected) < Math.abs(best - expected))) {
						best = y;
					}
				}
				if (Double.isNaN(best)) {
					continue;
				}
				double err = Math.abs(best - expected);
				sum += err;
				max = Math.max(max, err);
				n++;
			}
		}
		return new Result(name, n, n == 0 ? Double.NaN : sum / n, max);
	}

	private static void addTriangles(List<float[]> out, Face face, BlockPos origin) {
		float ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
		float[] q = {
			face.v0.x + ox, face.v0.y + oy, face.v0.z + oz,
			face.v1.x + ox, face.v1.y + oy, face.v1.z + oz,
			face.v2.x + ox, face.v2.y + oy, face.v2.z + oz,
			face.v3.x + ox, face.v3.y + oy, face.v3.z + oz,
		};
		out.add(new float[]{q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7], q[8]});
		out.add(new float[]{q[0], q[1], q[2], q[6], q[7], q[8], q[9], q[10], q[11]});
	}

	/** Height where a vertical line at (x, z) crosses the triangle, or NaN. */
	private static double rayHeight(float[] t, double x, double z) {
		double ax = t[0], ay = t[1], az = t[2], bx = t[3], by = t[4], bz = t[5], qx = t[6], qy = t[7], qz = t[8];
		double det = (bz - qz) * (ax - qx) + (qx - bx) * (az - qz);
		if (Math.abs(det) < 1e-9) {
			return Double.NaN;
		}
		double l1 = ((bz - qz) * (x - qx) + (qx - bx) * (z - qz)) / det;
		double l2 = ((qz - az) * (x - qx) + (ax - qx) * (z - qz)) / det;
		double l3 = 1 - l1 - l2;
		if (l1 < -1e-6 || l2 < -1e-6 || l3 < -1e-6) {
			return Double.NaN;
		}
		return l1 * ay + l2 * by + l3 * qy;
	}
}
