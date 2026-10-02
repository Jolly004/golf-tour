package work.benwalker.golftour.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import work.benwalker.golftour.game.GolfRound;
import work.benwalker.golftour.item.ClubItem;
import work.benwalker.golftour.physics.Vec;

/**
 * Animates the other golfers in a match on this client: set up over their ball when it's their shot, then
 * swinging through to a finish when their shot arrives. (Each client animates its own golfer from the mouse;
 * friends only see the result, so this rebuilds a believable swing from it.)
 */
public final class OtherGolfers {
	/** How a golfer should be posed: swing progress (+1 top, 0 address, -1 finish), body facing, and putter or not. */
	public record Pose(double swing, float facing, boolean putter) {
	}

	private record Strike(long nanos, boolean putt, float facing) {
	}

	private static final Map<Integer, Strike> STRIKES = new HashMap<>();

	private OtherGolfers() {
	}

	/** Another player's shot just arrived: they've hit it. */
	public static void onShot(String name, List<Vec> path, boolean putt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || path.size() < 2) {
			return;
		}
		for (Player p : mc.level.players()) {
			if (p.getPlainTextName().equals(name)) {
				Vec aim = path.get(Math.min(3, path.size() - 1)).sub(path.get(0));
				STRIKES.put(p.getId(), new Strike(System.nanoTime(), putt, Vec.yawOf(aim) + 90f));
				return;
			}
		}
	}

	/** Pose for player entity {@code id}, or null to leave them as vanilla poses them. */
	public static Pose pose(int id) {
		Minecraft mc = Minecraft.getInstance();
		Strike s = STRIKES.get(id);
		if (s != null) {
			double age = (System.nanoTime() - s.nanos()) / 1e9;
			if (age < 1.7) {
				return new Pose(finish(age), s.facing(), s.putt());
			}
			STRIKES.remove(id);
		}
		if (!ClientMatch.playing() || mc.level == null || ClientGolf.course() == null) {
			return null;
		}
		Entity e = mc.level.getEntity(id);
		if (!(e instanceof Player p) || p == mc.player) {
			return null;
		}
		for (var m : ClientMatch.members()) {
			if (!m.id().equals(p.getUUID())) {
				continue;
			}
			boolean up = m.id().equals(ClientMatch.state().turn()) && m.state() == GolfRound.State.ADDRESS.ordinal();
			double dx = p.getX() - m.ball().x(), dz = p.getZ() - m.ball().z();
			if (!up || dx * dx + dz * dz > 2.4 * 2.4 || !(p.getMainHandItem().getItem() instanceof ClubItem club)) {
				return null;
			}
			double t = System.nanoTime() / 1e9;
			float aim = Vec.yawOf(ClientGolf.cup().sub(m.ball()));
			return new Pose(0.03 * Math.sin(t * 2.3), aim + 90f, club.club().isPutter());
		}
		return null;
	}

	/** Through impact to a held finish, then back down (same timing as our own golfer's follow-through). */
	private static double finish(double age) {
		if (age < 0.32) {
			double x = age / 0.32;
			return -(1 - (1 - x) * (1 - x));
		}
		if (age < 1.05) {
			return -1;
		}
		double x = Math.min(1, (age - 1.05) / 0.65);
		return -(1 - x * x * (3 - 2 * x));
	}
}
