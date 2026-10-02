package work.benwalker.golftour.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import work.benwalker.golftour.course.CourseLayout;
import work.benwalker.golftour.course.HoleLayout;
import work.benwalker.golftour.game.GolfRound;
import work.benwalker.golftour.item.ClubItem;
import work.benwalker.golftour.net.BallFlightPayload;
import work.benwalker.golftour.net.RoundStatePayload;
import work.benwalker.golftour.physics.Club;
import work.benwalker.golftour.physics.ShotCalculator;
import work.benwalker.golftour.physics.ShotType;
import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.physics.Vec;

/** Client-side view of the player's round, plus the shot settings they have dialled in. */
public final class ClientGolf {
	private static RoundStatePayload state = RoundStatePayload.inactive();
	private static CourseLayout course;
	private static int lastHole = -1;

	/** Client tick counter, used to play back flights and animate overlays. */
	public static int ticks;

	// Shot settings
	public static ShotType shotType = ShotType.NORMAL;
	public static double shape;
	public static double spin;
	public static boolean ballCam = true;
	public static boolean greenRead = true;

	// Last flight
	public static List<Vec> flightPath = List.of();
	public static int flightStart;
	public static int flightLanding;
	public static String quality = "";
	public static int qualityColor = 0xFFFFFF;
	public static int qualityShownAt = -1000;
	public static float lastCarryYards;
	public static boolean lastWasPutt;
	/** Within this many blocks of the ball, right-click steps up to address it. */
	public static final double ADDRESS_RANGE = 3.5;
	/** True once the player has stepped up to the ball (stance, aim camera, swing). Walking breaks it. */
	private static boolean addressed;
	/** Address automatically when the player arrives at the ball before this tick (after "go to ball", the flyover). */
	private static int addressOnArrivalUntil = -1;
	public static String notice = "";
	public static int noticeColor = 0xFFFFFF;
	public static int noticeAt = -1000;

	private ClientGolf() {
	}

	public static void onState(RoundStatePayload payload) {
		RoundStatePayload previous = state;
		state = payload;
		if (!payload.active()) {
			course = null;
			lastHole = -1;
			flightPath = List.of();
			SwingMeter.reset();
			return;
		}
		if (course == null || course.seed != payload.courseSeed()) {
			course = CourseLayout.of(payload.courseSeed());
		}
		if (payload.hole() != lastHole) {
			lastHole = payload.hole();
			HoleMap.invalidate();
			flightPath = List.of();
		}
		boolean nowAddress = payload.state() == GolfRound.State.ADDRESS.ordinal();
		if (nowAddress && (!previous.active() || previous.state() == GolfRound.State.FLYOVER.ordinal() || previous.hole() != payload.hole())) {
			addressOnArrival();
		}
		if (payload.state() == GolfRound.State.FLYOVER.ordinal()) {
			if (previous.state() != payload.state() || previous.hole() != payload.hole() || !previous.active()) {
				CinematicCamera.startFlyover();
			}
		} else {
			CinematicCamera.stopFlyover();
		}
		boolean newShot = payload.state() == GolfRound.State.ADDRESS.ordinal()
			&& (previous.state() != payload.state() || previous.strokes() != payload.strokes() || previous.hole() != payload.hole());
		if (newShot) {
			shape = 0;
			spin = 0;
			shotType = ShotType.defaultFor(heldClub() == null ? Club.DRIVER : heldClub());
			SwingMeter.reset();
			ShotPlanner.invalidate();
		}
	}

	public static void onFlight(BallFlightPayload payload) {
		flightPath = payload.path();
		flightStart = ticks;
		flightLanding = payload.landingTick();
		quality = payload.quality();
		qualityColor = payload.qualityColor();
		qualityShownAt = ticks;
		lastCarryYards = payload.carryYards();
		lastWasPutt = payload.putt();
	}

	public static void notice(String text, int color) {
		notice = text;
		noticeColor = color;
		noticeAt = ticks;
	}

	public static boolean active() {
		return state.active() && course != null;
	}

	public static RoundStatePayload state() {
		return state;
	}

	public static CourseLayout course() {
		return course;
	}

	public static HoleLayout hole() {
		return course == null ? null : course.holes.get(Math.max(0, Math.min(course.holes.size() - 1, state.hole())));
	}

	public static GolfRound.State roundState() {
		return GolfRound.State.values()[Math.max(0, Math.min(GolfRound.State.values().length - 1, state.state()))];
	}

	public static boolean atAddress() {
		return active() && roundState() == GolfRound.State.ADDRESS;
	}

	public static boolean inFlight() {
		return active() && (roundState() == GolfRound.State.FLIGHT || roundState() == GolfRound.State.SETTLING) && !flightPath.isEmpty();
	}

	public static Vec ball() {
		return state.ball();
	}

	public static Vec cup() {
		return state.cup();
	}

	public static Surface lie() {
		return Surface.byId(state.lie());
	}

	public static Club heldClub() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return null;
		}
		ItemStack stack = mc.player.getMainHandItem();
		return stack.getItem() instanceof ClubItem club ? club.club() : null;
	}

	/** The shot type to use with {@code club}, falling back to its default if the selected one doesn't apply. */
	public static ShotType shotTypeFor(Club club) {
		return shotType.allowedFor(club) ? shotType : ShotType.defaultFor(club);
	}

	/** True when the player is set up over their ball: addressed, on foot, close enough, holding a club. */
	public static boolean atBall() {
		Minecraft mc = Minecraft.getInstance();
		if (!addressed || !atAddress() || mc.player == null || heldClub() == null || mc.player.isPassenger()) {
			return false;
		}
		Vec b = ball();
		return mc.player.position().distanceTo(new net.minecraft.world.phys.Vec3(b.x(), b.y(), b.z())) <= 6;
	}

	/** True when the player can start a swing right now: at the ball, and (in a match) it's their shot. */
	public static boolean canSwing() {
		return atBall() && ClientMatch.myTurn();
	}

	/** True once the player has stepped up to the ball. */
	public static boolean addressed() {
		return addressed && atAddress();
	}

	/** Horizontal distance from the player to their ball. */
	public static double distanceToBall() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return Double.MAX_VALUE;
		}
		Vec b = ball();
		double dx = mc.player.getX() - b.x(), dz = mc.player.getZ() - b.z();
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** True when the player is on foot with a club, close enough to step up to the ball. */
	public static boolean nearBall() {
		Minecraft mc = Minecraft.getInstance();
		return atAddress() && mc.player != null && !mc.player.isPassenger() && heldClub() != null && distanceToBall() <= ADDRESS_RANGE
			&& Math.abs(mc.player.getY() - ball().y()) < 3;
	}

	/** Steps up to the ball (right-click near it). */
	public static boolean tryAddress() {
		if (!addressed && nearBall()) {
			addressed = true;
			caddieClub();
			aimAtTarget();
			return true;
		}
		return false;
	}

	/**
	 * Hands the player a sensible club as they step up, as a caddie would: the putter on the green, the driver
	 * on a par 4 or 5 tee, and off the green a club that reaches the flag if they're still holding the putter.
	 * Otherwise their own choice stands.
	 */
	private static void caddieClub() {
		Minecraft mc = Minecraft.getInstance();
		Club held = heldClub();
		if (mc.player == null || hole() == null) {
			return;
		}
		Surface lie = lie();
		boolean tee = state.strokes() == 0 && lie == Surface.TEE;
		if (lie == Surface.GREEN) {
			selectClub(c -> c.isPutter());
		} else if (tee && hole().par() >= 4) {
			selectClub(c -> c == Club.DRIVER);
		} else if (held != null && held.isPutter() && lie != Surface.FRINGE || held == null) {
			double d = ball().horizontalDistanceTo(cup());
			Club best = null;
			for (Club c : hotbarClubs()) {
				if (c.isPutter()) {
					continue;
				}
				double carry = c.carryBlocks() * ShotCalculator.lieFactor(c, lie);
				boolean reaches = carry >= d * 0.95;
				if (best == null) {
					best = c;
				} else {
					double bestCarry = best.carryBlocks() * ShotCalculator.lieFactor(best, lie);
					boolean bestReaches = bestCarry >= d * 0.95;
					// Prefer the shortest club that reaches; failing that, the longest.
					if (reaches && (!bestReaches || carry < bestCarry) || !reaches && !bestReaches && carry > bestCarry) {
						best = c;
					}
				}
			}
			Club pick = best;
			if (pick != null) {
				selectClub(c -> c == pick);
			}
		}
	}

	private static java.util.List<Club> hotbarClubs() {
		var inv = Minecraft.getInstance().player.getInventory();
		java.util.List<Club> clubs = new java.util.ArrayList<>();
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).getItem() instanceof ClubItem c) {
				clubs.add(c.club());
			}
		}
		return clubs;
	}

	private static void selectClub(java.util.function.Predicate<Club> which) {
		var inv = Minecraft.getInstance().player.getInventory();
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).getItem() instanceof ClubItem c && which.test(c.club())) {
				inv.setSelectedSlot(i);
				return;
			}
		}
	}

	/** The club in hand last tick, to re-aim when switching between putter and full clubs (the camera moves). */
	private static Club lastClub;

	/**
	 * Lines the player up like a caddie would: down the tee's line on the tee, otherwise at the flag, with the
	 * crosshair on the flag (or as far as the club reaches), as seen from the address camera.
	 */
	public static void aimAtTarget() {
		Minecraft mc = Minecraft.getInstance();
		Club club = heldClub();
		if (mc.player == null || club == null) {
			return;
		}
		Vec b = ball();
		boolean tee = state.strokes() == 0 && lie() == Surface.TEE && hole() != null;
		float yaw = tee ? hole().teeYaw() : Vec.yawOf(cup().sub(b));
		double reach = club.isPutter() ? b.horizontalDistanceTo(cup())
			: Math.min(b.horizontalDistanceTo(cup()), club.carryBlocks() * ShotCalculator.lieFactor(club, lie()));
		Vec look = b.add(Vec.fromYaw(yaw).scale(Math.max(0.5, reach)));
		// The crosshair must meet the ground where it really is (the smooth surface), or it would run on past the target.
		double ground = course == null ? Double.NaN : course.heightAt(look.x(), look.z());
		look = look.withY(Double.isNaN(ground) ? b.y() : ground);
		Vec eye = CinematicCamera.addressEye(yaw, club.isPutter());
		float pitch = (float) Math.toDegrees(Math.atan2(eye.y() - look.y(), eye.horizontalDistanceTo(look)));
		mc.player.setYRot(yaw);
		mc.player.setXRot(Math.max(-30f, Math.min(80f, pitch)));
		mc.player.yRotO = yaw;
		mc.player.xRotO = mc.player.getXRot();
	}

	/** Address automatically on arriving at the ball within the next few seconds (after a teleport to it). */
	public static void addressOnArrival() {
		addressOnArrivalUntil = ticks + 80;
	}

	/** Per tick: walking (or jumping) away breaks the address; arriving after a teleport makes it. */
	public static void tickAddress() {
		Minecraft mc = Minecraft.getInstance();
		if (!atAddress() || mc.player == null || mc.player.isPassenger()) {
			addressed = false;
			return;
		}
		if (!addressed && ticks < addressOnArrivalUntil && nearBall()) {
			addressed = true;
			addressOnArrivalUntil = -1;
			caddieClub();
			aimAtTarget();
		}
		Club club = heldClub();
		if (addressed && club != null && lastClub != null && club.isPutter() != lastClub.isPutter() && SwingMeter.phase() == SwingMeter.Phase.IDLE) {
			aimAtTarget();
		}
		lastClub = club;
		if (addressed) {
			var keys = mc.player.input.keyPresses;
			boolean walking = keys.forward() || keys.backward() || keys.left() || keys.right() || keys.jump();
			if ((walking && SwingMeter.phase() == SwingMeter.Phase.IDLE) || distanceToBall() > 6 || heldClub() == null) {
				addressed = false;
			}
		}
	}

	public static Entity ballEntity() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null || state.ballEntity() < 0 ? null : mc.level.getEntity(state.ballEntity());
	}

	/** Index into the flight path for the current client tick. */
	public static double flightProgress(float partialTick) {
		return Math.min(flightPath.size() - 1, ticks - flightStart + partialTick);
	}

	public static int toParCompleted() {
		if (course == null) {
			return 0;
		}
		int diff = 0;
		int[] scores = state.scores();
		for (int i = 0; i < scores.length; i++) {
			if (scores[i] > 0) {
				diff += scores[i] - course.holes.get(i).par();
			}
		}
		return diff;
	}

	public static int holesCompleted() {
		int n = 0;
		for (int s : state.scores()) {
			if (s > 0) {
				n++;
			}
		}
		return n;
	}
}
