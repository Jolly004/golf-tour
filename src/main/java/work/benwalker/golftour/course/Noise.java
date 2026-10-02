package work.benwalker.golftour.course;

/** Small deterministic value noise and hashing used to shape the course terrain and scatter trees. */
public final class Noise {
	private final long seed;

	public Noise(long seed) {
		this.seed = seed;
	}

	public static long hash(long seed, long x, long z) {
		long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L;
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 32;
		h *= 0x94D049BB133111EBL;
		h ^= h >>> 29;
		return h;
	}

	/** Uniform 0..1 from a hash. */
	public static double unit(long seed, long x, long z) {
		return (hash(seed, x, z) >>> 11) * 0x1.0p-53;
	}

	/** Smooth noise in roughly -1..1. */
	public double value(double x, double z) {
		long x0 = (long) Math.floor(x), z0 = (long) Math.floor(z);
		double fx = x - x0, fz = z - z0;
		double sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
		double a = unit(seed, x0, z0), b = unit(seed, x0 + 1, z0);
		double c = unit(seed, x0, z0 + 1), d = unit(seed, x0 + 1, z0 + 1);
		double top = a + (b - a) * sx, bottom = c + (d - c) * sx;
		return (top + (bottom - top) * sz) * 2 - 1;
	}

	/** Two-octave fractal noise in roughly -1..1. */
	public double fbm(double x, double z) {
		return value(x, z) * 0.67 + value(x * 2.03 + 17.1, z * 2.03 - 9.3) * 0.33;
	}
}
