package se.itssimple.realearth.terrain;

import se.itssimple.realearth.climate.MetreGrid;

/**
 * Real elevation to block Y, and back. The dimension is the tallest the game allows (Y -2032 to 2031),
 * and heights are drawn to the map's own scale ({@link #blocksPerMeter}): one block per metre where the
 * map is true to scale.
 *
 * Layout 1, new worlds: the sea is at Y -1800, with 232 blocks of ground under the lowlands rather than
 * 832. Land is true to scale wherever the mountains around it fit under the build limit (3,677 blocks
 * above the sea, keeping 150 to spare). Around a range that does not (the Himalaya, Alaska's, the Andes, and more of them
 * toward the poles, where Mercator draws everything larger), the whole range, valleys and summits
 * alike, is scaled down by one factor that brings its highest ground to 150 blocks under the limit: it
 * keeps its shape, only lower. The factor comes from the highest ground within 150 km (relief.bin, built
 * by tools/relief) and changes over a hundred kilometres or more, a slope too gentle to see. Ground that
 * still rises past it, higher than the grid knows, eases off into those last 150 blocks rather than being
 * cut flat. Water is true to scale to 100 blocks deep; below that the seabed eases off logarithmically so
 * the deepest still fits above bedrock.
 *
 * Layout 0, worlds made before: the sea is at Y -1200, and one block is one map metre between two knees
 * (by default 600 m of depth and 2,800 m of land). Beyond them the scale eases off logarithmically,
 * with a slope of exactly 1 at the knee so there is no kink, until Everest just fits under the build
 * limit and the Mariana Trench just fits above bedrock.
 */
public final class VerticalScale {
	public static final int MIN_Y = -2032;
	public static final int HEIGHT = 4064;
	public static final int MAX_Y = MIN_Y + HEIGHT - 1;
	/** Sea level of new worlds. */
	public static final int SEA_LEVEL_Y = -1800;
	/** Sea level of worlds made in layout 0. */
	public static final int LEGACY_SEA_LEVEL_Y = -1200;
	/** The layout new worlds get. */
	public static final int LAYOUT = 1;

	/** Highest and lowest points on Earth, rounded outwards. */
	private static final double EVEREST = 8900.0;
	private static final double MARIANA = -11000.0;
	/** Layout 1: water this many blocks deep is true to scale. */
	private static final double SHALLOWS = 100.0;
	/** Layout 1: the deepest water fitted above bedrock, in blocks: the Arctic's 5.5 km at Mercator's edge, at Stockholm's scale. */
	private static final double DEEPEST = 40_000.0;
	/** Layout 1: headroom over a region's highest ground. */
	private static final double MARGIN = 1.01;
	/** Layout 1: blocks under the limit kept for ground higher than relief.bin knows, eased to fit half as much again. */
	private static final double TOP = 150.0;

	/** Highest ground within 150 km, in metres; loaded with the first layout 1 world. */
	private static final class Relief {
		static final MetreGrid GRID = MetreGrid.load("/realearth/relief.bin", "the tallest ranges are eased instead of scaled whole");
	}

	private final int layout;
	private final int seaLevel;
	private final MetreGrid relief;
	/** Blocks from the sea to where a region's highest ground is brought, layout 1. */
	private final double room;
	private final double upperKnee;
	private final double upperScale;
	private final double lowerKnee;
	private final double lowerScale;

	/** Layout 0, as configured. */
	public VerticalScale(double compressAboveMeters, double compressBelowMeters) {
		this(0, compressAboveMeters, compressBelowMeters);
	}

	/** The knees are layout 0's; layout 1 uses {@code compressAboveMeters} only if relief.bin will not load. */
	public VerticalScale(int layout, double compressAboveMeters, double compressBelowMeters) {
		this.layout = layout >= 1 ? 1 : 0;
		this.seaLevel = this.layout == 1 ? SEA_LEVEL_Y : LEGACY_SEA_LEVEL_Y;
		this.relief = this.layout == 1 ? Relief.GRID : null;
		this.room = MAX_Y - 4 - TOP - seaLevel;
		this.upperKnee = relief != null ? room : Math.min(compressAboveMeters, MAX_Y - 40 - seaLevel);
		this.upperScale = relief != null ? solveScale(room / 2, TOP) : solveScale(EVEREST - upperKnee, (MAX_Y - 4) - (seaLevel + upperKnee));
		if (this.layout == 1) {
			this.lowerKnee = -SHALLOWS;
			this.lowerScale = solveScale(DEEPEST - SHALLOWS, (seaLevel + lowerKnee) - (MIN_Y + 8));
		} else {
			this.lowerKnee = Math.max(compressBelowMeters, MIN_Y + 40 - seaLevel);
			this.lowerScale = solveScale(lowerKnee - MARIANA, (seaLevel + lowerKnee) - (MIN_Y + 8));
		}
	}

	/** True for either layout's sea level: how Earth is told from other dimensions where only that is known. */
	public static boolean isEarthSeaLevel(int y) {
		return y == SEA_LEVEL_Y || y == LEGACY_SEA_LEVEL_Y;
	}

	public int layout() {
		return layout;
	}

	public int seaLevelY() {
		return seaLevel;
	}

	/**
	 * Blocks per metre of height at a place, where the map draws {@code horizontal} blocks per metre: the same, except
	 * around ranges scaled down to fit.
	 */
	public double blocksPerMeter(double latitude, double longitude, double horizontal) {
		if (relief == null) {
			return horizontal;
		}
		double highest = relief.metersAt(latitude, longitude) * horizontal * MARGIN;
		return highest > room ? horizontal * room / highest : horizontal;
	}

	/** Finds A such that A * ln(1 + span / A) == room: the curve that fits span metres into room blocks. */
	private static double solveScale(double span, double room) {
		if (room >= span) {
			return Double.POSITIVE_INFINITY;
		}
		double lo = 1e-3;
		double hi = 1e9;
		for (int i = 0; i < 200; i++) {
			double mid = Math.sqrt(lo * hi);
			if (mid * Math.log1p(span / mid) > room) {
				hi = mid;
			} else {
				lo = mid;
			}
		}
		return lo;
	}

	private static double ease(double over, double scale) {
		return Double.isInfinite(scale) ? over : scale * Math.log1p(over / scale);
	}

	private static double unease(double eased, double scale) {
		return Double.isInfinite(scale) ? eased : scale * Math.expm1(eased / scale);
	}

	/** Block Y of a height above the sea in blocks (metres times {@link #blocksPerMeter}). */
	public double toY(double elevation) {
		double y;
		if (elevation > upperKnee) {
			y = seaLevel + upperKnee + ease(elevation - upperKnee, upperScale);
		} else if (elevation < lowerKnee) {
			y = seaLevel + lowerKnee - ease(lowerKnee - elevation, lowerScale);
		} else {
			y = seaLevel + elevation;
		}
		return Math.max(MIN_Y + 6, Math.min(MAX_Y - 2, y));
	}

	/** The inverse of {@link #toY}: blocks above the sea. */
	public double toElevation(double y) {
		double rel = y - seaLevel;
		if (rel > upperKnee) {
			return upperKnee + unease(rel - upperKnee, upperScale);
		} else if (rel < lowerKnee) {
			return lowerKnee - unease(lowerKnee - rel, lowerScale);
		}
		return rel;
	}

	public double upperKnee() {
		return upperKnee;
	}
}
