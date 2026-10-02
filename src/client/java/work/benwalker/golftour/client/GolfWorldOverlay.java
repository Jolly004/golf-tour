package work.benwalker.golftour.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;

import work.benwalker.golftour.course.HoleLayout;
import work.benwalker.golftour.game.GolfRound;
import work.benwalker.golftour.physics.Units;
import work.benwalker.golftour.physics.Vec;

/**
 * Draws the in-world overlays with Minecraft's gizmo primitives: the shot preview arc and landing ring,
 * the putt line, the pin marker, the TV-style shot tracer, and the green-reading slope grid.
 * Called from the client tick; tick gizmos stay on screen until the next tick.
 */
public final class GolfWorldOverlay {
	private static final int ARC = 0xE0FFFFFF;
	private static final int ROLL = 0x90FFE066;
	private static final int LANDING_FILL = 0x40FFE066;
	private static final int LANDING_EDGE = 0xF0FFE066;
	private static final int TRACER = 0xF0FF6A2A;

	private GolfWorldOverlay() {
	}

	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (!ClientGolf.active() || mc.player == null || mc.gui.hud.isHidden()) {
			return;
		}
		pinMarker(mc);
		if (ClientGolf.addressed()) {
			preview(mc);
		} else if (ClientGolf.atAddress()) {
			ballMarker(mc);
		}
		tracer();
		otherBalls(mc);
		watchedTracer();
		if (ClientGolf.greenRead && ClientGolf.lie().isPuttingSurface() && ClientGolf.roundState() != GolfRound.State.HOLED) {
			greenGrid();
		}
	}

	private static Vec3 v3(Vec v) {
		return new Vec3(v.x(), v.y(), v.z());
	}

	private static void pinMarker(Minecraft mc) {
		Vec cup = ClientGolf.cup();
		double dist = mc.player.position().distanceTo(v3(cup));
		if (dist > 18) {
			String label = Math.round(Units.toYards(ClientGolf.ball().horizontalDistanceTo(cup))) + "y";
			beacon(mc, cup.add(0, 3.2, 0), Math.min(40, dist * 0.18), 0xC0FF3030, "PIN " + label, 0xFFFFFFFF, 0.32);
		}
		Gizmos.circle(v3(cup.add(0, 0.03, 0)), 0.32f, GizmoStyle.stroke(0xFFFFFFFF, 2f));
	}

	/** Farthest a marker is drawn from the camera; farther ones are pulled in along the view line (same look). */
	private static final double MARKER_RANGE = 56;

	/**
	 * A vertical marker line from {@code base} up {@code height} blocks with a label on top, visible at any range:
	 * beyond {@link #MARKER_RANGE} it is drawn nearer the camera at the same direction and apparent size, so the
	 * render distance (far plane) never clips it.
	 */
	private static void beacon(Minecraft mc, Vec base, double height, int lineColor, String label, int textColor, double textScale) {
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		Vec3 b = v3(base);
		double dist = b.distanceTo(cam);
		double k = dist > MARKER_RANGE ? MARKER_RANGE / dist : 1;
		Vec3 bottom = cam.add(b.subtract(cam).scale(k));
		Vec3 top = cam.add(b.add(0, height, 0).subtract(cam).scale(k));
		Vec3 text = cam.add(b.add(0, height + 1, 0).subtract(cam).scale(k));
		Gizmos.line(bottom, top, lineColor, 3f).setAlwaysOnTop();
		float scale = (float) (textScale * Math.max(1, dist / 12) * k);
		Gizmos.billboardText(label, text, TextGizmo.Style.forColorAndCentered(textColor).withScale(scale)).setAlwaysOnTop();
	}

	/** A beacon over the player's ball while they walk (or drive) to it. */
	private static void ballMarker(Minecraft mc) {
		Vec ball = ClientGolf.ball();
		double dist = ClientGolf.distanceToBall();
		if (dist < 2.5) {
			return;
		}
		Gizmos.circle(v3(ball.add(0, 0.04, 0)), 0.45f, GizmoStyle.stroke(0xFFFFE066, 2f));
		boolean tee = ClientGolf.state().strokes() == 0 && ClientGolf.lie() == work.benwalker.golftour.physics.Surface.TEE;
		String label = (tee ? "TEE " : "BALL ") + Math.round(Units.toYards(dist)) + "y";
		beacon(mc, ball.add(0, 0.3, 0), 1.2 + Math.min(30, dist * 0.15), 0xD0FFE066, label, 0xFFFFE066, 0.3);
	}

	private static void preview(Minecraft mc) {
		ShotPlanner.Aim aim = ShotPlanner.aim();
		if (aim == null) {
			return;
		}
		List<Vec> path = aim.preview().path();
		Vec landing = aim.preview().firstLanding();
		int landingTick = aim.preview().flightTicks();
		boolean putt = aim.club().isPutter();
		int end = path.size() - 1;
		for (int i = 1; i <= end; i++) {
			Vec a = path.get(i - 1), b = path.get(i);
			boolean air = !putt && (landingTick < 0 || i <= landingTick);
			if (air) {
				Gizmos.line(v3(a.add(0, 0.1, 0)), v3(b.add(0, 0.1, 0)), ARC, 3.5f).setAlwaysOnTop();
			} else if (i % 2 == 0) {
				Gizmos.line(v3(a.add(0, 0.05, 0)), v3(b.add(0, 0.05, 0)), putt ? 0xE0FFFFFF : ROLL, putt ? 3f : 2.5f).setAlwaysOnTop();
			}
		}
		double dist = mc.player.position().distanceTo(v3(landing == null ? aim.preview().rest() : landing));
		if (putt) {
			Vec target = aim.target();
			Gizmos.circle(v3(new Vec(target.x(), target.y() + 0.04, target.z())), 0.25f, GizmoStyle.strokeAndFill(0xF0FFFFFF, 2f, 0x30FFFFFF));
		} else if (landing != null) {
			float radius = (float) (1.0 + aim.carryBlocks() * 0.018);
			Gizmos.circle(v3(landing.add(0, 0.06, 0)), radius, GizmoStyle.strokeAndFill(LANDING_EDGE, 3f, LANDING_FILL)).setAlwaysOnTop();
			Gizmos.circle(v3(landing.add(0, 0.07, 0)), radius * 0.35f, GizmoStyle.fill(0xA0FFE066)).setAlwaysOnTop();
			String text = Math.round(Units.toYards(aim.carryBlocks())) + "y";
			float scale = (float) (0.32 * Math.max(1, dist / 10));
			Gizmos.billboardText(text, v3(landing.add(0, radius * 0.6 + 0.8 * scale / 0.32, 0)), TextGizmo.Style.forColorAndCentered(0xFFFFE066).withScale(scale))
				.setAlwaysOnTop();
		}
		Gizmos.circle(v3(aim.preview().rest().add(0, 0.05, 0)), 0.3f, GizmoStyle.stroke(0xD0FFFFFF, 2f));
	}

	private static void tracer() {
		List<Vec> path = ClientGolf.flightPath;
		if (path.size() < 2) {
			return;
		}
		int age = ClientGolf.ticks - ClientGolf.flightStart;
		if (age > path.size() + 60) {
			return;
		}
		int upto = (int) Math.min(path.size() - 1, Math.max(1, ClientGolf.flightProgress(0)));
		boolean putt = ClientGolf.lastWasPutt;
		int fade = Math.max(0, age - path.size());
		int alpha = (int) (0xF0 * Math.max(0, 1 - fade / 60.0));
		int color = (alpha << 24) | (TRACER & 0xFFFFFF);
		int landing = ClientGolf.flightLanding;
		for (int i = 1; i <= upto; i++) {
			if (!putt && landing > 0 && i > landing) {
				break;
			}
			Gizmos.line(v3(path.get(i - 1).add(0, 0.1, 0)), v3(path.get(i).add(0, 0.1, 0)), color, putt ? 2.5f : 4.5f).setAlwaysOnTop();
		}
	}

	/** A named beacon, in the player's colour, over each friend's ball still in play. */
	private static void otherBalls(Minecraft mc) {
		if (!ClientMatch.playing()) {
			return;
		}
		var me = ClientMatch.me();
		for (var m : ClientMatch.members()) {
			if (m.id().equals(me) || ClientMatch.holedOut(m) || m.state() < 0 || m.ball().equals(Vec.ZERO)
				|| m.state() == work.benwalker.golftour.game.GolfRound.State.FLIGHT.ordinal()) {
				continue;
			}
			Vec ball = m.ball();
			double dist = mc.player.position().distanceTo(v3(ball));
			if (dist < 3) {
				continue;
			}
			int color = ClientMatch.color(m.id());
			String label = m.name() + " " + Math.round(Units.toYards(ball.horizontalDistanceTo(ClientGolf.cup()))) + "y";
			beacon(mc, ball.add(0, 0.3, 0), 1.0 + Math.min(18, dist * 0.1), (0xC0 << 24) | (color & 0xFFFFFF), label, color, 0.26);
			Gizmos.circle(v3(ball.add(0, 0.04, 0)), 0.35f, GizmoStyle.stroke(color, 2f));
		}
	}

	/** Tracer for a friend's shot, in their colour. */
	private static void watchedTracer() {
		if (!ClientMatch.watching()) {
			return;
		}
		List<Vec> path = ClientMatch.watchPath;
		if (path.size() < 2) {
			return;
		}
		int upto = (int) Math.min(path.size() - 1, Math.max(1, ClientMatch.watchProgress(0)));
		int color = (0xE0 << 24) | (ClientMatch.colorOf(ClientMatch.watchName) & 0xFFFFFF);
		for (int i = 1; i <= upto; i++) {
			if (!ClientMatch.watchPutt && ClientMatch.watchLanding > 0 && i > ClientMatch.watchLanding) {
				break;
			}
			Gizmos.line(v3(path.get(i - 1).add(0, 0.1, 0)), v3(path.get(i).add(0, 0.1, 0)), color, ClientMatch.watchPutt ? 2.5f : 4f).setAlwaysOnTop();
		}
	}

	private static void greenGrid() {
		HoleLayout hole = ClientGolf.hole();
		Vec green = hole.green();
		int r = (int) Math.ceil(hole.greenLong() + 2);
		int phase = ClientGolf.ticks % 30;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				double x = Math.floor(green.x()) + dx + 0.5, z = Math.floor(green.z()) + dz + 0.5;
				if (hole.greenRadius(x, z, 1.6) > 1) {
					continue;
				}
				Vec grad = hole.slope().gradient(x, z);
				double slope = grad.length();
				Vec downhill = slope < 1e-6 ? Vec.ZERO : new Vec(-grad.x() / slope, 0, -grad.z() / slope);
				double y = ClientGolf.course().heightAt(x, z) + 0.05;
				int color = slope < 0.01 ? 0xB06EC8FF : slope < 0.02 ? 0xC03CE0C0 : slope < 0.03 ? 0xD0FFD84A : 0xE0FF5A3A;
				Vec p = new Vec(x, y, z);
				double len = 0.18 + slope * 14;
				Vec tip = p.add(downhill.scale(len));
				tip = tip.withY(ClientGolf.course().heightAt(tip.x(), tip.z()) + 0.05);
				Gizmos.line(v3(p), v3(tip), color, 2.2f).setAlwaysOnTop();
				// Flowing dot: moves downhill faster on steeper ground.
				double t = ((phase / 30.0) * (0.5 + slope * 30)) % 1.0;
				Vec dot = p.lerp(tip, t);
				Gizmos.point(v3(dot), color | 0xFF000000, 4f).setAlwaysOnTop();
			}
		}
	}
}
