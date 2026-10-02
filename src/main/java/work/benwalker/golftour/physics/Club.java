package work.benwalker.golftour.physics;

/**
 * The 14-club bag. Carry distances are for a strong player with a perfect strike from the fairway, no
 * wind, landing on flat ground; launch angle and spin follow tour launch-monitor averages so each club
 * flies with its own shape (penetrating driver, towering wedges).
 */
public enum Club {
	//            name             short  category          carry launch  spin  forgiveness
	DRIVER("Driver", "DR", Category.DRIVER, 260, 10.9, 2700, 0.80),
	WOOD_3("3 Wood", "3W", Category.WOOD, 240, 9.5, 3600, 0.85),
	WOOD_5("5 Wood", "5W", Category.WOOD, 225, 9.8, 4300, 0.90),
	HYBRID_4("4 Hybrid", "4H", Category.HYBRID, 210, 10.4, 4400, 1.00),
	IRON_5("5 Iron", "5i", Category.IRON, 195, 12.1, 5300, 0.95),
	IRON_6("6 Iron", "6i", Category.IRON, 183, 14.1, 6200, 1.00),
	IRON_7("7 Iron", "7i", Category.IRON, 172, 16.3, 7000, 1.00),
	IRON_8("8 Iron", "8i", Category.IRON, 160, 18.1, 7900, 1.05),
	IRON_9("9 Iron", "9i", Category.IRON, 148, 20.4, 8600, 1.05),
	PITCHING_WEDGE("Pitching Wedge", "PW", Category.WEDGE, 136, 24.2, 9300, 1.10),
	GAP_WEDGE("Gap Wedge", "GW", Category.WEDGE, 122, 27.0, 9600, 1.10),
	SAND_WEDGE("Sand Wedge", "SW", Category.WEDGE, 106, 30.0, 10000, 1.10),
	LOB_WEDGE("Lob Wedge", "LW", Category.WEDGE, 90, 33.0, 10200, 1.05),
	PUTTER("Putter", "PT", Category.PUTTER, 0, 0, 0, 1.30);

	public enum Category {
		DRIVER, WOOD, HYBRID, IRON, WEDGE, PUTTER
	}

	public final String displayName;
	public final String shortName;
	public final Category category;
	/** Full-swing carry in yards. */
	public final double carryYards;
	public final double launchDegrees;
	public final double spinRpm;
	/** Scales the swing meter's sweet spot. */
	public final double forgiveness;

	Club(String displayName, String shortName, Category category, double carryYards, double launchDegrees, double spinRpm, double forgiveness) {
		this.displayName = displayName;
		this.shortName = shortName;
		this.category = category;
		this.carryYards = carryYards;
		this.launchDegrees = launchDegrees;
		this.spinRpm = spinRpm;
		this.forgiveness = forgiveness;
	}

	public boolean isPutter() {
		return category == Category.PUTTER;
	}

	public double carryBlocks() {
		return Units.fromYards(carryYards);
	}

	/** Item registry path, e.g. "pitching_wedge". */
	public String id() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	public static Club byId(int id) {
		Club[] values = values();
		return id >= 0 && id < values.length ? values[id] : IRON_7;
	}
}
