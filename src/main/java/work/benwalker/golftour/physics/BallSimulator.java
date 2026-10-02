package work.benwalker.golftour.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic golf ball simulation: flight with drag, Magnus lift and wind; bounces that depend on the
 * surface and backspin; rolling with surface friction, green slopes, steps, walls and the cup.
 *
 * <p>The server runs it once per shot and plays the recorded path back tick by tick; the client runs the
 * same code (without wind) for the shot preview, so the landing marker matches what actually happens.
 * Positions are the bottom of the ball (its contact point).
 */
public final class BallSimulator {
	public enum Outcome {
		REST, HOLED, WATER, LOST, TIMEOUT
	}

	public enum EventType {
		BOUNCE, LEAVES, WALL, HOLED, SPLASH, LIP_OUT
	}

	public record Event(int tick, EventType type, Vec pos, Surface surface) {
	}

	public record Options(int maxTicks, boolean stopAtFirstLanding, Vec wind, Vec cup, long seed, boolean recordPath) {
		public static Options shot(Vec wind, Vec cup, long seed) {
			return new Options(900, false, wind, cup, seed, true);
		}

		public static Options preview(Vec cup) {
			return new Options(900, false, Vec.ZERO, cup, 0L, true);
		}

		public static Options carryOnly() {
			return new Options(400, true, Vec.ZERO, null, 0L, false);
		}
	}

	/**
	 * @param path ball position at every tick, starting with the launch position
	 * @param firstLanding where the ball first touched down (end of carry), or null if it never landed
	 * @param hazardPoint where the ball entered water, for the penalty drop
	 * @param flightTicks ticks from launch to first landing
	 * @param apex highest point above the launch height, in blocks
	 */
	public record Result(List<Vec> path, Outcome outcome, Vec rest, Surface restSurface, Vec firstLanding, Vec hazardPoint,
						 List<Event> events, int flightTicks, double apex) {
		public double carryFrom(Vec start) {
			return firstLanding == null ? 0 : firstLanding.horizontalDistanceTo(start);
		}
	}

	/** Ball radius in metres. */
	private static final double BALL_RADIUS_M = 0.02135;
	/** Aerodynamic constant 0.5*rho*A/m converted to per-block units. */
	private static final double K = 0.5 * 1.225 * Math.PI * BALL_RADIUS_M * BALL_RADIUS_M / 0.04593 * Units.METERS_PER_BLOCK;
	private static final double G = Units.GRAVITY;
	private static final double SPIN_DECAY_SECONDS = 20.0;
	private static final double MAX_STEP_BLOCKS = 0.2;
	/** Vertical speed after a bounce below which the ball settles into a roll. */
	private static final double ROLL_THRESHOLD = 0.9;
	private static final double STOP_SPEED = 0.03;
	private static final double ROLLING = 5.0 / 7.0;
	/** Cup capture radius in blocks. A real cup is much smaller, but the game is played at half scale. */
	public static final double CUP_RADIUS = 0.19;
	/** Fastest a ball can be moving over the centre of the cup and still drop, in blocks/s. */
	public static final double CUP_CAPTURE_SPEED = 1.25;

	private BallSimulator() {
	}

	public static Result simulate(BallWorld world, Vec start, Launch launch, Options options) {
		return new Run(world, start, launch, options).run();
	}

	private enum Mode {
		FLIGHT, ROLL
	}

	private static final class Run {
		private final BallWorld world;
		private final Options opt;
		private final Random random;
		private final Vec start;
		private final Vec initialDir;
		private final List<Vec> path = new ArrayList<>();
		private final List<Event> events = new ArrayList<>();

		private Vec pos;
		private Vec vel;
		private Vec spinAxis;
		private double omega;
		/** Backspin still acting on a ball that has just started rolling (rad/s). */
		private double residualSpin;
		private Mode mode;
		private int tick;
		private Outcome outcome;
		private Vec firstLanding;
		private Vec hazardPoint;
		private int flightTicks = -1;
		private double apex;
		private long lastLeafCell = Long.MIN_VALUE;
		private boolean lipped;
		private Surface restSurface;
		/** The world has a continuous ground surface (the golf course); blocks are then only obstacles. */
		private final boolean smooth;

		Run(BallWorld world, Vec start, Launch launch, Options options) {
			this.world = world;
			this.opt = options;
			this.random = new Random(options.seed());
			this.start = start;
			this.pos = start;
			this.vel = launch.velocity();
			this.spinAxis = launch.spinAxis();
			this.omega = Units.rpmToRadPerSecond(launch.spinRpm());
			this.mode = launch.rolling() ? Mode.ROLL : Mode.FLIGHT;
			Vec h = launch.velocity().horizontal();
			this.initialDir = h.lengthSqr() > 1e-9 ? h.normalize() : new Vec(0, 0, 1);
			if (launch.rolling()) {
				this.flightTicks = 0;
			}
			this.smooth = !Double.isNaN(world.surfaceHeight(start.x(), start.z()));
		}

		Result run() {
			if (opt.recordPath()) {
				path.add(pos);
			}
			for (tick = 1; tick <= opt.maxTicks() && outcome == null; tick++) {
				double remaining = Units.TICK_SECONDS;
				while (remaining > 1e-9 && outcome == null) {
					double speed = vel.length();
					double h = Math.min(remaining, Math.max(0.0005, MAX_STEP_BLOCKS / Math.max(speed, 1e-3)));
					if (mode == Mode.FLIGHT) {
						stepFlight(h);
					} else if (smooth) {
						stepRollSmooth(h);
					} else {
						stepRoll(h);
					}
					remaining -= h;
					if (opt.stopAtFirstLanding() && firstLanding != null && outcome == null) {
						outcome = Outcome.REST;
					}
				}
				if (opt.recordPath()) {
					path.add(pos);
				}
			}
			if (outcome == null) {
				outcome = Outcome.TIMEOUT;
			}
			if (restSurface == null) {
				restSurface = surfaceUnder(pos);
			}
			return new Result(path, outcome, pos, restSurface, firstLanding, hazardPoint, events, flightTicks, apex);
		}

		// ---------------------------------------------------------------- flight

		private void stepFlight(double h) {
			Vec rel = vel.sub(opt.wind());
			double spd = rel.length();
			Vec acc = new Vec(0, -G, 0);
			if (spd > 1e-6) {
				double spinParam = Math.min(0.6, BALL_RADIUS_M * omega / (spd * Units.METERS_PER_BLOCK));
				double cd = 0.235 + 0.15 * spinParam;
				double cl = spinParam <= 0 ? 0 : Math.min(0.42, 0.54 * Math.pow(spinParam, 0.4));
				acc = acc.add(rel.scale(-K * cd * spd));
				Vec liftDir = spinAxis.cross(rel).normalize();
				acc = acc.add(liftDir.scale(K * cl * spd * spd));
			}
			vel = vel.add(acc.scale(h));
			omega *= Math.exp(-h / SPIN_DECAY_SECONDS);
			Vec next = pos.add(vel.scale(h));
			apex = Math.max(apex, next.y() - start.y());

			if (next.y() < world.minY()) {
				pos = next;
				finish(Outcome.LOST);
				return;
			}
			if (checkDunk(next)) {
				return;
			}
			if (smooth) {
				double groundNext = world.surfaceHeight(next.x(), next.z());
				if (next.y() <= groundNext && world.cell(floor(next.x()), floor(groundNext + 0.05), floor(next.z())).kind() != BallWorld.Kind.WATER) {
					double f0 = pos.y() - world.surfaceHeight(pos.x(), pos.z());
					double f1 = next.y() - groundNext;
					double t = f0 <= 0 ? 0 : Math.min(1, f0 / Math.max(1e-9, f0 - f1));
					Vec contact = pos.lerp(next, t);
					pos = contact.withY(world.surfaceHeight(contact.x(), contact.z()));
					bounceGround(surfaceBelow(pos), normalAt(pos));
					return;
				}
			}

			int nx = floor(next.x()), ny = floor(next.y()), nz = floor(next.z());
			BallWorld.Cell cell = world.cell(nx, ny, nz);
			switch (cell.kind()) {
				case WATER -> {
					pos = next;
					hazardPoint = next;
					event(EventType.SPLASH, Surface.WATER);
					finish(Outcome.WATER);
				}
				case LEAVES -> {
					long key = cellKey(nx, ny, nz);
					if (key != lastLeafCell) {
						lastLeafCell = key;
						double keep = 0.45 + random.nextDouble() * 0.2;
						Vec jitter = new Vec(random.nextGaussian(), random.nextGaussian() * 0.5, random.nextGaussian()).scale(0.12 * vel.length());
						vel = vel.scale(keep).add(jitter.scale(keep));
						omega *= 0.5;
						event(EventType.LEAVES, Surface.ROUGH);
					}
					pos = next;
				}
				case SOLID -> {
					double top = ny + cell.height();
					boolean isGround = smooth && top <= world.surfaceHeight(next.x(), next.z()) + 0.25;
					if (!isGround && next.y() - ny < cell.height()) {
						collideFlight(next, nx, ny, nz, top, cell.surface());
					} else {
						pos = next;
					}
				}
				default -> pos = next;
			}
		}

		/** The ball drops straight into the cup from the air. */
		private boolean checkDunk(Vec next) {
			Vec cup = opt.cup();
			if (cup == null || pos.y() < cup.y() || next.y() > cup.y()) {
				return false;
			}
			double t = (pos.y() - cup.y()) / Math.max(1e-9, pos.y() - next.y());
			Vec crossing = pos.lerp(next, Math.min(1, Math.max(0, t)));
			if (crossing.horizontalDistanceTo(cup) <= CUP_RADIUS && vel.horizontalLength() < 6.5) {
				pos = new Vec(cup.x(), cup.y(), cup.z());
				event(EventType.HOLED, Surface.GREEN);
				markLanding();
				restSurface = Surface.GREEN;
				finish(Outcome.HOLED);
				return true;
			}
			return false;
		}

		private void collideFlight(Vec next, int nx, int ny, int nz, double top, Surface surface) {
			int ox = floor(pos.x()), oz = floor(pos.z());
			if (pos.y() >= top - 1e-6) {
				double t = (pos.y() - top) / Math.max(1e-9, pos.y() - next.y());
				pos = pos.lerp(next, Math.min(1, Math.max(0, t))).withY(top);
				bounceGround(surface, Vec.UP);
			} else if (ox != nx) {
				wallBounce(new Vec(ox < nx ? -1 : 1, 0, 0), surface);
			} else if (oz != nz) {
				wallBounce(new Vec(0, 0, oz < nz ? -1 : 1), surface);
			} else {
				pos = next.withY(top);
				bounceGround(surface, Vec.UP);
			}
		}

		private void wallBounce(Vec normal, Surface surface) {
			double vn = vel.dot(normal);
			Vec vt = vel.sub(normal.scale(vn));
			double e = Math.max(0.25, surface.bounce + 0.15);
			vel = vt.scale(0.8).sub(normal.scale(vn * e));
			omega *= 0.6;
			event(EventType.WALL, surface);
		}

		/** Bounce off ground whose surface normal is {@code n} (tilted on slopes, bunker faces and mounds). */
		private void bounceGround(Surface surface, Vec n) {
			markLanding();
			double vn = vel.dot(n);
			double impact = Math.max(0, -vn);
			Vec vt = vel.sub(n.scale(vn));
			double vtLen = vt.length();
			Vec moveDir = vtLen > 1e-6 ? vt.scale(1 / vtLen) : initialDir.sub(n.scale(initialDir.dot(n))).normalize();
			double e = surface.bounce * clamp(1.15 - impact / 25.0, 0.55, 1.0);
			double newVn = impact * e;

			Vec rightOfMotion = moveDir.cross(n).normalize();
			double backspin = omega * Math.max(0, spinAxis.dot(rightOfMotion));
			double bite = surface.spinGrab * (Units.radPerSecondToRpm(backspin) / 10000.0) * impact * 0.6;
			double newVt = vtLen * surface.keep - bite;
			if (newVt < 0) {
				newVt = Math.max(newVt, -0.22 * vtLen);
			}
			vel = moveDir.scale(newVt).add(n.scale(newVn));
			omega *= 0.5;
			event(EventType.BOUNCE, surface);

			if (newVn < ROLL_THRESHOLD) {
				startRoll();
			}
		}

		private void startRoll() {
			mode = Mode.ROLL;
			// Skidding to a pure roll costs speed; leftover backspin keeps biting for a moment.
			vel = vel.horizontal().scale(5.0 / 7.0);
			Vec h = vel.lengthSqr() > 1e-9 ? vel.normalize() : initialDir;
			residualSpin = omega * Math.max(0, spinAxis.dot(h.cross(Vec.UP).normalize()));
		}

		private void markLanding() {
			if (firstLanding == null) {
				firstLanding = pos;
				flightTicks = tick;
			}
		}

		// ---------------------------------------------------------------- rolling

		private void stepRoll(double h) {
			int bx = floor(pos.x()), bz = floor(pos.z());
			int by = floor(pos.y() - 1e-3);
			BallWorld.Cell below = world.cell(bx, by, bz);
			if (below.kind() == BallWorld.Kind.WATER) {
				hazardPoint = pos;
				event(EventType.SPLASH, Surface.WATER);
				finish(Outcome.WATER);
				return;
			}
			double supportTop = below.kind() == BallWorld.Kind.SOLID ? by + below.height()
				: below.kind() == BallWorld.Kind.LEAVES ? by + 1.0 : Double.NEGATIVE_INFINITY;
			if (pos.y() - supportTop > 0.05) {
				mode = Mode.FLIGHT;
				return;
			}
			pos = pos.withY(supportTop);
			Surface surface = below.kind() == BallWorld.Kind.SOLID ? below.surface() : Surface.ROUGH;

			Vec slope = world.slopeAt(pos.x(), pos.z());
			// A rolling sphere feels 5/7 of the downhill pull (the rest goes into spinning it up).
			Vec slopeAcc = slope == null ? Vec.ZERO : new Vec(-slope.x() * G * ROLLING, 0, -slope.z() * G * ROLLING);
			double spinDecel = surface.spinGrab * (Units.radPerSecondToRpm(residualSpin) / 10000.0) * 2.5;
			residualSpin *= Math.exp(-h / 0.4);
			double decel = surface.rollDecel + spinDecel;
			double holdLimit = surface.rollDecel * 0.85;

			Vec v = vel.horizontal();
			double speed = v.length();
			if (speed < STOP_SPEED) {
				if (slopeAcc.length() <= holdLimit) {
					restSurface = surface;
					finish(Outcome.REST);
					return;
				}
				v = slopeAcc.scale(h);
			} else {
				Vec newV = v.add(v.scale(-decel * h / speed)).add(slopeAcc.scale(h));
				if (newV.dot(v) <= 0) {
					if (slopeAcc.length() <= holdLimit) {
						vel = Vec.ZERO;
						restSurface = surface;
						finish(Outcome.REST);
						return;
					}
					newV = slopeAcc.scale(h);
				}
				v = newV;
			}
			speed = v.length();
			Vec next = pos.add(v.scale(h));

			if (checkCupRoll(next, speed, v)) {
				return;
			}

			int nx = floor(next.x()), nz = floor(next.z());
			int layer = floor(pos.y() + 0.02);
			BallWorld.Cell ahead = world.cell(nx, layer, nz);
			if (ahead.kind() == BallWorld.Kind.WATER) {
				pos = next;
				hazardPoint = next;
				event(EventType.SPLASH, Surface.WATER);
				finish(Outcome.WATER);
				return;
			}
			if (ahead.kind() == BallWorld.Kind.SOLID || ahead.kind() == BallWorld.Kind.LEAVES) {
				double aheadTop = layer + (ahead.kind() == BallWorld.Kind.LEAVES ? 1.0 : ahead.height());
				double step = aheadTop - pos.y();
				if (step > 1e-4) {
					boolean headroom = world.cell(nx, layer + 1, nz).kind() != BallWorld.Kind.SOLID;
					double climbCost = 2 * G * step * 1.4;
					if (headroom && ahead.kind() == BallWorld.Kind.SOLID && (step <= 0.55 || speed * speed > climbCost)) {
						double newSpeed = Math.sqrt(Math.max(0, speed * speed - climbCost));
						v = speed > 1e-9 ? v.scale(newSpeed / speed) : v;
						pos = next.withY(aheadTop);
					} else {
						if (nx != bx) {
							v = new Vec(-v.x() * 0.3, 0, v.z() * 0.8);
						}
						if (nz != bz) {
							v = new Vec(v.x() * 0.8, 0, -v.z() * 0.3);
						}
						event(EventType.WALL, ahead.surface() == null ? Surface.ROUGH : ahead.surface());
					}
					vel = v;
					return;
				}
			}
			pos = next;
			vel = v;
		}

		/** Rolling over the continuous course surface: gravity pulls the ball along every slope. */
		private void stepRollSmooth(double h) {
			double g0 = world.surfaceHeight(pos.x(), pos.z());
			if (pos.y() > g0 + 0.08) {
				mode = Mode.FLIGHT;
				return;
			}
			pos = pos.withY(g0);
			if (world.cell(floor(pos.x()), floor(g0 + 0.05), floor(pos.z())).kind() == BallWorld.Kind.WATER) {
				hazardPoint = pos;
				event(EventType.SPLASH, Surface.WATER);
				finish(Outcome.WATER);
				return;
			}
			Surface surface = surfaceBelow(pos);
			Vec grad = world.surfaceGradient(pos.x(), pos.z());
			Vec slopeAcc = new Vec(-grad.x() * G * ROLLING, 0, -grad.z() * G * ROLLING);
			double spinDecel = surface.spinGrab * (Units.radPerSecondToRpm(residualSpin) / 10000.0) * 2.5;
			residualSpin *= Math.exp(-h / 0.4);
			double decel = surface.rollDecel + spinDecel;
			double holdLimit = surface.rollDecel * 0.85;

			Vec v = vel.horizontal();
			double speed = v.length();
			if (speed < STOP_SPEED) {
				if (slopeAcc.length() <= holdLimit) {
					restSurface = surface;
					finish(Outcome.REST);
					return;
				}
				v = slopeAcc.scale(h);
			} else {
				Vec newV = v.add(v.scale(-decel * h / speed)).add(slopeAcc.scale(h));
				if (newV.dot(v) <= 0) {
					if (slopeAcc.length() <= holdLimit) {
						vel = Vec.ZERO;
						restSurface = surface;
						finish(Outcome.REST);
						return;
					}
					newV = slopeAcc.scale(h);
				}
				v = newV;
			}
			speed = v.length();
			Vec next = pos.add(v.scale(h));
			if (checkCupRoll(next, speed, v)) {
				return;
			}
			double g1 = world.surfaceHeight(next.x(), next.z());
			int bx = floor(pos.x()), bz = floor(pos.z());
			int nx = floor(next.x()), nz = floor(next.z());
			int layer = floor(g1 + 0.05);
			BallWorld.Cell ahead = world.cell(nx, layer, nz);
			if (ahead.kind() == BallWorld.Kind.WATER) {
				pos = next.withY(g1);
				hazardPoint = pos;
				event(EventType.SPLASH, Surface.WATER);
				finish(Outcome.WATER);
				return;
			}
			boolean obstacle = (ahead.kind() == BallWorld.Kind.SOLID && layer + ahead.height() > g1 + 0.25) || ahead.kind() == BallWorld.Kind.LEAVES;
			if (obstacle || g1 > g0 + 0.6) {
				if (nx != bx) {
					v = new Vec(-v.x() * 0.3, 0, v.z() * 0.8);
				}
				if (nz != bz) {
					v = new Vec(v.x() * 0.8, 0, -v.z() * 0.3);
				}
				if (nx == bx && nz == bz) {
					v = v.scale(-0.3);
				}
				vel = v;
				event(EventType.WALL, ahead.surface() == null ? Surface.ROUGH : ahead.surface());
				return;
			}
			if (g1 < g0 - 0.45) {
				// Runs off a ledge: launch from the edge and fall.
				pos = next.withY(g0);
				vel = v;
				mode = Mode.FLIGHT;
				return;
			}
			pos = next.withY(g1);
			vel = v;
		}

		private Vec normalAt(Vec p) {
			Vec g = world.surfaceGradient(p.x(), p.z());
			return new Vec(-g.x(), 1, -g.z()).normalize();
		}

		private Surface surfaceBelow(Vec p) {
			// The smooth surface can sit a hair above the top of a full turf block; then the cell under the ball is
			// the air above it, so look one block further down rather than calling it rough.
			int x = floor(p.x()), y = floor(p.y() - 0.01), z = floor(p.z());
			for (int i = 0; i < 2; i++) {
				BallWorld.Cell c = world.cell(x, y - i, z);
				if (c.kind() == BallWorld.Kind.SOLID) {
					return c.surface();
				}
			}
			return Surface.ROUGH;
		}

		private boolean checkCupRoll(Vec next, double speed, Vec v) {
			Vec cup = opt.cup();
			if (cup == null || Math.abs(pos.y() - cup.y()) > 0.3) {
				return false;
			}
			double dist = segmentPointDistance(pos, next, cup);
			if (dist > CUP_RADIUS) {
				lipped = false;
				return false;
			}
			double capture = CUP_CAPTURE_SPEED * (1 - 0.6 * dist / CUP_RADIUS);
			if (speed <= capture) {
				pos = new Vec(cup.x(), cup.y(), cup.z());
				vel = Vec.ZERO;
				event(EventType.HOLED, Surface.GREEN);
				restSurface = Surface.GREEN;
				finish(Outcome.HOLED);
				return true;
			}
			if (!lipped) {
				lipped = true;
				// Too fast: the ball catches the edge and is knocked off line.
				Vec toCup = cup.sub(pos).horizontal();
				double side = Math.signum(v.x() * toCup.z() - v.z() * toCup.x());
				double deflect = (1 - dist / CUP_RADIUS) * 25 * (side == 0 ? 1 : side);
				vel = v.rotateYaw(deflect).scale(0.8);
				event(EventType.LIP_OUT, Surface.GREEN);
			}
			return false;
		}

		// ---------------------------------------------------------------- helpers

		private Surface surfaceUnder(Vec p) {
			BallWorld.Cell c = world.cell(floor(p.x()), floor(p.y() - 1e-3), floor(p.z()));
			return switch (c.kind()) {
				case SOLID -> c.surface();
				case WATER -> Surface.WATER;
				default -> Surface.ROUGH;
			};
		}

		private void finish(Outcome result) {
			outcome = result;
		}

		private void event(EventType type, Surface surface) {
			events.add(new Event(tick, type, pos, surface));
		}
	}

	/** Horizontal distance from point {@code p} to segment a-b. */
	static double segmentPointDistance(Vec a, Vec b, Vec p) {
		double abx = b.x() - a.x(), abz = b.z() - a.z();
		double len2 = abx * abx + abz * abz;
		double t = len2 < 1e-12 ? 0 : ((p.x() - a.x()) * abx + (p.z() - a.z()) * abz) / len2;
		t = Math.max(0, Math.min(1, t));
		double cx = a.x() + abx * t - p.x(), cz = a.z() + abz * t - p.z();
		return Math.sqrt(cx * cx + cz * cz);
	}

	private static int floor(double v) {
		return (int) Math.floor(v);
	}

	private static long cellKey(int x, int y, int z) {
		return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
