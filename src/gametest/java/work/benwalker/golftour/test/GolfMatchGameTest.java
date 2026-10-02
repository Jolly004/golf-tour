package work.benwalker.golftour.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;

import work.benwalker.golftour.client.ClientGolf;
import work.benwalker.golftour.client.ClientMatch;
import work.benwalker.golftour.client.GolfKeys;
import work.benwalker.golftour.game.GolfMatch;
import work.benwalker.golftour.game.GolfRound;
import work.benwalker.golftour.game.MatchManager;
import work.benwalker.golftour.game.RoundManager;
import work.benwalker.golftour.game.TurnOrder;
import work.benwalker.golftour.physics.Club;
import work.benwalker.golftour.physics.ShotCalculator;
import work.benwalker.golftour.physics.ShotType;
import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.physics.Vec;
import work.benwalker.golftour.world.GolfDimension;

/**
 * A two-hole, three-player match in the real game: the client player hosts, two friends (fake players driven from
 * the server) join, and everyone plays in turn. Checks the order of play, that out-of-turn shots are refused,
 * honours on the second tee, and the final result, with screenshots of the leaderboard, a friend's shot and the
 * group scorecard.
 */
public class GolfMatchGameTest implements FabricClientGameTest {
	private static final UUID SAM = UUID.fromString("00000000-0000-4000-8000-00000000a001");
	private static final UUID ALEX = UUID.fromString("00000000-0000-4000-8000-00000000a002");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass())) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			UUID me = ctx.computeOnClient(mc -> mc.player.getUUID());

			sp.getServer().runCommand("execute as @a run golf match create");
			ctx.waitFor(mc -> ClientMatch.lobby(), 100);
			sp.getServer().runOnServer(server -> {
				ServerPlayer host = server.getPlayerList().getPlayer(me);
				MatchManager.join(friend(server, SAM, "Sam"), host);
				MatchManager.join(friend(server, ALEX, "Alex"), host);
			});
			ctx.waitFor(mc -> ClientMatch.members().size() == 3, 100);
			ctx.waitTicks(5);
			ctx.takeScreenshot("match-01-lobby");

			sp.getServer().runCommand("execute as @a run golf match start 2");
			ctx.waitFor(mc -> ClientMatch.playing(), 200);

			List<String> order = new ArrayList<>();
			boolean triedOutOfTurn = false, watched = false, boardShot = false;
			List<UUID> hole2Honours = null;
			Map<UUID, Integer> hole1Scores = null;
			for (int step = 0; step < 80; step++) {
				ctx.waitFor(mc -> ClientMatch.finished() || ClientMatch.state().turn() != null, 2400);
				if (ctx.computeOnClient(mc -> ClientMatch.finished())) {
					break;
				}
				UUID turn = ctx.computeOnClient(mc -> ClientMatch.state().turn());
				int hole = ctx.computeOnClient(mc -> ClientMatch.state().hole());
				if (hole == 1 && hole2Honours == null) {
					hole2Honours = sp.getServer().computeOnServer(server -> match(server, me).honours());
					hole1Scores = sp.getServer().computeOnServer(server -> Map.of(
						me, RoundManager.get(server.getPlayerList().getPlayer(me)).scores()[0],
						SAM, RoundManager.get(friendOnline(server, SAM)).scores()[0],
						ALEX, RoundManager.get(friendOnline(server, ALEX)).scores()[0]));
				}
				String who = turn.equals(me) ? "me" : turn.equals(SAM) ? "Sam" : "Alex";
				order.add((hole + 1) + ":" + who);
				int strokesBefore = strokes(sp, turn, me);

				if (turn.equals(me)) {
					atBall(ctx);
					if (hole == 1 && !boardShot) {
						ctx.takeScreenshot("match-03-board-hole2");
						ctx.getInput().holdKey(GolfKeys.SCORECARD);
						ctx.waitTicks(3);
						ctx.takeScreenshot("match-04-scorecard");
						ctx.getInput().releaseKey(GolfKeys.SCORECARD);
						boardShot = true;
					}
					String[] plan = ctx.computeOnClient(mc -> plan());
					sp.getServer().runCommand("execute as @a run golf hit " + plan[0] + " " + plan[1]);
				} else {
					if (!triedOutOfTurn) {
						// Out of turn: the server must refuse the real player's shot.
						int mine = strokes(sp, me, me);
						sp.getServer().runCommand("execute as @a run golf hit driver 1.0");
						ctx.waitTicks(10);
						if (strokes(sp, me, me) != mine) {
							throw new AssertionError("An out-of-turn shot was played");
						}
						triedOutOfTurn = true;
					}
					if (!watched) {
						// Step back from our own ball so the camera follows the friend's shot.
						ctx.getInput().holdKeyFor(o -> o.keyDown, 3);
						ctx.waitTicks(5);
					}
					sp.getServer().runOnServer(server -> friendShot(server, turn));
					if (!watched) {
						ctx.waitTicks(30);
						ctx.takeScreenshot("match-02-watching-" + who.toLowerCase());
						watched = true;
					}
				}
				// Wait for that shot to finish: their stroke count went up and their ball is at rest (or holed).
				boolean done = false;
				for (int t = 0; t < 400 && !done; t++) {
					ctx.waitTicks(4);
					done = strokes(sp, turn, me) != strokesBefore && atRest(sp, turn);
				}
				if (!done) {
					throw new AssertionError("Shot " + order.size() + " by " + who + " never finished");
				}
				ctx.waitTicks(3);
			}
			System.out.println("[match] order of play: " + order);

			if (!order.get(0).equals("1:me") || !order.get(1).equals("1:Sam") || !order.get(2).equals("1:Alex")) {
				throw new AssertionError("First tee should go in join order (me, Sam, Alex), got " + order.subList(0, 3));
			}
			if (hole2Honours == null) {
				throw new AssertionError("Never reached the second hole");
			}
			List<UUID> expected = TurnOrder.honours(List.of(me, SAM, ALEX), hole1Scores);
			System.out.println("[match] hole 1 scores " + hole1Scores + " -> honours " + hole2Honours);
			if (!hole2Honours.equals(expected)) {
				throw new AssertionError("Second-tee honours should follow hole 1 scores: expected " + expected + " got " + hole2Honours);
			}
			ctx.waitFor(mc -> ClientMatch.finished(), 600);
			ctx.waitTicks(30);
			ctx.takeScreenshot("match-05-result");
			String board = ctx.computeOnClient(mc -> {
				StringBuilder sb = new StringBuilder();
				for (var m : ClientMatch.standings()) {
					sb.append(m.name()).append(' ').append(m.scores()[0]).append('+').append(m.scores()[1]).append("  ");
				}
				return sb.toString();
			});
			System.out.println("[match] final: " + board);
		}
	}

	private static FakePlayer friend(MinecraftServer server, UUID id, String name) {
		return FakePlayer.get(server.getLevel(GolfDimension.LINKS), new GameProfile(id, name));
	}

	private static ServerPlayer friendOnline(MinecraftServer server, UUID id) {
		return RoundManager.player(server, id);
	}

	private static GolfMatch match(MinecraftServer server, UUID me) {
		return MatchManager.of(server.getPlayerList().getPlayer(me));
	}

	private static int strokes(TestSingleplayerContext sp, UUID who, UUID me) {
		return sp.getServer().computeOnServer(server -> {
			ServerPlayer p = RoundManager.player(server, who);
			GolfRound r = p == null ? null : RoundManager.get(p);
			return r == null ? -1 : r.strokes() + java.util.Arrays.stream(r.scores()).sum();
		});
	}

	private static boolean atRest(TestSingleplayerContext sp, UUID who) {
		return sp.getServer().computeOnServer(server -> {
			ServerPlayer p = RoundManager.player(server, who);
			GolfRound r = p == null ? null : RoundManager.get(p);
			return r != null && (r.state() == GolfRound.State.ADDRESS || r.state() == GolfRound.State.HOLED || r.state() == GolfRound.State.FINISHED);
		});
	}

	/** Gets the real player set up over their ball (skipping the walk and any tee flyover). */
	private static void atBall(ClientGameTestContext ctx) {
		if (ctx.computeOnClient(mc -> ClientGolf.addressed())) {
			return;
		}
		ctx.getInput().pressKey(GolfKeys.GO_TO_BALL);
		for (int i = 0; i < 60 && !ctx.computeOnClient(mc -> ClientGolf.addressed()); i++) {
			if (ctx.computeOnClient(mc -> ClientGolf.roundState() == GolfRound.State.FLYOVER)) {
				ctx.getInput().pressMouse(0);
			}
			ctx.waitTicks(10);
		}
		ctx.waitTicks(10);
	}

	/** A friend's shot: a simple caddie picks the club, aimed at the flag. */
	private static void friendShot(MinecraftServer server, UUID id) {
		ServerPlayer p = RoundManager.player(server, id);
		GolfRound r = RoundManager.get(p);
		Vec ball = r.ballPosition(), cup = r.cupPosition();
		double d = ball.horizontalDistanceTo(cup);
		Club club = Club.WOOD_3;
		double power = 1.0;
		if (r.lie() == Surface.GREEN || r.lie() == Surface.FRINGE) {
			club = Club.PUTTER;
		} else {
			for (Club c : new Club[] {Club.LOB_WEDGE, Club.SAND_WEDGE, Club.PITCHING_WEDGE, Club.IRON_9, Club.IRON_7, Club.IRON_5, Club.HYBRID_4, Club.WOOD_3}) {
				double carry = c.carryBlocks() * ShotCalculator.lieFactor(c, r.lie());
				if (carry >= d * 0.93) {
					club = c;
					power = Math.max(0.15, Math.min(1.0, d * 0.93 / carry));
					break;
				}
			}
			if (r.lie() == Surface.TEE && d > Club.WOOD_3.carryBlocks()) {
				club = Club.DRIVER;
			}
		}
		RoundManager.hitFromCommand(p, r, club, ShotType.defaultFor(club), power, 0, Vec.yawOf(cup.sub(ball)));
	}

	/** Club and power for the real player's shot ({@code /golf hit} aims where they look, at the flag). */
	private static String[] plan() {
		Vec ball = ClientGolf.ball();
		double d = ball.horizontalDistanceTo(ClientGolf.cup());
		Surface lie = ClientGolf.lie();
		if (lie.isPuttingSurface()) {
			return new String[] {"putter", "1.0"};
		}
		for (Club club : new Club[] {Club.LOB_WEDGE, Club.SAND_WEDGE, Club.PITCHING_WEDGE, Club.IRON_9, Club.IRON_7, Club.IRON_5, Club.HYBRID_4, Club.WOOD_3}) {
			double carry = club.carryBlocks() * ShotCalculator.lieFactor(club, lie);
			if (carry >= d * 0.93) {
				return new String[] {club.id(), String.valueOf((float) Math.max(0.15, Math.min(1.0, d * 0.93 / carry)))};
			}
		}
		return new String[] {lie == Surface.TEE ? Club.DRIVER.id() : Club.WOOD_3.id(), "1.0"};
	}
}
