package work.benwalker.golftour.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A stroke-play match between friends: same holes, one ball each, playing in turn (server side). */
public final class GolfMatch {
	public enum Phase {
		/** Waiting for players to join. */
		LOBBY,
		/** Playing. */
		PLAYING,
		/** All holes played; results shown. */
		FINISHED
	}

	/** Most players in one match (the tee box fits four balls side by side). */
	public static final int MAX_PLAYERS = 4;

	UUID host;
	/** Players in join order. */
	final List<UUID> members = new ArrayList<>();
	final Map<UUID, String> names = new HashMap<>();
	/** Tee order for the current hole, honour first. */
	final List<UUID> honours = new ArrayList<>();
	Phase phase = Phase.LOBBY;
	int firstHole;
	int lastHole = 17;
	int hole;
	/** Whose shot it is, or null while a ball is moving or the hole is finished. */
	UUID turn;
	/** Ticks until the group moves on, once everyone has finished the hole. */
	int advanceTimer = -1;
	/** Everyone in the match gets the same wind on each hole. */
	final long windSeed = System.nanoTime();

	GolfMatch(UUID host, String name) {
		this.host = host;
		members.add(host);
		names.put(host, name);
	}

	String name(UUID player) {
		return names.getOrDefault(player, "?");
	}

	public Phase phase() {
		return phase;
	}

	public UUID turn() {
		return turn;
	}

	/** Current hole (0-based). */
	public int hole() {
		return hole;
	}

	/** Tee order for the current hole, honour first. */
	public List<UUID> honours() {
		return List.copyOf(honours);
	}
}
