package work.benwalker.golftour.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Course turf with sixteen heights (one pixel each). The course generator picks the height that matches its
 * smooth terrain, so slopes, bunker bowls and banks rise in single-pixel steps instead of whole blocks, and
 * players walk over them without jumping.
 */
public class TurfBlock extends Block {
	public static final int MAX = 16;
	public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, MAX);
	private static final VoxelShape[] SHAPES = Block.boxes(MAX, h -> Block.column(16.0, 0.0, h));

	public TurfBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(LAYERS, MAX));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LAYERS);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES[state.getValue(LAYERS)];
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES[state.getValue(LAYERS)];
	}

	@Override
	protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
		return SHAPES[state.getValue(LAYERS)];
	}

	@Override
	protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES[state.getValue(LAYERS)];
	}

	@Override
	protected boolean useShapeForLightOcclusion(BlockState state) {
		return true;
	}

	/** This block at the given height (1..16 sixteenths). */
	public BlockState withLayers(int layers) {
		return defaultBlockState().setValue(LAYERS, Math.max(1, Math.min(MAX, layers)));
	}
}
