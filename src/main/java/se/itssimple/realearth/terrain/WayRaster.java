package se.itssimple.realearth.terrain;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.geo.Projection;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.osm.Ways;

/**
 * Draws roads, tracks, paths and railways into a chunk's terrain.
 *
 * A column within half a way's width of its centreline becomes that way, the higher kind winning where
 * ways overlap. Its ground is levelled to the height of the nearest point on the centreline, so roads
 * lie flat across instead of tilting with the hillside -- unless that would cut or fill more than a
 * few metres, where the road simply follows the ground. Railway tracks are traced block by block, so
 * they form one continuous line of rails, on a bed of ballast.
 *
 * Bridges are laid on their run's deck (see {@link Bridges}): a deck where it stands a few blocks clear
 * of what is under it, and nearer the ground a road at the deck's own height, on filled ground, so a
 * bridge has no step where it leaves the ground. Roads meeting a deck end held above the ground ramp
 * up to it. Tunnels are not drawn at all: they are inside the ground.
 */
final class WayRaster {
	/** Most a road levels the ground, up or down. Steeper than this it follows the slope. */
	private static final double MAX_LEVELLING_METERS = 3.0;
	/** A bridge column is a deck from this many blocks above the ground; lower, a road on filled ground. */
	private static final double DECK_MIN_BLOCKS = 3;
	/** Half the width of a railway's ballast bed. */
	private static final double RAIL_BED_HALF_METERS = 1.75;

	private WayRaster() {
	}

	static void apply(TerrainField field, ChunkTerrain t, Map<Long, ProcessedTile> tiles, double[] sx, double[] sy, boolean[] onGlobe,
		List<OsmWater.Way> ways, List<se.itssimple.realearth.osm.OsmRoads.Segment> segments, Bridges.Network bridges, int chunkX, int chunkZ,
		boolean roads, boolean rails, Map<Long, Integer> railEnds) {
		int n = ChunkTerrain.SPAN;
		int centre = ChunkTerrain.index(8, 8);
		double minSX = Double.MAX_VALUE;
		double minSY = Double.MAX_VALUE;
		double maxSX = -Double.MAX_VALUE;
		double maxSY = -Double.MAX_VALUE;
		for (int k = 0; k < n * n; k++) {
			if (onGlobe[k]) {
				minSX = Math.min(minSX, sx[k]);
				maxSX = Math.max(maxSX, sx[k]);
				minSY = Math.min(minSY, sy[k]);
				maxSY = Math.max(maxSY, sy[k]);
			}
		}
		if (minSX > maxSX || maxSX - minSX > 1000) {
			return; // off the globe, or straddling the antimeridian
		}
		double lat = t.latitude[centre];
		double spacing = Mercator.sampleSpacingMeters(lat);
		double perMeter = field.projection().blocksPerMeter(lat);

		double[] target = new double[n * n];
		// Where the target may fill the ground further than levelling would: under a bridge or a ramp.
		boolean[] raised = new boolean[n * n];
		double[] deck = new double[n * n];
		Arrays.fill(target, Double.NaN);
		Arrays.fill(deck, Double.NaN);
		double[] near = new double[7];

		// Road bridges side by side, their edges overlapping: a column goes to the one whose centreline is
		// clearly the nearer, whatever their kinds, so each keeps its own deck down its middle. (Where they
		// cross, the higher kind still wins.)
		double[] bridgeNear = new double[n * n];
		double[] bridgeDx = new double[n * n];
		double[] bridgeDz = new double[n * n];
		Arrays.fill(bridgeNear, Double.MAX_VALUE);
		for (OsmWater.Way way : ways) {
			Bridges.Run run = bridges.runOf.get(way);
			double half = half(way, perMeter) / spacing;
			if (!roads || !way.bridge() || run == null || isRail(way) || !reaches(way, half + 1, minSX, minSY, maxSX, maxSY)) {
				continue;
			}
			for (int k = 0; k < n * n; k++) {
				if (onGlobe[k] && covers(way, run, t, k, sx[k], sy[k], half, near) && near[0] < bridgeNear[k]) {
					bridgeNear[k] = near[0];
					bridgeDx[k] = near[5];
					bridgeDz[k] = near[6];
				}
			}
		}
		double clearlyNearer = 0.5 / perMeter / spacing;
		// The way each column is drawn from, and where on it the column lies: metres across (signed) and along.
		OsmWater.Way[] drawnFrom = new OsmWater.Way[n * n];
		double[] across = new double[n * n];
		double[] along = new double[n * n];
		boolean[] alongX = new boolean[n * n];
		double[] dirX = new double[n * n];
		double[] dirZ = new double[n * n];

		for (OsmWater.Way way : ways) {
			boolean rail = isRail(way);
			if (rail ? !rails : !roads) {
				continue;
			}
			double half = half(way, perMeter);
			if (!reaches(way, half / spacing + 1, minSX, minSY, maxSX, maxSY)) {
				continue;
			}
			byte kind = rail ? Ways.RAIL_BED : way.kind();
			Bridges.Run run = way.bridge() ? bridges.runOf.get(way) : null;
			if (way.bridge() && run == null) {
				continue; // a neighbouring map tile's copy of a piece of bridge (see Bridges.runs): that tile lays it
			}
			double[] wp = way.points();
			double rampStart = way.bridge() ? Double.NaN : bridges.anchors.getOrDefault(Bridges.node(wp[0], wp[1]), Double.NaN);
			double rampEnd = way.bridge() ? Double.NaN : bridges.anchors.getOrDefault(Bridges.node(wp[wp.length - 2], wp[wp.length - 1]), Double.NaN);
			for (int k = 0; k < n * n; k++) {
				// A deck competes with decks only: the way on the ground beneath it is no reason to leave a hole.
				if (!onGlobe[k] || (way.bridge() ? kind < t.deckWay[k] : kind < t.way[k] && Double.isNaN(deck[k]))) {
					continue;
				}
				if (way.bridge() ? !covers(way, run, t, k, sx[k], sy[k], half / spacing, near) : !nearest(way.points(), sx[k], sy[k], near, half / spacing)) {
					continue;
				}
				if (way.bridge() && !rail && bridgeNear[k] < near[0] - clearlyNearer && Math.abs(bridgeDx[k] * near[5] + bridgeDz[k] * near[6]) > 0.7) {
					continue; // another bridge alongside, its centreline the nearer
				}
				double h = TerrainField.splineHeight(tiles, near[1], near[2]);
				boolean lifted = false;
				if (way.bridge()) {
					double d = run.heightOnWay(way, near[3], near[4]);
					if (!Double.isNaN(d)) {
						if (!Float.isNaN(t.waterMeters[k]) || (d - t.groundMeters[k]) * perMeter >= DECK_MIN_BLOCKS) {
							if (kind >= t.deckWay[k]) {
								deck[k] = d;
								t.deckWay[k] = kind;
								t.surveyedDeck[k] = run.facts != null && !run.facts.osm().startsWith("map:"); // a map outline's isn't
							}
							continue;
						}
						h = d;
						lifted = true;
					}
				} else if (!Double.isNaN(rampStart) || !Double.isNaN(rampEnd)) {
					double fromStart = near[3] * spacing;
					double fromEnd = (near[4] - near[3]) * spacing;
					double cone = Math.max(Double.isNaN(rampStart) ? Double.NEGATIVE_INFINITY : rampStart - Bridges.GRADIENT * fromStart,
						Double.isNaN(rampEnd) ? Double.NEGATIVE_INFINITY : rampEnd - Bridges.GRADIENT * fromEnd);
					if (cone > h) {
						h = cone;
						lifted = true;
					}
				}
				// The higher kind wins; between two of a kind, the higher surface, so a ramp isn't buried
				// by the street it joins.
				if (kind > t.way[k] || kind == t.way[k] && !(h <= target[k])) {
					t.way[k] = kind;
					target[k] = h;
					raised[k] = lifted;
					drawnFrom[k] = way;
					t.surface[k] = way.surface(); // an overlay's surveyed surface, drawn after, wins
					// Where its pavements are laid (the loop below): the same street, on built-up land.
					t.kerb[k] = way.sides() != 0 && (t.cover[k] & 0xFF) == se.itssimple.realearth.landcover.WorldCover.BUILT;
					t.grade[k] = lifted ? 1f : grade(tiles, near, spacing);
					double side = (sx[k] - near[1]) * near[6] - (sy[k] - near[2]) * near[5];
					across[k] = (side < 0 ? -near[0] : near[0]) * spacing;
					along[k] = near[3] * spacing;
					alongX[k] = Math.abs(near[5]) >= Math.abs(near[6]);
					dirX[k] = near[5];
					dirZ[k] = near[6];
				}
			}
			if (rail) {
				// The track's heights as the way's: its deck on a bridge, the ground (and ramps) elsewhere.
				Bridges.Run railRun = run;
				double railRampStart = rampStart;
				double railRampEnd = rampEnd;
				double[] q = new double[7];
				RailTracks.trace(field, t, way, chunkX, chunkZ, deck, false, (px, py) -> {
					nearest(way.points(), px, py, q);
					double h = TerrainField.splineHeight(tiles, q[1], q[2]);
					if (way.bridge()) {
						double d = railRun == null ? Double.NaN : railRun.heightOnWay(way, q[3], q[4]);
						return Double.isNaN(d) ? h : d;
					}
					double fromStart = q[3] * spacing;
					double fromEnd = (q[4] - q[3]) * spacing;
					double cone = Math.max(Double.isNaN(railRampStart) ? Double.NEGATIVE_INFINITY : railRampStart - Bridges.GRADIENT * fromStart,
						Double.isNaN(railRampEnd) ? Double.NEGATIVE_INFINITY : railRampEnd - Bridges.GRADIENT * fromEnd);
					return Math.max(h, cone);
				}, railEnds);
			}
		}
		if (rails) {
			RailTracks.joinEnds(t);
		}
		// Road version 7: a bridge the overlay outlines is as wide as its outline: its deck fills it where it stands clear, and
		// nearer the ground (at its ends) its surface does, on filled ground at the deck's height.
		for (Bridges.Run run : new java.util.LinkedHashSet<>(bridges.runOf.values())) {
			if (!roads || run.rail || run.facts == null || run.main != run || run.facts.maxX() < minSX || run.facts.minX() > maxSX || run.facts.maxY() < minSY || run.facts.minY() > maxSY) {
				continue;
			}
			for (int k = 0; k < n * n; k++) {
				if (!onGlobe[k] || !Double.isNaN(deck[k]) || !BuildingPlan.contains(run.facts.rings(), sx[k], sy[k])) {
					continue;
				}
				nearest(run.points, sx[k], sy[k], near);
				double d = run.heightAt(near[3]);
				if (Double.isNaN(d)) {
					continue;
				}
				if (!Float.isNaN(t.waterMeters[k]) || (d - t.groundMeters[k]) * perMeter >= DECK_MIN_BLOCKS) {
					deck[k] = d;
					t.deckWay[k] = run.kind;
					t.surveyedDeck[k] = !run.facts.osm().startsWith("map:");
				} else if (t.way[k] == Ways.NONE && d > t.groundMeters[k]) {
					target[k] = d;
					raised[k] = true;
					t.way[k] = run.kind;
					t.grade[k] = 1f;
				}
			}
		}
		// Road version 7: where an overlay surveys a bridge's deck and measures its top (the BGT and AHN, in the Netherlands), the
		// deck is there, at its measured top by each column (Bridges.deckTop): a deck over the water, the surface it carries raised to it
		// nearer the ground. So a bridge is as wide as surveyed, also where the map carries its street over without calling it
		// a bridge; carrying the way that crosses it, else the way at its ends, else (the map has none) a path.
		for (se.itssimple.realearth.osm.OsmDetail.BridgePart p : !roads || bridges.overlay == null ? List.<se.itssimple.realearth.osm.OsmDetail.BridgePart>of()
			: bridges.overlay.parts()) {
			if (!p.kind().equals("deck") || p.maxX() < minSX || p.minX() > maxSX || p.maxY() < minSY || p.minY() > maxSY) {
				continue;
			}
			List<double[]> tops = bridges.overlay.heights().stream().filter(h -> h[0] >= p.minX() && h[0] <= p.maxX() && h[1] >= p.minY() && h[1] <= p.maxY()
				&& BuildingPlan.contains(p.rings(), h[0], h[1])).toList();
			if (tops.isEmpty()) {
				continue;
			}
			byte carried = Ways.NONE;
			byte beside = Ways.NONE;
			double reach = 3 / spacing;
			// Track crossing it is the railway's to lay (RailTracks); the rest of the deck carries the roads and paths on it.
			for (int k = 0; k < n * n; k++) {
				byte here = (byte) Math.max(Ways.isRail(t.way[k]) ? 0 : t.way[k], Ways.isRail(t.deckWay[k]) ? 0 : t.deckWay[k]);
				if (onGlobe[k] && BuildingPlan.contains(p.rings(), sx[k], sy[k])) {
					carried = (byte) Math.max(carried, here);
				} else if (onGlobe[k] && sx[k] >= p.minX() - reach && sx[k] <= p.maxX() + reach && sy[k] >= p.minY() - reach && sy[k] <= p.maxY() + reach) {
					beside = (byte) Math.max(beside, here);
				}
			}
			carried = carried != Ways.NONE ? carried : beside != Ways.NONE ? beside : Ways.PATH;
			for (int k = 0; k < n * n; k++) {
				if (!onGlobe[k] || !Double.isNaN(deck[k]) || Ways.isRail(t.way[k]) || !BuildingPlan.contains(p.rings(), sx[k], sy[k])) {
					continue;
				}
				double d = Bridges.deckTop(tops, sx[k], sy[k], 3 / spacing);
				if (!Float.isNaN(t.waterMeters[k]) || (d - t.groundMeters[k]) * perMeter >= DECK_MIN_BLOCKS) {
					deck[k] = d;
					t.deckWay[k] = t.way[k] != Ways.NONE ? t.way[k] : carried;
					t.surveyedDeck[k] = true;
				} else if (d > t.groundMeters[k]) {
					target[k] = d;
					raised[k] = true;
					t.way[k] = t.way[k] != Ways.NONE ? t.way[k] : carried;
					t.grade[k] = 1f;
				}
			}
		}
		// Pavements either side of town streets (built-up land only), past a grass verge where the region has one, at the
		// street's height; where the map has its own footway there, it stays.
		for (OsmWater.Way way : ways) {
			if (!roads || way.sides() == 0) {
				continue;
			}
			double kerb = half(way, perMeter) + Ways.vergeMeters(way.sides());
			double outer = kerb + Ways.pavementMeters(way.sides());
			if (!reaches(way, outer / spacing + 1, minSX, minSY, maxSX, maxSY)) {
				continue;
			}
			for (int k = 0; k < n * n; k++) {
				if (!onGlobe[k] || t.way[k] != Ways.NONE || (t.cover[k] & 0xFF) != se.itssimple.realearth.landcover.WorldCover.BUILT
					|| !Float.isNaN(t.waterMeters[k]) || t.deckWay[k] != Ways.NONE || t.tunnel[k] != 0) {
					continue;
				}
				nearest(way.points(), sx[k], sy[k], near);
				double d = near[0] * spacing;
				if (d > kerb && d <= outer) {
					t.way[k] = Ways.PATH;
					t.surface[k] = (byte) (se.itssimple.realearth.osm.OsmDetail.FOOTWAY * 8 + se.itssimple.realearth.osm.OsmDetail.TILES);
					target[k] = TerrainField.splineHeight(tiles, near[1], near[2]);
					t.grade[k] = grade(tiles, near, spacing);
				}
			}
		}
		// What the map says a way is paved with beyond paved or unpaved, from the nearest road segment of its kind
		// (OsmRoads) within its width and a metre and a half; steps stay steps.
		double[] q = new double[7];
		for (int k = 0; k < n * n && !segments.isEmpty(); k++) {
			OsmWater.Way w = drawnFrom[k];
			if (w == null || t.way[k] != w.kind() || w.surface() / 8 == se.itssimple.realearth.osm.OsmDetail.STEPS) {
				continue;
			}
			double reach = (w.widthMeters() / 2 + 1.5) / spacing;
			double best = reach;
			for (se.itssimple.realearth.osm.OsmRoads.Segment s : segments) {
				if (s.path() != (w.kind() == Ways.PATH) || sx[k] < s.minX() - best || sx[k] > s.maxX() + best || sy[k] < s.minY() - best
					|| sy[k] > s.maxY() + best) {
					continue;
				}
				nearest(s.points(), sx[k], sy[k], q);
				if (q[0] < best) {
					best = q[0];
					t.surface[k] = s.surface();
				}
			}
		}
		for (int k = 0; k < n * n; k++) {
			OsmWater.Way w = drawnFrom[k];
			if (w != null && w.markings() != 0 && t.way[k] == w.kind()) {
				byte paint = Markings.at(w.markings(), across[k], along[k], w.widthMeters(), perMeter);
				t.marking[k] = paint != Markings.NONE && alongX[k] ? (byte) (paint | Markings.ALONG_X) : paint;
			}
		}
		// Marked crossings, on the road each lies on: a column's road must run through the crossing's own spot.
		for (ChunkTerrain.Crossing c : t.crossings) {
			for (int k = 0; k < n * n; k++) {
				OsmWater.Way w = drawnFrom[k];
				if (w == null || !Ways.isPaved(w.kind()) || t.way[k] != w.kind()) {
					continue;
				}
				double dx = (chunkX * 16 + k % n - 1 - c.x()) / perMeter;
				double dz = (chunkZ * 16 + k / n - 1 - c.z()) / perMeter;
				double alongCrossing = dx * dirX[k] + dz * dirZ[k];
				double acrossCrossing = dx * dirZ[k] - dz * dirX[k];
				if (Math.abs(across[k] - acrossCrossing) > 2 || Math.abs(alongCrossing) > 4) {
					continue;
				}
				byte paint = Markings.crossing(c.markings(), alongCrossing, across[k], w.widthMeters(), perMeter);
				if (paint != Markings.NONE) {
					// A line across the road lies at right angles to it; a bar is solid.
					t.marking[k] = paint == Markings.WHITE && !alongX[k] ? (byte) (paint | Markings.ALONG_X) : paint;
				}
			}
		}

		for (int k = 0; k < n * n; k++) {
			// A deck and a way on the ground in one column, too close for one to pass under the other, are
			// the same surface -- a twin carriageway, or an approach overlapping its bridge's end: one
			// road, at the deck's height.
			if (!Double.isNaN(deck[k]) && t.way[k] != Ways.NONE && !Double.isNaN(target[k]) && Float.isNaN(t.waterMeters[k])
				&& (deck[k] - target[k]) * perMeter < DECK_MIN_BLOCKS) {
				target[k] = Math.max(deck[k], target[k]);
				raised[k] = true;
				t.grade[k] = 1f;
				t.way[k] = (byte) Math.max(t.way[k], t.deckWay[k]);
				t.deckWay[k] = Ways.NONE;
				deck[k] = Double.NaN;
			}
			if (!Double.isNaN(deck[k])) {
				t.deckMeters[k] = (float) deck[k];
			}
			if (t.way[k] == Ways.NONE) {
				continue;
			}
			if (!Float.isNaN(t.waterMeters[k]) && !Double.isNaN(deck[k])) {
				t.way[k] = Ways.NONE; // under a deck, over water: the deck is the road
				continue;
			}
			if (!Float.isNaN(t.waterMeters[k])) {
				// A way the map draws across the water's edge without calling it a bridge -- a quay, a
				// causeway, an abutment the water polygon overlaps -- is built up out of the water, so
				// the road doesn't break off at the shore.
				double causeway = Math.max(Double.isNaN(target[k]) ? t.waterMeters[k] : target[k], t.waterMeters[k] + 1 / perMeter);
				t.groundMeters[k] = (float) causeway;
				t.waterMeters[k] = Float.NaN;
				t.lake[k] = false;
				continue;
			}
			if (!Double.isNaN(target[k]) && (raised[k] && target[k] >= t.groundMeters[k] || Math.abs(target[k] - t.groundMeters[k]) <= MAX_LEVELLING_METERS)) {
				t.groundMeters[k] = (float) target[k];
			}
		}
	}

	/** The ground's slope along a way at its nearest point to a column ({@link #nearest}'s out), metres a metre over 8 m; 0 off the tiles. */
	private static float grade(Map<Long, ProcessedTile> tiles, double[] near, double spacing) {
		double d = 4 / spacing;
		double ahead = TerrainField.splineHeight(tiles, near[1] + near[5] * d, near[2] + near[6] * d);
		double behind = TerrainField.splineHeight(tiles, near[1] - near[5] * d, near[2] - near[6] * d);
		return Double.isNaN(ahead) || Double.isNaN(behind) ? 0f : (float) (Math.abs(ahead - behind) / 8);
	}

	private static boolean isRail(OsmWater.Way way) {
		return way.kind() == Ways.RAIL || way.kind() == Ways.TRAM;
	}

	/** Half a way's width in metres: at least a block wide, wherever the map is shrunk. */
	private static double half(OsmWater.Way way, double perMeter) {
		return isRail(way) ? Math.max(RAIL_BED_HALF_METERS, 1.5 / perMeter) : Math.max(way.widthMeters() / 2, 0.55 / perMeter);
	}

	/** Whether a way's box comes within {@code margin} samples of the chunk's. */
	private static boolean reaches(OsmWater.Way way, double margin, double minSX, double minSY, double maxSX, double maxSY) {
		return way.maxX() >= minSX - margin && way.minX() <= maxSX + margin && way.maxY() >= minSY - margin && way.minY() <= maxSY + margin;
	}

	/**
	 * Whether a bridge way is laid on column k, {@code half} samples either side of its centreline (its
	 * nearest point into {@code near}). Past the run's own ends the bridge stops: what lies beyond is the
	 * road it joins, ramping on from there, not a cap of deck sticking out at the deck's height.
	 */
	private static boolean covers(OsmWater.Way way, Bridges.Run run, ChunkTerrain t, int k, double x, double y, double half, double[] near) {
		if (!nearest(way.points(), x, y, near, half)) {
			return false;
		}
		double s = run.positionOnWay(way, near[3], near[4]);
		return Double.isNaN(s) || s > 1e-6 && s < run.length() - 1e-6 || !Float.isNaN(t.waterMeters[k]);
	}

	/** {@link #nearest}, and whether the point lies within {@code half} of the line. */
	private static boolean nearest(double[] p, double x, double y, double[] out, double half) {
		nearest(p, x, y, out);
		return out[0] <= half;
	}

	/**
	 * Nearest point of a polyline to (x, y): out[0] distance, out[1..2] the point, out[3] distance
	 * along the line to it, out[4] the line's length, out[5..6] the direction of the line there. All in
	 * sample units.
	 */
	private static void nearest(double[] p, double x, double y, double[] out) {
		double best = Double.MAX_VALUE;
		double length = 0;
		for (int i = 0; i + 3 < p.length; i += 2) {
			double ax = p[i];
			double ay = p[i + 1];
			double dx = p[i + 2] - ax;
			double dy = p[i + 3] - ay;
			double segment = Math.sqrt(dx * dx + dy * dy);
			double u = segment > 0 ? Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (segment * segment))) : 0;
			double qx = ax + u * dx;
			double qy = ay + u * dy;
			double d = Math.hypot(x - qx, y - qy);
			if (d < best) {
				best = d;
				out[1] = qx;
				out[2] = qy;
				out[3] = length + u * segment;
				out[5] = segment > 0 ? dx / segment : 0;
				out[6] = segment > 0 ? dy / segment : 0;
			}
			length += segment;
		}
		out[0] = best;
		out[4] = length;
	}

}
