package work.benwalker.golftour.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import work.benwalker.golftour.course.HoleLayout;
import work.benwalker.golftour.game.GolfRound;
import work.benwalker.golftour.game.RoundManager;
import work.benwalker.golftour.physics.Club;
import work.benwalker.golftour.physics.ShotCalculator;
import work.benwalker.golftour.physics.ShotType;
import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.physics.Units;
import work.benwalker.golftour.physics.Vec;

/** The broadcast-style golf HUD: hole card, wind, hole map, shot panel, swing meter and scorecard. */
public final class GolfHud {
	static final int PANEL = 0xB0101A12;
	static final int PANEL_LIGHT = 0xC0182A1C;
	static final int ACCENT = 0xFF46C25A;
	static final int WHITE = 0xFFFFFFFF;
	static final int GREY = 0xFFB8C4BA;
	static final int YELLOW = 0xFFFFE066;
	static final int RED = 0xFFFF5A5A;
	static final int BLUE = 0xFF7CC4FF;

	private static final double METER_MIN = ShotCalculator.DUFF_ACCURACY;
	private static final double METER_MAX = ShotCalculator.MAX_POWER;

	private GolfHud() {
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gui.hud.isHidden()) {
			return;
		}
		if (!ClientGolf.active()) {
			if (ClientMatch.lobby()) {
				matchBoard(g, mc.font, g.guiWidth()); // the lobby, before anyone tees off
			}
			return;
		}
		Font font = mc.font;
		int w = g.guiWidth(), h = g.guiHeight();
		HoleLayout hole = ClientGolf.hole();

		holeCard(g, font, hole);
		wind(g, font, w - 26, 26);
		HoleMap.render(g, w - 90, 56, 84, 120);
		if (ClientGolf.roundState() == GolfRound.State.ADDRESS && (ClientGolf.addressed() || SwingMeter.phase() != SwingMeter.Phase.IDLE)) {
			shotPanel(g, font, w, h);
		}
		swingPath(g, font, w / 2 + 91 + 6, h - 37 + 7 - 32);
		flightInfo(g, font, w, h, delta.getGameTimeDeltaPartialTick(false));
		if (CinematicCamera.flyoverActive()) {
			String skip = "CLICK TO SKIP FLYOVER";
			g.text(font, skip, w / 2 - font.width(skip) / 2, h - 40, 0xC0FFFFFF, true);
		}
		matchBoard(g, font, w);
		walkPrompt(g, font, w, h);
		qualityBanner(g, font, w, h);
		noticeBanner(g, font, w);
		if (GolfKeys.SCORECARD.isDown() || ClientGolf.roundState() == GolfRound.State.FINISHED) {
			Scorecard.render(g, font, w, h);
		}
	}

	// ---------------------------------------------------------------- hole card

	private static void holeCard(GuiGraphicsExtractor g, Font font, HoleLayout hole) {
		int x = 6, y = 6, cw = 156, ch = 70;
		g.fill(x, y, x + cw, y + ch, PANEL);
		g.fill(x, y, x + cw, y + 2, ACCENT);

		g.pose().pushMatrix();
		g.pose().translate(x + 6, y + 7);
		g.pose().scale(1.6f, 1.6f);
		g.text(font, "HOLE " + hole.number(), 0, 0, WHITE, true);
		g.pose().popMatrix();
		String par = "PAR " + hole.par();
		g.text(font, par, x + cw - 6 - font.width(par), y + 8, YELLOW, true);
		g.text(font, hole.yards() + " YDS  ·  " + ClientGolf.course().name, x + 6, y + 24, GREY, false);

		var state = ClientGolf.state();
		Vec ball = ClientGolf.ball();
		double toPin = ball.horizontalDistanceTo(ClientGolf.cup());
		int shotNo = state.strokes() + (ClientGolf.roundState() == GolfRound.State.ADDRESS ? 1 : 0);
		g.text(font, "SHOT " + Math.max(1, shotNo), x + 6, y + 36, WHITE, true);
		Surface lie = ClientGolf.lie();
		String lieText = lie.displayName.toUpperCase();
		g.text(font, lieText, x + cw - 6 - font.width(lieText), y + 36, 0xFF000000 | lie.color, true);

		String pin = ClientGolf.lie().isPuttingSurface() ? Math.round(Units.toFeet(toPin)) + " FT TO PIN" : Math.round(Units.toYards(toPin)) + " YDS TO PIN";
		g.text(font, pin, x + 6, y + 48, WHITE, true);
		double elev = ClientGolf.cup().y() - ball.y();
		if (Math.abs(elev) >= 0.5) {
			String e = (elev > 0 ? "▲ " : "▼ ") + Math.round(Math.abs(Units.toFeet(elev))) + " FT";
			g.text(font, e, x + cw - 6 - font.width(e), y + 48, elev > 0 ? RED : BLUE, true);
		}

		int toPar = ClientGolf.toParCompleted();
		int thru = ClientGolf.holesCompleted();
		String total = "TOTAL " + RoundManager.formatToPar(toPar) + (thru > 0 ? "  THRU " + thru : "");
		g.text(font, total, x + 6, y + 60, toPar < 0 ? RED : toPar == 0 ? WHITE : BLUE, true);
	}

	// ---------------------------------------------------------------- wind

	private static void wind(GuiGraphicsExtractor g, Font font, int cx, int cy) {
		Minecraft mc = Minecraft.getInstance();
		int r = 16;
		g.fill(cx - r - 4, cy - r - 4, cx + r + 4, cy + r + 14, PANEL);
		ring(g, cx, cy, r, 0x80FFFFFF);
		double mph = Units.blocksPerSecondToMph(ClientGolf.state().windSpeed());
		float camYaw = mc.gameRenderer.mainCamera().yRot();
		double rel = Math.toRadians(ClientGolf.state().windYaw() - camYaw);
		double dx = Math.sin(rel), dy = -Math.cos(rel);
		int color = mph < 5 ? 0xFF9FE6A0 : mph < 10 ? YELLOW : RED;
		double len = r - 3;
		line(g, cx - dx * len, cy - dy * len, cx + dx * len, cy + dy * len, color);
		// arrow head
		double hx = cx + dx * len, hy = cy + dy * len;
		double px = -dy, py = dx;
		line(g, hx, hy, hx - dx * 6 + px * 4, hy - dy * 6 + py * 4, color);
		line(g, hx, hy, hx - dx * 6 - px * 4, hy - dy * 6 - py * 4, color);
		String text = Math.round(mph) + " MPH";
		g.text(font, text, cx - font.width(text) / 2, cy + r + 3, WHITE, true);
	}

	// ---------------------------------------------------------------- shot panel and meter

	private static void shotPanel(GuiGraphicsExtractor g, Font font, int w, int h) {
		Club club = ClientGolf.heldClub();
		int mx = w / 2 - 91, my = h - 37, mw = 182, mh = 7;
		int pw = 172, ph = 56, px = 6, py = h - ph - 6;
		if (px + pw > mx - 6) {
			py = my - ph - 8;
		}
		g.fill(px, py, px + pw, py + ph, PANEL);
		g.fill(px, py, px + 2, py + ph, ACCENT);
		if (club == null) {
			g.text(font, "SELECT A CLUB", px + 8, py + 8, YELLOW, true);
			g.text(font, "Clubs are on your hotbar (1-9)", px + 8, py + 22, GREY, false);
			return;
		}
		ShotType type = ClientGolf.shotTypeFor(club);
		ShotPlanner.Aim aim = ShotPlanner.aim();

		g.pose().pushMatrix();
		g.pose().translate(px + 8, py + 6);
		g.pose().scale(1.25f, 1.25f);
		g.text(font, club.displayName.toUpperCase(), 0, 0, WHITE, true);
		g.pose().popMatrix();
		String shot = type.displayName.toUpperCase();
		g.text(font, shot, px + pw - 30 - font.width(shot), py + 7, YELLOW, true);

		String line1 = "", line2 = "";
		if (aim != null && club.isPutter()) {
			double length = aim.target().horizontalDistanceTo(aim.start());
			double rise = aim.target().y() - aim.start().y();
			line1 = "PUTT " + Math.round(Units.toFeet(length)) + " FT";
			if (Math.abs(aim.puttTarget() - length) > Math.max(0.15, length * 0.12)) {
				line1 += "  ·  PLAYS " + Math.round(Units.toFeet(aim.puttTarget())) + " FT" + (rise > 0.05 ? " UPHILL" : rise < -0.05 ? " DOWNHILL" : "");
			}
			line2 = "ROLLS " + Math.round(Units.toFeet(aim.totalBlocks())) + " FT · PULL TO THE MARKER";
		} else if (aim != null) {
			line1 = "CARRY " + Math.round(Units.toYards(aim.carryBlocks())) + "  ·  TOTAL " + Math.round(Units.toYards(aim.totalBlocks())) + " YDS";
			line2 = "POWER " + Math.round(SwingMeter.plannedPower() * 100) + "%  ·  MAX " + Math.round(Units.toYards(aim.maxCarry())) + " YDS";
		}
		g.text(font, line1, px + 8, py + 22, WHITE, false);
		g.text(font, line2, px + 8, py + 33, GREY, false);
		g.pose().pushMatrix();
		g.pose().translate(px + 8, py + 46);
		g.pose().scale(0.7f, 0.7f);
		g.text(font, "HOLD RIGHT-CLICK · PULL BACK · PUSH THROUGH  ·  G SHOT  ·  ARROWS SPIN", 0, 0, 0xC0FFFFFF, false);
		g.pose().popMatrix();
		strikeWidget(g, px + pw - 15, py + 25);

		meter(g, font, club, type, mx, my, mw, mh);
	}

	private static int meterX(int mx, int mw, double v) {
		return mx + (int) Math.round((v - METER_MIN) / (METER_MAX - METER_MIN) * mw);
	}

	private static void meter(GuiGraphicsExtractor g, Font font, Club club, ShotType type, int mx, int my, int mw, int mh) {
		g.fill(mx - 1, my - 1, mx + mw + 1, my + mh + 1, 0xFF000000);
		g.fill(mx, my, meterX(mx, mw, 0), my + mh, 0xFF2A2F2B);
		int x0 = meterX(mx, mw, 0), x1 = meterX(mx, mw, 1.0);
		for (int x = x0; x < x1; x++) {
			double t = (x - x0) / (double) (x1 - x0);
			int r = (int) (60 + t * 190), gr = (int) (190 - t * 40), b = 70;
			g.fill(x, my, x + 1, my + mh, 0xFF000000 | (r << 16) | (gr << 8) | b);
		}
		g.fill(x1, my, mx + mw, my + mh, 0xFFC03A30);
		for (double tick : new double[] {0.25, 0.5, 0.75, 1.0}) {
			int tx = meterX(mx, mw, tick);
			g.fill(tx, my, tx + 1, my + mh, 0x90000000);
		}
		g.fill(meterX(mx, mw, 0), my - 3, meterX(mx, mw, 0) + 1, my + mh + 3, WHITE);

		// Planned power marker (where to stop the meter to land on the target).
		int px = meterX(mx, mw, SwingMeter.plannedPower());
		g.fill(px - 1, my - 4, px + 1, my, WHITE);
		g.fill(px - 1, my + mh, px + 1, my + mh + 4, WHITE);

		if (SwingMeter.phase() == SwingMeter.Phase.DOWNSWING || SwingMeter.phase() == SwingMeter.Phase.BACKSWING) {
			if (SwingMeter.phase() == SwingMeter.Phase.DOWNSWING) {
				int lx = meterX(mx, mw, SwingMeter.lockedPower());
				g.fill(lx - 1, my - 3, lx + 1, my + mh + 3, YELLOW);
			}
			double needle = Math.max(METER_MIN, Math.min(METER_MAX, SwingMeter.needle()));
			int nx = meterX(mx, mw, needle);
			g.fill(nx - 1, my - 5, nx + 2, my + mh + 5, 0xFF000000);
			g.fill(nx, my - 4, nx + 1, my + mh + 4, WHITE);
		}
	}

	/**
	 * The swing stick's path: the mouse trail of the swing in progress (depth down, sideways across), and after
	 * the strike the tempo and how far the stroke strayed from straight.
	 */
	private static void swingPath(GuiGraphicsExtractor g, Font font, int x, int y) {
		SwingMeter.Feedback fb = SwingMeter.feedback();
		boolean live = SwingMeter.phase() != SwingMeter.Phase.IDLE;
		boolean recent = fb != null && ClientGolf.ticks - fb.at() < 100;
		if (!live && !recent && !ClientGolf.addressed()) {
			return;
		}
		int bw = 30, bh = 32;
		g.fill(x, y, x + bw, y + bh, PANEL);
		int cx = x + bw / 2, top = y + 4;
		double scale = (bh - 6) / ShotCalculator.MAX_POWER;
		// Guide: straight back and through
		for (int yy = top; yy < y + bh - 3; yy += 3) {
			g.fill(cx, yy, cx + 1, yy + 1, 0x60FFFFFF);
		}
		if (live || recent) {
			var trail = SwingMeter.trail();
			for (int i = 1; i < trail.size(); i++) {
				double[] a = trail.get(i - 1), b = trail.get(i);
				boolean through = b[1] < a[1];
				int color = through ? 0xFFFFE066 : 0xFFBFC7C2;
				line(g, cx + a[0] * scale, top + a[1] * scale, cx + b[0] * scale, top + b[1] * scale, color);
			}
		}
		if (!live && recent) {
			double path = fb.throughDegrees() + 0.5 * fb.backDegrees();
			String shape = Math.abs(path) < 3.5 ? "STRAIGHT" : (path > 0 ? "SLICE " : "HOOK ") + Math.round(Math.abs(path)) + "°";
			g.text(font, shape, x + bw + 4, y + 4, Math.abs(path) < 3.5 ? 0xFF55FF55 : 0xFFFF9933, true);
			g.text(font, "TEMPO " + fb.tempo(), x + bw + 4, y + 16, fb.tempoColor(), true);
		} else if (live) {
			g.text(font, Math.round(SwingMeter.needle() * 100) + "%", x + bw + 4, y + 4, WHITE, true);
		}
	}

	/** Ball-face widget: dot shows the strike point (up = topspin, down = backspin, left/right = draw/fade). */
	private static void strikeWidget(GuiGraphicsExtractor g, int cx, int cy) {
		int r = 9;
		g.fill(cx - r - 3, cy - r - 3, cx + r + 3, cy + r + 3, PANEL);
		disc(g, cx, cy, r, 0xFFF2F2F2);
		ring(g, cx, cy, r, 0xFF9AA0A0);
		int dx = (int) Math.round(ClientGolf.shape * (r - 3));
		int dy = (int) Math.round(ClientGolf.spin * (r - 3));
		disc(g, cx + dx, cy + dy, 2, 0xFFE03030);
	}

	// ---------------------------------------------------------------- flight and messages

	private static void flightInfo(GuiGraphicsExtractor g, Font font, int w, int h, float partial) {
		if (!ClientGolf.inFlight()) {
			watchedFlightInfo(g, font, w, h, partial);
			return;
		}
		var path = ClientGolf.flightPath;
		int idx = (int) Math.max(0, Math.min(path.size() - 1, ClientGolf.flightProgress(partial)));
		Vec start = path.get(0);
		double dist = path.get(idx).horizontalDistanceTo(start);
		boolean putt = ClientGolf.lastWasPutt;
		String text = putt ? Math.round(Units.toFeet(dist)) + " FT" : Math.round(Units.toYards(dist)) + " YDS";
		int y = h - 70;
		g.pose().pushMatrix();
		g.pose().translate(w / 2f, y);
		g.pose().scale(2f, 2f);
		g.text(font, text, -font.width(text) / 2, 0, WHITE, true);
		g.pose().popMatrix();
		if (!putt && idx >= ClientGolf.flightLanding && ClientGolf.flightLanding > 0) {
			String carry = "CARRY " + Math.round(ClientGolf.lastCarryYards);
			g.text(font, carry, w / 2 - font.width(carry) / 2, y + 20, GREY, true);
		}
	}

	private static void qualityBanner(GuiGraphicsExtractor g, Font font, int w, int h) {
		int age = ClientGolf.ticks - ClientGolf.qualityShownAt;
		if (age > 40 || ClientGolf.quality.isEmpty() || ClientGolf.roundState() == GolfRound.State.HOLED) {
			return;
		}
		int alpha = age < 30 ? 255 : (int) (255 * (40 - age) / 10.0);
		String text = ClientGolf.quality;
		g.pose().pushMatrix();
		g.pose().translate(w / 2f, h / 2f - 78);
		g.pose().scale(2.2f, 2.2f);
		g.text(font, text, -font.width(text) / 2, 0, (Math.max(8, alpha) << 24) | (ClientGolf.qualityColor & 0xFFFFFF), true);
		g.pose().popMatrix();
	}

	/** Distance readout for another player's shot: "SAM · 245 YDS". */
	private static void watchedFlightInfo(GuiGraphicsExtractor g, Font font, int w, int h, float partial) {
		if (!ClientMatch.watching()) {
			return;
		}
		var path = ClientMatch.watchPath;
		int idx = (int) Math.max(0, Math.min(path.size() - 1, ClientMatch.watchProgress(partial)));
		double dist = path.get(idx).horizontalDistanceTo(path.get(0));
		String text = ClientMatch.watchName.toUpperCase() + "  ·  " + (ClientMatch.watchPutt ? Math.round(Units.toFeet(dist)) + " FT" : Math.round(Units.toYards(dist)) + " YDS");
		int y = h - 92;
		g.pose().pushMatrix();
		g.pose().translate(w / 2f, y);
		g.pose().scale(1.5f, 1.5f);
		g.text(font, text, -font.width(text) / 2, 0, ClientMatch.colorOf(ClientMatch.watchName), true);
		g.pose().popMatrix();
		if (ClientGolf.ticks - ClientMatch.watchStart < 40 && !ClientMatch.watchQuality.isEmpty()) {
			String q = ClientMatch.watchQuality;
			g.text(font, q, w / 2 - font.width(q) / 2, y + 16, 0xFF000000 | ClientMatch.watchColor, true);
		}
	}

	/**
	 * The match leaderboard (top centre): everyone's score to par and holes played, with an arrow on whoever is up,
	 * and a turn banner underneath.
	 */
	private static void matchBoard(GuiGraphicsExtractor g, Font font, int w) {
		if (!ClientMatch.active()) {
			return;
		}
		var rows = ClientMatch.standings();
		int bw = 108, rowH = 9;
		int x = w / 2 - bw / 2, y = 4;
		int bh = 12 + rows.size() * rowH;
		g.fill(x, y, x + bw, y + bh, PANEL);
		g.fill(x, y, x + bw, y + 1, ACCENT);
		String head = ClientMatch.lobby() ? "MATCH LOBBY · " + rows.size() + "/4"
			: ClientMatch.finished() ? "FINAL" : "MATCH · HOLE " + (ClientMatch.state().hole() + 1) + " OF " + (ClientMatch.state().lastHole() + 1);
		small(g, font, head, x + 4, y + 3, 0xFFFFE066);
		var me = ClientMatch.me();
		for (int i = 0; i < rows.size(); i++) {
			var m = rows.get(i);
			int ry = y + 11 + i * rowH;
			boolean up = m.id().equals(ClientMatch.state().turn());
			if (m.id().equals(me)) {
				g.fill(x + 1, ry - 1, x + bw - 1, ry + rowH - 1, 0x30FFFFFF);
			}
			g.fill(x + 3, ry + 1, x + 6, ry + 5, ClientMatch.color(m.id()));
			String name = m.name().length() > 12 ? m.name().substring(0, 12) : m.name();
			small(g, font, (up ? "> " : "") + name, x + 9, ry, up ? 0xFFFFFFFF : 0xFFD8D8D8);
			if (!ClientMatch.lobby()) {
				int toPar = ClientMatch.toPar(m);
				String score = RoundManager.formatToPar(toPar);
				int done = ClientMatch.holesDone(m);
				String thru = ClientMatch.holedOut(m) ? "IN" : m.strokes() > 0 ? "(" + m.strokes() + ")" : "";
				small(g, font, score, x + bw - 44, ry, toPar < 0 ? 0xFFFF6B6B : toPar == 0 ? 0xFFFFFFFF : 0xFF8FC6FF);
				small(g, font, thru.isEmpty() ? "THRU " + done : thru, x + bw - 26, ry, 0xFFB0B0B0);
			}
		}
		if (ClientMatch.playing()) {
			boolean mine = ClientMatch.myTurn();
			String who = ClientMatch.turnName();
			int ty = y + bh + 3;
			if (mine && ClientGolf.ticks - ClientMatch.turnChangedAt < 70) {
				String t = "YOUR SHOT";
				g.pose().pushMatrix();
				g.pose().translate(w / 2f, ty);
				g.pose().scale(1.6f, 1.6f);
				g.text(font, t, -font.width(t) / 2, 0, 0xFF55FF55, true);
				g.pose().popMatrix();
			} else if (!who.isEmpty()) {
				String t = mine ? "YOUR SHOT" : who.toUpperCase() + " TO PLAY";
				g.text(font, t, w / 2 - font.width(t) / 2, ty, mine ? 0xFF55FF55 : ClientMatch.colorOf(who), true);
			}
		}
	}

	private static void small(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(0.75f, 0.75f);
		g.text(font, text, 0, 0, color, true);
		g.pose().popMatrix();
	}

	/** While the ball waits and the player isn't at it: where to go, and how to get there faster. */
	private static void walkPrompt(GuiGraphicsExtractor g, Font font, int w, int h) {
		Minecraft mc = Minecraft.getInstance();
		if (!ClientGolf.atAddress() || ClientGolf.addressed() || mc.player == null || CinematicCamera.flyoverActive()) {
			return;
		}
		boolean tee = ClientGolf.state().strokes() == 0 && ClientGolf.lie() == work.benwalker.golftour.physics.Surface.TEE;
		String main;
		if (ClientGolf.nearBall()) {
			main = "RIGHT-CLICK TO ADDRESS THE BALL";
		} else if (mc.player.isPassenger()) {
			main = (tee ? "DRIVE TO THE TEE" : "DRIVE TO YOUR BALL") + "  ·  " + Math.round(Units.toYards(ClientGolf.distanceToBall())) + " YDS";
		} else if (ClientGolf.heldClub() == null) {
			main = "SELECT A CLUB";
		} else {
			main = (tee ? "WALK TO THE TEE" : "WALK TO YOUR BALL") + "  ·  " + Math.round(Units.toYards(ClientGolf.distanceToBall())) + " YDS";
		}
		String hint = "[" + GolfKeys.GO_TO_BALL.getTranslatedKeyMessage().getString() + "] " + (tee ? "GO TO THE TEE" : "GO TO BALL")
			+ "   [" + GolfKeys.CALL_BUGGY.getTranslatedKeyMessage().getString() + "] CALL BUGGY";
		int y = h - 58;
		int bw = Math.max(font.width(main), font.width(hint)) + 16;
		g.fill(w / 2 - bw / 2, y - 4, w / 2 + bw / 2, y + 22, 0xA0101A12);
		g.text(font, main, w / 2 - font.width(main) / 2, y, 0xFFFFE066, true);
		g.text(font, hint, w / 2 - font.width(hint) / 2, y + 11, 0xC0FFFFFF, true);
	}

	/** Top-centre banner for course notices (penalties, lie and yardage after a shot). */
	private static void noticeBanner(GuiGraphicsExtractor g, Font font, int w) {
		int age = ClientGolf.ticks - ClientGolf.noticeAt;
		if (age > 90 || ClientGolf.notice.isEmpty()) {
			return;
		}
		float alpha = age < 75 ? 1f : (90 - age) / 15f;
		String text = ClientGolf.notice;
		int maxWidth = Math.min(w - 110, 300);
		float scale = font.width(text) + 12 > maxWidth ? Math.max(0.7f, (maxWidth - 12) / (float) font.width(text)) : 1f;
		int tw = (int) (font.width(text) * scale);
		int x = 6, y = 82;
		g.fill(x, y, x + tw + 12, y + (int) (9 * scale) + 8, ((int) (0xC8 * alpha) << 24) | 0x101A12);
		g.fill(x, y, x + 2, y + (int) (9 * scale) + 8, ((int) (0xFF * alpha) << 24) | (ClientGolf.noticeColor & 0xFFFFFF));
		g.pose().pushMatrix();
		g.pose().translate(x + 7, y + 4);
		g.pose().scale(scale, scale);
		g.text(font, text, 0, 0, ((int) (0xFF * Math.max(0.05f, alpha)) << 24) | (ClientGolf.noticeColor & 0xFFFFFF), true);
		g.pose().popMatrix();
	}

	// ---------------------------------------------------------------- drawing helpers

	static void line(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int color) {
		int steps = (int) Math.max(1, Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0))));
		for (int i = 0; i <= steps; i++) {
			int x = (int) Math.round(x0 + (x1 - x0) * i / steps);
			int y = (int) Math.round(y0 + (y1 - y0) * i / steps);
			g.fill(x, y, x + 1, y + 1, color);
		}
	}

	static void ring(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
		int n = Math.max(16, r * 6);
		for (int i = 0; i < n; i++) {
			double a = i * Math.PI * 2 / n;
			int x = cx + (int) Math.round(Math.cos(a) * r), y = cy + (int) Math.round(Math.sin(a) * r);
			g.fill(x, y, x + 1, y + 1, color);
		}
	}

	static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.floor(Math.sqrt(r * r - dy * dy + 0.5));
			g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}
}
