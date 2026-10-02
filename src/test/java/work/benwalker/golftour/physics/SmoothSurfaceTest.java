package work.benwalker.golftour.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.DoubleBinaryOperator;

import org.junit.jupiter.api.Test;

/** The ball on a continuous surface: landing on slopes, breaking putts and rolling into bowls. */
class SmoothSurfaceTest {
	/** A world whose ground is the given height function, made of matching full-height turf below it. */
	private static BallWorld surface(Surface type, DoubleBinaryOperator height) {
		return new BallWorld() {
			@Override
			public Cell cell(int x, int y, int z) {
				// Like the course: full blocks below, and a partial-height block holding the surface itself.
				double h = height.applyAsDouble(x + 0.5, z + 0.5);
				if (y < Math.floor(h)) {
					return Cell.solid(type, 1.0);
				}
				return y == Math.floor(h) && h - y > 1e-6 ? Cell.solid(type, h - y) : Cell.EMPTY;
			}

			@Override
			public double surfaceHeight(double x, double z) {
				return height.applyAsDouble(x, z);
			}
		};
	}

	@Test
	void flatSmoothSurfaceMatchesBlockPhysics() {
		Vec start = new Vec(0.5, 0, 0.5);
		ShotCalculator.Swing swing = ShotCalculator.Swing.perfect(Club.IRON_7, ShotType.NORMAL, 1.0, 0, 0, 0f, Surface.TEE, 5);
		Launch launch = ShotCalculator.compute(swing).launch();
		BallSimulator.Result blocks = BallSimulator.simulate(BallWorld.flat(Surface.FAIRWAY, 0), start, launch, BallSimulator.Options.preview(null));
		BallSimulator.Result smooth = BallSimulator.simulate(surface(Surface.FAIRWAY, (x, z) -> 0), start, launch, BallSimulator.Options.preview(null));
		System.out.printf("7 iron total: blocks %.1f, smooth %.1f blocks%n", blocks.rest().horizontalDistanceTo(start), smooth.rest().horizontalDistanceTo(start));
		assertEquals(blocks.carryFrom(start), smooth.carryFrom(start), 0.3);
		assertEquals(blocks.rest().horizontalDistanceTo(start), smooth.rest().horizontalDistanceTo(start), 1.0);
	}

	@Test
	void puttBreaksOnARealSideSlope() {
		// Ground rises 2% towards +x, so a putt straight down +z drifts towards -x.
		BallWorld green = surface(Surface.GREEN, (x, z) -> 0.02 * x);
		Vec start = new Vec(0.5, 0.01, 0.5);
		ShotCalculator.Swing swing = ShotCalculator.Swing.perfect(Club.PUTTER, ShotType.PUTT, 1.0, 0, 0, 0f, Surface.GREEN, 5);
		BallSimulator.Result r = BallSimulator.simulate(green, start, ShotCalculator.compute(swing).launch(), BallSimulator.Options.preview(null));
		double drift = start.x() - r.rest().x();
		System.out.printf("30 ft putt on a 2%% side slope breaks %.2f blocks (%.1f ft), finishing on the slope at y=%.3f%n", drift, Units.toFeet(drift), r.rest().y());
		assertTrue(drift > 0.3, "breaks downhill");
		assertEquals(0.02 * r.rest().x(), r.rest().y(), 1e-3, "rests on the surface");
	}

	@Test
	void uphillPuttsComeUpShortDownhillRunsOut() {
		Vec start = new Vec(0.5, 0, 0.5);
		ShotCalculator.Swing swing = ShotCalculator.Swing.perfect(Club.PUTTER, ShotType.PUTT, 1.0, 0, 0, 0f, Surface.GREEN, 4);
		Launch launch = ShotCalculator.compute(swing).launch();
		double up = BallSimulator.simulate(surface(Surface.GREEN, (x, z) -> 0.02 * z), start, launch, BallSimulator.Options.preview(null)).rest().z();
		double flat = BallSimulator.simulate(surface(Surface.GREEN, (x, z) -> 0), start, launch, BallSimulator.Options.preview(null)).rest().z();
		double down = BallSimulator.simulate(surface(Surface.GREEN, (x, z) -> -0.02 * z), start, launch, BallSimulator.Options.preview(null)).rest().z();
		System.out.printf("4-block putt: uphill %.2f, flat %.2f, downhill %.2f%n", up, flat, down);
		assertTrue(up < flat - 0.5 && down > flat + 0.5);
	}

	@Test
	void ballLandingOnABankKicksDownhill() {
		// A steep bank falling away to -x: a ball dropped straight down should skip off towards -x.
		BallWorld bank = surface(Surface.ROUGH, (x, z) -> 0.5 * x);
		Launch drop = new Launch(new Vec(0, -6, 0), 0, new Vec(1, 0, 0), false);
		BallSimulator.Result r = BallSimulator.simulate(bank, new Vec(10.5, 9, 0.5), drop, BallSimulator.Options.preview(null));
		System.out.printf("dropped ball on a 50%% bank ends at x=%.1f%n", r.rest().x());
		assertTrue(r.rest().x() < 10.0, "kicks downhill");
	}

	@Test
	void slowBallStaysInABunkerBowl() {
		// Bowl: 1.5 deep, radius 3, centred at the origin.
		BallWorld bowl = surface(Surface.BUNKER, (x, z) -> {
			double r = Math.hypot(x, z);
			return r >= 3 ? 0 : -1.5 * (1 - (r / 3) * (r / 3));
		});
		Launch roll = Launch.putt(0, 1.5);
		BallSimulator.Result r = BallSimulator.simulate(bowl, new Vec(0, -1.5, -2.5).withY(bowl.surfaceHeight(0, -2.5)), roll, BallSimulator.Options.preview(null));
		System.out.printf("ball rolled into the bunker rests %.2f blocks from its centre%n", Math.hypot(r.rest().x(), r.rest().z()));
		assertTrue(Math.hypot(r.rest().x(), r.rest().z()) < 2.5, "stays in the bunker");
	}
}
