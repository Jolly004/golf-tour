package work.benwalker.golftour.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Order of play in a match, as in real golf: on the tee the player with the honour (best score on the last hole)
 * goes first and everyone tees off before anyone plays again; after that the player farthest from the hole plays.
 */
public final class TurnOrder {

	/**
	 * One player's position on the current hole.
	 *
	 * @param done holed out (or picked up)
	 * @param teedOff has played at least one shot on this hole
	 * @param distanceToCup how far their ball is from the hole (any unit)
	 */
	public record Entry(UUID player, boolean done, boolean teedOff, double distanceToCup) {
	}

	private TurnOrder() {
	}

	/** Who plays next, or null when everyone is done. {@code honours} lists players best-first for the tee. */
	public static UUID next(List<Entry> entries, List<UUID> honours) {
		List<Entry> tee = new ArrayList<>();
		Entry away = null;
		for (Entry e : entries) {
			if (e.done()) {
				continue;
			}
			if (!e.teedOff()) {
				tee.add(e);
			} else if (away == null || e.distanceToCup() > away.distanceToCup()) {
				away = e;
			}
		}
		if (!tee.isEmpty()) {
			tee.sort(Comparator.comparingInt(e -> rank(honours, e.player())));
			return tee.get(0).player();
		}
		return away == null ? null : away.player();
	}

	/**
	 * Honours for the next tee: lowest score on the hole just played first; ties keep the order they teed off in.
	 *
	 * @param scores strokes on the hole just played, by player
	 */
	public static List<UUID> honours(List<UUID> previous, java.util.Map<UUID, Integer> scores) {
		List<UUID> order = new ArrayList<>(previous);
		order.sort(Comparator.comparingInt((UUID p) -> scores.getOrDefault(p, Integer.MAX_VALUE)).thenComparingInt(previous::indexOf));
		return order;
	}

	private static int rank(List<UUID> honours, UUID player) {
		int i = honours.indexOf(player);
		return i < 0 ? Integer.MAX_VALUE : i;
	}
}
