package work.benwalker.golftour.physics;

/** Shot shapes the player can select before swinging (cycled with the shot-type key). */
public enum ShotType {
	//            name     launch spin  distance window
	NORMAL("Normal", 1.00, 1.00, 1.00, 1.00),
	POWER("Power", 0.95, 0.90, 1.07, 0.55),
	PUNCH("Punch", 0.55, 0.65, 0.85, 1.10),
	CHIP("Chip", 0.60, 0.55, 0.45, 1.20),
	FLOP("Flop", 1.65, 1.20, 0.60, 0.80),
	PUTT("Putt", 0, 0, 1.00, 1.00);

	public final String displayName;
	public final double launchMul;
	public final double spinMul;
	/** Full-swing carry multiplier. */
	public final double distanceMul;
	/** Swing-meter sweet-spot multiplier (power shots are harder to time). */
	public final double windowMul;

	ShotType(String displayName, double launchMul, double spinMul, double distanceMul, double windowMul) {
		this.displayName = displayName;
		this.launchMul = launchMul;
		this.spinMul = spinMul;
		this.distanceMul = distanceMul;
		this.windowMul = windowMul;
	}

	public boolean allowedFor(Club club) {
		return switch (this) {
			case PUTT -> club.isPutter();
			case NORMAL -> !club.isPutter();
			case POWER -> club.category != Club.Category.PUTTER && club.category != Club.Category.WEDGE;
			case PUNCH -> club.category == Club.Category.IRON || club.category == Club.Category.HYBRID || club.category == Club.Category.WOOD;
			case CHIP -> club.category == Club.Category.IRON || club.category == Club.Category.WEDGE || club.category == Club.Category.HYBRID;
			case FLOP -> club == Club.SAND_WEDGE || club == Club.LOB_WEDGE || club == Club.GAP_WEDGE;
		};
	}

	/** The default shot for a club. */
	public static ShotType defaultFor(Club club) {
		return club.isPutter() ? PUTT : NORMAL;
	}

	/** Next shot type allowed for {@code club}, wrapping around. */
	public ShotType next(Club club) {
		ShotType[] values = values();
		for (int i = 1; i <= values.length; i++) {
			ShotType candidate = values[(ordinal() + i) % values.length];
			if (candidate.allowedFor(club)) {
				return candidate;
			}
		}
		return defaultFor(club);
	}

	public static ShotType byId(int id) {
		ShotType[] values = values();
		return id >= 0 && id < values.length ? values[id] : NORMAL;
	}
}
