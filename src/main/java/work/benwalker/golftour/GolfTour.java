package work.benwalker.golftour;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import work.benwalker.golftour.command.GolfCommand;
import work.benwalker.golftour.game.RoundManager;
import work.benwalker.golftour.net.GolfNetworking;
import work.benwalker.golftour.registry.GolfAttachments;
import work.benwalker.golftour.registry.GolfBlocks;
import work.benwalker.golftour.registry.GolfEntities;
import work.benwalker.golftour.registry.GolfItems;
import work.benwalker.golftour.registry.GolfSounds;
import work.benwalker.golftour.world.GolfDimension;

/** Golf Tour: PGA-style golf in Minecraft. */
public final class GolfTour implements ModInitializer {
	public static final String MOD_ID = "golftour";
	public static final Logger LOG = LoggerFactory.getLogger("Golf Tour");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		GolfSounds.init();
		GolfBlocks.init();
		work.benwalker.golftour.compat.NoCubesCompat.init();
		GolfItems.init();
		GolfEntities.init();
		GolfAttachments.init();
		GolfDimension.init();
		GolfNetworking.init();
		RoundManager.init();
		work.benwalker.golftour.game.MatchManager.init();
		GolfCommand.init();
		LOG.info("Golf Tour loaded");
	}
}
