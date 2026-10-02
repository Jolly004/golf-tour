package work.benwalker.golftour.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import work.benwalker.golftour.game.GolfMatch;
import work.benwalker.golftour.game.GolfRound;
import work.benwalker.golftour.net.BallFlightPayload;
import work.benwalker.golftour.net.MatchStatePayload;
import work.benwalker.golftour.physics.Vec;

/** Client-side view of the match the player is in: who's playing, whose turn it is, and other players' shots. */
public final class ClientMatch {
	/** Player colours by join order, for markers, tracers and the leaderboard. */
	private static final int[] COLORS = {0xFF4FC3F7, 0xFFFF8A65, 0xFFBA68C8, 0xFF9CCC65};

	private static MatchStatePayload state = MatchStatePayload.inactive();
	/** Client tick when the turn last changed (for the "YOUR SHOT" banner). */
	public static int turnChangedAt = -1000;

	// The last shot someone else hit
	public static List<Vec> watchPath = List.of();
	public static int watchStart = -1000;
	public static int watchLanding;
	public static int watchBall = -1;
	public static String watchName = "";
	public static String watchQuality = "";
	public static int watchColor;
	public static float watchCarry;
	public static boolean watchPutt;

	private ClientMatch() {
	}

	public static void onState(MatchStatePayload payload) {
		java.util.UUID before = state.active() ? state.turn() : null;
		state = payload;
		if (payload.turn() != null && !payload.turn().equals(before)) {
			turnChangedAt = ClientGolf.ticks;
		}
	}

	public static void onOtherFlight(BallFlightPayload payload) {
		watchPath = payload.path();
		watchStart = ClientGolf.ticks;
		watchLanding = payload.landingTick();
		watchBall = payload.ballEntity();
		watchName = payload.player();
		watchQuality = payload.quality();
		watchColor = payload.qualityColor();
		watchCarry = payload.carryYards();
		watchPutt = payload.putt();
		OtherGolfers.onShot(payload.player(), payload.path(), payload.putt());
	}

	public static void reset() {
		state = MatchStatePayload.inactive();
		watchPath = List.of();
	}

	public static boolean active() {
		return state.active();
	}

	public static boolean lobby() {
		return active() && state.phase() == GolfMatch.Phase.LOBBY.ordinal();
	}

	public static boolean playing() {
		return active() && state.phase() == GolfMatch.Phase.PLAYING.ordinal();
	}

	public static boolean finished() {
		return active() && state.phase() == GolfMatch.Phase.FINISHED.ordinal();
	}

	public static MatchStatePayload state() {
		return state;
	}

	public static UUID me() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : mc.player.getUUID();
	}

	/** True unless the player is in a running match and it's someone else's shot. */
	public static boolean myTurn() {
		return !playing() || (state.turn() != null && state.turn().equals(me()));
	}

	/** Name of whoever is up, or "" if nobody is (a ball is moving, or the hole is finished). */
	public static String turnName() {
		if (state.turn() == null) {
			return "";
		}
		for (MatchStatePayload.Member m : state.members()) {
			if (m.id().equals(state.turn())) {
				return m.name();
			}
		}
		return "";
	}

	public static List<MatchStatePayload.Member> members() {
		return state.members();
	}

	public static int color(UUID player) {
		List<MatchStatePayload.Member> ms = state.members();
		for (int i = 0; i < ms.size(); i++) {
			if (ms.get(i).id().equals(player)) {
				return COLORS[i % COLORS.length];
			}
		}
		return 0xFFFFFFFF;
	}

	public static int colorOf(String name) {
		for (MatchStatePayload.Member m : state.members()) {
			if (m.name().equals(name)) {
				return color(m.id());
			}
		}
		return 0xFFFFFFFF;
	}

	/** Strokes against par over the holes this player has finished. */
	public static int toPar(MatchStatePayload.Member m) {
		int diff = 0;
		var course = ClientGolf.course();
		if (course == null) {
			return 0;
		}
		for (int i = 0; i < 18; i++) {
			if (m.scores()[i] > 0) {
				diff += m.scores()[i] - course.holes.get(i).par();
			}
		}
		return diff;
	}

	public static int holesDone(MatchStatePayload.Member m) {
		int n = 0;
		for (int s : m.scores()) {
			if (s > 0) {
				n++;
			}
		}
		return n;
	}

	/** Leaderboard order: best score to par, then fewest holes left to play. */
	public static List<MatchStatePayload.Member> standings() {
		List<MatchStatePayload.Member> list = new ArrayList<>(state.members());
		list.sort(Comparator.comparingInt(ClientMatch::toPar).thenComparing(m -> -holesDone(m)));
		return list;
	}

	public static boolean holedOut(MatchStatePayload.Member m) {
		return m.state() == GolfRound.State.HOLED.ordinal() || m.state() == GolfRound.State.FINISHED.ordinal();
	}

	/** True while another player's shot is in the air or rolling (plus a moment to see where it stopped). */
	public static boolean watching() {
		return !watchPath.isEmpty() && ClientGolf.ticks - watchStart < watchPath.size() + 30;
	}

	public static double watchProgress(float partialTick) {
		return Math.min(watchPath.size() - 1, ClientGolf.ticks - watchStart + partialTick);
	}

	public static Entity watchedBall() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null || watchBall < 0 ? null : mc.level.getEntity(watchBall);
	}
}
