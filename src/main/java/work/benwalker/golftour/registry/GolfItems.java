package work.benwalker.golftour.registry;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;

import work.benwalker.golftour.GolfTour;
import work.benwalker.golftour.item.ClubItem;
import work.benwalker.golftour.physics.Club;

public final class GolfItems {
	public static final Map<Club, ClubItem> CLUBS = new EnumMap<>(Club.class);
	public static final Item GOLF_BALL;

	static {
		for (Club club : Club.values()) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, GolfTour.id(club.id()));
			CLUBS.put(club, Registry.register(BuiltInRegistries.ITEM, key, new ClubItem(club, new Item.Properties().stacksTo(1).setId(key))));
		}
		ResourceKey<Item> ballKey = ResourceKey.create(Registries.ITEM, GolfTour.id("golf_ball"));
		GOLF_BALL = Registry.register(BuiltInRegistries.ITEM, ballKey, new Item(new Item.Properties().setId(ballKey)));

		CreativeModeTab tab = FabricCreativeModeTab.builder()
			.title(Component.translatable("itemGroup.golftour"))
			.icon(() -> new ItemStack(CLUBS.get(Club.DRIVER)))
			.displayItems((params, output) -> {
				CLUBS.values().forEach(output::accept);
				output.accept(GOLF_BALL);
				GolfBlocks.ALL.forEach(output::accept);
			})
			.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, GolfTour.id("golf"), tab);
	}

	private GolfItems() {
	}

	public static ItemStack club(Club club) {
		return new ItemStack(CLUBS.get(club));
	}

	public static void init() {
	}
}
