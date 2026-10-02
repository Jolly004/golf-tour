package work.benwalker.golftour.world;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import work.benwalker.golftour.GolfTour;
import work.benwalker.golftour.course.CourseLayout;

/** The golf course dimension ({@code golftour:links}). It has its own clock, held at noon. */
public final class GolfDimension {
	public static final ResourceKey<Level> LINKS = ResourceKey.create(Registries.DIMENSION, GolfTour.id("links"));
	public static final ResourceKey<WorldClock> CLOCK = ResourceKey.create(Registries.WORLD_CLOCK, GolfTour.id("links"));
	private static final long NOON = 6000L;

	private GolfDimension() {
	}

	public static void init() {
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, GolfTour.id("course"), CourseChunkGenerator.CODEC);
		ServerLifecycleEvents.SERVER_STARTED.register(GolfDimension::holdNoon);
	}

	private static void holdNoon(MinecraftServer server) {
		server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).get(CLOCK).ifPresentOrElse(clock -> {
			server.clockManager().setTotalTicks(clock, NOON);
			server.clockManager().setPaused(clock, true);
		}, () -> GolfTour.LOG.warn("Golf clock {} missing; the course will follow the normal day cycle", CLOCK.identifier()));
	}

	public static ServerLevel level(MinecraftServer server) {
		return server.getLevel(LINKS);
	}

	public static boolean isCourse(Level level) {
		return level.dimension() == LINKS;
	}

	/** The course laid out in the golf dimension. */
	public static CourseLayout course(ServerLevel level) {
		if (level.getChunkSource().getGenerator() instanceof CourseChunkGenerator generator) {
			return generator.course();
		}
		return CourseLayout.of(2026L);
	}
}
