package se.itssimple.realearth.terrain;

import java.util.Arrays;
import java.util.List;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.osm.OsmWater;

/** Hydro-flattening of mapped water using the elevation raster as evidence. */
final class WaterSurfaceProfiles {
	static final byte LINE = 1;
	static final byte STANDING = 2;
	static final byte RIVER = 3;
	static final byte SEA = 4;
	private static final int COMPLETE_BASIN_SPAN = 40;
	private static final int CROSS_SECTION_REACH = 12;
	private static final int SHORT_REACH_SPAN = 24;
	private static final int MAX_REACH_CELLS = 96;
	private static final int LOCAL_PROFILE_REACH = 3;
	private static final short NO_LINK = Short.MIN_VALUE;
	private static final short AMBIGUOUS_LINK = Short.MIN_VALUE + 1;

	private WaterSurfaceProfiles() {
	}

	static byte kind(OsmWater.Area area) {
		return area.sea() ? SEA : area.river() ? RIVER : STANDING;
	}

	static byte wetMask(byte kind) {
		return kind == SEA ? (byte) 2 : kind == 0 ? (byte) 0 : (byte) 1;
	}

	static boolean sameSurface(byte a, byte b) {
		return a == b || flowing(a) && flowing(b);
	}

	/** Vector tiles use the canonical longitude, even when a processing halo wraps around it. */
	static double longitudeShift(double originX, double featureX) {
		return Math.rint((featureX - originX) / Mercator.SAMPLES_PER_AXIS) * Mercator.SAMPLES_PER_AXIS;
	}

	private static boolean flowing(byte kind) {
		return kind == LINE || kind == RIVER;
	}

	static void fit(float[] raw, float[] levels, byte[] kinds, List<OsmWater.Shapes> map, int span, double originX, double originY,
		int version) {
		boolean standing = false, river = false;
		for (byte kind : kinds) {
			standing |= kind == STANDING;
			river |= flowing(kind);
			if (standing && river) break;
		}
		if (!standing && !river) return;
		int[] queue = new int[kinds.length];
		if (standing) fitSmallBasins(raw, levels, kinds, span, queue);
		if (river) {
			fitRiverCrossSections(levels, kinds, map, span, originX, originY, queue, version);
			joinReceivingWater(levels, kinds, span, queue);
		}
	}

	/** Only flatten basins small enough for every touching terrain tile to see the whole basin. */
	private static void fitSmallBasins(float[] raw, float[] levels, byte[] kinds, int span, int[] queue) {
		boolean[] seen = new boolean[kinds.length];
		float[] observations = new float[COMPLETE_BASIN_SPAN * COMPLETE_BASIN_SPAN];
		for (int start = 0; start < kinds.length; start++) {
			if (kinds[start] != STANDING || seen[start]) continue;
			int size = 1;
			queue[0] = start;
			seen[start] = true;
			int left = span, top = span, right = 0, bottom = 0;
			for (int head = 0; head < size; head++) {
				int i = queue[head], x = i % span, y = i / span;
				left = Math.min(left, x);
				right = Math.max(right, x);
				top = Math.min(top, y);
				bottom = Math.max(bottom, y);
				if (x > 0) size = enqueue(kinds, seen, queue, size, i - 1);
				if (x + 1 < span) size = enqueue(kinds, seen, queue, size, i + 1);
				if (y > 0) size = enqueue(kinds, seen, queue, size, i - span);
				if (y + 1 < span) size = enqueue(kinds, seen, queue, size, i + span);
			}
			if (left == 0 || top == 0 || right == span - 1 || bottom == span - 1
				|| right - left >= COMPLETE_BASIN_SPAN || bottom - top >= COMPLETE_BASIN_SPAN) continue;
			int count = 0;
			for (int j = 0; j < size; j++) {
				int i = queue[j];
				float z = raw[i];
				if (kinds[i - 1] == STANDING && kinds[i + 1] == STANDING && kinds[i - span] == STANDING
					&& kinds[i + span] == STANDING && Math.abs(raw[i - 1] - z) <= 0.05f && Math.abs(raw[i + 1] - z) <= 0.05f
					&& Math.abs(raw[i - span] - z) <= 0.05f && Math.abs(raw[i + span] - z) <= 0.05f) {
					observations[count++] = z;
				}
			}
			boolean measuredFlat = count > 0;
			if (!measuredFlat) {
				for (int j = 0; j < size; j++) observations[count++] = levels[queue[j]];
			}
			Arrays.sort(observations, 0, count);
			float level = observations[measuredFlat ? count / 2 : count / 4];
			for (int j = 0; j < size; j++) levels[queue[j]] = level;
		}
	}

	private static int enqueue(byte[] kinds, boolean[] seen, int[] queue, int size, int i) {
		if (kinds[i] == STANDING && !seen[i]) {
			seen[i] = true;
			queue[size++] = i;
		}
		return size;
	}

	private static void fitRiverCrossSections(float[] levels, byte[] kinds, List<OsmWater.Shapes> map, int span,
		double originX, double originY, int[] queue, int version) {
		if (map.stream().allMatch(s -> s.lines().isEmpty())) return;
		boolean directedWater = version >= 2;
		float[] profile = new float[levels.length];
		byte[] distance = new byte[levels.length];
		Arrays.fill(distance, (byte) -1);
		int[] reachCells = directedWater ? new int[MAX_REACH_CELLS] : null;
		int[] groups = directedWater ? new int[MAX_REACH_CELLS] : null;
		float[] means = directedWater ? new float[MAX_REACH_CELLS] : null;
		short[] upstream = null;
		short[] downstream = null;
		int tail = 0;
		for (OsmWater.Shapes shapes : map) {
			for (OsmWater.Line line : shapes.lines()) {
				double[] p = line.points();
				if (p.length < 4) continue;
				double lineOrigin = originX + (directedWater ? longitudeShift(originX, p[0]) : 0);
				boolean followFlow = version >= 3 && line.naturalFlow();
				if (directedWater && line.naturalFlow()) {
					int count = collectShortReach(p, lineOrigin, originY, span, kinds, reachCells);
					int fitted = fitDownhill(levels, reachCells, count, groups, means);
					if (fitted > 0) {
						int station = 0;
						for (int group = 0; group < fitted; group++) {
							for (int j = 0; j < groups[group]; j++) {
								tail = seed(profile, distance, queue, tail, reachCells[station++], means[group]);
							}
						}
						if (!followFlow) continue;
					}
				}
				int previous = -1;
				for (int j = 0; j + 3 < p.length; j += 2) {
					double ax = p[j] - lineOrigin, ay = p[j + 1] - originY;
					double dx = p[j + 2] - p[j], dy = p[j + 3] - p[j + 1];
					if (Math.max(ax, ax + dx) < -1 || Math.min(ax, ax + dx) > span
						|| Math.max(ay, ay + dy) < -1 || Math.min(ay, ay + dy) > span) {
						previous = -1;
						continue;
					}
					int steps = (int) Math.ceil(Math.hypot(dx, dy) * 2);
					if (steps == 0) continue;
					for (int step = 0; step <= steps; step++) {
						double t = step / (double) steps;
						int i = cell(ax + t * dx, ay + t * dy, span);
						if (i < 0 || !flowing(kinds[i])) {
							previous = -1;
							continue;
						}
						if (followFlow) {
							if (upstream == null) {
								upstream = new short[levels.length];
								downstream = new short[levels.length];
							}
							if (downstream[i] == 0) downstream[i] = NO_LINK;
							if (previous >= 0 && previous != i) {
								link(downstream, previous, i);
								link(upstream, i, previous);
							}
							previous = i;
							continue;
						}
						double reach = 6.0 / steps; // three DEM samples along the mapped reach
						float centre = levels[i];
						float before = observation(levels, kinds, span, ax, ay, dx, dy, t - reach, centre);
						float after = observation(levels, kinds, span, ax, ay, dx, dy, t + reach, centre);
						// Reject a single DEM spike, for example a bridge deck crossing the channel.
						float height = Math.clamp(centre, Math.min(before, after), Math.max(before, after));
						tail = seed(profile, distance, queue, tail, i, height);
					}
				}
			}
		}
		if (upstream != null) {
			// Keep fitted short profiles and resolve the remaining stations with local context.
			for (int i = 0; i < levels.length; i++) {
				if (downstream[i] == 0 || distance[i] == 0) continue;
				float level = levels[i];
				int before = follow(upstream, downstream, i);
				int after = follow(downstream, upstream, i);
				if (before >= 0 && after >= 0) {
					float high = levels[before], low = levels[after];
					if (high >= low && (level > high || level < low)) {
						float fitted = (high + low) * 0.5f;
						if (Math.abs(fitted - level) <= 2f) level = fitted;
					}
				}
				tail = seed(profile, distance, queue, tail, i, level);
			}
		}
		for (int head = 0; head < tail; head++) {
			int i = queue[head], x = i % span, y = i / span;
			if (distance[i] >= CROSS_SECTION_REACH) continue;
			if (x > 0) tail = spread(kinds, profile, distance, queue, tail, i, i - 1);
			if (x + 1 < span) tail = spread(kinds, profile, distance, queue, tail, i, i + 1);
			if (y > 0) tail = spread(kinds, profile, distance, queue, tail, i, i - span);
			if (y + 1 < span) tail = spread(kinds, profile, distance, queue, tail, i, i + span);
		}
		for (int head = 0; head < tail; head++) {
			int i = queue[head];
			// Fade out at the edge of the finite cross-section search for very wide rivers.
			float keep = Math.clamp((CROSS_SECTION_REACH + 1 - distance[i]) / 4f, 0f, 1f);
			levels[i] += (profile[i] - levels[i]) * keep;
		}
	}

	/** Half-sample stepping links only adjacent cells, so offsets fit in shorts. */
	private static void link(short[] links, int from, int to) {
		short offset = (short) (to - from);
		short old = links[from];
		links[from] = old == 0 || old == NO_LINK || old == offset ? offset : AMBIGUOUS_LINK;
	}

	private static int follow(short[] links, short[] reverse, int from) {
		int cell = from;
		for (int j = 0; j < LOCAL_PROFILE_REACH; j++) {
			int offset = links[cell];
			if (offset == 0 || offset == NO_LINK || offset == AMBIGUOUS_LINK) return -1;
			int next = cell + offset;
			if (next == from || reverse[next] != -offset) return -1;
			cell = next;
		}
		return cell;
	}

	private static int seed(float[] profile, byte[] distance, int[] queue, int tail, int cell, float height) {
		if (distance[cell] == 0) {
			profile[cell] = Math.min(profile[cell], height);
		} else {
			profile[cell] = height;
			distance[cell] = 0;
			queue[tail++] = cell;
		}
		return tail;
	}

	/** A short continuous reach fits in the shared halo of adjacent tiles. */
	private static int collectShortReach(double[] points, double originX, double originY, int span, byte[] kinds, int[] cells) {
		double left = span, right = 0, top = span, bottom = 0;
		for (int j = 0; j < points.length; j += 2) {
			double x = points[j] - originX, y = points[j + 1] - originY;
			if (x < 1 || x > span - 2 || y < 1 || y > span - 2) return 0;
			left = Math.min(left, x);
			right = Math.max(right, x);
			top = Math.min(top, y);
			bottom = Math.max(bottom, y);
			if (right - left > SHORT_REACH_SPAN || bottom - top > SHORT_REACH_SPAN) return 0;
		}
		int count = 0;
		for (int j = 0; j + 3 < points.length; j += 2) {
			double x = points[j] - originX, y = points[j + 1] - originY;
			double dx = points[j + 2] - points[j], dy = points[j + 3] - points[j + 1];
			int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) * 2));
			for (int step = 0; step <= steps; step++) {
				double t = step / (double) steps;
				int cell = cell(x + dx * t, y + dy * t, span);
				if (cell < 0 || !flowing(kinds[cell])) return 0;
				if (count > 0 && cells[count - 1] == cell) continue;
				if (count == cells.length) return 0;
				cells[count++] = cell;
			}
		}
		return count > 2 && cells[0] != cells[count - 1] ? count : 0;
	}

	/** Pool successive uphill observations, retaining downhill drops and refusing large changes. */
	private static int fitDownhill(float[] levels, int[] cells, int count, int[] groups, float[] means) {
		if (count == 0 || levels[cells[count - 1]] > levels[cells[0]] + 0.5f) return 0;
		int groupCount = 0;
		for (int j = 0; j < count; j++) {
			means[groupCount] = levels[cells[j]];
			groups[groupCount++] = 1;
			while (groupCount > 1 && means[groupCount - 2] < means[groupCount - 1]) {
				int end = --groupCount;
				int before = end - 1;
				means[before] = (means[before] * groups[before] + means[end] * groups[end]) / (groups[before] + groups[end]);
				groups[before] += groups[end];
			}
		}
		if (groupCount == count) return 0;
		int station = 0;
		for (int group = 0; group < groupCount; group++) {
			for (int j = 0; j < groups[group]; j++) {
				if (Math.abs(levels[cells[station++]] - means[group]) > 2f) return 0;
			}
		}
		return groupCount;
	}

	private static int spread(byte[] kinds, float[] profile, byte[] distance, int[] queue, int tail, int from, int to) {
		if (distance[to] < 0 && flowing(kinds[to])) {
			distance[to] = (byte) (distance[from] + 1);
			profile[to] = profile[from];
			queue[tail++] = to;
		} else if (distance[to] == distance[from] + 1) {
			profile[to] = Math.min(profile[to], profile[from]);
		}
		return tail;
	}

	/** Match a neighbouring lake or sea over a short approach when the DEM agrees they meet. */
	private static void joinReceivingWater(float[] levels, byte[] kinds, int span, int[] queue) {
		float[] outlet = new float[levels.length];
		byte[] distance = new byte[levels.length];
		Arrays.fill(distance, (byte) -1);
		int tail = 0;
		for (int y = 1; y < span - 1; y++) {
			for (int x = 1; x < span - 1; x++) {
				int i = y * span + x;
				if (!flowing(kinds[i])) continue;
				float receiving = Math.min(Math.min(receivingLevel(levels, kinds, i - 1), receivingLevel(levels, kinds, i + 1)),
					Math.min(receivingLevel(levels, kinds, i - span), receivingLevel(levels, kinds, i + span)));
				// Larger drops can be real falls or locks. Leave those to the local profile.
				if (Math.abs(levels[i] - receiving) <= 2) {
					outlet[i] = receiving;
					distance[i] = 0;
					queue[tail++] = i;
				}
			}
		}
		for (int head = 0; head < tail; head++) {
			int i = queue[head], x = i % span, y = i / span;
			if (distance[i] == 4) continue;
			if (x > 0) tail = spread(kinds, outlet, distance, queue, tail, i, i - 1);
			if (x + 1 < span) tail = spread(kinds, outlet, distance, queue, tail, i, i + 1);
			if (y > 0) tail = spread(kinds, outlet, distance, queue, tail, i, i - span);
			if (y + 1 < span) tail = spread(kinds, outlet, distance, queue, tail, i, i + span);
		}
		for (int j = 0; j < tail; j++) {
			int i = queue[j];
			if (Math.abs(levels[i] - outlet[i]) <= 2) levels[i] += (outlet[i] - levels[i]) * (1 - distance[i] / 5f);
		}
	}

	private static float receivingLevel(float[] levels, byte[] kinds, int i) {
		return kinds[i] == STANDING || kinds[i] == SEA ? levels[i] : Float.POSITIVE_INFINITY;
	}

	private static float observation(float[] levels, byte[] kinds, int span, double ax, double ay, double dx, double dy,
		double t, float fallback) {
		if (t < 0 || t > 1) return fallback;
		int i = cell(ax + t * dx, ay + t * dy, span);
		return i >= 0 && flowing(kinds[i]) ? levels[i] : fallback;
	}

	private static int cell(double x, double y, int span) {
		int ix = (int) Math.round(x), iy = (int) Math.round(y);
		return ix >= 0 && ix < span && iy >= 0 && iy < span ? iy * span + ix : -1;
	}
}
