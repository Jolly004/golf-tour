package work.benwalker.golftour.physics;

/**
 * What the ball is touching. Each surface sets how the ball bounces and rolls on it and how it
 * affects the next shot played from it (the "lie").
 */
public enum Surface {
	//         name          bounce keep  roll  grab  lieDist lieSpin color
	TEE("Tee Box", 0.30, 0.60, 1.20, 0.55, 1.00, 1.00, 0x7FCB5E),
	FAIRWAY("Fairway", 0.30, 0.60, 1.20, 0.55, 1.00, 1.00, 0x6CC04A),
	FRINGE("Fringe", 0.24, 0.55, 0.50, 0.75, 0.98, 0.90, 0x8AD86A),
	GREEN("Green", 0.20, 0.55, 0.273, 1.00, 1.00, 1.00, 0x9BE07A),
	ROUGH("Rough", 0.18, 0.45, 2.00, 0.35, 0.88, 0.60, 0x3F8F2F),
	DEEP_ROUGH("Deep Rough", 0.12, 0.30, 4.00, 0.20, 0.72, 0.40, 0x2C6B22),
	BUNKER("Bunker", 0.05, 0.20, 9.00, 0.10, 0.80, 0.50, 0xE8D9A0),
	HARD("Cart Path", 0.55, 0.85, 0.45, 0.30, 0.95, 0.90, 0x9E9E9E),
	WATER("Water", 0, 0, 0, 0, 0, 0, 0x3F76E4),
	OUT("Out of Bounds", 0.18, 0.45, 2.00, 0.35, 0.88, 0.60, 0x1E4D18);

	/** Name shown on the HUD. */
	public final String displayName;
	/** Coefficient of restitution for the vertical part of a bounce. */
	public final double bounce;
	/** Fraction of the along-ground speed kept on a bounce (before spin). */
	public final double keep;
	/** Rolling deceleration in blocks/s^2 (green = stimp 11). */
	public final double rollDecel;
	/** How strongly backspin bites on landing (greens grab the most). */
	public final double spinGrab;
	/** Carry multiplier for shots played from this lie. */
	public final double lieDistance;
	/** Spin multiplier for shots played from this lie (grass between club and ball kills spin). */
	public final double lieSpin;
	/** Map and HUD colour (RGB). */
	public final int color;

	Surface(String displayName, double bounce, double keep, double rollDecel, double spinGrab, double lieDistance, double lieSpin, int color) {
		this.displayName = displayName;
		this.bounce = bounce;
		this.keep = keep;
		this.rollDecel = rollDecel;
		this.spinGrab = spinGrab;
		this.lieDistance = lieDistance;
		this.lieSpin = lieSpin;
		this.color = color;
	}

	public boolean isPuttingSurface() {
		return this == GREEN || this == FRINGE;
	}

	public static Surface byId(int id) {
		Surface[] values = values();
		return id >= 0 && id < values.length ? values[id] : FAIRWAY;
	}
}
