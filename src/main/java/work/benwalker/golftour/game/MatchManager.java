package work.benwalker.golftour.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import work.benwalker.golftour.net.GolfNetworking;
import work.benwalker.golftour.net.MatchStatePayload;
import work.benwalker.golftour.physics.Vec;
import work.benwalker.golftour.registry.GolfSounds;

/**
 * Multiplayer matches: a host creates a lobby, friends join from a clickable invite, and the host starts it.
 * Everyone plays their own ball on the same holes in real golf order (honours on the tee, then farthest from the
 * hole), the group moves to the next tee once everyone has holed out, and the lowest total wins.
 */
public final class MatchManager {
	private static final Map<UUID, GolfMatch> BY_PLAYER = new HashMap<>();
	/** Players who dropped out of a running match, so they can rejoin it. */
	private static final Map<UUID, GolfMatch> DEPARTED = new HashMap<>();
	/** Ticks between the last player holing out and the group heading to the next tee. */
	private static final int HOLE_BREAK = 80;

	private MatchManager() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(MatchManager::tick);
	}

	public static GolfMatch of(ServerPlayer player) {
		return BY_PLAYER.get(player.getUUID());
	}

	// ---------------------------------------------------------------- lobby

	public static boolean create(ServerPlayer host) {
		if (BY_PLAYER.containsKey(host.getUUID())) {
			host.sendSystemMessage(Component.literal("You're already in a match. Leave it first with /golf match leave").withStyle(ChatFormatting.RED));
			return false;
		}
		GolfMatch match = new GolfMatch(host.getUUID(), host.getPlainTextName());
		BY_PLAYER.put(host.getUUID(), match);
		RoundManager.rememberIfFake(host);
		String name = host.getPlainTextName();
		for (ServerPlayer other : host.level().getServer().getPlayerList().getPlayers()) {
			if (other != host && !BY_PLAYER.containsKey(other.getUUID())) {
				other.sendSystemMessage(Component.literal(name + " is starting a golf match.  ").withStyle(ChatFormatting.GREEN)
					.append(button("[JOIN]", "/golf match join " + name, ChatFormatting.YELLOW)));
			}
		}
		host.sendSystemMessage(Component.literal("Match created. Friends can join from the invite in chat, or with /golf match join " + name + ".  ")
			.withStyle(ChatFormatting.GREEN)
			.append(button("[START 18]", "/golf match start 18", ChatFormatting.YELLOW))
			.append(Component.literal(" "))
			.append(button("[START 9]", "/golf match start 9", ChatFormatting.YELLOW)));
		broadcast(host.level().getServer(), match);
		return true;
	}

	public static boolean join(ServerPlayer player, ServerPlayer host) {
		GolfMatch match = BY_PLAYER.get(host.getUUID());
		if (match == null) {
			player.sendSystemMessage(Component.literal(host.getPlainTextName() + " isn't hosting a match.").withStyle(ChatFormatting.RED));
			return false;
		}
		if (BY_PLAYER.get(player.getUUID()) == match) {
			return true;
		}
		if (BY_PLAYER.containsKey(player.getUUID())) {
			player.sendSystemMessage(Component.literal("You're already in a match. Leave it first with /golf match leave").withStyle(ChatFormatting.RED));
			return false;
		}
		if (match.phase != GolfMatch.Phase.LOBBY) {
			player.sendSystemMessage(Component.literal("That match has already started.").withStyle(ChatFormatting.RED));
			return false;
		}
		if (match.members.size() >= GolfMatch.MAX_PLAYERS) {
			player.sendSystemMessage(Component.literal("That match is full (" + GolfMatch.MAX_PLAYERS + " players).").withStyle(ChatFormatting.RED));
			return false;
		}
		match.members.add(player.getUUID());
		match.names.put(player.getUUID(), player.getPlainTextName());
		BY_PLAYER.put(player.getUUID(), match);
		RoundManager.rememberIfFake(player);
		MinecraftServer server = player.level().getServer();
		for (ServerPlayer m : online(server, match)) {
			m.sendSystemMessage(Component.literal(player.getPlainTextName() + " joined the match (" + match.members.size() + "/" + GolfMatch.MAX_PLAYERS + ").")
				.withStyle(ChatFormatting.GREEN));
		}
		broadcast(server, match);
		return true;
	}

	public static void leave(ServerPlayer player) {
		GolfMatch match = BY_PLAYER.get(player.getUUID());
		if (match == null) {
			player.sendSystemMessage(Component.literal("You're not in a match.").withStyle(ChatFormatting.RED));
			return;
		}
		boolean playing = match.phase == GolfMatch.Phase.PLAYING;
		remove(player.level().getServer(), match, player.getUUID(), player.getPlainTextName() + " left the match.");
		GolfNetworking.send(player, MatchStatePayload.inactive());
		if (playing && RoundManager.get(player) != null) {
			RoundManager.quit(player);
		}
	}

	public static boolean start(ServerPlayer host, int holes) {
		GolfMatch match = BY_PLAYER.get(host.getUUID());
		if (match == null || !match.host.equals(host.getUUID())) {
			host.sendSystemMessage(Component.literal("Only the match host can start it. Create one with /golf match create").withStyle(ChatFormatting.RED));
			return false;
		}
		if (match.phase != GolfMatch.Phase.LOBBY) {
			return false;
		}
		MinecraftServer server = host.level().getServer();
		match.phase = GolfMatch.Phase.PLAYING;
		match.firstHole = 0;
		match.lastHole = Math.max(0, Math.min(17, holes - 1));
		match.hole = 0;
		match.honours.clear();
		match.honours.addAll(match.members);
		for (int i = 0; i < match.members.size(); i++) {
			ServerPlayer p = RoundManager.player(server, match.members.get(i));
			if (p != null) {
				RoundManager.startInMatch(p, match, i, match.hole, null);
			}
		}
		for (ServerPlayer m : online(server, match)) {
			m.sendSystemMessage(Component.literal("Match on: " + (match.lastHole + 1) + " holes, stroke play. " + match.name(match.honours.get(0))
				+ " has the honour.").withStyle(ChatFormatting.GOLD));
		}
		update(server, match);
		return true;
	}

	/** Lists the match (or open lobbies) in chat. */
	public static void status(ServerPlayer player) {
		GolfMatch match = BY_PLAYER.get(player.getUUID());
		if (match == null) {
			MutableComponent line = Component.literal("Open matches: ").withStyle(ChatFormatting.GREEN);
			boolean any = false;
			for (GolfMatch m : new java.util.HashSet<>(BY_PLAYER.values())) {
				if (m.phase == GolfMatch.Phase.LOBBY) {
					line.append(button("[" + m.name(m.host) + "]", "/golf match join " + m.name(m.host), ChatFormatting.YELLOW)).append(" ");
					any = true;
				}
			}
			player.sendSystemMessage(any ? line : Component.literal("No open matches. Start one with /golf match create").withStyle(ChatFormatting.GRAY));
			return;
		}
		player.sendSystemMessage(Component.literal("Match (" + match.phase.name().toLowerCase() + "), hosted by " + match.name(match.host) + ":")
			.withStyle(ChatFormatting.GOLD));
		for (UUID id : match.members) {
			player.sendSystemMessage(Component.literal("  " + match.name(id)));
		}
	}

	// ---------------------------------------------------------------- playing

	/** Whether {@code player} may hit now (always true outside a match). Tells them who's up if not. */
	static boolean mayPlay(ServerPlayer player, GolfRound round) {
		GolfMatch match = round.match;
		if (match == null || match.phase != GolfMatch.Phase.PLAYING) {
			return true;
		}
		if (player.getUUID().equals(match.turn)) {
			return true;
		}
		String who = match.turn == null ? "the group" : match.name(match.turn);
		RoundManager.notice(player, "WAIT · " + who.toUpperCase() + " TO PLAY", 0xFFE066);
		return false;
	}

	/** A member's round changed (shot played, ball at rest, holed, new hole...): work out the turn and sync. */
	static void onRoundChanged(MinecraftServer server, GolfMatch match) {
		if (match.phase == GolfMatch.Phase.PLAYING) {
			update(server, match);
		} else {
			broadcast(server, match);
		}
	}

	/** Another member's shot, for their tracer and ball camera. */
	static void shareShot(MinecraftServer server, GolfMatch match, ServerPlayer hitter, work.benwalker.golftour.net.BallFlightPayload flight) {
		for (ServerPlayer m : online(server, match)) {
			if (m != hitter) {
				GolfNetworking.send(m, flight.forOthers(hitter.getPlainTextName()));
			}
		}
	}

	private static void update(MinecraftServer server, GolfMatch match) {
		List<TurnOrder.Entry> entries = new ArrayList<>();
		boolean moving = false;
		boolean allDone = true;
		for (UUID id : match.members) {
			GolfRound r = RoundManager.round(id);
			if (r == null) {
				continue;
			}
			boolean done = r.state == GolfRound.State.HOLED || r.state == GolfRound.State.FINISHED;
			moving |= r.state == GolfRound.State.FLIGHT || r.state == GolfRound.State.SETTLING;
			allDone &= done;
			entries.add(new TurnOrder.Entry(id, done, r.strokes > 0, r.ballPos.horizontalDistanceTo(r.cup())));
		}
		UUID next = moving ? null : TurnOrder.next(entries, match.honours);
		if (next != null && !next.equals(match.turn)) {
			match.turn = next;
			ServerPlayer up = RoundManager.player(server, next);
			if (up != null) {
				RoundManager.notice(up, "YOUR SHOT", 0x55FF55);
				up.level().playSound(null, up.getX(), up.getY(), up.getZ(), GolfSounds.UI_HOLE_START, net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.4f);
			}
			for (ServerPlayer m : online(server, match)) {
				if (!m.getUUID().equals(next)) {
					RoundManager.notice(m, match.name(next).toUpperCase() + " TO PLAY", 0xFFFFFF);
				}
			}
		} else if (next == null) {
			match.turn = null;
		}
		if (!moving && allDone && !entries.isEmpty() && match.advanceTimer < 0) {
			match.advanceTimer = HOLE_BREAK;
		}
		broadcast(server, match);
	}

	private static void tick(MinecraftServer server) {
		for (GolfMatch match : new java.util.HashSet<>(BY_PLAYER.values())) {
			if (match.phase == GolfMatch.Phase.PLAYING && match.advanceTimer >= 0 && --match.advanceTimer < 0) {
				nextHole(server, match);
			}
		}
	}

	private static void nextHole(MinecraftServer server, GolfMatch match) {
		Map<UUID, Integer> scores = new HashMap<>();
		for (UUID id : match.members) {
			GolfRound r = RoundManager.round(id);
			if (r != null) {
				scores.put(id, r.scores[match.hole]);
			}
		}
		List<UUID> honours = TurnOrder.honours(match.honours.stream().filter(match.members::contains).toList(), scores);
		match.honours.clear();
		match.honours.addAll(honours);
		for (UUID id : match.members) {
			if (!match.honours.contains(id)) {
				match.honours.add(id);
			}
		}
		if (match.hole >= match.lastHole) {
			finish(server, match);
			return;
		}
		match.hole++;
		match.turn = null;
		for (int i = 0; i < match.members.size(); i++) {
			UUID id = match.members.get(i);
			ServerPlayer p = RoundManager.player(server, id);
			GolfRound r = RoundManager.round(id);
			if (p != null && r != null) {
				RoundManager.matchNextHole(p, r, i);
			}
		}
		for (ServerPlayer m : online(server, match)) {
			m.sendSystemMessage(Component.literal("Hole " + (match.hole + 1) + ": " + match.name(match.honours.get(0)) + " has the honour.")
				.withStyle(ChatFormatting.GOLD));
		}
		update(server, match);
	}

	private static void finish(MinecraftServer server, GolfMatch match) {
		match.phase = GolfMatch.Phase.FINISHED;
		match.turn = null;
		List<UUID> standings = new ArrayList<>(match.members);
		standings.sort(Comparator.comparingInt(MatchManager::total));
		UUID winner = standings.get(0);
		boolean tie = standings.size() > 1 && total(standings.get(1)) == total(winner);
		for (ServerPlayer m : online(server, match)) {
			GolfRound r = RoundManager.round(m.getUUID());
			if (r != null) {
				RoundManager.finishRound(m, r, false);
			}
			boolean won = !tie && m.getUUID().equals(winner);
			String headline = tie ? "TIED" : won ? "YOU WIN!" : match.name(winner).toUpperCase() + " WINS";
			RoundManager.title(m, Component.literal(headline).withStyle(won ? ChatFormatting.GOLD : ChatFormatting.WHITE, ChatFormatting.BOLD),
				Component.literal(standingsLine(match, standings)).withStyle(ChatFormatting.WHITE), 120);
			m.sendSystemMessage(Component.literal("Final standings").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			int place = 1;
			for (UUID id : standings) {
				GolfRound pr = RoundManager.round(id);
				m.sendSystemMessage(Component.literal("  " + place++ + ". " + match.name(id) + "  " + total(id)
					+ (pr == null ? "" : "  (" + RoundManager.formatToPar(pr.toPar()) + ")")).withStyle(id.equals(winner) ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
			}
			if (won) {
				m.level().playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, net.minecraft.sounds.SoundSource.PLAYERS, 0.9f, 1f);
			}
		}
		broadcast(server, match);
		// The group stays together for a rematch; the host can start a new match once everyone has left this one.
		for (UUID id : match.members) {
			BY_PLAYER.remove(id);
			DEPARTED.remove(id);
		}
	}

	private static String standingsLine(GolfMatch match, List<UUID> standings) {
		StringBuilder sb = new StringBuilder();
		for (UUID id : standings) {
			if (!sb.isEmpty()) {
				sb.append("  ·  ");
			}
			GolfRound r = RoundManager.round(id);
			sb.append(match.name(id)).append(' ').append(r == null ? "-" : RoundManager.formatToPar(r.toPar()));
		}
		return sb.toString();
	}

	private static int total(UUID id) {
		GolfRound r = RoundManager.round(id);
		return r == null ? Integer.MAX_VALUE : r.totalStrokes();
	}

	// ---------------------------------------------------------------- joining and leaving mid-match

	/** A player disconnected or left the course. */
	static void onLeave(MinecraftServer server, ServerPlayer player) {
		GolfMatch match = BY_PLAYER.get(player.getUUID());
		if (match == null) {
			return;
		}
		if (match.phase == GolfMatch.Phase.PLAYING) {
			DEPARTED.put(player.getUUID(), match);
		}
		remove(server, match, player.getUUID(), player.getPlainTextName() + " left the match.");
	}

	/** A player who dropped out of a running match came back: put them back in on the current hole. */
	static boolean rejoin(ServerPlayer player, int[] scores) {
		GolfMatch match = DEPARTED.remove(player.getUUID());
		if (match == null || match.phase != GolfMatch.Phase.PLAYING || match.members.size() >= GolfMatch.MAX_PLAYERS) {
			return false;
		}
		match.members.add(player.getUUID());
		match.names.put(player.getUUID(), player.getPlainTextName());
		if (!match.honours.contains(player.getUUID())) {
			match.honours.add(player.getUUID());
		}
		BY_PLAYER.put(player.getUUID(), match);
		MinecraftServer server = player.level().getServer();
		RoundManager.startInMatch(player, match, match.members.size() - 1, match.hole, scores);
		for (ServerPlayer m : online(server, match)) {
			m.sendSystemMessage(Component.literal(player.getPlainTextName() + " is back in the match.").withStyle(ChatFormatting.GREEN));
		}
		update(server, match);
		return true;
	}

	private static void remove(MinecraftServer server, GolfMatch match, UUID id, String message) {
		match.members.remove(id);
		BY_PLAYER.remove(id);
		GolfRound r = RoundManager.round(id);
		if (r != null && r.match == match) {
			r.match = null;
		}
		if (match.members.isEmpty()) {
			return;
		}
		if (match.host.equals(id)) {
			match.host = match.members.get(0);
		}
		for (ServerPlayer m : online(server, match)) {
			m.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.YELLOW));
		}
		if (match.phase == GolfMatch.Phase.PLAYING) {
			if (id.equals(match.turn)) {
				match.turn = null;
			}
			update(server, match);
		} else {
			broadcast(server, match);
		}
	}

	// ---------------------------------------------------------------- sync

	private static List<ServerPlayer> online(MinecraftServer server, GolfMatch match) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : match.members) {
			ServerPlayer p = RoundManager.player(server, id);
			if (p != null) {
				out.add(p);
			}
		}
		return out;
	}

	private static void broadcast(MinecraftServer server, GolfMatch match) {
		List<MatchStatePayload.Member> members = new ArrayList<>();
		for (UUID id : match.members) {
			GolfRound r = RoundManager.round(id);
			if (r == null || r.match != match) {
				members.add(new MatchStatePayload.Member(id, match.name(id), -1, new int[18], 0, Vec.ZERO, -1));
			} else {
				members.add(new MatchStatePayload.Member(id, match.name(id), r.state.ordinal(), r.scores.clone(), r.strokes, r.ballPos,
					r.ball == null ? -1 : r.ball.getId()));
			}
		}
		var payload = new MatchStatePayload(true, match.phase.ordinal(), match.host, match.firstHole, match.lastHole, match.hole, match.turn, members);
		for (ServerPlayer m : online(server, match)) {
			GolfNetworking.send(m, payload);
		}
	}

	private static MutableComponent button(String text, String command, ChatFormatting color) {
		return Component.literal(text).withStyle(s -> s.withColor(color).withBold(true).withClickEvent(new ClickEvent.RunCommand(command)));
	}
}
