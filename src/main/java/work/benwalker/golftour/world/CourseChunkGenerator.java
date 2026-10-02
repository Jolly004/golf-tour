package work.benwalker.golftour.world;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import work.benwalker.golftour.course.CourseLayout;
import work.benwalker.golftour.course.HoleLayout;
import work.benwalker.golftour.course.Noise;
import work.benwalker.golftour.physics.Vec;
import work.benwalker.golftour.block.TurfBlock;
import work.benwalker.golftour.registry.GolfBlocks;

/** Generates the golf dimension straight from a {@link CourseLayout}: turf, sand, water, cups, flags, trees. */
public class CourseChunkGenerator extends ChunkGenerator {
	public static final MapCodec<CourseChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
		Codec.LONG.fieldOf("course_seed").forGetter(CourseChunkGenerator::courseSeed)
	).apply(i, i.stable(CourseChunkGenerator::new)));

	private static final int MIN_Y = 0;
	private static final int HEIGHT = 256;

	private final long courseSeed;

	public CourseChunkGenerator(BiomeSource biomeSource, long courseSeed) {
		super(biomeSource);
		this.courseSeed = courseSeed;
	}

	public long courseSeed() {
		return courseSeed;
	}

	public CourseLayout course() {
		return CourseLayout.of(courseSeed);
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structureManager,
													   BiomeManager biomeManager, @Nullable WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes) {
		CourseLayout course = course();
		int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		int bottom = Math.max(chunk.getMinY(), MIN_Y);

		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				int wx = minX + lx, wz = minZ + lz;
				CourseLayout.Column c = course.column(wx, wz);
				int ground = c.groundY();
				BlockState top = topBlock(c);
				for (int y = bottom; y <= ground; y++) {
					BlockState state;
					if (y == bottom) {
						state = Blocks.BEDROCK.defaultBlockState();
					} else if (y == ground) {
						state = top;
					} else if (y == ground - 1 && c.layers() < 16 && c.surface() == work.benwalker.golftour.physics.Surface.BUNKER) {
						state = GolfBlocks.BUNKER_SAND.defaultBlockState();
					} else if (y >= ground - 3) {
						state = switch (c.surface()) {
							case BUNKER -> y >= ground - 1 ? GolfBlocks.BUNKER_SAND.defaultBlockState() : Blocks.DIRT.defaultBlockState();
							case WATER -> Blocks.SAND.defaultBlockState();
							default -> Blocks.DIRT.defaultBlockState();
						};
					} else {
						state = Blocks.STONE.defaultBlockState();
					}
					set(chunk, p.set(lx, y, lz), state, oceanFloor, worldSurface);
				}
				for (int y = ground + 1; y <= c.waterTop(); y++) {
					double r = Noise.unit(courseSeed ^ 0x5EA6L ^ y, wx, wz);
					BlockState water = y == ground + 1 && y < c.waterTop() && r < 0.22 ? Blocks.SEAGRASS.defaultBlockState() : Blocks.WATER.defaultBlockState();
					set(chunk, p.set(lx, y, lz), water, oceanFloor, worldSurface);
				}
				if (c.waterTop() != Integer.MIN_VALUE && Noise.unit(courseSeed ^ 0x111CL, wx, wz) < 0.045) {
					set(chunk, p.set(lx, c.waterTop() + 1, lz), Blocks.LILY_PAD.defaultBlockState(), oceanFloor, worldSurface);
				}
				if (c.waterTop() == Integer.MIN_VALUE && c.layers() >= 13) {
					BlockState deco = decoration(c, wx, wz);
					if (deco != null) {
						set(chunk, p.set(lx, ground + 1, lz), deco, oceanFloor, worldSurface);
					}
					if (c.layers() < 13) {
						// Stakes would float above low turf; skip this spot.
					} else if (course.isStake(wx, wz)) {
						set(chunk, p.set(lx, ground + 1, lz), GolfBlocks.OB_STAKE.defaultBlockState(), oceanFloor, worldSurface);
					} else if (c.surface() != work.benwalker.golftour.physics.Surface.GREEN && course.isHazardStake(wx, wz)) {
						set(chunk, p.set(lx, ground + 1, lz), GolfBlocks.HAZARD_STAKE.defaultBlockState(), oceanFloor, worldSurface);
					}
				}
			}
		}

		for (HoleLayout hole : course.holes) {
			int px = (int) Math.floor(hole.pin().x()), pz = (int) Math.floor(hole.pin().z());
			if (inChunk(px, pz, minX, minZ)) {
				int g = hole.greenY();
				set(chunk, p.set(px - minX, g, pz - minZ), GolfBlocks.CUP.defaultBlockState(), oceanFloor, worldSurface);
				set(chunk, p.set(px - minX, g + 1, pz - minZ), Blocks.AIR.defaultBlockState(), oceanFloor, worldSurface);
				set(chunk, p.set(px - minX, g + 1, pz - minZ), GolfBlocks.FLAGSTICK.defaultBlockState(), oceanFloor, worldSurface);
				set(chunk, p.set(px - minX, g + 2, pz - minZ), GolfBlocks.FLAGSTICK.defaultBlockState(), oceanFloor, worldSurface);
				set(chunk, p.set(px - minX, g + 3, pz - minZ), GolfBlocks.FLAG.defaultBlockState(), oceanFloor, worldSurface);
			}
			for (HoleLayout.Marker marker : hole.yardageMarkers()) {
				int mx = (int) Math.floor(marker.pos().x()), mz = (int) Math.floor(marker.pos().z());
				if (inChunk(mx, mz, minX, minZ)) {
					CourseLayout.Column mc = course.column(mx, mz);
					// Posts stand on a block top; on low partial turf, make that one block full so the post sits flush.
					if (mc.layers() < 16) {
						set(chunk, p.set(mx - minX, mc.groundY(), mz - minZ), topBlock(new CourseLayout.Column(mc.surface(), mc.groundY() + 1, mc.groundY(), 16,
							mc.waterTop(), mc.hole(), mc.stripe())), oceanFloor, worldSurface);
					}
					set(chunk, p.set(mx - minX, mc.groundY() + 1, mz - minZ), GolfBlocks.yardageMarker(marker.yards()).defaultBlockState(), oceanFloor, worldSurface);
				}
			}
			for (Vec marker : teeMarkers(hole)) {
				int mx = (int) Math.floor(marker.x()), mz = (int) Math.floor(marker.z());
				if (inChunk(mx, mz, minX, minZ)) {
					set(chunk, p.set(mx - minX, hole.teeY() + 1, mz - minZ), GolfBlocks.TEE_MARKER.defaultBlockState(), oceanFloor, worldSurface);
				}
			}
		}

		for (int cx = Math.floorDiv(minX - 4, 6); cx <= Math.floorDiv(minX + 19, 6); cx++) {
			for (int cz = Math.floorDiv(minZ - 4, 6); cz <= Math.floorDiv(minZ + 19, 6); cz++) {
				CourseLayout.Tree tree = course.treeInCell(cx, cz);
				if (tree != null) {
					placeTree(chunk, tree, minX, minZ, p, oceanFloor, worldSurface);
				}
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	/** The two tee markers at the front corners of a tee box. */
	public static List<Vec> teeMarkers(HoleLayout hole) {
		Vec dir = hole.line().get(1).sub(hole.tee()).horizontal().normalize();
		Vec perp = new Vec(-dir.z(), 0, dir.x());
		Vec front = hole.tee().add(dir.scale(HoleLayout.TEE_LENGTH / 2 - 1));
		double side = HoleLayout.TEE_WIDTH / 2 - 0.6;
		return List.of(front.add(perp.scale(side)), front.add(perp.scale(-side)));
	}

	private static boolean inChunk(int x, int z, int minX, int minZ) {
		return x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16;
	}

	private static void set(ChunkAccess chunk, BlockPos pos, BlockState state, Heightmap a, Heightmap b) {
		chunk.setBlockState(pos, state);
		a.update(pos.getX() & 15, pos.getY(), pos.getZ() & 15, state);
		b.update(pos.getX() & 15, pos.getY(), pos.getZ() & 15, state);
	}

	private static BlockState topBlock(CourseLayout.Column c) {
		Block block = switch (c.surface()) {
			case TEE -> GolfBlocks.TEE_BOX;
			case FAIRWAY -> c.stripe() ? GolfBlocks.FAIRWAY : GolfBlocks.FAIRWAY_DARK;
			case FRINGE -> GolfBlocks.FRINGE;
			case GREEN -> c.stripe() ? GolfBlocks.GREEN : GolfBlocks.GREEN_DARK;
			case ROUGH -> GolfBlocks.ROUGH;
			case BUNKER -> GolfBlocks.BUNKER_SAND;
			case WATER -> Blocks.SAND;
			default -> GolfBlocks.DEEP_ROUGH;
		};
		return block instanceof TurfBlock turf ? turf.withLayers(c.layers()) : block.defaultBlockState();
	}

	private BlockState decoration(CourseLayout.Column c, int x, int z) {
		double r = Noise.unit(courseSeed ^ 0xDEC0L, x, z);
		return switch (c.surface()) {
			case ROUGH -> r < 0.05 ? Blocks.SHORT_GRASS.defaultBlockState() : null;
			case DEEP_ROUGH, OUT -> {
				if (r < 0.30) {
					yield Blocks.SHORT_GRASS.defaultBlockState();
				} else if (r < 0.35) {
					yield Blocks.FERN.defaultBlockState();
				} else if (r < 0.358) {
					yield Blocks.DANDELION.defaultBlockState();
				} else if (r < 0.366) {
					yield Blocks.POPPY.defaultBlockState();
				} else if (r < 0.372) {
					yield Blocks.OXEYE_DAISY.defaultBlockState();
				} else if (r < 0.378) {
					yield Blocks.CORNFLOWER.defaultBlockState();
				}
				yield null;
			}
			default -> null;
		};
	}

	private void placeTree(ChunkAccess chunk, CourseLayout.Tree tree, int minX, int minZ, BlockPos.MutableBlockPos p, Heightmap a, Heightmap b) {
		BlockState log, leaves;
		switch (tree.type()) {
			case BIRCH -> {
				log = Blocks.BIRCH_LOG.defaultBlockState();
				leaves = Blocks.BIRCH_LEAVES.defaultBlockState();
			}
			case SPRUCE -> {
				log = Blocks.SPRUCE_LOG.defaultBlockState();
				leaves = Blocks.SPRUCE_LEAVES.defaultBlockState();
			}
			default -> {
				log = Blocks.OAK_LOG.defaultBlockState();
				leaves = Blocks.OAK_LEAVES.defaultBlockState();
			}
		}
		leaves = leaves.setValue(LeavesBlock.PERSISTENT, true).setValue(LeavesBlock.DISTANCE, 1);
		int top = tree.baseY() + tree.trunkHeight() - 1;
		int r = tree.radius();
		if (tree.type() == CourseLayout.TreeType.SPRUCE) {
			for (int y = top + 1; y >= tree.baseY() + 2; y--) {
				int layer = top + 1 - y;
				int lr = layer == 0 ? 0 : Math.min(r + 1, 1 + layer / 2 - (layer % 2 == 0 ? 0 : 1) + (layer > 3 ? 1 : 0));
				leafDisc(chunk, tree, y, lr, leaves, minX, minZ, p, a, b);
			}
		} else {
			for (int y = top - 2; y <= top + 1; y++) {
				int lr = y >= top ? r - 1 : r;
				leafDisc(chunk, tree, y, Math.max(1, lr), leaves, minX, minZ, p, a, b);
			}
		}
		for (int y = tree.baseY(); y <= top; y++) {
			if (inChunk(tree.x(), tree.z(), minX, minZ)) {
				set(chunk, p.set(tree.x() - minX, y, tree.z() - minZ), log, a, b);
			}
		}
	}

	private void leafDisc(ChunkAccess chunk, CourseLayout.Tree tree, int y, int radius, BlockState leaves, int minX, int minZ,
						  BlockPos.MutableBlockPos p, Heightmap a, Heightmap b) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int d2 = dx * dx + dz * dz;
				if (d2 > radius * radius + 1) {
					continue;
				}
				if (d2 == radius * radius + 1 && Noise.unit(courseSeed ^ y, tree.x() + dx, tree.z() + dz) < 0.5) {
					continue;
				}
				int x = tree.x() + dx, z = tree.z() + dz;
				if (!inChunk(x, z, minX, minZ)) {
					continue;
				}
				p.set(x - minX, y, z - minZ);
				if (chunk.getBlockState(p).isAir()) {
					set(chunk, p, leaves, a, b);
				}
			}
		}
	}

	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
	}

	@Override
	public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureManager structureManager, ChunkAccess centerChunk,
								 StructureTemplateManager structureTemplateManager, ResourceKey<Level> level) {
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
		CourseLayout.Column c = course().column(x, z);
		if (c.waterTop() != Integer.MIN_VALUE && type.isOpaque().test(Blocks.WATER.defaultBlockState())) {
			return c.waterTop() + 1;
		}
		return c.groundY() + 1;
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
		CourseLayout.Column c = course().column(x, z);
		int minY = heightAccessor.getMinY();
		BlockState[] states = new BlockState[heightAccessor.getHeight()];
		for (int i = 0; i < states.length; i++) {
			int y = minY + i;
			states[i] = y < c.groundY() ? Blocks.STONE.defaultBlockState()
				: y == c.groundY() ? topBlock(c)
				: y <= c.waterTop() ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
		}
		return new NoiseColumn(minY, states);
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext samplerContext) {
		result.add("Golf course seed " + courseSeed);
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion worldGenRegion) {
	}

	@Override
	public int getMinY() {
		return MIN_Y;
	}

	@Override
	public int getGenDepth() {
		return HEIGHT;
	}

	@Override
	public int getSeaLevel() {
		return 62;
	}
}
