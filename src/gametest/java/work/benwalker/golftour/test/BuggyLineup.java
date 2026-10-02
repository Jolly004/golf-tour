package work.benwalker.golftour.test;

import io.github.foundationgames.automobility.automobile.AutomobileEngine;
import io.github.foundationgames.automobility.automobile.AutomobileFrame;
import io.github.foundationgames.automobility.automobile.AutomobileWheel;
import io.github.foundationgames.automobility.entity.AutomobileEntity;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resources.ResourceKey;
import work.benwalker.golftour.physics.Vec;

import java.util.List;

/** Parks a row of candidate buggy frames in front of the player and screenshots them, to choose the course buggy. */
final class BuggyLineup {
	private static final List<ResourceKey<AutomobileFrame>> FRAMES = List.of(
		AutomobileFrame.STEEL_MOTORCAR, AutomobileFrame.STANDARD_WHITE, AutomobileFrame.WOODEN_MOTORCAR, AutomobileFrame.QUARTZ_RICKSHAW,
		AutomobileFrame.STANDARD_GREEN);

	private BuggyLineup() {
	}

	static void show(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		float yaw = ctx.computeOnClient(mc -> mc.player.getYRot());
		Vec eye = ctx.computeOnClient(mc -> new Vec(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
		List<Integer> ids = sp.getServer().computeOnServer(server -> {
			var level = server.getLevel(work.benwalker.golftour.world.GolfDimension.LINKS);
			var registries = level.registryAccess();
			Vec look = Vec.fromYaw(yaw);
			Vec side = new Vec(-look.z(), 0, look.x());
			var out = new java.util.ArrayList<Integer>();
			for (int i = 0; i < FRAMES.size(); i++) {
				Vec p = eye.add(look.scale(9)).add(side.scale((i - (FRAMES.size() - 1) / 2.0) * 3.2));
				int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(p.x()), (int) Math.floor(p.z()));
				var car = new AutomobileEntity(level);
				car.setComponents(registries.lookupOrThrow(AutomobileFrame.REGISTRY).getOrThrow(FRAMES.get(i)),
					registries.lookupOrThrow(AutomobileWheel.REGISTRY).getOrThrow(AutomobileWheel.STANDARD),
					registries.lookupOrThrow(AutomobileEngine.REGISTRY).getOrThrow(AutomobileEngine.IRON));
				car.snapTo(p.x(), top + 0.5, p.z(), yaw + 150, 0);
				level.addFreshEntity(car);
				out.add(car.getId());
			}
			return out;
		});
		ctx.runOnClient(mc -> mc.player.setXRot(18));
		ctx.waitTicks(30);
		ctx.waitTicks(60);
		ctx.takeScreenshot("golf-13-buggy-lineup");
		sp.getServer().runOnServer(server -> {
			var level = server.getLevel(work.benwalker.golftour.world.GolfDimension.LINKS);
			for (int id : ids) {
				var e = level.getEntity(id);
				if (e != null) {
					e.discard();
				}
			}
		});
	}
}
