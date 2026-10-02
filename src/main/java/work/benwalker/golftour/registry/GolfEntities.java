package work.benwalker.golftour.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import work.benwalker.golftour.GolfTour;
import work.benwalker.golftour.entity.GolfBallEntity;

public final class GolfEntities {
	private static final ResourceKey<EntityType<?>> GOLF_BALL_KEY = ResourceKey.create(Registries.ENTITY_TYPE, GolfTour.id("golf_ball"));

	public static final EntityType<GolfBallEntity> GOLF_BALL = Registry.register(BuiltInRegistries.ENTITY_TYPE, GOLF_BALL_KEY,
		EntityType.Builder.<GolfBallEntity>of(GolfBallEntity::new, MobCategory.MISC)
			.sized(0.2f, 0.2f)
			.clientTrackingRange(16)
			.updateInterval(1)
			.noSave()
			.noSummon()
			.noLootTable()
			.build(GOLF_BALL_KEY));

	private GolfEntities() {
	}

	public static void init() {
	}
}
