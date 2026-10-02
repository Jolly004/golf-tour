package work.benwalker.golftour.physics;

/** The view of the world the ball simulator needs: what is in each block cell, and green slopes. */
public interface BallWorld {
	enum Kind {
		/** Nothing the ball can hit (air, grass, flowers, flagsticks). */
		AIR,
		/** Collides. {@link Cell#height()} is the top of the collision shape within the cell (1 = full block). */
		SOLID,
		/** Tree leaves: the ball crashes through and loses most of its speed. */
		LEAVES,
		/** A water hazard. */
		WATER
	}

	record Cell(Kind kind, Surface surface, double height) {
		public static final Cell EMPTY = new Cell(Kind.AIR, null, 0);
		public static final Cell WATER_CELL = new Cell(Kind.WATER, Surface.WATER, 1);
		public static final Cell LEAF_CELL = new Cell(Kind.LEAVES, Surface.ROUGH, 1);

		public static Cell solid(Surface surface, double height) {
			return new Cell(Kind.SOLID, surface, height);
		}
	}

	Cell cell(int x, int y, int z);

	/**
	 * Gradient (dh/dx, 0, dh/dz) of the putting surface at a point, or {@code null} when flat. Greens are
	 * flat blocks with a virtual height field so putts break like real greens.
	 */
	default Vec slopeAt(double x, double z) {
		return null;
	}

	/**
	 * Smooth ground height at a point, or NaN when this world only has blocks. When present, the ball lands
	 * on and rolls over this continuous surface (and its slope), and blocks only matter as obstacles.
	 */
	default double surfaceHeight(double x, double z) {
		return Double.NaN;
	}

	/** Gradient (dh/dx, 0, dh/dz) of {@link #surfaceHeight}. */
	default Vec surfaceGradient(double x, double z) {
		double e = 0.05;
		return new Vec((surfaceHeight(x + e, z) - surfaceHeight(x - e, z)) / (2 * e), 0,
			(surfaceHeight(x, z + e) - surfaceHeight(x, z - e)) / (2 * e));
	}

	/** Below this height the ball is lost. */
	default int minY() {
		return -64;
	}

	/** An endless flat world with its top at {@code groundY}, used to calibrate clubs and in tests. */
	static BallWorld flat(Surface surface, int groundY) {
		Cell ground = Cell.solid(surface, 1.0);
		return (x, y, z) -> y < groundY ? ground : Cell.EMPTY;
	}
}
