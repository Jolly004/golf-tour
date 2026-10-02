package work.benwalker.golftour.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import work.benwalker.golftour.GolfTour;
import work.benwalker.golftour.block.ThinBlock;
import work.benwalker.golftour.block.TurfBlock;

/** Course building blocks. Turf blocks are plain cubes; the ball physics reads which one it lands on. */
public final class GolfBlocks {
	public static final List<Block> ALL = new ArrayList<>();

	public static final Block TEE_BOX = turf("tee_box", MapColor.COLOR_LIGHT_GREEN);
	public static final Block FAIRWAY = turf("fairway", MapColor.COLOR_LIGHT_GREEN);
	public static final Block FAIRWAY_DARK = turf("fairway_dark", MapColor.COLOR_GREEN);
	public static final Block FRINGE = turf("fringe", MapColor.COLOR_LIGHT_GREEN);
	public static final Block GREEN = turf("green", MapColor.COLOR_LIGHT_GREEN);
	public static final Block GREEN_DARK = turf("green_dark", MapColor.COLOR_LIGHT_GREEN);
	public static final Block ROUGH = turf("rough", MapColor.GRASS);
	public static final Block DEEP_ROUGH = turf("deep_rough", MapColor.COLOR_GREEN);
	public static final Block CUP = turf("cup", MapColor.COLOR_LIGHT_GREEN);
	public static final Block BUNKER_SAND = register("bunker_sand", TurfBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.SAND).strength(0.5f).sound(SoundType.SAND));
	public static final Block FLAGSTICK = register("flagstick", p -> new ThinBlock(p, Block.column(2, 0, 16)),
		BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).noCollision().noOcclusion().strength(0.3f).sound(SoundType.METAL));
	public static final Block FLAG = register("flag", p -> new ThinBlock(p, Block.column(2, 0, 16)),
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).noCollision().noOcclusion().strength(0.3f).sound(SoundType.WOOL));
	public static final Block TEE_MARKER = register("tee_marker", p -> new ThinBlock(p, Block.box(5, 0, 5, 11, 6, 11)),
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLUE).noCollision().noOcclusion().instabreak().sound(SoundType.WOOD));
	public static final Block OB_STAKE = register("ob_stake", p -> new ThinBlock(p, Block.column(3, 0, 14)),
		BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).noCollision().noOcclusion().instabreak().sound(SoundType.WOOD));
	public static final Block HAZARD_STAKE = register("hazard_stake", p -> new ThinBlock(p, Block.column(3, 0, 14)),
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).noCollision().noOcclusion().instabreak().sound(SoundType.WOOD));
	public static final Block YARDAGE_100 = marker("yardage_100", MapColor.COLOR_RED);
	public static final Block YARDAGE_150 = marker("yardage_150", MapColor.SNOW);
	public static final Block YARDAGE_200 = marker("yardage_200", MapColor.COLOR_BLUE);

	private GolfBlocks() {
	}

	private static Block marker(String name, MapColor color) {
		return register(name, p -> new ThinBlock(p, Block.column(5, 0, 9)),
			BlockBehaviour.Properties.of().mapColor(color).noCollision().noOcclusion().instabreak().sound(SoundType.WOOD));
	}

	public static Block yardageMarker(int yards) {
		return yards == 100 ? YARDAGE_100 : yards == 150 ? YARDAGE_150 : YARDAGE_200;
	}

	private static Block turf(String name, MapColor color) {
		return register(name, TurfBlock::new, BlockBehaviour.Properties.of().mapColor(color).strength(0.6f).sound(SoundType.GRASS));
	}

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, GolfTour.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(properties.setId(key)));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, GolfTour.id(name));
		BlockItem item = new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(itemKey));
		item.registerBlocks(Item.BY_BLOCK, item);
		Registry.register(BuiltInRegistries.ITEM, itemKey, item);
		ALL.add(block);
		return block;
	}

	public static void init() {
	}
}
