package work.benwalker.golftour.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import work.benwalker.golftour.client.ClientGolf;
import work.benwalker.golftour.client.GolfKeys;
import work.benwalker.golftour.client.ShotPlanner;
import work.benwalker.golftour.client.SwingMeter;
import work.benwalker.golftour.game.GolfRound;
import work.benwalker.golftour.game.RoundManager;
import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.physics.Units;
import work.benwalker.golftour.physics.Vec;

/**
 * Plays putts from short, medium and long range on hole 1's green and measures how the address behaves: the
 * golfer and aim must hold still with no mouse input, turn smoothly with it, and the putt (a real swing-stick
 * stroke at the planned length) should finish close.
 */
public class GolfPuttingGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass())) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			UUID me = ctx.computeOnClient(mc -> mc.player.getUUID());
			// Minecraft ignores the first mouse movement after grabbing the cursor; use it up.
			ctx.getInput().moveCursor(1, 0);
			ctx.waitTicks(2);

			List<String> report = new ArrayList<>();
			double[] distances = {0.4, 1.2, 3, 6};
			for (int i = 0; i < distances.length; i++) {
				double d = distances[i];
				// A fresh card on hole 1 for each putt.
				sp.getServer().runCommand("execute as @a run golf play 1");
				ctx.waitFor(mc -> ClientGolf.roundState() == GolfRound.State.FLYOVER, 600);
				ctx.waitTicks(10);
				ctx.getInput().pressMouse(0);
				ctx.waitFor(mc -> ClientGolf.addressed() && ClientGolf.lie() == Surface.TEE, 600);
				boolean placed = sp.getServer().computeOnServer(server -> {
					ServerPlayer p = server.getPlayerList().getPlayer(me);
					GolfRound r = RoundManager.get(p);
					Vec cup = r.cupPosition();
					// Try directions around the cup until the spot is on the green.
					for (int k = 0; k < 16; k++) {
						Vec dir = Vec.fromYaw(k * 22.5f);
						Vec spot = cup.add(dir.scale(d));
						if (RoundManager.placeBall(p, spot.x(), spot.z()) && RoundManager.get(p).lie() == Surface.GREEN) {
							return true;
						}
					}
					return false;
				});
				if (!placed) {
					throw new AssertionError("Couldn't place a ball on the green " + d + " blocks out");
				}
				ctx.waitTicks(5);
				ctx.getInput().pressKey(GolfKeys.GO_TO_BALL);
				ctx.waitFor(mc -> ClientGolf.addressed(), 300);
				ctx.getInput().pressKey(o -> o.keyHotbarSlots[8]); // putter
				ctx.waitTicks(25);

				// 1. No input: nothing should move.
				Sample start = sample(ctx);
				double maxYaw = 0, maxMove = 0, maxAim = 0;
				for (int t = 0; t < 40; t++) {
					ctx.waitTick();
					Sample s = sample(ctx);
					maxYaw = Math.max(maxYaw, Math.abs(wrap(s.yaw - start.yaw)));
					maxMove = Math.max(maxMove, Math.hypot(s.x - start.x, s.z - start.z));
					maxAim = Math.max(maxAim, Math.abs(wrap(s.aim - start.aim)));
				}
				ctx.takeScreenshot("putt-" + i + "-address");

				// 2. A small mouse nudge: the aim turns a little and then holds.
				ctx.getInput().moveCursor(12, 0);
				ctx.waitTicks(10);
				Sample afterNudge = sample(ctx);
				double settleAim = 0;
				for (int t = 0; t < 20; t++) {
					ctx.waitTick();
					settleAim = Math.max(settleAim, Math.abs(wrap(sample(ctx).aim - afterNudge.aim)));
				}
				double nudgeTurn = Math.abs(wrap(afterNudge.aim - start.aim));
				ctx.getInput().moveCursor(-12, 0);
				ctx.waitTicks(10);

				// 3. Putt it: a straight swing-stick stroke to the planned length.
				String diag = ctx.computeOnClient(mc -> {
					var aim = ShotPlanner.aim();
					Vec ball = ClientGolf.ball(), cup = ClientGolf.cup();
					double cupDist = ball.horizontalDistanceTo(cup);
					StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
						"aim length %.2f vs cup %.2f blocks, aim yaw %.1f vs to-cup %.1f, ball y %.3f cup y %.3f, surface at cup %.3f, pitch %.1f",
						aim.target().horizontalDistanceTo(ball), cupDist, aim.yaw(), Vec.yawOf(cup.sub(ball)), ball.y(), cup.y(),
						ClientGolf.course().heightAt(cup.x(), cup.z()), mc.player.getXRot()));
					var world = new work.benwalker.golftour.world.LevelBallWorld(mc.level, ClientGolf.course());
					float toCup = Vec.yawOf(cup.sub(ball));
					for (double k : new double[] {1.0, 1.5, 2.0}) {
						var swing = work.benwalker.golftour.physics.ShotCalculator.Swing.perfect(work.benwalker.golftour.physics.Club.PUTTER,
							work.benwalker.golftour.physics.ShotType.defaultFor(work.benwalker.golftour.physics.Club.PUTTER), 1.0, 0, 0, toCup,
							ClientGolf.lie(), cupDist * k);
						var r = work.benwalker.golftour.physics.BallSimulator.simulate(world, ball,
							work.benwalker.golftour.physics.ShotCalculator.compute(swing).launch(),
							work.benwalker.golftour.physics.BallSimulator.Options.preview(new Vec(cup.x() + 500, cup.y(), cup.z())));
						sb.append(String.format(Locale.ROOT, " | L=%.1fx: rolled %.2f, %s, events %s", k, r.rest().horizontalDistanceTo(ball), r.outcome(),
							r.events().stream().map(e -> e.type() + "@" + String.format(Locale.ROOT, "%.1f", e.pos().horizontalDistanceTo(ball))).toList()));
					}
					return sb.toString();
				});
				System.out.println("[putt] diag " + diag);

				// Read the green like a player: turn the aim until the preview line finishes nearest the hole.
				float base = ctx.computeOnClient(mc -> mc.player.getYRot());
				float bestYaw = base;
				double bestMiss = Double.MAX_VALUE;
				for (float off = -12; off <= 12; off += 1) {
					float yaw = base + off;
					ctx.runOnClient(mc -> mc.player.setYRot(yaw));
					ctx.waitTicks(2);
					double miss = ctx.computeOnClient(mc -> ShotPlanner.aim() == null ? Double.MAX_VALUE
						: ShotPlanner.aim().preview().outcome() == work.benwalker.golftour.physics.BallSimulator.Outcome.HOLED ? 0
						: ShotPlanner.aim().preview().rest().horizontalDistanceTo(ClientGolf.cup()));
					if (miss < bestMiss) {
						bestMiss = miss;
						bestYaw = yaw;
					}
				}
				float chosen = bestYaw;
				ctx.runOnClient(mc -> mc.player.setYRot(chosen));
				ctx.waitTicks(10);
				double readOff = wrap(chosen - base);

				double power = ctx.computeOnClient(mc -> SwingMeter.plannedPower());
				String plays = ctx.computeOnClient(mc -> {
					var aim = ShotPlanner.aim();
					return String.format(Locale.ROOT, "plays %.1f ft, preview stops %.1f ft from the cup", Units.toFeet(aim.puttTarget()),
						Units.toFeet(aim.preview().rest().horizontalDistanceTo(ClientGolf.cup())));
				});
				double before = ctx.computeOnClient(mc -> ClientGolf.ball().horizontalDistanceTo(ClientGolf.cup()));
				int strokes = ctx.computeOnClient(mc -> ClientGolf.state().strokes());
				stickSwing(ctx, power);
				ctx.waitTicks(12);
				ctx.takeScreenshot("putt-" + i + "-rolling");
				ctx.waitFor(mc -> ClientGolf.roundState() == GolfRound.State.HOLED
					|| (ClientGolf.atAddress() && ClientGolf.state().strokes() != strokes), 600);
				boolean holed = ctx.computeOnClient(mc -> ClientGolf.roundState() == GolfRound.State.HOLED);
				double left = holed ? 0 : ctx.computeOnClient(mc -> ClientGolf.ball().horizontalDistanceTo(ClientGolf.cup()));
				String line = String.format(Locale.ROOT,
					"%4.1f ft putt: still yaw %.2f° move %.3f aim %.2f° | nudge turned %.2f°, drift after %.2f° | read %.0f° of break, power %.2f, %s -> %s",
					Units.toFeet(before), maxYaw, maxMove, maxAim, nudgeTurn, settleAim, readOff, power, plays,
					holed ? "HOLED" : String.format(Locale.ROOT, "%.1f ft left", Units.toFeet(left)));
				report.add(line);
				System.out.println("[putt] " + line);
				ctx.waitTicks(10);
			}
			System.out.println("[putt] summary:\n  " + String.join("\n  ", report));
		}
	}

	private record Sample(double yaw, double x, double z, double aim) {
	}

	private static Sample sample(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			var aim = ShotPlanner.aim();
			return new Sample(mc.player.getYRot(), mc.player.getX(), mc.player.getZ(), aim == null ? Double.NaN : aim.yaw());
		});
	}

	private static double wrap(double deg) {
		if (Double.isNaN(deg)) {
			return 999;
		}
		deg = deg % 360;
		return deg > 180 ? deg - 360 : deg < -180 ? deg + 360 : deg;
	}

	private static void stickSwing(ClientGameTestContext ctx, double power) {
		double unit = ctx.computeOnClient(mc -> (double) mc.getWindow().getScreenHeight()) * SwingMeter.FULL_SWING;
		ctx.getInput().holdMouse(1);
		ctx.waitTicks(2);
		for (int i = 0; i < 10; i++) {
			ctx.getInput().moveCursor(0, power * unit / 10);
			ctx.waitTick();
		}
		ctx.waitTicks(3);
		double through = power * unit * 1.15;
		for (int i = 0; i < 5; i++) {
			ctx.getInput().moveCursor(0, -through / 5);
			ctx.waitTick();
		}
		ctx.getInput().releaseMouse(1);
	}
}
