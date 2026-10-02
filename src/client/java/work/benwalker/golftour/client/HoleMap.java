package work.benwalker.golftour.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import work.benwalker.golftour.course.CourseLayout;
import work.benwalker.golftour.course.HoleLayout;
import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.physics.Vec;

/**
 * Overhead map of the current hole, rotated so the green is at the top (like a TV hole flyover card).
 * The terrain is rasterised once per hole from the course layout and drawn as merged horizontal runs.
 */
public final class HoleMap {
	private record Run(int x, int y, int w, int color) {
	}

	private static List<Run> runs;
	private static int mapW, mapH;
	private static Vec center, forward, right;
	private static double scale;

	private HoleMap() {
	}

	public static void invalidate() {
		runs = null;
	}

	private static void build(HoleLayout hole, CourseLayout course, int w, int h) {
		mapW = w;
		mapH = h;
		Vec tee = hole.tee(), green = hole.green();
		forward = green.sub(tee).horizontal().normalize();
		right = new Vec(-forward.z(), 0, forward.x());
		// Fit the hole's centre line (plus margin) into the panel.
		double minF = Double.MAX_VALUE, maxF = -Double.MAX_VALUE, maxR = 0;
		for (Vec p : hole.line()) {
			double f = p.sub(tee).dot(forward), r = Math.abs(p.sub(tee).dot(right));
			minF = Math.min(minF, f);
			maxF = Math.max(maxF, f);
			maxR = Math.max(maxR, r);
		}
		double spanF = (maxF - minF) + 40;
		double spanR = (maxR + 30) * 2;
		scale = Math.max(spanF / h, spanR / w);
		center = tee.add(forward.scale((minF + maxF) / 2));

		List<Run> out = new ArrayList<>();
		for (int y = 0; y < h; y++) {
			int runStart = 0, runColor = 0;
			for (int x = 0; x <= w; x++) {
				int color = x == w ? -1 : colorAt(course, toWorld(x + 0.5, y + 0.5));
				if (x == 0) {
					runColor = color;
				} else if (color != runColor) {
					out.add(new Run(runStart, y, x - runStart, runColor));
					runStart = x;
					runColor = color;
				}
			}
		}
		runs = out;
	}

	private static int colorAt(CourseLayout course, Vec world) {
		CourseLayout.Column c = course.column((int) Math.floor(world.x()), (int) Math.floor(world.z()));
		Surface s = c.surface();
		int rgb = s.color;
		if (c.stripe() && (s == Surface.FAIRWAY || s == Surface.GREEN)) {
			rgb = shade(rgb, 0.92);
		}
		rgb = shade(rgb, 1 + (c.groundY() - CourseLayout.BASE_Y) * 0.035);
		return 0xFF000000 | rgb;
	}

	private static int shade(int rgb, double f) {
		int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * f);
		int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * f);
		int b = (int) Math.min(255, (rgb & 0xFF) * f);
		return (r << 16) | (g << 8) | b;
	}

	private static Vec toWorld(double mx, double my) {
		double r = (mx - mapW / 2.0) * scale;
		double f = -(my - mapH / 2.0) * scale;
		return center.add(right.scale(r)).add(forward.scale(f));
	}

	private static double[] toMap(Vec world) {
		Vec d = world.sub(center).horizontal();
		return new double[] {mapW / 2.0 + d.dot(right) / scale, mapH / 2.0 - d.dot(forward) / scale};
	}

	public static void render(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		HoleLayout hole = ClientGolf.hole();
		CourseLayout course = ClientGolf.course();
		if (hole == null || course == null) {
			return;
		}
		if (runs == null || mapW != w || mapH != h) {
			build(hole, course, w, h);
		}
		g.fill(x - 2, y - 2, x + w + 2, y + h + 2, GolfHud.PANEL);
		for (Run run : runs) {
			g.fill(x + run.x(), y + run.y(), x + run.x() + run.w(), y + run.y() + 1, run.color());
		}

		// Aim line and landing ring.
		Vec ball = ClientGolf.ball();
		double[] b = toMap(ball);
		ShotPlanner.Aim aim = ShotPlanner.aim();
		if (aim != null && ClientGolf.addressed()) {
			Vec landing = aim.preview().firstLanding() != null ? aim.preview().firstLanding() : aim.preview().rest();
			double[] l = toMap(landing);
			dotted(g, x + b[0], y + b[1], x + l[0], y + l[1], 0xFFFFE066);
			double[] rest = toMap(aim.preview().rest());
			GolfHud.ring(g, x + (int) Math.round(l[0]), y + (int) Math.round(l[1]), 3, 0xFFFFE066);
			g.fill(x + (int) rest[0], y + (int) rest[1], x + (int) rest[0] + 1, y + (int) rest[1] + 1, 0xFFFFFFFF);
		}
		// Flight trace.
		if (ClientGolf.inFlight()) {
			var path = ClientGolf.flightPath;
			int upto = (int) Math.max(0, ClientGolf.flightProgress(0));
			for (int i = 1; i <= upto && i < path.size(); i++) {
				double[] a = toMap(path.get(i - 1)), c = toMap(path.get(i));
				GolfHud.line(g, x + a[0], y + a[1], x + c[0], y + c[1], 0xFFFF8A3D);
			}
		}

		// Friends' shots and balls, in their colours.
		if (ClientMatch.watching()) {
			var path = ClientMatch.watchPath;
			int upto = (int) Math.max(0, ClientMatch.watchProgress(0));
			int color = ClientMatch.colorOf(ClientMatch.watchName);
			for (int i = 1; i <= upto && i < path.size(); i++) {
				double[] a = toMap(path.get(i - 1)), c = toMap(path.get(i));
				GolfHud.line(g, x + a[0], y + a[1], x + c[0], y + c[1], color);
			}
		}
		if (ClientMatch.playing()) {
			for (var m : ClientMatch.members()) {
				if (m.id().equals(ClientMatch.me()) || ClientMatch.holedOut(m) || m.state() < 0) {
					continue;
				}
				double[] o = toMap(m.ball());
				int ox = x + (int) Math.round(o[0]), oy = y + (int) Math.round(o[1]);
				if (ox >= x && ox < x + w && oy >= y && oy < y + h) {
					g.fill(ox - 1, oy - 1, ox + 2, oy + 2, 0xFF000000);
					g.fill(ox, oy, ox + 1, oy + 1, ClientMatch.color(m.id()));
				}
			}
		}

		double[] pin = toMap(ClientGolf.cup());
		int px = x + (int) Math.round(pin[0]), py = y + (int) Math.round(pin[1]);
		g.fill(px, py - 6, px + 1, py + 1, 0xFFFFFFFF);
		g.fill(px + 1, py - 6, px + 4, py - 3, 0xFFE02828);

		int bx = x + (int) Math.round(b[0]), by = y + (int) Math.round(b[1]);
		g.fill(bx - 1, by - 1, bx + 2, by + 2, 0xFF000000);
		g.fill(bx, by, bx + 1, by + 1, 0xFFFFFFFF);

		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			double[] me = toMap(new Vec(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
			int mx = x + (int) Math.round(me[0]), my = y + (int) Math.round(me[1]);
			if (mx >= x && mx < x + w && my >= y && my < y + h) {
				g.fill(mx, my, mx + 1, my + 1, 0xFF4FA8FF);
			}
		}
		String label = "PAR " + hole.par() + " · " + hole.yards() + "Y";
		g.text(mc.font, label, x + 2, y + h - 10, 0xFFFFFFFF, true);
	}

	private static void dotted(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int color) {
		int steps = (int) Math.max(1, Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0))));
		for (int i = 0; i <= steps; i += 2) {
			int px = (int) Math.round(x0 + (x1 - x0) * i / steps);
			int py = (int) Math.round(y0 + (y1 - y0) * i / steps);
			g.fill(px, py, px + 1, py + 1, color);
		}
	}
}
