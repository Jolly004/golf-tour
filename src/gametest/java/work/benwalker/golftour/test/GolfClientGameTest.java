package work.benwalker.golftour.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import work.benwalker.golftour.client.ClientGolf;
import work.benwalker.golftour.client.GolfKeys;
import work.benwalker.golftour.client.SwingMeter;
import work.benwalker.golftour.course.HoleLayout;
import work.benwalker.golftour.game.GolfRound;
import work.benwalker.golftour.physics.Club;
import work.benwalker.golftour.physics.ShotCalculator;
import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.physics.Vec;

/**
 * End-to-end check in the real game: start a round, look at the HUD and scorecard, wind up the swing meter
 * with real mouse clicks, then play hole 1 to the cup with the test hit command, taking screenshots.
 */
public class GolfClientGameTest implements FabricClientGameTest {
	private record Plan(String club, float power, int hole, int strokes) {
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass())) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("execute as @a run golf play");
			ctx.waitFor(mc -> ClientGolf.active() && ClientGolf.roundState() == GolfRound.State.FLYOVER, 600);
			ctx.waitTicks(75);
			ctx.takeScreenshot("golf-00-flyover");
			ctx.waitFor(mc -> ClientGolf.addressed(), 1200);
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(30);
			ctx.takeScreenshot("golf-01-tee");

			ctx.getInput().holdKey(GolfKeys.SCORECARD);
			ctx.waitTicks(3);
			ctx.takeScreenshot("golf-02-scorecard");
			ctx.getInput().releaseKey(GolfKeys.SCORECARD);

			// Swing stick: hold right-click and pull the mouse back; letting go before impact cancels.
			double unit = ctx.computeOnClient(mc -> (double) mc.getWindow().getScreenHeight()) * SwingMeter.FULL_SWING;
			ctx.getInput().holdMouse(1);
			ctx.waitTicks(2);
			for (int i = 0; i < 8; i++) {
				ctx.getInput().moveCursor(0, 0.7 * unit / 8);
				ctx.waitTick();
			}
			ctx.waitTicks(2);
			ctx.takeScreenshot("golf-03-backswing");
			ctx.getInput().releaseMouse(1);
			ctx.waitTicks(3);
			if (ctx.computeOnClient(mc -> SwingMeter.phase()) != SwingMeter.Phase.IDLE) {
				throw new AssertionError("Releasing right-click didn't cancel the swing");
			}

			for (int shot = 1; shot <= 12; shot++) {
				if (shot == 2) {
					walkTowardBall(ctx);
				}
				goToBall(ctx);
				Plan plan = ctx.computeOnClient(mc -> plan());
				if (plan == null) {
					break;
				}
				sweepAim(ctx);
				if (shot == 1) {
					// The opening drive is a real, straight swing-stick swing.
					stickSwing(ctx, 1.0, 0);
					ctx.waitFor(mc -> ClientGolf.inFlight(), 100);
					String q = ctx.computeOnClient(mc -> ClientGolf.quality);
					System.out.println("[swing] straight drive: " + q);
					if (!q.equals("PERFECT") && !q.equals("GREAT")) {
						throw new AssertionError("A straight swing-stick swing should strike cleanly, got " + q);
					}
				} else {
					sp.getServer().runCommand("execute as @a run golf hit " + plan.club() + " " + plan.power());
				}
				ctx.waitTicks(16);
				if (shot == 1) {
					ctx.takeScreenshot("golf-04-ballcam");
				}
				int hole = plan.hole(), strokes = plan.strokes();
				ctx.waitFor(mc -> ClientGolf.roundState() == GolfRound.State.HOLED || ClientGolf.state().hole() != hole
					|| (ClientGolf.atAddress() && ClientGolf.state().strokes() != strokes), 1600);
				if (ClientGolf.roundState() == GolfRound.State.HOLED || ClientGolf.state().hole() != hole) {
					ctx.waitTicks(8);
					ctx.takeScreenshot("golf-07-holed");
					break;
				}
				sp.getConnection().waitForChunksRender();
				ctx.waitTicks(12);
				boolean green = ctx.computeOnClient(mc -> ClientGolf.lie().isPuttingSurface());
				ctx.takeScreenshot("golf-05-shot" + (shot + 1) + (green ? "-green" : ""));
			}

			ctx.waitFor(mc -> ClientGolf.atAddress() && ClientGolf.state().hole() == 1, 400);
			ctx.waitTicks(10);
			ctx.takeScreenshot("golf-07b-head-to-tee");
			buggy(ctx, sp);
			// Skip the walk: going to the tee plays its flyover, then puts us on the tee, addressed.
			ctx.getInput().pressKey(GolfKeys.GO_TO_BALL);
			ctx.waitFor(mc -> ClientGolf.roundState() == GolfRound.State.FLYOVER, 200);
			ctx.waitTicks(20);
			ctx.getInput().pressMouse(0);
			ctx.waitFor(mc -> ClientGolf.addressed() && ClientGolf.state().hole() == 1, 400);
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(20);
			ctx.takeScreenshot("golf-08-hole2-tee");

			// A swing-stick swing pushed out to the right on the way through: that's a slice.
			stickSwing(ctx, 0.9, 25);
			ctx.waitTicks(2);
			ctx.takeScreenshot("golf-08b-follow-through");
			ctx.waitFor(mc -> ClientGolf.inFlight(), 100);
			String sliced = ctx.computeOnClient(mc -> ClientGolf.quality);
			System.out.println("[swing] pushed-right drive: " + sliced);
			if (!sliced.equals("SLICE")) {
				throw new AssertionError("Pushing the mouse right on the way through should slice, got " + sliced);
			}
			ctx.waitFor(mc -> ClientGolf.atAddress() && ClientGolf.state().strokes() > 0, 1600);
			goToBall(ctx);
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
			ctx.waitTicks(5);
			ctx.takeScreenshot("golf-08c-third-person");
			ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));

			// A water hole: deliberately find the pond to check the splash, penalty and drop.
			int waterHole = ctx.computeOnClient(mc -> {
				for (HoleLayout h : ClientGolf.course().holes) {
					if (!h.water().isEmpty() && h.par() == 3) {
						return h.number();
					}
				}
				for (HoleLayout h : ClientGolf.course().holes) {
					if (!h.water().isEmpty()) {
						return h.number();
					}
				}
				return -1;
			});
			if (waterHole > 0) {
				sp.getServer().runCommand("execute as @a run golf flyover off");
				sp.getServer().runCommand("execute as @a run golf play " + waterHole);
				ctx.waitFor(mc -> ClientGolf.addressed() && ClientGolf.state().hole() == waterHole - 1, 1200);
				sp.getConnection().waitForChunksRender();
				ctx.waitTicks(30);
				ctx.takeScreenshot("golf-10-water-tee");
				Plan into = ctx.computeOnClient(mc -> {
					HoleLayout h = ClientGolf.hole();
					var c = h.water().get(0).get(0);
					double d = ClientGolf.ball().horizontalDistanceTo(new Vec(c.x(), 0, c.z()));
					for (Club club : new Club[] {Club.LOB_WEDGE, Club.SAND_WEDGE, Club.PITCHING_WEDGE, Club.IRON_8, Club.IRON_6, Club.HYBRID_4, Club.WOOD_3}) {
						if (club.carryBlocks() >= d) {
							return new Plan(club.id(), (float) (d / club.carryBlocks()), h.number() - 1, 0);
						}
					}
					return new Plan(Club.DRIVER.id(), 1f, h.number() - 1, 0);
				});
				float yaw = ctx.computeOnClient(mc -> {
					var c = ClientGolf.hole().water().get(0).get(0);
					return Vec.yawOf(new Vec(c.x(), 0, c.z()).sub(ClientGolf.ball()));
				});
				sp.getServer().runCommand("execute as @a at @s run tp @s ~ ~ ~ " + yaw + " 0");
				ctx.waitTicks(3);
				sp.getServer().runCommand("execute as @a run golf hit " + into.club() + " " + into.power());
				ctx.waitTicks(40);
				ctx.takeScreenshot("golf-11-water-flight");
				ctx.waitFor(mc -> ClientGolf.atAddress() && ClientGolf.state().strokes() > 0, 1200);
				goToBall(ctx);
				sp.getConnection().waitForChunksRender();
				ctx.waitTicks(15);
				ctx.takeScreenshot("golf-12-water-drop");
			}

			HoleLayout first = ctx.computeOnClient(mc -> ClientGolf.course().holes.get(0));
			Vec mid = first.tee().lerp(first.green(), 0.5);
			sp.getServer().runCommand("execute as @a run gamemode spectator");
			sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "execute in golftour:links as @a run tp @s %.1f %d %.1f %.1f 70",
				mid.x(), first.teeY() + 70, mid.z() - 60, 0f));
			ctx.waitTicks(10);
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(20);
			ctx.takeScreenshot("golf-09-aerial");

			if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("nocubes")) {
				String report = sp.getServer().computeOnServer(server -> {
					var level = server.getLevel(work.benwalker.golftour.world.GolfDimension.LINKS);
					var course = work.benwalker.golftour.world.GolfDimension.course(level);
					var lines = new java.util.ArrayList<String>();
					for (int i = 0; i < 3; i++) {
						HoleLayout h = course.holes.get(i);
						lines.add(SmoothSurfaceCheck.measure("hole " + (i + 1) + " green", level, course, h.pin().x(), h.pin().z(), 10).toString());
						lines.add(SmoothSurfaceCheck.measure("hole " + (i + 1) + " tee", level, course, h.tee().x(), h.tee().z(), 8).toString());
						if (!h.bunkers().isEmpty()) {
							HoleLayout.Circle b = h.bunkers().get(0).get(0);
							lines.add(SmoothSurfaceCheck.measure("hole " + (i + 1) + " bunker", level, course, b.x(), b.z(), 9).toString());
						}
					}
					return String.join(System.lineSeparator(), lines);
				});
				System.out.println("[smooth-surface]" + System.lineSeparator() + report);
				try {
					java.nio.file.Files.writeString(java.nio.file.Path.of("smooth-surface.txt"), report);
				} catch (java.io.IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			}

			sp.getServer().runCommand("execute as @a run golf quit");
			ctx.waitTicks(20);
			ctx.takeScreenshot("golf-13-quit");
		}
	}

	/**
	 * A real swing-stick swing: hold right-click, pull the mouse back to {@code power} of a full swing, then push
	 * it through past the start, leaning {@code throughDegrees} to the right (negative: left).
	 */
	private static void stickSwing(ClientGameTestContext ctx, double power, double throughDegrees) {
		double unit = ctx.computeOnClient(mc -> (double) mc.getWindow().getScreenHeight()) * SwingMeter.FULL_SWING;
		ctx.getInput().holdMouse(1);
		ctx.waitTicks(2);
		for (int i = 0; i < 12; i++) {
			ctx.getInput().moveCursor(0, power * unit / 12);
			ctx.waitTick();
		}
		ctx.waitTicks(3);
		double through = power * unit * 1.15;
		double side = Math.tan(Math.toRadians(throughDegrees)) * through;
		for (int i = 0; i < 4; i++) {
			ctx.getInput().moveCursor(side / 4, -through / 4);
			ctx.waitTick();
		}
		ctx.getInput().releaseMouse(1);
	}

	/** Uses the "go to ball" key if we aren't already at the ball, and waits until we've stepped up to it. */
	private static void goToBall(ClientGameTestContext ctx) {
		if (!ctx.computeOnClient(mc -> ClientGolf.atAddress()) || ctx.computeOnClient(mc -> ClientGolf.addressed())) {
			return;
		}
		ctx.getInput().pressKey(GolfKeys.GO_TO_BALL);
		ctx.waitFor(mc -> ClientGolf.addressed(), 300);
		ctx.waitTicks(10);
	}

	/** Walks toward the ball for a few seconds with the real movement keys; the server must accept every step. */
	private static void walkTowardBall(ClientGameTestContext ctx) {
		double before = ctx.computeOnClient(mc -> ClientGolf.distanceToBall());
		ctx.runOnClient(mc -> {
			Vec b = ClientGolf.ball();
			mc.player.setYRot(Vec.yawOf(b.sub(new Vec(mc.player.getX(), mc.player.getY(), mc.player.getZ()))));
			mc.player.setXRot(10);
		});
		ctx.getInput().holdKey(o -> o.keySprint);
		ctx.getInput().holdKey(o -> o.keyUp);
		ctx.waitTicks(50);
		ctx.takeScreenshot("golf-06-walking");
		ctx.waitTicks(30);
		ctx.getInput().releaseKey(o -> o.keyUp);
		ctx.getInput().releaseKey(o -> o.keySprint);
		ctx.waitTicks(5);
		double after = ctx.computeOnClient(mc -> ClientGolf.distanceToBall());
		if (before - after < 8) {
			throw new AssertionError("Walking toward the ball didn't get closer: " + before + " -> " + after);
		}
	}

	/** Calls the buggy, drives it for a few seconds, and gets out. Skipped without Automobility. */
	private static void buggy(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("automobility")) {
			return;
		}
		BuggyLineup.show(ctx, sp);
		ctx.getInput().pressKey(GolfKeys.CALL_BUGGY);
		ctx.waitTicks(20);
		sp.getServer().runCommand("execute as @a at @s run ride @s mount @e[type=automobility:automobile,sort=nearest,limit=1]");
		ctx.waitFor(mc -> mc.player.isPassenger(), 100);
		ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
		Vec start = ctx.computeOnClient(mc -> new Vec(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
		ctx.getInput().holdKey(o -> o.keyUp);
		ctx.waitTicks(60);
		ctx.takeScreenshot("golf-14-driving");
		ctx.getInput().releaseKey(o -> o.keyUp);
		ctx.waitTicks(30);
		double moved = ctx.computeOnClient(mc -> new Vec(mc.player.getX(), mc.player.getY(), mc.player.getZ()).horizontalDistanceTo(start));
		System.out.println("[buggy] drove " + moved + " blocks");
		if (moved < 4) {
			throw new AssertionError("The buggy didn't drive: moved " + moved);
		}
		sp.getServer().runCommand("execute as @a run ride @s dismount");
		ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
		ctx.waitTicks(10);
	}

	/**
	 * Swings the aim left and right like a player lining up a shot. The golfer's stance follows the aim around
	 * the ball, which is what used to get rubber-banded on sloped ground by the server's movement check.
	 */
	private static void sweepAim(ClientGameTestContext ctx) {
		float base = ctx.computeOnClient(mc -> mc.player.getYRot());
		for (int i = 0; i <= 30; i++) {
			float yaw = base + (float) Math.sin(i / 30.0 * Math.PI * 2) * 70f;
			ctx.runOnClient(mc -> mc.player.setYRot(yaw));
			ctx.waitTick();
		}
		ctx.runOnClient(mc -> mc.player.setYRot(base));
		ctx.waitTicks(5);
	}

	/** Picks a club and power for the next shot, like a caddie would. Runs on the client thread. */
	private static Plan plan() {
		if (!ClientGolf.atAddress()) {
			return null;
		}
		Vec ball = ClientGolf.ball();
		double d = ball.horizontalDistanceTo(ClientGolf.cup());
		Surface lie = ClientGolf.lie();
		int hole = ClientGolf.state().hole(), strokes = ClientGolf.state().strokes();
		if (lie.isPuttingSurface()) {
			return new Plan("putter", 1.0f, hole, strokes);
		}
		Club[] order = {Club.LOB_WEDGE, Club.SAND_WEDGE, Club.GAP_WEDGE, Club.PITCHING_WEDGE, Club.IRON_9, Club.IRON_8, Club.IRON_7, Club.IRON_6,
			Club.IRON_5, Club.HYBRID_4, Club.WOOD_5, Club.WOOD_3};
		for (Club club : order) {
			double carry = club.carryBlocks() * ShotCalculator.lieFactor(club, lie);
			if (carry >= d * 0.93) {
				return new Plan(club.id(), (float) Math.max(0.15, Math.min(1.0, d * 0.93 / carry)), hole, strokes);
			}
		}
		return new Plan(lie == Surface.TEE ? Club.DRIVER.id() : Club.WOOD_3.id(), 1.0f, hole, strokes);
	}
}
