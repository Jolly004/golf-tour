package work.benwalker.golftour.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import work.benwalker.golftour.course.CourseLayout;
import work.benwalker.golftour.game.RoundManager;

/** Full 18-hole scorecard overlay (hold the scorecard key, shown automatically when the round ends). */
public final class Scorecard {
	private static final int CELL = 17;
	private static final int LABEL = 38;
	private static final int ROW = 12;

	private Scorecard() {
	}

	/** A row on the card: the player's own, or each match player's. */
	private record Player(String name, int[] scores, int color, boolean current) {
	}

	private static java.util.List<Player> players() {
		java.util.List<Player> list = new java.util.ArrayList<>();
		if (ClientMatch.active() && !ClientMatch.lobby()) {
			for (var m : ClientMatch.members()) {
				String name = m.name().length() > 7 ? m.name().substring(0, 7) : m.name();
				list.add(new Player(name.toUpperCase(), m.id().equals(ClientMatch.me()) ? ClientGolf.state().scores() : m.scores(), ClientMatch.color(m.id()),
					m.id().equals(ClientMatch.me())));
			}
		}
		if (list.isEmpty()) {
			list.add(new Player("SCORE", ClientGolf.state().scores(), 0xFF111111, true));
		}
		return list;
	}

	public static void render(GuiGraphicsExtractor g, Font font, int w, int h) {
		CourseLayout course = ClientGolf.course();
		java.util.List<Player> players = players();
		int rows = 3 + players.size();
		int tableW = LABEL + CELL * 9 + 26;
		int cardW = tableW + 34, cardH = rows * ROW * 2 + 52;
		int x = w / 2 - cardW / 2, y = h / 2 - cardH / 2;
		g.fill(x, y, x + cardW, y + cardH, 0xE0F4F1E6);
		g.fill(x, y, x + cardW, y + 16, 0xFF1F5A2C);
		String title = course.name.toUpperCase() + "  —  SCORECARD";
		g.text(font, title, x + 8, y + 4, 0xFFFFFFFF, false);

		int[] totals = new int[players.size()];
		int[] playedPar = new int[players.size()];
		int totalPar = 0;
		for (int nine = 0; nine < 2; nine++) {
			int ty = y + 22 + nine * (rows * ROW + 8);
			int tx = x + 8;
			row(g, font, tx, ty, nine == 0 ? "HOLE" : "HOLE", 0xFF1F5A2C, 0xFFFFFFFF);
			row(g, font, tx, ty + ROW, "PAR", 0xFFE4E0D0, 0xFF333333);
			row(g, font, tx, ty + ROW * 2, "YDS", 0xFFE4E0D0, 0xFF666666);
			for (int p = 0; p < players.size(); p++) {
				Player pl = players.get(p);
				row(g, font, tx, ty + ROW * (3 + p), pl.name(), pl.current() ? 0xFFFFFFFF : 0xFFF7F5EE, players.size() > 1 ? darker(pl.color()) : 0xFF111111);
			}
			int parSum = 0, yards = 0;
			int[] sums = new int[players.size()];
			for (int i = 0; i < 9; i++) {
				int hole = nine * 9 + i;
				int cx = tx + LABEL + i * CELL;
				var layout = course.holes.get(hole);
				centered(g, font, String.valueOf(hole + 1), cx, ty, 0xFFFFFFFF);
				centered(g, font, String.valueOf(layout.par()), cx, ty + ROW, 0xFF333333);
				small(g, font, String.valueOf(layout.yards()), cx + CELL / 2f, ty + ROW * 2 + 3, 0xFF666666);
				parSum += layout.par();
				yards += layout.yards();
				for (int p = 0; p < players.size(); p++) {
					int ry = ty + ROW * (3 + p);
					int s = players.get(p).scores()[hole];
					if (s > 0) {
						int diff = s - layout.par();
						int bg = diff <= -2 ? 0xFFF2C14E : diff == -1 ? 0xFFE45B5B : diff == 0 ? 0x00000000 : diff == 1 ? 0xFF7CB7E8 : 0xFF3E6FA8;
						if (bg != 0) {
							g.fill(cx + 2, ry + 1, cx + CELL - 2, ry + ROW - 1, bg);
						}
						centered(g, font, String.valueOf(s), cx, ry, diff == 0 ? 0xFF111111 : 0xFFFFFFFF);
						sums[p] += s;
						playedPar[p] += layout.par();
					} else if (hole == ClientGolf.state().hole()) {
						g.fill(cx + 2, ry + 1, cx + CELL - 2, ry + ROW - 1, 0x40000000);
					}
				}
			}
			int sx = tx + LABEL + 9 * CELL;
			centered26(g, font, nine == 0 ? "OUT" : "IN", sx, ty, 0xFFFFFFFF);
			centered26(g, font, String.valueOf(parSum), sx, ty + ROW, 0xFF333333);
			small(g, font, String.valueOf(yards), sx + 13f, ty + ROW * 2 + 3, 0xFF666666);
			for (int p = 0; p < players.size(); p++) {
				centered26(g, font, sums[p] > 0 ? String.valueOf(sums[p]) : "-", sx, ty + ROW * (3 + p), 0xFF111111);
				totals[p] += sums[p];
			}
			totalPar += parSum;
		}
		StringBuilder footer = new StringBuilder();
		for (int p = 0; p < players.size(); p++) {
			if (!footer.isEmpty()) {
				footer.append("   ");
			}
			String who = players.size() > 1 ? players.get(p).name() + " " : "TOTAL ";
			footer.append(who).append(totals[p] > 0 ? totals[p] + " (" + RoundManager.formatToPar(totals[p] - playedPar[p]) + ")" : "-");
		}
		footer.append("   ·   PAR ").append(totalPar);
		String f = footer.toString();
		float scale = font.width(f) > cardW - 10 ? (cardW - 10) / (float) font.width(f) : 1f;
		g.pose().pushMatrix();
		g.pose().translate(x + cardW / 2f, y + cardH - 12);
		g.pose().scale(scale, scale);
		g.text(font, f, -font.width(f) / 2, 0, 0xFF1F5A2C, false);
		g.pose().popMatrix();
	}

	private static int darker(int argb) {
		int r = (argb >> 16 & 255) * 3 / 5, gr = (argb >> 8 & 255) * 3 / 5, b = (argb & 255) * 3 / 5;
		return 0xFF000000 | r << 16 | gr << 8 | b;
	}

	private static void row(GuiGraphicsExtractor g, Font font, int x, int y, String label, int bg, int fg) {
		g.fill(x, y, x + LABEL + CELL * 9 + 26, y + ROW, bg);
		g.fill(x, y + ROW - 1, x + LABEL + CELL * 9 + 26, y + ROW, 0x30000000);
		g.text(font, label, x + 3, y + 2, fg, false);
	}

	private static void centered(GuiGraphicsExtractor g, Font font, String s, int cellX, int y, int color) {
		g.text(font, s, cellX + CELL / 2 - font.width(s) / 2, y + 2, color, false);
	}

	private static void small(GuiGraphicsExtractor g, Font font, String s, float centerX, int y, int color) {
		g.pose().pushMatrix();
		g.pose().translate(centerX, y);
		g.pose().scale(0.66f, 0.66f);
		g.text(font, s, -font.width(s) / 2, 0, color, false);
		g.pose().popMatrix();
	}

	private static void centered26(GuiGraphicsExtractor g, Font font, String s, int cellX, int y, int color) {
		g.text(font, s, cellX + 13 - font.width(s) / 2, y + 2, color, false);
	}
}
