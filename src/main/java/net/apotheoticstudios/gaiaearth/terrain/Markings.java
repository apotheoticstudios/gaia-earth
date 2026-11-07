package net.apotheoticstudios.gaiaearth.terrain;

/**
 * Road markings: what is painted where on a carriageway, from the road's class and whether it is one-way, and the
 * country's custom -- a centre line (yellow in the Americas, Norway and Finland, white elsewhere; solid on North
 * American major roads, else dashed), dashed lines between the lanes of a one-way road, edge lines on major roads
 * and motorways. Residential streets, service roads and paths are left unmarked, as most are. Lines are a block
 * wide, and only drawn where the road is wide enough in blocks to show asphalt beside them.
 */
public final class Markings {
	public static final byte NONE = 0;
	public static final byte WHITE = 1;
	public static final byte YELLOW = 2;
	/** A zebra crossing's bar: a solid white block. */
	public static final byte BAR = 3;
	/** Added to a paint where the line runs east-west (more along X than Z), so it is laid that way. */
	public static final byte ALONG_X = 4;

	/** A road's style, as bits: a centre line, dashed, yellow; edge lines; dashed lines between lanes. */
	static final byte CENTRE = 1;
	static final byte DASHED = 2;
	static final byte YELLOW_CENTRE = 4;
	static final byte EDGES = 8;
	static final byte LANES = 16;

	/** A dash and the gap after it, in metres (3 m and 6 m, as on most of the world's roads). */
	private static final double DASH = 3;
	private static final double PERIOD = 9;

	private Markings() {
	}

	/** The markings of a road of OpenMapTiles class {@code cls}, as {@link #at} reads them. */
	public static byte style(String cls, boolean oneway, boolean ramp, double latitude, double longitude) {
		if (cls == null) {
			return 0;
		}
		boolean americas = longitude < -30 && longitude > -170;
		boolean yellow = americas || norway(latitude, longitude) || finland(latitude, longitude);
		int major = switch (cls) {
			case "motorway", "trunk", "primary" -> 2;
			case "secondary", "tertiary" -> 1;
			default -> 0;
		};
		if (major == 0) {
			return 0;
		}
		byte edges = major == 2 ? EDGES : 0;
		if (ramp) {
			return edges;
		}
		if (oneway || cls.equals("motorway")) {
			return (byte) (LANES | edges);
		}
		boolean solid = americas && !cls.equals("tertiary");
		return (byte) (CENTRE | (solid ? 0 : DASHED) | (yellow ? YELLOW_CENTRE : 0) | edges);
	}

	/**
	 * The paint at a point of a road {@code widthMeters} wide in style {@code style}, {@code across} metres from its
	 * centreline (either side) and {@code along} metres along it, where a block is {@code 1 / perMeter} metres.
	 */
	public static byte at(byte style, double across, double along, double widthMeters, double perMeter) {
		double block = 1 / perMeter;
		double blocksWide = widthMeters * perMeter;
		double half = widthMeters / 2;
		double a = Math.abs(across);
		if ((style & EDGES) != 0 && blocksWide >= 6 && a > half - 1.5 * block && a <= half - 0.5 * block) {
			return WHITE;
		}
		if (blocksWide < 4) {
			return NONE;
		}
		boolean dash = Math.floorMod((long) Math.floor(along / DASH), (long) (PERIOD / DASH)) == 0;
		if ((style & CENTRE) != 0 && across > -0.5 * block && across <= 0.5 * block) {
			return (style & DASHED) != 0 && !dash ? NONE : (style & YELLOW_CENTRE) != 0 ? YELLOW : WHITE;
		}
		if ((style & LANES) != 0 && dash) {
			int lanes = Math.max(2, (int) Math.round(widthMeters / 3.5));
			for (int i = 1; i < lanes; i++) {
				double line = -half + i * widthMeters / lanes;
				if (across > line - 0.5 * block && across <= line + 0.5 * block) {
					return WHITE;
				}
			}
		}
		return NONE;
	}

	/** Norway's border with Sweden and Finland, roughly: latitude, and the longitude east of which Norway ends. */
	private static final double[][] NORWAY_EAST = {{57.9, 9.0}, {58.9, 10.5}, {59.05, 11.2}, {59.8, 11.8}, {61.0, 12.4}, {62.5, 12.2}, {63.5, 12.1},
		{64.5, 14.0}, {65.5, 14.5}, {66.5, 15.6}, {67.5, 16.6}, {68.3, 18.1}, {68.6, 20.2}, {69.0, 21.2}, {69.3, 28.5}, {71.3, 31.2}};

	/**
	 * The paint a marked crossing puts on its road ({@code markings}: OsmTrees.ZEBRA or LINES), at a point {@code along}
	 * metres from the crossing along the road and {@code across} metres from the road's centreline: zebra bars a block
	 * wide, alternating across the road and about 3 m deep along it; or a line across the road at each side of a crossing
	 * 3 m deep. WHITE here is a line across the road.
	 */
	public static byte crossing(byte markings, double along, double across, double widthMeters, double perMeter) {
		double block = 1 / perMeter;
		double half = Math.max(1.5, block);
		if (Math.abs(across) > widthMeters / 2) {
			return NONE;
		}
		if (markings == net.apotheoticstudios.gaiaearth.osm.OsmTrees.ZEBRA) {
			return Math.abs(along) <= half && Math.floorMod((long) Math.floor(across / block), 2L) == 0 ? BAR : NONE;
		}
		double a = Math.abs(along);
		return markings == net.apotheoticstudios.gaiaearth.osm.OsmTrees.LINES && a > half - block && a <= half ? WHITE : NONE;
	}

	// shortcut: rough borders, not a border map; good enough for the colour of a centre line, worth a border map if more depends on country.
	private static boolean norway(double lat, double lon) {
		if (lat < NORWAY_EAST[0][0] || lat > NORWAY_EAST[NORWAY_EAST.length - 1][0] || lon < 4.5) {
			return false;
		}
		for (int i = 1; i < NORWAY_EAST.length; i++) {
			double[] a = NORWAY_EAST[i - 1];
			double[] b = NORWAY_EAST[i];
			if (lat <= b[0]) {
				return lon < a[1] + (b[1] - a[1]) * (lat - a[0]) / (b[0] - a[0]);
			}
		}
		return false;
	}

	private static boolean finland(double lat, double lon) {
		return lat >= 59.7 && lat <= 70.1 && lon >= 20.6 && lon <= 31.6 && !(lat >= 65.5 && lon < 24) && !norway(lat, lon) && !(lat < 63.5 && lon < 21.3);
	}
}
