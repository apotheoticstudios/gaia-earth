package se.itssimple.realearth.osm;

/**
 * Kinds of way drawn on Earth, from OpenMapTiles' {@code transportation} classes, in drawing
 * priority: where two overlap, the higher kind wins (a railway crosses a road, a road a path).
 */
public final class Ways {
	public static final byte NONE = 0;
	/** Footways, cycleways, bridleways, steps: dirt paths. */
	public static final byte PATH = 1;
	/** Farm and forest tracks: coarse dirt and gravel. */
	public static final byte TRACK = 2;
	/** Driveways, parking aisles, alleys. */
	public static final byte SERVICE = 3;
	/** Residential streets and tertiary roads. */
	public static final byte MINOR = 4;
	/** Primary and secondary roads, trunk roads. */
	public static final byte MAJOR = 5;
	public static final byte MOTORWAY = 6;
	/** The ballast either side of a track. */
	public static final byte RAIL_BED = 7;
	/** The track itself: one line of rail blocks. */
	public static final byte RAIL = 8;
	/**
	 * A tram or light-rail line, as read from the map; drawn exactly like a railway (as RAIL), but towns
	 * may build right up to it.
	 */
	public static final byte TRAM = 9;

	private Ways() {
	}

	public static boolean isRail(byte kind) {
		return kind == RAIL || kind == RAIL_BED;
	}

	public static boolean isPaved(byte kind) {
		return kind >= SERVICE && kind <= MOTORWAY;
	}

	/** The kind for an OpenMapTiles class and subclass, or NONE for ways not drawn (ferries, lifts, construction). */
	public static byte kind(String cls, String subclass) {
		if (cls == null) {
			return NONE;
		}
		return switch (cls) {
			case "motorway" -> MOTORWAY;
			case "trunk", "primary", "secondary" -> MAJOR;
			case "tertiary", "minor", "busway" -> MINOR;
			case "service", "raceway" -> SERVICE;
			case "track" -> TRACK;
			case "path" -> PATH;
			case "rail" -> RAIL;
			// Trams and light rail run on the surface; subways and monorails don't (or not on the ground).
			case "transit" -> "tram".equals(subclass) || "light_rail".equals(subclass) ? TRAM : NONE;
			default -> NONE;
		};
	}

	/**
	 * Width of a way where it is, in metres: its lanes at the region's lane width (North America's wide lanes and
	 * streets with parking either side, Japan's narrow ones, Australia's and New Zealand's in between), a one-way
	 * street narrower than a two-way one. Each carriageway of a motorway or dual carriageway is its own line in the map.
	 */
	public static double widthMeters(byte kind, String cls, boolean oneway, double latitude, double longitude) {
		boolean america = latitude > 14 && latitude < 72 && longitude > -170 && longitude < -52;
		boolean japan = latitude > 24 && latitude < 46 && longitude > 122 && longitude < 146;
		boolean oceania = latitude > -48 && latitude < -10 && longitude > 112 && longitude < 179;
		double lane = america ? 3.65 : japan ? 3.0 : oceania ? 3.5 : 3.25;
		double street = america ? 10.0 : japan ? 4.5 : oceania ? 7.5 : 5.5;
		return switch (kind) {
			case MOTORWAY -> 2 * lane + 3.5; // two lanes, a hard shoulder and the inner verge
			case MAJOR -> "secondary".equals(cls) ? 2 * lane + 0.5 : 2 * lane + 1.0;
			case MINOR -> "tertiary".equals(cls) ? 2 * lane : oneway ? street * 0.7 : street;
			case SERVICE -> america ? 4.5 : 3.5;
			default -> widthMeters(kind, cls);
		};
	}

	/** Typical width of a way, in metres. Each carriageway of a motorway is its own line in the map. */
	public static double widthMeters(byte kind, String cls) {
		return switch (kind) {
			case MOTORWAY -> 10.0;
			case MAJOR -> "secondary".equals(cls) ? 7.0 : 8.0;
			case MINOR -> "tertiary".equals(cls) ? 6.5 : 5.0;
			case SERVICE -> 3.5;
			case TRACK -> 3.0;
			case PATH -> 1.6;
			case RAIL, TRAM -> 3.5;
			default -> 0.0;
		};
	}
}
