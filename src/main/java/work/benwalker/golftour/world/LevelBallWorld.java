package work.benwalker.golftour.world;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import work.benwalker.golftour.course.CourseLayout;
import work.benwalker.golftour.physics.BallWorld;
import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.physics.Vec;
import work.benwalker.golftour.registry.GolfBlocks;

/** Lets the ball simulator read real blocks (works on both the server and client level). */
public final class LevelBallWorld implements BallWorld {
	private final Level level;
	private final CourseLayout course;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private final Map<Long, Cell> cache = new HashMap<>();
	private final Map<Long, Double> corners = new HashMap<>();

	public LevelBallWorld(Level level, CourseLayout course) {
		this.level = level;
		this.course = course;
	}

	@Override
	public Cell cell(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		Cell cached = cache.get(key);
		if (cached == null) {
			cached = lookup(x, y, z);
			cache.put(key, cached);
		}
		return cached;
	}

	private Cell lookup(int x, int y, int z) {
		if (course != null && level.isClientSide() && !level.hasChunk(x >> 4, z >> 4)) {
			// The client hasn't received this chunk yet (beyond render distance): the course layout knows the terrain.
			CourseLayout.Column c = course.column(x, z);
			if (y <= c.groundY()) {
				Surface s = c.surface() == Surface.OUT ? Surface.DEEP_ROUGH : c.surface() == Surface.WATER ? Surface.ROUGH : c.surface();
				return Cell.solid(s, 1.0);
			}
			return y <= c.waterTop() ? Cell.WATER_CELL : Cell.EMPTY;
		}
		pos.set(x, y, z);
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return Cell.EMPTY;
		}
		if (state.getFluidState().is(FluidTags.WATER)) {
			return Cell.WATER_CELL;
		}
		if (state.is(BlockTags.LEAVES)) {
			return Cell.LEAF_CELL;
		}
		if (state.is(Blocks.LILY_PAD)) {
			return Cell.EMPTY;
		}
		// NoCubes gives smoothed blocks rough collision boxes for walking on; they poke up through the smooth surface the
		// ball rolls on and would read as walls. The ball uses the block's real shape (turf at its layer height) instead.
		VoxelShape shape = work.benwalker.golftour.compat.NoCubesCompat.smoothed(state) ? state.getShape(level, pos) : state.getCollisionShape(level, pos);
		if (shape.isEmpty()) {
			return Cell.EMPTY;
		}
		double top = Math.min(1.0, shape.max(Direction.Axis.Y));
		return Cell.solid(surfaceOf(state), top);
	}

	public static Surface surfaceOf(BlockState state) {
		Block b = state.getBlock();
		if (b == GolfBlocks.GREEN || b == GolfBlocks.GREEN_DARK || b == GolfBlocks.CUP) {
			return Surface.GREEN;
		}
		if (b == GolfBlocks.FRINGE) {
			return Surface.FRINGE;
		}
		if (b == GolfBlocks.FAIRWAY || b == GolfBlocks.FAIRWAY_DARK) {
			return Surface.FAIRWAY;
		}
		if (b == GolfBlocks.TEE_BOX) {
			return Surface.TEE;
		}
		if (b == GolfBlocks.ROUGH || b == Blocks.GRASS_BLOCK) {
			return Surface.ROUGH;
		}
		if (b == GolfBlocks.DEEP_ROUGH || b == Blocks.MOSS_BLOCK || b == Blocks.PODZOL || b == Blocks.COARSE_DIRT) {
			return Surface.DEEP_ROUGH;
		}
		if (b == GolfBlocks.BUNKER_SAND || state.is(BlockTags.SAND)) {
			return Surface.BUNKER;
		}
		SoundType sound = state.getSoundType();
		if (sound == SoundType.STONE || sound == SoundType.WOOD || sound == SoundType.METAL || sound == SoundType.GRAVEL) {
			return Surface.HARD;
		}
		return Surface.ROUGH;
	}

	@Override
	public Vec slopeAt(double x, double z) {
		// Greens are real slopes now (part of the course surface), so there is no separate virtual slope.
		return null;
	}

	@Override
	public double surfaceHeight(double x, double z) {
		if (course == null) {
			return Double.NaN;
		}
		int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
		double u = x - ix, v = z - iz;
		double h00 = corner(ix, iz), h10 = corner(ix + 1, iz), h01 = corner(ix, iz + 1), h11 = corner(ix + 1, iz + 1);
		return (h00 * (1 - u) + h10 * u) * (1 - v) + (h01 * (1 - u) + h11 * u) * v;
	}

	@Override
	public Vec surfaceGradient(double x, double z) {
		if (course == null) {
			return Vec.ZERO;
		}
		int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
		double u = x - ix, v = z - iz;
		double h00 = corner(ix, iz), h10 = corner(ix + 1, iz), h01 = corner(ix, iz + 1), h11 = corner(ix + 1, iz + 1);
		return new Vec((h10 - h00) * (1 - v) + (h11 - h01) * v, 0, (h01 - h00) * (1 - u) + (h11 - h10) * u);
	}

	private double corner(int x, int z) {
		long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
		Double h = corners.get(key);
		if (h == null) {
			h = course.heightAt(x, z);
			corners.put(key, h);
		}
		return h;
	}

	@Override
	public int minY() {
		return level.getMinY();
	}

	/** Surface the ball would be sitting on at {@code p} (its contact point). */
	public Surface surfaceUnder(Vec p) {
		int x = (int) Math.floor(p.x()), y = (int) Math.floor(p.y() - 1e-3), z = (int) Math.floor(p.z());
		// As in the simulator: a ball resting a hair above a full turf block is still on that turf.
		for (int i = 0; i < 2; i++) {
			Cell c = cell(x, y - i, z);
			if (c.kind() == Kind.SOLID) {
				return c.surface();
			}
			if (c.kind() == Kind.WATER) {
				return Surface.WATER;
			}
		}
		return Surface.ROUGH;
	}
}
