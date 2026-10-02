package work.benwalker.golftour.course;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.physics.Units;
import work.benwalker.golftour.physics.Vec;

class CourseLayoutTest {
	static final long SEED = 2026L;

	@Test
	void generatesAPlayableParSeventyTwo() {
		long t0 = System.nanoTime();
		CourseLayout course = CourseLayout.of(SEED);
		System.out.printf("%s generated in %d ms: par %d, %d yards%n", course.name, (System.nanoTime() - t0) / 1_000_000, course.totalPar(), course.totalYards());
		assertEquals(18, course.holes.size());
		assertEquals(72, course.totalPar());
		for (HoleLayout h : course.holes) {
			System.out.printf("  hole %2d  par %d  %3d yds  bunkers %d  water %d  slope %.1f%%%n", h.number(), h.par(), h.yards(),
				h.bunkers().size(), h.water().size(), h.slope().gradient(h.green().x(), h.green().z()).length() * 100);
			int yards = h.yards();
			switch (h.par()) {
				case 3 -> assertTrue(yards >= 150 && yards <= 215, "par 3 length " + yards);
				case 4 -> assertTrue(yards >= 340 && yards <= 435, "par 4 length " + yards);
				default -> assertTrue(yards >= 485 && yards <= 560, "par 5 length " + yards);
			}
			CourseLayout.Column cup = course.column((int) Math.floor(h.pin().x()), (int) Math.floor(h.pin().z()));
			assertEquals(Surface.GREEN, cup.surface(), "pin on green, hole " + h.number());
			assertEquals(h.greenY(), cup.groundY());
			CourseLayout.Column tee = course.column((int) Math.floor(h.tee().x()), (int) Math.floor(h.tee().z()));
			assertEquals(Surface.TEE, tee.surface(), "tee box, hole " + h.number());
			assertEquals(h.teeY(), tee.groundY());
			// The landing zone of a driver from the tee is in play.
			if (h.par() >= 4) {
				Vec landing = h.pointAt(Units.fromYards(250));
				Surface s = course.column((int) Math.floor(landing.x()), (int) Math.floor(landing.z())).surface();
				assertTrue(s != Surface.OUT, "drive zone in bounds on hole " + h.number());
			}
		}
	}

	@Test
	void holesDoNotOverlap() {
		CourseLayout course = CourseLayout.of(SEED);
		for (HoleLayout a : course.holes) {
			for (HoleLayout b : course.holes) {
				if (a.number() >= b.number()) {
					continue;
				}
				double d = b.nearest(a.green().x(), a.green().z()).distance();
				if (b.number() == a.number() + 1) {
					continue;
				}
				assertTrue(d > 38, "green " + a.number() + " too close to hole " + b.number() + " (" + d + ")");
			}
		}
	}

	@Test
	void otherSeedsAlsoRoute() {
		for (long seed = 1; seed <= 12; seed++) {
			assertNotNull(CourseLayout.of(seed));
			assertEquals(72, CourseLayout.of(seed).totalPar());
		}
	}

	@Test
	void writesCourseMap() throws Exception {
		CourseLayout course = CourseLayout.of(SEED);
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (HoleLayout h : course.holes) {
			for (Vec p : h.line()) {
				minX = Math.min(minX, (int) p.x());
				minZ = Math.min(minZ, (int) p.z());
				maxX = Math.max(maxX, (int) p.x());
				maxZ = Math.max(maxZ, (int) p.z());
			}
		}
		int pad = 60;
		minX -= pad;
		minZ -= pad;
		maxX += pad;
		maxZ += pad;
		BufferedImage img = new BufferedImage(maxX - minX, maxZ - minZ, BufferedImage.TYPE_INT_RGB);
		for (int z = minZ; z < maxZ; z++) {
			for (int x = minX; x < maxX; x++) {
				CourseLayout.Column c = course.column(x, z);
				int rgb = c.surface().color;
				if (c.stripe() && (c.surface() == Surface.FAIRWAY || c.surface() == Surface.GREEN)) {
					rgb = shade(rgb, 0.9);
				}
				int shadeBy = c.groundY() - CourseLayout.BASE_Y;
				rgb = shade(rgb, 1 + shadeBy * 0.03);
				img.setRGB(x - minX, z - minZ, rgb);
			}
		}
		for (int cz = Math.floorDiv(minZ, 6); cz <= Math.floorDiv(maxZ, 6); cz++) {
			for (int cx = Math.floorDiv(minX, 6); cx <= Math.floorDiv(maxX, 6); cx++) {
				CourseLayout.Tree t = course.treeInCell(cx, cz);
				if (t != null && t.x() > minX && t.x() < maxX - 1 && t.z() > minZ && t.z() < maxZ - 1) {
					img.setRGB(t.x() - minX, t.z() - minZ, 0x0F3D0F);
				}
			}
		}
		for (HoleLayout h : course.holes) {
			img.setRGB((int) h.pin().x() - minX, (int) h.pin().z() - minZ, 0xFF0000);
		}
		File out = new File("build/course-map.png");
		out.getParentFile().mkdirs();
		ImageIO.write(img, "png", out);
		System.out.println("course map " + img.getWidth() + "x" + img.getHeight() + " -> " + out.getAbsolutePath());
	}

	private static int shade(int rgb, double f) {
		int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * f);
		int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * f);
		int b = (int) Math.min(255, (rgb & 0xFF) * f);
		return (r << 16) | (g << 8) | b;
	}
}
