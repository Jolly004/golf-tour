package work.benwalker.golftour.compat;

import io.github.cadiboo.nocubes.NoCubes;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import work.benwalker.golftour.GolfTour;
import work.benwalker.golftour.block.TurfBlock;

/**
 * Smooth terrain through NoCubes (26.3 port), when it is installed. Every course turf block is registered as a
 * layered smoothable, so NoCubes puts the smooth surface at the turf's real height: greens slope, bunkers bowl and
 * banks curve, at the same height the ball physics uses. Without NoCubes the course keeps its layered turf.
 */
public final class NoCubesCompat {

	private NoCubesCompat() {
	}

	public static boolean active() {
		return FabricLoader.getInstance().isModLoaded("nocubes");
	}

	public static void init() {
		if (active()) {
			Impl.register();
		}
	}

	/** True if NoCubes smooths this block (its collision shape is then NoCubes' approximation, not the block's). */
	public static boolean smoothed(net.minecraft.world.level.block.state.BlockState state) {
		return active() && Impl.smoothable(state);
	}

	/** Only loaded when NoCubes is present. */
	private static final class Impl {
		static boolean smoothable(net.minecraft.world.level.block.state.BlockState state) {
			return NoCubes.isSmoothable(state);
		}

		static void register() {
			int count = 0;
			for (var block : BuiltInRegistries.BLOCK) {
				if (block instanceof TurfBlock && BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(GolfTour.MOD_ID)) {
					NoCubes.addLayeredSmoothable(block, TurfBlock.LAYERS);
					count++;
				}
			}
			GolfTour.LOG.info("NoCubes found: {} turf blocks render as smooth terrain", count);
		}
	}
}
