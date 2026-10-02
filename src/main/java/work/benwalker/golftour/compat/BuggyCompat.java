package work.benwalker.golftour.compat;

import io.github.foundationgames.automobility.automobile.AutomobileEngine;
import io.github.foundationgames.automobility.automobile.AutomobileFrame;
import io.github.foundationgames.automobility.automobile.AutomobileWheel;
import io.github.foundationgames.automobility.entity.AutomobileEntity;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * The golf buggy, when Automobility (unofficial 26.3 port) is installed: a ready-built vehicle parked by the first
 * tee that the player can drive around the course. Without Automobility there is no buggy and players walk.
 */
public final class BuggyCompat {

	private BuggyCompat() {
	}

	public static boolean active() {
		return FabricLoader.getInstance().isModLoaded("automobility");
	}

	/** Spawns a buggy and returns its id, or null when Automobility isn't installed. */
	public static @Nullable UUID spawn(ServerLevel level, double x, double y, double z, float yaw) {
		return active() ? Impl.spawn(level, x, y, z, yaw) : null;
	}

	/** The buggy entity, if it still exists. */
	public static @Nullable Entity find(ServerLevel level, @Nullable UUID id) {
		if (id == null) {
			return null;
		}
		Entity entity = level.getEntity(id);
		return entity == null || entity.isRemoved() ? null : entity;
	}

	/** Moves an existing, empty buggy to a spot. Returns false if it's missing or someone is in it. */
	public static boolean move(ServerLevel level, @Nullable UUID id, double x, double y, double z, float yaw) {
		Entity buggy = find(level, id);
		if (buggy == null || buggy.isVehicle()) {
			return false;
		}
		buggy.teleportTo(level, x, y, z, Set.of(), yaw, 0, true);
		buggy.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		return true;
	}

	public static void remove(ServerLevel level, @Nullable UUID id) {
		Entity buggy = find(level, id);
		if (buggy != null) {
			buggy.ejectPassengers();
			Impl.destroy(buggy);
		}
	}

	/** Only loaded when Automobility is present. */
	private static final class Impl {
		/** Frame, wheels and engine of the course buggy. Tune here. */
		static final ResourceKey<AutomobileFrame> FRAME = AutomobileFrame.QUARTZ_RICKSHAW; // white, with a canopy: the most golf-cart-like
		static final ResourceKey<AutomobileWheel> WHEELS = AutomobileWheel.STANDARD;
		static final ResourceKey<AutomobileEngine> ENGINE = AutomobileEngine.IRON;

		/** Automobility's own removal, which also takes away the car's hitbox entities. */
		static void destroy(Entity buggy) {
			if (buggy instanceof AutomobileEntity car) {
				car.destroyAutomobile(false, Entity.RemovalReason.DISCARDED);
			} else {
				buggy.discard();
			}
		}

		static UUID spawn(ServerLevel level, double x, double y, double z, float yaw) {
			var registries = level.registryAccess();
			var buggy = new AutomobileEntity(level);
			buggy.setComponents(
				registries.lookupOrThrow(AutomobileFrame.REGISTRY).getOrThrow(FRAME),
				registries.lookupOrThrow(AutomobileWheel.REGISTRY).getOrThrow(WHEELS),
				registries.lookupOrThrow(AutomobileEngine.REGISTRY).getOrThrow(ENGINE));
			buggy.snapTo(x, y, z, yaw, 0);
			level.addFreshEntity(buggy);
			return buggy.getUUID();
		}
	}
}
