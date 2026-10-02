package work.benwalker.golftour.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import work.benwalker.golftour.GolfTour;

/** Golf Tour's sound events. The audio itself is synthesised by {@code tools/gen_sounds.py}. */
public final class GolfSounds {
	public static final SoundEvent CLUB_DRIVER = register("club.driver");
	public static final SoundEvent CLUB_IRON = register("club.iron");
	public static final SoundEvent CLUB_WEDGE = register("club.wedge");
	public static final SoundEvent CLUB_PUTTER = register("club.putter");
	public static final SoundEvent CLUB_SAND = register("club.sand");
	public static final SoundEvent SWING_WHOOSH = register("swing.whoosh");
	public static final SoundEvent LAND_GRASS = register("ball.land_grass");
	public static final SoundEvent LAND_GREEN = register("ball.land_green");
	public static final SoundEvent LAND_SAND = register("ball.land_sand");
	public static final SoundEvent LAND_HARD = register("ball.land_hard");
	public static final SoundEvent BALL_TREE = register("ball.tree");
	public static final SoundEvent BALL_SPLASH = register("ball.splash");
	public static final SoundEvent BALL_CUP = register("ball.cup");
	public static final SoundEvent BALL_LIP = register("ball.lip");
	public static final SoundEvent CROWD_APPLAUSE = register("crowd.applause");
	public static final SoundEvent CROWD_CHEER = register("crowd.cheer");
	public static final SoundEvent CROWD_GROAN = register("crowd.groan");
	public static final SoundEvent AMBIENT_COURSE = register("ambient.course");
	public static final SoundEvent AMBIENT_BIRDS = register("ambient.birds");
	public static final SoundEvent UI_METER_TICK = register("ui.meter_tick");
	public static final SoundEvent UI_METER_LOCK = register("ui.meter_lock");
	public static final SoundEvent UI_HOLE_START = register("ui.hole_start");

	private GolfSounds() {
	}

	private static SoundEvent register(String name) {
		Identifier id = GolfTour.id(name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	public static void init() {
	}
}
