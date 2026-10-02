package work.benwalker.golftour.course;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import work.benwalker.golftour.course.HoleLayout.Circle;
import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.physics.Vec;

/**
 * A complete 18-hole course generated from a seed: hole routing, fairways, greens, bunkers, water, terrain
 * height and trees. It is pure data, so the server's chunk generator, the client's hole map and the unit
 * tests all derive the identical course from the same seed.
 */
public final class CourseLayout {
	public static final int BASE_Y = 64;
	/** Half width of a hole's playing corridor; beyond it is out of bounds. */
	public static final double CORRIDOR = 48;
	private static final double MIN_GAP = 62;
	private static final int[] PARS = {4, 4, 3, 5, 4, 4, 3, 4, 5, 4, 3, 4, 5, 4, 4, 3, 5, 4};
	private static final String[] NAMES = {"Whispering Pines", "Copper Creek", "Emerald Dunes", "Cedar Valley", "Highland Links", "Juniper Ridge"};
	private static final int CELL = 32;
	private static final Map<Long, CourseLayout> CACHE = new ConcurrentHashMap<>();

	public enum TreeType {
		OAK, BIRCH, SPRUCE
	}

	public record Tree(int x, int baseY, int z, TreeType type, int trunkHeight, int radius) {
	}

	/**
	 * @param surface course surface at this column
	 * @param height smooth surface height at the column centre (the ball's contact height)
	 * @param groundY y of the top (possibly partial) solid block
	 * @param layers height of that top block in sixteenths (16 = full block)
	 * @param waterTop y of the highest water block, or {@link Integer#MIN_VALUE}
	 * @param hole index of the hole this column belongs to, or -1
	 * @param stripe alternate mowing stripe
	 */
	public record Column(Surface surface, double height, int groundY, int layers, int waterTop, int hole, boolean stripe) {
		static Column of(Surface surface, double height, int waterTop, int hole, boolean stripe) {
			int top = (int) Math.floor(height - 1e-6);
			int layers = (int) Math.round((height - top) * 16);
			if (layers <= 0) {
				top -= 1;
				layers = 16;
			}
			return new Column(surface, height, top, Math.min(16, layers), waterTop, hole, stripe);
		}
	}

	public final long seed;
	public final String name;
	public final List<HoleLayout> holes;
	private final Noise terrain;
	private final Map<Long, int[]> index = new HashMap<>();

	private CourseLayout(long seed, String name, List<HoleLayout> holes, Noise terrain) {
		this.seed = seed;
		this.name = name;
		this.holes = List.copyOf(holes);
		this.terrain = terrain;
		buildIndex();
	}

	public static CourseLayout of(long seed) {
		return CACHE.computeIfAbsent(seed, CourseLayout::generate);
	}

	public int totalPar() {
		return holes.stream().mapToInt(HoleLayout::par).sum();
	}

	public int totalYards() {
		return holes.stream().mapToInt(HoleLayout::yards).sum();
	}

	// ---------------------------------------------------------------- queries

	public double terrainRaw(double x, double z) {
		return BASE_Y + 3.4 * terrain.fbm(x / 120.0, z / 120.0) + 1.4 * terrain.fbm(x / 48.0 + 100, z / 48.0 - 50);
	}

	private int[] candidates(double x, double z) {
		int[] found = index.get(cellKey(Math.floorDiv((int) Math.floor(x), CELL), Math.floorDiv((int) Math.floor(z), CELL)));
		return found == null ? new int[0] : found;
	}

	/** Distance to the nearest hole centre line (large when far from every hole). */
	public double nearestLineDistance(double x, double z) {
		double best = 1e9;
		for (int i : candidates(x, z)) {
			best = Math.min(best, holes.get(i).nearest(x, z).distance());
		}
		return best;
	}

	public boolean isOutOfBounds(double x, double z) {
		return nearestLineDistance(x, z) > CORRIDOR;
	}

	/** The virtual green slope at a point, or null off the greens. */
	public Vec slopeAt(double x, double z) {
		for (int i : candidates(x, z)) {
			HoleLayout h = holes.get(i);
			if (h.greenRadius(x, z, 1.6) <= 1.0) {
				return h.slope().gradient(x, z);
			}
		}
		return null;
	}

	public Column column(int bx, int bz) {
		double x = bx + 0.5, z = bz + 0.5;
		Surface best = Surface.OUT;
		HoleLayout bestHole = null;
		double bestAlong = 0;
		for (int i : candidates(x, z)) {
			HoleLayout h = holes.get(i);
			HoleLayout.LinePoint lp = h.nearest(x, z);
			Surface s = surfaceFor(h, x, z, lp);
			if (rank(s) > rank(best)) {
				best = s;
				bestHole = h;
				bestAlong = lp.along();
			}
		}
		int hole = bestHole == null ? -1 : bestHole.number() - 1;
		boolean stripe = switch (best) {
			case FAIRWAY, TEE -> Math.floorMod((int) Math.floor(bestAlong / 3.5), 2) == 0;
			case GREEN, FRINGE -> Math.floorMod((int) Math.floor(bestAlong / 2.0), 2) == 0;
			default -> false;
		};
		if (best == Surface.WATER && bestHole != null) {
			// Pond beds are whole blocks so the water above them has no air gaps.
			int bed = (int) Math.floor(heightAt(x, z));
			return new Column(best, bed + 1, bed, 16, bestHole.waterLevel(), hole, false);
		}
		return Column.of(best, heightAt(x, z), Integer.MIN_VALUE, hole, stripe);
	}

	/**
	 * The course's smooth surface height at any point: rolling terrain, raised tees, sloped greens blended
	 * into their surrounds, bowl-shaped bunkers with curved faces, and banks shelving into the water.
	 */
	public double heightAt(double x, double z) {
		double h = terrainRaw(x, z) + 1;
		int[] near = candidates(x, z);
		for (int i : near) {
			h = teeAndGreen(holes.get(i), x, z, h);
		}
		for (int i : near) {
			h = bunkersAndWater(holes.get(i), x, z, h);
		}
		return h;
	}

	private static double teeAndGreen(HoleLayout hole, double x, double z, double h) {
		double teeOut = teeOutside(hole, x, z);
		if (teeOut < 6) {
			h = lerp(h, hole.teeY() + 1, 1 - smoothstep(1.5, 6, teeOut));
		}
		double avgR = (hole.greenLong() + hole.greenShort()) / 2;
		double outside = (hole.greenRadius(x, z, 0) - 1) * avgR;
		if (outside < 11) {
			h = lerp(h, greenSurface(hole, x, z), 1 - smoothstep(3, 11, outside));
		}
		return h;
	}

	private static double bunkersAndWater(HoleLayout hole, double x, double z, double h) {
		for (List<Circle> bunker : hole.bunkers()) {
			double inside = -1e9;
			for (Circle c : bunker) {
				inside = Math.max(inside, c.r() - Math.hypot(x - c.x(), z - c.z()));
			}
			if (inside > -1.5) {
				Circle first = bunker.get(0);
				boolean greenside = hole.greenRadius(first.x(), first.z(), 10) <= 1;
				double depth = greenside ? 1.7 : 1.05;
				// Bowl with a curved face, plus a small raised lip around the rim.
				h -= depth * smoothstep(0, 2.4, inside);
				if (inside < 0) {
					h += 0.22 * (1 - Math.abs(inside + 0.75) / 0.75) * (inside > -1.5 ? 1 : 0);
				}
			}
		}
		if (!hole.water().isEmpty()) {
			double edge = hole.waterEdgeDistance(x, z);
			double surface = hole.waterLevel() + 0.9;
			if (edge > 0) {
				h = Math.min(h, surface + 0.12 + edge * 0.42);
			} else {
				h = hole.waterLevel() - 0.2 - 1.6 * smoothstep(0, 4, -edge);
			}
		}
		return h;
	}

	/** Green surface: the putting slope, anchored so the cup sits exactly on a block top. */
	private static double greenSurface(HoleLayout hole, double x, double z) {
		double rel = hole.slope().height(x, z) - hole.slope().height(hole.pin().x(), hole.pin().z());
		return hole.greenY() + 1 + rel;
	}

	/** Distance outside the tee box rectangle (0 inside). */
	private static double teeOutside(HoleLayout h, double x, double z) {
		Vec dir = h.line().get(1).sub(h.tee()).horizontal().normalize();
		double dx = x - h.tee().x(), dz = z - h.tee().z();
		double u = Math.abs(dx * dir.x() + dz * dir.z()) - (HoleLayout.TEE_LENGTH / 2 + 1);
		double v = Math.abs(-dx * dir.z() + dz * dir.x()) - (HoleLayout.TEE_WIDTH / 2 + 1);
		return Math.hypot(Math.max(0, u), Math.max(0, v));
	}

	private static double smoothstep(double a, double b, double x) {
		double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static boolean inTeePad(HoleLayout h, double x, double z) {
		Vec dir = h.line().get(1).sub(h.tee()).horizontal().normalize();
		double dx = x - h.tee().x(), dz = z - h.tee().z();
		double u = dx * dir.x() + dz * dir.z();
		double v = -dx * dir.z() + dz * dir.x();
		return Math.abs(u) <= HoleLayout.TEE_LENGTH / 2 + 1.5 && Math.abs(v) <= HoleLayout.TEE_WIDTH / 2 + 1.5;
	}

	private static Surface surfaceFor(HoleLayout h, double x, double z, HoleLayout.LinePoint lp) {
		if (h.greenRadius(x, z, 0) <= 1) {
			return Surface.GREEN;
		}
		if (h.greenRadius(x, z, 1.6) <= 1) {
			return Surface.FRINGE;
		}
		if (HoleLayout.inAny(h.bunkers(), x, z)) {
			return Surface.BUNKER;
		}
		if (HoleLayout.inAny(h.water(), x, z)) {
			return Surface.WATER;
		}
		if (h.inTeeBox(x, z)) {
			return Surface.TEE;
		}
		double hw = h.halfWidthAt(lp.along());
		if (lp.along() >= h.fairwayStart() && lp.along() <= h.fairwayEnd() && lp.distance() <= hw) {
			return Surface.FAIRWAY;
		}
		if (lp.distance() <= hw + 11 || h.greenRadius(x, z, 9) <= 1 || h.tee().horizontalDistanceTo(new Vec(x, 0, z)) <= 10) {
			return Surface.ROUGH;
		}
		return lp.distance() <= CORRIDOR ? Surface.DEEP_ROUGH : Surface.OUT;
	}

	private static int rank(Surface s) {
		return switch (s) {
			case GREEN -> 9;
			case FRINGE -> 8;
			case BUNKER -> 7;
			case WATER -> 6;
			case TEE -> 5;
			case FAIRWAY -> 4;
			case ROUGH -> 3;
			case DEEP_ROUGH -> 2;
			default -> 1;
		};
	}

	/** True for the column holding hole {@code h}'s cup. */
	public static boolean isCup(HoleLayout h, int bx, int bz) {
		return (int) Math.floor(h.pin().x()) == bx && (int) Math.floor(h.pin().z()) == bz;
	}

	/** Red hazard stakes ringing the water. */
	public boolean isHazardStake(int bx, int bz) {
		if (Math.floorMod(bx * 5 + bz * 3, 7) != 0) {
			return false;
		}
		for (int i : candidates(bx + 0.5, bz + 0.5)) {
			HoleLayout h = holes.get(i);
			if (!h.water().isEmpty()) {
				double d = h.waterEdgeDistance(bx + 0.5, bz + 0.5);
				if (d >= 0.8 && d <= 1.7) {
					return true;
				}
			}
		}
		return false;
	}

	/** Out-of-bounds stake positions: sparse posts along the corridor boundary. */
	public boolean isStake(int bx, int bz) {
		if (Math.floorMod(bx * 3 + bz * 7, 11) != 0) {
			return false;
		}
		double d = nearestLineDistance(bx + 0.5, bz + 0.5);
		return Math.abs(d - CORRIDOR) < 0.8;
	}

	/** The tree (if any) rooted in tree-grid cell (cx, cz). Cells are 6 blocks square. */
	public Tree treeInCell(int cx, int cz) {
		long h = Noise.hash(seed ^ 0x7EE5L, cx, cz);
		int tx = cx * 6 + (int) Math.floorMod(h, 6L);
		int tz = cz * 6 + (int) Math.floorMod(h >>> 20, 6L);
		double d = nearestLineDistance(tx + 0.5, tz + 0.5);
		double density = d >= CORRIDOR ? 0.7 : Math.max(0, Math.min(1, (d - 24) / 12.0)) * 0.85;
		if (density <= 0 || Noise.unit(seed ^ 0x1EAFL, cx, cz) > density) {
			return null;
		}
		for (int i : candidates(tx + 0.5, tz + 0.5)) {
			HoleLayout hole = holes.get(i);
			if (hole.greenRadius(tx + 0.5, tz + 0.5, 15) <= 1 || hole.tee().horizontalDistanceTo(new Vec(tx + 0.5, 0, tz + 0.5)) < 13) {
				return null;
			}
		}
		Column c = column(tx, tz);
		if (c.surface() != Surface.DEEP_ROUGH && c.surface() != Surface.OUT) {
			return null;
		}
		double roll = Noise.unit(seed ^ 0x5EC1L, cx, cz);
		TreeType type = roll < 0.55 ? TreeType.OAK : roll < 0.8 ? TreeType.BIRCH : TreeType.SPRUCE;
		int trunk = 4 + (int) (Noise.unit(seed ^ 0xA11L, cx, cz) * 3) + (type == TreeType.SPRUCE ? 2 : 0);
		int radius = type == TreeType.SPRUCE ? 2 : 2 + (Noise.unit(seed ^ 0xB0BL, cx, cz) < 0.4 ? 1 : 0);
		return new Tree(tx, c.layers() >= 16 ? c.groundY() + 1 : c.groundY(), tz, type, trunk, radius);
	}

	// ---------------------------------------------------------------- generation

	private static CourseLayout generate(long seed) {
		// Routing is greedy; on the rare seed that paints itself into a corner, retry with a derived seed.
		long attemptSeed = seed;
		for (int attempt = 0; attempt < 32; attempt++) {
			CourseLayout course = tryGenerate(seed, attemptSeed);
			if (course != null) {
				return course;
			}
			attemptSeed = attemptSeed * 6364136223846793005L + 1442695040888963407L;
		}
		throw new IllegalStateException("Could not route a course for seed " + seed);
	}

	private static CourseLayout tryGenerate(long seed, long routingSeed) {
		Random rnd = new Random(routingSeed);
		Noise terrain = new Noise(seed ^ 0x5EEDL);
		CourseLayout probe = new CourseLayout(seed, "", List.of(), terrain);
		List<HoleLayout> holes = new ArrayList<>();
		List<List<Vec>> lines = new ArrayList<>();
		double heading = rnd.nextDouble() * 360;
		Vec prevGreen = null;

		for (int i = 0; i < PARS.length; i++) {
			int par = PARS[i];
			List<Vec> bestLine = null;
			double bestScore = Double.MAX_VALUE;
			for (double gap = MIN_GAP; bestLine == null && gap >= 40; gap -= 6) {
				for (int attempt = 0; attempt < 160; attempt++) {
					double dir = i == 0 && attempt == 0 ? heading : rnd.nextDouble() * 360;
					Vec tee = prevGreen == null ? Vec.ZERO : prevGreen.add(Vec.fromYaw(dir).scale(24 + rnd.nextDouble() * 10));
					List<Vec> line = designLine(rnd, par, tee, dir);
					if (!clear(line, lines, gap)) {
						continue;
					}
					Vec green = line.get(line.size() - 1);
					Vec mid = line.get(0).lerp(green, 0.5);
					double score = green.horizontalLength() + 0.6 * mid.horizontalLength() + rnd.nextDouble() * 25;
					if (score < bestScore) {
						bestScore = score;
						bestLine = line;
					}
				}
			}
			if (bestLine == null) {
				return null;
			}
			lines.add(bestLine);
			HoleLayout hole = designHole(rnd, probe, i + 1, par, bestLine);
			holes.add(hole);
			prevGreen = hole.green();
		}
		return new CourseLayout(seed, NAMES[(int) Math.floorMod(seed, (long) NAMES.length)], holes, terrain);
	}

	private static List<Vec> designLine(Random rnd, int par, Vec tee, double heading) {
		List<Vec> line = new ArrayList<>();
		line.add(tee);
		double length = switch (par) {
			case 3 -> 78 + rnd.nextDouble() * 28;
			case 5 -> 245 + rnd.nextDouble() * 32;
			default -> 172 + rnd.nextDouble() * 44;
		};
		double dir = heading;
		Vec at = tee;
		if (par == 4 && rnd.nextDouble() < 0.65) {
			double s1 = Math.min(length - 55, 116 + rnd.nextDouble() * 16);
			at = at.add(Vec.fromYaw(dir).scale(s1));
			line.add(at);
			dir += (rnd.nextBoolean() ? 1 : -1) * (14 + rnd.nextDouble() * 22);
			at = at.add(Vec.fromYaw(dir).scale(length - s1));
		} else if (par == 5) {
			double s1 = 124 + rnd.nextDouble() * 16;
			at = at.add(Vec.fromYaw(dir).scale(s1));
			line.add(at);
			dir += (rnd.nextBoolean() ? 1 : -1) * (8 + rnd.nextDouble() * 22);
			double s2 = 55 + rnd.nextDouble() * 20;
			at = at.add(Vec.fromYaw(dir).scale(s2));
			line.add(at);
			dir += (rnd.nextBoolean() ? 1 : -1) * (rnd.nextDouble() * 20);
			at = at.add(Vec.fromYaw(dir).scale(length - s1 - s2));
		} else {
			at = at.add(Vec.fromYaw(dir).scale(length));
		}
		line.add(at);
		return line;
	}

	private static boolean clear(List<Vec> line, List<List<Vec>> existing, double gap) {
		for (int j = 0; j < existing.size(); j++) {
			HoleLayout probe = lineOnly(existing.get(j));
			boolean previous = j == existing.size() - 1;
			double walked = 0;
			for (int k = 1; k < line.size(); k++) {
				Vec a = line.get(k - 1), b = line.get(k);
				double len = a.horizontalDistanceTo(b);
				for (double s = 0; s <= len; s += 4) {
					double along = walked + s;
					Vec p = a.lerp(b, len < 1e-9 ? 0 : s / len);
					double d = probe.nearest(p.x(), p.z()).distance();
					if (previous && along < 30) {
						if (d < 20) {
							return false;
						}
					} else if (d < gap) {
						return false;
					}
				}
				walked += len;
			}
		}
		return true;
	}

	private static HoleLayout lineOnly(List<Vec> line) {
		return new HoleLayout(0, 4, line, 1, 1, 0, line.get(line.size() - 1), 0, 0, 0, 0, List.of(), List.of(), null, 0, 0, 0);
	}

	private static HoleLayout designHole(Random rnd, CourseLayout probe, int number, int par, List<Vec> line) {
		Vec green = line.get(line.size() - 1);
		Vec beforeGreen = line.get(line.size() - 2);
		double approachYaw = Vec.yawOf(green.sub(beforeGreen));
		double greenLong = 8.0 + rnd.nextDouble() * 2.0;
		double greenShort = 6.0 + rnd.nextDouble() * 2.0;
		double greenAngle = approachYaw + (rnd.nextDouble() - 0.5) * 30;
		HoleLayout shape = new HoleLayout(number, par, line, greenLong, greenShort, greenAngle, green, 0, 0, 9.5, 0,
			List.of(), List.of(), null, 0, 0, 0);

		double length = shape.lengthBlocks();
		double halfWidth = 8.5 + rnd.nextDouble() * 2.0;
		double phase = rnd.nextDouble() * Math.PI * 2;
		double fairwayStart = par == 3 ? Math.max(20, length - 32) : 48;
		double fairwayEnd = length - greenLong * 0.6;

		// Pin: somewhere sensible on the green, centred on a block.
		Vec pin = green;
		for (int tries = 0; tries < 30; tries++) {
			double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * 0.6;
			double ar = Math.toRadians(greenAngle);
			Vec along = new Vec(-Math.sin(ar), 0, Math.cos(ar));
			Vec across = new Vec(-along.z(), 0, along.x());
			Vec p = green.add(along.scale(Math.cos(a) * r * greenLong)).add(across.scale(Math.sin(a) * r * greenShort));
			Vec snapped = new Vec(Math.floor(p.x()) + 0.5, 0, Math.floor(p.z()) + 0.5);
			if (shape.greenRadius(snapped.x(), snapped.z(), 0) <= 0.75) {
				pin = snapped;
				break;
			}
		}
		if (pin == green) {
			pin = new Vec(Math.floor(green.x()) + 0.5, 0, Math.floor(green.z()) + 0.5);
		}

		HoleLayout sized = new HoleLayout(number, par, line, greenLong, greenShort, greenAngle, pin, fairwayStart, fairwayEnd, halfWidth, phase,
			List.of(), List.of(), null, 0, 0, 0);

		// Greenside bunkers sit just off the fringe.
		List<List<Circle>> bunkers = new ArrayList<>();
		double[] sides = {-40, 40, -95, 95, 180};
		int greenside = par == 3 ? 2 + rnd.nextInt(2) : 1 + rnd.nextInt(3);
		List<Double> used = new ArrayList<>();
		for (int k = 0; k < greenside; k++) {
			double side = sides[rnd.nextInt(sides.length)];
			if (used.contains(side)) {
				continue;
			}
			used.add(side);
			double yaw = approachYaw + 180 + side + (rnd.nextDouble() - 0.5) * 20;
			Vec dir = Vec.fromYaw(yaw);
			double r = 2.4 + rnd.nextDouble() * 1.2;
			double edge = 0;
			while (sized.greenRadius(green.x() + dir.x() * edge, green.z() + dir.z() * edge, 1.6) <= 1 && edge < 30) {
				edge += 0.25;
			}
			Vec c = green.add(dir.scale(edge + r + 0.8));
			bunkers.add(blob(rnd, c, dir, r));
		}
		// Fairway bunkers guard the driving zone (and the lay-up on par 5s).
		if (par >= 4) {
			int count = 1 + rnd.nextInt(2);
			for (int k = 0; k < count; k++) {
				double s = (par == 5 && k == 1) ? length - 70 - rnd.nextDouble() * 15 : 118 + rnd.nextDouble() * 22;
				double side = rnd.nextBoolean() ? 1 : -1;
				Vec p = sized.pointAt(s);
				Vec dir = sized.pointAt(s + 1).sub(p).horizontal().normalize();
				Vec perp = new Vec(-dir.z(), 0, dir.x()).scale(side);
				double r = 2.8 + rnd.nextDouble() * 1.4;
				Vec c = p.add(perp.scale(sized.halfWidthAt(s) + r * 0.6 + rnd.nextDouble() * 2));
				bunkers.add(blob(rnd, c, dir, r));
			}
		}

		// Water: carry-over ponds on par 3s, lakes beside par 4s, creeks, and lay-up ponds on par 5s.
		List<List<Circle>> water = new ArrayList<>();
		double roll = rnd.nextDouble();
		if (par == 3) {
			if (roll < 0.6 && length > 80) {
				double s = length - greenLong - 14 - rnd.nextDouble() * 4;
				water.add(lake(rnd, sized.pointAt(s), dirAt(sized, s), 7.5 + rnd.nextDouble() * 2.5, 1.7));
			}
		} else if (par == 4) {
			if (roll < 0.34) {
				double s = 85 + rnd.nextDouble() * 70;
				Vec dir = dirAt(sized, s);
				Vec perp = new Vec(-dir.z(), 0, dir.x()).scale(rnd.nextBoolean() ? 1 : -1);
				Vec c = sized.pointAt(s).add(perp.scale(sized.halfWidthAt(s) + 12 + rnd.nextDouble() * 3));
				water.add(lake(rnd, c, dir, 8.5 + rnd.nextDouble() * 3, 0.55));
			} else if (roll < 0.48) {
				double s = length - 52 - rnd.nextDouble() * 14;
				water.add(lake(rnd, sized.pointAt(s), dirAt(sized, s), 4.2, 3.4));
			}
		} else {
			if (roll < 0.38) {
				double s = length - greenLong - 19 - rnd.nextDouble() * 6;
				water.add(lake(rnd, sized.pointAt(s), dirAt(sized, s), 7.5 + rnd.nextDouble() * 2, 1.5));
			} else if (roll < 0.62) {
				double s = length * (0.5 + rnd.nextDouble() * 0.15);
				Vec dir = dirAt(sized, s);
				Vec perp = new Vec(-dir.z(), 0, dir.x()).scale(rnd.nextBoolean() ? 1 : -1);
				Vec c = sized.pointAt(s).add(perp.scale(sized.halfWidthAt(s) + 13));
				water.add(lake(rnd, c, dir, 10 + rnd.nextDouble() * 3, 0.5));
			}
		}
		// Never flood the tee or creep onto the green complex.
		for (List<Circle> pond : water) {
			pond.removeIf(c -> c.r() + 14 > Math.hypot(c.x() - line.get(0).x(), c.z() - line.get(0).z())
				|| sized.greenRadius(c.x(), c.z(), c.r() + 6) <= 1);
		}
		water.removeIf(List::isEmpty);
		// Keep sand out of water.
		bunkers.removeIf(b -> b.stream().anyMatch(c -> HoleLayout.inAny(water, c.x(), c.z())));

		int waterLevel = 0;
		if (!water.isEmpty()) {
			double min = Double.MAX_VALUE;
			for (List<Circle> pond : water) {
				for (Circle c : pond) {
					for (int k = 0; k < 12; k++) {
						double a = k * Math.PI / 6;
						min = Math.min(min, probe.terrainRaw(c.x() + Math.cos(a) * (c.r() + 2), c.z() + Math.sin(a) * (c.r() + 2)));
					}
					min = Math.min(min, probe.terrainRaw(c.x(), c.z()));
				}
			}
			waterLevel = (int) Math.round(min) - 1;
		}

		// Green slope: an overall tilt plus one or two humps or hollows.
		double tiltAngle = rnd.nextDouble() * Math.PI * 2;
		double tilt = 0.006 + rnd.nextDouble() * 0.010;
		List<GreenSlope.Bump> bumps = new ArrayList<>();
		int bumpCount = 1 + rnd.nextInt(2);
		for (int k = 0; k < bumpCount; k++) {
			double a = rnd.nextDouble() * Math.PI * 2, r = rnd.nextDouble() * 0.7;
			bumps.add(new GreenSlope.Bump(green.x() + Math.cos(a) * r * greenLong, green.z() + Math.sin(a) * r * greenShort,
				(rnd.nextBoolean() ? 1 : -1) * (0.05 + rnd.nextDouble() * 0.06), 2.8 + rnd.nextDouble() * 1.5));
		}
		GreenSlope slope = new GreenSlope(green.x(), green.z(), Math.cos(tiltAngle) * tilt, Math.sin(tiltAngle) * tilt, bumps);

		int teeY = (int) Math.round(probe.terrainRaw(line.get(0).x(), line.get(0).z())) + 1;
		int greenY = (int) Math.round(probe.terrainRaw(green.x(), green.z()));
		return new HoleLayout(number, par, line, greenLong, greenShort, greenAngle, pin, fairwayStart, fairwayEnd, halfWidth, phase,
			bunkers, water, slope, teeY, greenY, waterLevel);
	}

	private static List<Circle> blob(Random rnd, Vec center, Vec dir, double r) {
		Vec tangent = new Vec(-dir.z(), 0, dir.x());
		List<Circle> circles = new ArrayList<>();
		circles.add(new Circle(center.x(), center.z(), r));
		int extra = 1 + rnd.nextInt(2);
		for (int k = 0; k < extra; k++) {
			double off = (rnd.nextDouble() - 0.5) * r * 2.2;
			Vec c = center.add(tangent.scale(off)).add(dir.scale((rnd.nextDouble() - 0.5) * r * 0.6));
			circles.add(new Circle(c.x(), c.z(), r * (0.6 + rnd.nextDouble() * 0.3)));
		}
		return circles;
	}

	private static Vec dirAt(HoleLayout hole, double s) {
		return hole.pointAt(s + 1).sub(hole.pointAt(s)).horizontal().normalize();
	}

	/**
	 * An organic pond: a main circle plus satellites scattered on an ellipse. {@code across} stretches it
	 * across the line of play (>1, a creek or carry pond) or along it (<1, a lake beside the fairway).
	 */
	private static List<Circle> lake(Random rnd, Vec center, Vec dir, double r, double across) {
		Vec perp = new Vec(-dir.z(), 0, dir.x());
		double along = across >= 1 ? 1 : 1 / across;
		double wide = across >= 1 ? across : 1;
		List<Circle> pond = new ArrayList<>();
		pond.add(new Circle(center.x(), center.z(), r));
		int satellites = 6 + (int) Math.round(Math.max(along, wide) * 2);
		for (int k = 0; k < satellites; k++) {
			double a = rnd.nextDouble() * Math.PI * 2;
			double dist = r * (0.55 + rnd.nextDouble() * 0.5);
			Vec c = center.add(dir.scale(Math.cos(a) * dist * along)).add(perp.scale(Math.sin(a) * dist * wide));
			pond.add(new Circle(c.x(), c.z(), r * (0.5 + rnd.nextDouble() * 0.35)));
		}
		return pond;
	}

	private void buildIndex() {
		Map<Long, List<Integer>> cells = new HashMap<>();
		for (int i = 0; i < holes.size(); i++) {
			HoleLayout h = holes.get(i);
			double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
			for (Vec p : h.line()) {
				minX = Math.min(minX, p.x());
				minZ = Math.min(minZ, p.z());
				maxX = Math.max(maxX, p.x());
				maxZ = Math.max(maxZ, p.z());
			}
			double pad = CORRIDOR + 16;
			for (int cx = Math.floorDiv((int) Math.floor(minX - pad), CELL); cx <= Math.floorDiv((int) Math.floor(maxX + pad), CELL); cx++) {
				for (int cz = Math.floorDiv((int) Math.floor(minZ - pad), CELL); cz <= Math.floorDiv((int) Math.floor(maxZ + pad), CELL); cz++) {
					cells.computeIfAbsent(cellKey(cx, cz), k -> new ArrayList<>()).add(i);
				}
			}
		}
		cells.forEach((k, v) -> index.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
	}

	private static long cellKey(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
	}
}
