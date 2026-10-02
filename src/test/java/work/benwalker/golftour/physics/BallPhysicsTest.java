package work.benwalker.golftour.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BallPhysicsTest {
	private static final Vec TEE = new Vec(0.5, 0.0, 0.5);

	private static BallSimulator.Result hit(Club club, ShotType type, double power, double shape, double spin, Surface ground) {
		ShotCalculator.Swing swing = ShotCalculator.Swing.perfect(club, type, power, shape, spin, 0f, Surface.TEE, 5);
		Launch launch = ShotCalculator.compute(swing).launch();
		return BallSimulator.simulate(BallWorld.flat(ground, 0), TEE, launch, BallSimulator.Options.preview(null));
	}

	@Test
	void everyClubCarriesItsRatedDistance() {
		System.out.println("club            carry  total  roll(fw) roll(green) apex  hang");
		for (Club club : Club.values()) {
			if (club.isPutter()) {
				continue;
			}
			BallSimulator.Result fw = hit(club, ShotType.NORMAL, 1.0, 0, 0, Surface.FAIRWAY);
			BallSimulator.Result gr = hit(club, ShotType.NORMAL, 1.0, 0, 0, Surface.GREEN);
			double carry = Units.toYards(fw.carryFrom(TEE));
			double total = Units.toYards(fw.rest().horizontalDistanceTo(TEE));
			double totalGreen = Units.toYards(gr.rest().horizontalDistanceTo(TEE));
			System.out.printf("%-15s %5.0f  %5.0f  %6.1f  %8.1f   %5.1f %5.1f%n", club.displayName, carry, total, total - carry,
				totalGreen - carry, Units.toYards(fw.apex()), fw.flightTicks() * Units.TICK_SECONDS);
			assertEquals(club.carryYards, carry, club.carryYards * 0.02, club + " carry");
			assertTrue(total >= carry, club + " rolls forward on the fairway");
		}
	}

	@Test
	void partialSwingsScaleCarry() {
		BallSimulator.Result half = hit(Club.IRON_7, ShotType.NORMAL, 0.5, 0, 0, Surface.FAIRWAY);
		assertEquals(Club.IRON_7.carryYards * 0.5, Units.toYards(half.carryFrom(TEE)), 3.0);
	}

	@Test
	void fadeCurvesRightAndDrawCurvesLeft() {
		// Aim yaw 0 is due south (+z); the golfer's right is west (-x).
		BallSimulator.Result fade = hit(Club.IRON_5, ShotType.NORMAL, 1.0, 1.0, 0, Surface.FAIRWAY);
		BallSimulator.Result draw = hit(Club.IRON_5, ShotType.NORMAL, 1.0, -1.0, 0, Surface.FAIRWAY);
		System.out.printf("fade lands x=%.1f, draw lands x=%.1f%n", fade.firstLanding().x(), draw.firstLanding().x());
		assertTrue(fade.firstLanding().x() < -1.0, "fade finishes right of target");
		assertTrue(draw.firstLanding().x() > 1.0, "draw finishes left of target");
	}

	@Test
	void puttRollsToTargetOnFlatGreen() {
		for (double feet : new double[] {5, 20, 60}) {
			double blocks = feet / Units.FEET_PER_BLOCK;
			ShotCalculator.Swing swing = ShotCalculator.Swing.perfect(Club.PUTTER, ShotType.PUTT, 1.0, 0, 0, 0f, Surface.GREEN, blocks);
			Launch launch = ShotCalculator.compute(swing).launch();
			BallSimulator.Result r = BallSimulator.simulate(BallWorld.flat(Surface.GREEN, 0), TEE, launch, BallSimulator.Options.preview(null));
			double rolled = Units.toFeet(r.rest().horizontalDistanceTo(TEE));
			System.out.printf("putt %.0f ft -> %.1f ft in %.1f s%n", feet, rolled, r.path().size() * Units.TICK_SECONDS);
			assertEquals(feet, rolled, feet * 0.06 + 0.5);
		}
	}

	@Test
	void puttDropsWhenStruckAtTheRightPace() {
		double blocks = 3.0;
		Vec cup = TEE.add(0, 0, blocks);
		ShotCalculator.Swing swing = ShotCalculator.Swing.perfect(Club.PUTTER, ShotType.PUTT, 1.05, 0, 0, 0f, Surface.GREEN, blocks);
		BallSimulator.Result r = BallSimulator.simulate(BallWorld.flat(Surface.GREEN, 0), TEE, ShotCalculator.compute(swing).launch(),
			BallSimulator.Options.preview(cup));
		assertEquals(BallSimulator.Outcome.HOLED, r.outcome());

		ShotCalculator.Swing tooHard = ShotCalculator.Swing.perfect(Club.PUTTER, ShotType.PUTT, 1.0, 0, 0, 0f, Surface.GREEN, blocks * 3);
		BallSimulator.Result r2 = BallSimulator.simulate(BallWorld.flat(Surface.GREEN, 0), TEE, ShotCalculator.compute(tooHard).launch(),
			BallSimulator.Options.preview(cup));
		assertTrue(r2.outcome() != BallSimulator.Outcome.HOLED, "a charged putt lips out");
	}

	@Test
	void slopedGreenBreaksThePutt() {
		BallWorld flat = BallWorld.flat(Surface.GREEN, 0);
		BallWorld sloped = new BallWorld() {
			@Override
			public Cell cell(int x, int y, int z) {
				return flat.cell(x, y, z);
			}

			@Override
			public Vec slopeAt(double x, double z) {
				return new Vec(0.02, 0, 0); // ground rises towards +x, so the ball breaks towards -x
			}
		};
		double blocks = 5;
		ShotCalculator.Swing swing = ShotCalculator.Swing.perfect(Club.PUTTER, ShotType.PUTT, 1.0, 0, 0, 0f, Surface.GREEN, blocks);
		BallSimulator.Result r = BallSimulator.simulate(sloped, TEE, ShotCalculator.compute(swing).launch(), BallSimulator.Options.preview(null));
		System.out.printf("2%% side slope moved a 30 ft putt %.2f blocks sideways%n", TEE.x() - r.rest().x());
		assertTrue(r.rest().x() < TEE.x() - 0.3, "putt breaks downhill");
	}

	@Test
	void windPushesTheBall() {
		ShotCalculator.Swing swing = ShotCalculator.Swing.perfect(Club.IRON_7, ShotType.NORMAL, 1.0, 0, 0, 0f, Surface.FAIRWAY, 5);
		Launch launch = ShotCalculator.compute(swing).launch();
		Vec headwind = new Vec(0, 0, -Units.mphToBlocksPerSecond(15));
		BallSimulator.Result calm = BallSimulator.simulate(BallWorld.flat(Surface.FAIRWAY, 0), TEE, launch, BallSimulator.Options.preview(null));
		BallSimulator.Result into = BallSimulator.simulate(BallWorld.flat(Surface.FAIRWAY, 0), TEE, launch,
			new BallSimulator.Options(900, false, headwind, null, 0, true));
		double loss = Units.toYards(calm.carryFrom(TEE) - into.carryFrom(TEE));
		System.out.printf("15 mph headwind costs a 7 iron %.0f yards%n", loss);
		assertTrue(loss > 8 && loss < 30, "headwind loss is realistic");
	}

	@Test
	void mishitsGoOffline() {
		ShotCalculator.Swing hook = new ShotCalculator.Swing(Club.DRIVER, ShotType.NORMAL, 1.0, 0.12, 0, 0, 0f, Surface.TEE, 5);
		ShotCalculator.Result result = ShotCalculator.compute(hook);
		BallSimulator.Result r = BallSimulator.simulate(BallWorld.flat(Surface.FAIRWAY, 0), TEE, result.launch(), BallSimulator.Options.preview(null));
		System.out.printf("early click (%s): lands %.1f blocks left%n", result.quality(), r.firstLanding().x() - TEE.x());
		assertEquals(ShotCalculator.Quality.HOOK, result.quality());
		assertTrue(r.firstLanding().x() > TEE.x() + 5, "hook finishes left");
	}

	@Test
	void wedgeChecksUpOnTheGreen() {
		BallSimulator.Result sw = hit(Club.SAND_WEDGE, ShotType.NORMAL, 1.0, 0, 1.0, Surface.GREEN);
		double roll = Units.toYards(sw.rest().horizontalDistanceTo(TEE) - sw.carryFrom(TEE));
		System.out.printf("max-backspin sand wedge rolls %.1f yards after landing%n", roll);
		assertTrue(roll < 3.0);
	}
}
