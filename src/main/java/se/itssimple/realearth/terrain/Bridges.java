package se.itssimple.realearth.terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.osm.OsmDetail;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.osm.Ways;

/**
 * Bridges as whole structures, with decks you can walk onto: the car game's (GeoWorld's) way of
 * building them, in blocks.
 *
 * <ul>
 * <li><b>Runs.</b> Mappers split a bridge wherever a tag changes, and each piece took the ground under
 * the split for an abutment, so a viaduct stepped up and down mid-span. Bridge ways that meet end to
 * end are joined into one run, carrying on straight where a ramp forks off; the fork then starts from
 * the run's height. The map also cuts a bridge at a tile's edge: from road version 10 each tile's copy
 * is cut there and the copies joined (as RailTracks lays track), the chunk reading the tiles a bridge
 * goes on into.</li>
 * <li><b>The deck</b> is the line between the run's two ends, lifted only where something below asks
 * for it: water (the deck clears it by a few blocks), a road or path passing under (headroom), and bare
 * ground above the line (not to be buried). Each demand holds the deck up in a rounded crest that eases
 * back down at {@link #GRADIENT}, so the lift is where it is needed and no further.</li>
 * <li><b>Approaches.</b> Where a crest still holds a run's end above the ground, the roads that meet it
 * ramp up to it at the same gradient, across as many ways as the climb needs.</li>
 * </ul>
 *
 * Everything here is worked out from the map and the elevation alone, so every chunk a bridge touches
 * gets the same deck.
 */
public final class Bridges {
	/** Rise per run of a crest's flanks and of the ramps up to a deck: a block up every three or so. */
	static final double GRADIENT = 0.3;
	/** The same for a bridge the overlay describes (road version 7): as steep as real roads and railways climb. */
	private static final double ROAD_GRADIENT = 0.05;
	private static final double RAIL_GRADIENT = 0.025;
	/** Half the length of a crest's level top, in blocks. */
	private static final double CREST_BLOCKS = 3;
	/** A deck's block stands at least this many blocks above the water's top block. */
	private static final double WATER_CLEARANCE_BLOCKS = 3;
	/** And this many above a way passing under it: three blocks of headroom. */
	private static final double WAY_CLEARANCE_BLOCKS = 4;
	/** Most a deck is lifted above the line between its ends. */
	private static final double MAX_LIFT_BLOCKS = 12;
	/** The ends of a span stand on the ground by definition: neither ground nor ways count there. */
	private static final double ABUTMENT_FRACTION = 0.15;
	private static final double MAX_ABUTMENT_BLOCKS = 8;
	/** Deck heights are worked out about this often along a run, in blocks. */
	private static final double STEP_BLOCKS = 1;
	/** Two way ends this close (in samples) are the same node of the map. */
	private static final double NODE = 1.0 / 32;
	/** Two copies' ends this close (samples) where the map cut a bridge at a tile's edge are one point (road version 10). */
	private static final double CUT_JOIN = 1.0;

	private static final Map<String, double[][]> PROFILES = new ConcurrentHashMap<>();

	private Bridges() {
	}

	/** One bridge: its ways joined end to end, and once worked out, its deck. */
	static final class Run {
		/** Joined centreline in sample coordinates, interleaved x, y, and the arc length at each point. */
		final double[] points;
		final double[] arc;
		final boolean rail;
		/** The way kind it carries, its members' highest; and the layer it is on (OSM's), its longest member's. */
		byte kind;
		byte layer;
		/** The overlay's bridge it is (road version 7, {@link #match}), or null; the measured deck tops and parts in its outline. */
		OsmDetail.Bridge facts;
		List<double[]> measured = List.of();
		List<OsmDetail.BridgePart> parts = List.of();
		/** The decks surveyed in and round its outline: its deck where the survey is wider than the map. */
		List<OsmDetail.BridgePart> decks = List.of();
		/** Half its deck's width in samples, from its outline; and its spans (metres along it), once matched. */
		double halfWidth;
		/** The run whose deck the matched bridge's other runs follow: its longest (itself if it is), so a bridge has one deck. */
		Run main;
		List<Span> spans = List.of();
		/** For each way of the run: where along the run its start would be (samples), and whether it runs backwards. */
		final Map<OsmWater.Way, double[]> members = new IdentityHashMap<>();
		/** Half a sample past each of its ends where the map cut it at a tile's edge and no copy carries it on (road version 10). */
		final List<double[]> cutEnds = new ArrayList<>();
		/** Deck height (metres) against arc length (samples), every {@link #STEP_BLOCKS} or so. */
		double[] profileArc;
		double[] profileMeters;
		final double minX;
		final double minY;
		final double maxX;
		final double maxY;

		Run(double[] points, boolean rail) {
			this.points = points;
			this.rail = rail;
			int n = points.length / 2;
			arc = new double[n];
			double x0 = Double.MAX_VALUE;
			double y0 = Double.MAX_VALUE;
			double x1 = -Double.MAX_VALUE;
			double y1 = -Double.MAX_VALUE;
			for (int i = 0; i < n; i++) {
				if (i > 0) {
					arc[i] = arc[i - 1] + Math.hypot(points[2 * i] - points[2 * i - 2], points[2 * i + 1] - points[2 * i - 1]);
				}
				x0 = Math.min(x0, points[2 * i]);
				x1 = Math.max(x1, points[2 * i]);
				y0 = Math.min(y0, points[2 * i + 1]);
				y1 = Math.max(y1, points[2 * i + 1]);
			}
			minX = x0;
			minY = y0;
			maxX = x1;
			maxY = y1;
		}

		double length() {
			return arc[arc.length - 1];
		}

		/** The point at arc length s, into out[0..1]. */
		void pointAt(double s, double[] out) {
			int i = 1;
			while (i < arc.length - 1 && arc[i] < s) {
				i++;
			}
			double span = arc[i] - arc[i - 1];
			double t = span > 0 ? Math.max(0, Math.min(1, (s - arc[i - 1]) / span)) : 0;
			out[0] = points[2 * i - 2] + (points[2 * i] - points[2 * i - 2]) * t;
			out[1] = points[2 * i - 1] + (points[2 * i + 1] - points[2 * i - 1]) * t;
		}

		/** Deck height in metres at arc length s, or NaN before the deck is worked out. */
		double heightAt(double s) {
			if (profileArc == null) {
				return Double.NaN;
			}
			if (s <= 0) {
				return profileMeters[0];
			}
			int n = profileArc.length;
			if (s >= profileArc[n - 1]) {
				return profileMeters[n - 1];
			}
			int i = (int) Math.min(n - 2, Math.max(0, s / profileArc[n - 1] * (n - 1)));
			while (i > 0 && profileArc[i] > s) {
				i--;
			}
			while (i < n - 2 && profileArc[i + 1] < s) {
				i++;
			}
			double t = (s - profileArc[i]) / Math.max(1e-12, profileArc[i + 1] - profileArc[i]);
			return profileMeters[i] + (profileMeters[i + 1] - profileMeters[i]) * t;
		}

		/** Where along the run (samples) one of its ways is {@code along} samples from that way's start. */
		double positionOnWay(OsmWater.Way way, double along, double wayLength) {
			double[] m = members.get(way);
			return m == null ? Double.NaN : m[0] + (m[1] != 0 ? wayLength - along : along);
		}

		/** Deck height in metres where one of its ways is {@code along} samples from that way's start. */
		double heightOnWay(OsmWater.Way way, double along, double wayLength) {
			double s = positionOnWay(way, along, wayLength);
			return Double.isNaN(s) ? Double.NaN : heightAt(s);
		}

		private String key() {
			return points[0] + "," + points[1] + "," + points[points.length - 2] + "," + points[points.length - 1] + "," + members.size() + "," + length();
		}
	}

	/** The bridges and the ramps up to them, for one chunk's ways. */
	static final class Network {
		final Map<OsmWater.Way, Run> runOf = new IdentityHashMap<>();
		/** Height (metres) a ramp must reach at a node, by node key. */
		final Map<Long, Double> anchors = new HashMap<>();
		/** The bridge overlays round the chunk (road version 7), or null. */
		OsmDetail.BridgeTile overlay;
	}

	static long node(double x, double y) {
		return (Math.round(x / NODE) << 32) ^ (Math.round(y / NODE) & 0xFFFFFFFFL);
	}

	private static boolean railFamily(byte kind) {
		return kind == Ways.RAIL || kind == Ways.TRAM;
	}

	/**
	 * Whether a way lies wholly in the strip its map tile reaches into its neighbours: a copy of a piece
	 * the neighbour has too. Laid as a run of its own beside that piece, it would be a second deck.
	 */
	static boolean outsideOwnTile(OsmWater.Way w) {
		// The tile's own samples, in the frame its ways are given in (see OsmWater).
		double per = Mercator.TILE_SIZE * (double) (1 << (Mercator.ZOOM - OsmWater.ZOOM));
		double x0 = w.tileX() * per - 0.5;
		double y0 = w.tileY() * per - 0.5;
		return w.maxX() < x0 || w.minX() >= x0 + per || w.maxY() < y0 || w.minY() >= y0 + per;
	}

	/** A bridge way's piece in a run: its points, where along the way it starts and ends (samples), and the way's length. */
	private record Piece(double[] points, double from, double to, double length) {
		boolean cutAtStart() {
			return from > 1e-9;
		}

		boolean cutAtEnd() {
			return to < length - 1e-9;
		}
	}

	/** The length (metres) of each run some bridge ways make, joined as a world of road version 10 ({@code cut}) or before joins them. */
	public static List<Double> runMeters(List<OsmWater.Way> ways, boolean cut) {
		return runs(ways, cut).stream().map(r -> r.length() * Mercator.sampleSpacingMeters(Mercator.latitudeOfSampleY((r.minY + r.maxY) / 2))).toList();
	}

	/**
	 * Joins the bridge ways into runs. Ways are taken in a fixed order, so every chunk that sees the
	 * same ways makes the same runs. With {@code cut} (road version 10) each map tile's copy of a way is
	 * cut at its tile's edge and the copies' ends there joined, so a bridge the edge crosses is one run;
	 * before, each copy (reaching a little past the edge) was a run of its own.
	 */
	static List<Run> runs(List<OsmWater.Way> ways, boolean cut) {
		List<OsmWater.Way> bridges = new ArrayList<>();
		Map<OsmWater.Way, Piece> pieces = new IdentityHashMap<>();
		for (OsmWater.Way w : ways) {
			if (!w.bridge() || w.points().length < 4) {
				continue;
			}
			Piece piece = cut ? ownPiece(w) : outsideOwnTile(w) ? null : new Piece(w.points(), 0, length(w.points()), length(w.points()));
			if (piece != null) {
				bridges.add(w);
				pieces.put(w, piece);
			}
		}
		bridges.sort(Comparator.comparingDouble(OsmWater.Way::minX).thenComparingDouble(OsmWater.Way::minY).thenComparingDouble(OsmWater.Way::maxX)
			.thenComparingDouble(OsmWater.Way::maxY).thenComparingInt(w -> w.points().length));
		if (cut) {
			joinCuts(bridges, pieces);
		}
		Map<Long, List<OsmWater.Way>> ends = new HashMap<>();
		for (OsmWater.Way w : bridges) {
			double[] p = pieces.get(w).points();
			ends.computeIfAbsent(node(p[0], p[1]), k -> new ArrayList<>()).add(w);
			ends.computeIfAbsent(node(p[p.length - 2], p[p.length - 1]), k -> new ArrayList<>()).add(w);
		}
		Map<OsmWater.Way, Boolean> used = new IdentityHashMap<>();
		List<Run> runs = new ArrayList<>();
		for (OsmWater.Way first : bridges) {
			if (used.containsKey(first)) {
				continue;
			}
			used.put(first, true);
			boolean rail = railFamily(first.kind());
			// Pieces in order along the run, each with whether it runs backwards.
			ArrayDeque<Object[]> chain = new ArrayDeque<>();
			chain.add(new Object[] {first, false});
			for (int side = 0; side < 2; side++) {
				while (true) {
					Object[] end = side == 0 ? chain.peekLast() : chain.peekFirst();
					OsmWater.Way w = (OsmWater.Way) end[0];
					boolean rev = (Boolean) end[1];
					double[] p = pieces.get(w).points();
					// The run's free end, and the direction it arrives in.
					boolean atStart = side == 0 == rev;
					int a = atStart ? 0 : p.length - 2;
					int b = atStart ? 2 : p.length - 4;
					double dx = p[a] - p[b];
					double dy = p[a + 1] - p[b + 1];
					OsmWater.Way next = null;
					boolean nextRev = false;
					// A way turning back alongside the run (two pieces leaving one node the same way) is no
					// carrying on.
					double best = -0.5;
					for (OsmWater.Way c : ends.getOrDefault(node(p[a], p[a + 1]), List.of())) {
						if (used.containsKey(c) || railFamily(c.kind()) != rail) {
							continue;
						}
						double[] q = pieces.get(c).points();
						boolean fromStart = node(q[0], q[1]) == node(p[a], p[a + 1]);
						int i0 = fromStart ? 0 : q.length - 2;
						int i1 = fromStart ? 2 : q.length - 4;
						double ex = q[i1] - q[i0];
						double ey = q[i1 + 1] - q[i0 + 1];
						// The straightest carrying on wins; the others fork off.
						double straight = (dx * ex + dy * ey) / Math.max(1e-12, Math.hypot(dx, dy) * Math.hypot(ex, ey));
						if (straight > best) {
							best = straight;
							next = c;
							// Walking away from the run's end: forwards if it starts here (on the far side,
							// mirrored).
							nextRev = side == 0 ? !fromStart : fromStart;
						}
					}
					if (next == null) {
						break;
					}
					used.put(next, true);
					if (side == 0) {
						chain.addLast(new Object[] {next, nextRev});
					} else {
						chain.addFirst(new Object[] {next, nextRev});
					}
				}
			}
			// The joined centreline, each way's start along it.
			List<double[]> pts = new ArrayList<>();
			List<double[]> offsets = new ArrayList<>();
			for (Object[] piece : chain) {
				double[] p = pieces.get((OsmWater.Way) piece[0]).points();
				boolean rev = (Boolean) piece[1];
				offsets.add(new double[] {pts.size()});
				for (int i = 0; i < p.length / 2; i++) {
					int j = rev ? p.length / 2 - 1 - i : i;
					if (i == 0 && !pts.isEmpty()) {
						continue; // the node it shares with the piece before
					}
					pts.add(new double[] {p[2 * j], p[2 * j + 1]});
				}
			}
			double[] flat = new double[pts.size() * 2];
			for (int i = 0; i < pts.size(); i++) {
				flat[2 * i] = pts.get(i)[0];
				flat[2 * i + 1] = pts.get(i)[1];
			}
			Run run = new Run(flat, rail);
			int index = 0;
			double longest = -1;
			for (Object[] piece : chain) {
				int startPoint = (int) offsets.get(index++)[0];
				// Each piece after the first starts at the point it shares with the one before.
				double start = run.arc[Math.max(0, startPoint == 0 ? 0 : startPoint - 1)];
				// Where the whole way's start would be along the run: the piece may be cut short of either end.
				Piece pc = pieces.get((OsmWater.Way) piece[0]);
				boolean rev = (Boolean) piece[1];
				run.members.put((OsmWater.Way) piece[0], new double[] {rev ? start - (pc.length() - pc.to()) : start - pc.from(), rev ? 1 : 0});
				run.kind = (byte) Math.max(run.kind, ((OsmWater.Way) piece[0]).kind());
				if (pc.to() - pc.from() > longest) {
					longest = pc.to() - pc.from();
					run.layer = ((OsmWater.Way) piece[0]).layer();
				}
			}
			Piece head = pieces.get((OsmWater.Way) chain.peekFirst()[0]);
			Piece tail = pieces.get((OsmWater.Way) chain.peekLast()[0]);
			if ((Boolean) chain.peekFirst()[1] ? head.cutAtEnd() : head.cutAtStart()) {
				run.cutEnds.add(beyond(flat, 0, 2));
			}
			if ((Boolean) chain.peekLast()[1] ? tail.cutAtStart() : tail.cutAtEnd()) {
				run.cutEnds.add(beyond(flat, flat.length - 2, flat.length - 4));
			}
			runs.add(run);
		}
		return runs;
	}

	private static double length(double[] p) {
		double length = 0;
		for (int i = 0; i + 3 < p.length; i += 2) {
			length += Math.hypot(p[i + 2] - p[i], p[i + 3] - p[i + 1]);
		}
		return length;
	}

	/** Half a sample on past point {@code a} of a line, away from its point {@code b}. */
	private static double[] beyond(double[] p, int a, int b) {
		double dx = p[a] - p[b];
		double dy = p[a + 1] - p[b + 1];
		double d = Math.max(1e-12, Math.hypot(dx, dy));
		return new double[] {p[a] + dx / d * 0.5, p[a + 1] + dy / d * 0.5};
	}

	/** A bridge way's piece in its own map tile, cut at the tile's edge (Liang-Barsky); null if none of it lies there. */
	private static Piece ownPiece(OsmWater.Way w) {
		// The tile's own samples, as in outsideOwnTile.
		double per = Mercator.TILE_SIZE * (double) (1 << (Mercator.ZOOM - OsmWater.ZOOM));
		double x0 = w.tileX() * per - 0.5;
		double y0 = w.tileY() * per - 0.5;
		double[] p = w.points();
		double[] out = new double[p.length];
		int n = 0;
		double along = 0;
		double from = 0;
		double to = 0;
		boolean left = false;
		for (int i = 0; i + 3 < p.length; i += 2) {
			double dx = p[i + 2] - p[i];
			double dy = p[i + 3] - p[i + 1];
			double len = Math.hypot(dx, dy);
			// The part of the segment in the tile: t0 to t1 of it.
			double t0 = 0;
			double t1 = 1;
			double[] dir = {-dx, dx, -dy, dy};
			double[] room = {p[i] - x0, x0 + per - p[i], p[i + 1] - y0, y0 + per - p[i + 1]};
			for (int k = 0; k < 4; k++) {
				if (dir[k] == 0) {
					t1 = room[k] < 0 ? -1 : t1;
				} else if (dir[k] < 0) {
					t0 = Math.max(t0, room[k] / dir[k]);
				} else {
					t1 = Math.min(t1, room[k] / dir[k]);
				}
			}
			if (t0 < t1 && !left) {
				if (n == 0) {
					out[n++] = p[i] + dx * t0;
					out[n++] = p[i + 1] + dy * t0;
					from = along + len * t0;
				}
				out[n++] = p[i] + dx * t1;
				out[n++] = p[i + 1] + dy * t1;
				to = along + len * t1;
				// shortcut: a way leaving its tile and coming back keeps its first piece only; bridges don't, in practice.
				left = t1 < 1;
			} else if (n > 0) {
				left = true;
			}
			along += len;
		}
		return n < 4 || to - from < 1e-9 ? null : new Piece(java.util.Arrays.copyOf(out, n), from, to, along);
	}

	/**
	 * Where two copies of a way (from neighbouring map tiles, of one kind) are cut at the edge between their tiles, makes
	 * their cut ends one point, so the copies join. Each tile rounds the map's points its own way, so the two cuts can lie a
	 * little apart: an end goes with its nearest other (each the other's nearest), within {@link #CUT_JOIN}.
	 */
	private static void joinCuts(List<OsmWater.Way> bridges, Map<OsmWater.Way, Piece> pieces) {
		List<int[]> cuts = new ArrayList<>();
		for (int i = 0; i < bridges.size(); i++) {
			Piece pc = pieces.get(bridges.get(i));
			if (pc.cutAtStart()) {
				cuts.add(new int[] {i, 0});
			}
			if (pc.cutAtEnd()) {
				cuts.add(new int[] {i, pc.points().length - 2});
			}
		}
		int[] nearest = new int[cuts.size()];
		for (int i = 0; i < cuts.size(); i++) {
			nearest[i] = -1;
			OsmWater.Way a = bridges.get(cuts.get(i)[0]);
			double[] p = pieces.get(a).points();
			int pi = cuts.get(i)[1];
			double best = CUT_JOIN;
			for (int j = 0; j < cuts.size(); j++) {
				OsmWater.Way b = bridges.get(cuts.get(j)[0]);
				if (b.kind() != a.kind() || b.tileX() == a.tileX() && b.tileY() == a.tileY()) {
					continue;
				}
				double[] q = pieces.get(b).points();
				int qi = cuts.get(j)[1];
				double d = Math.hypot(p[pi] - q[qi], p[pi + 1] - q[qi + 1]);
				if (d < best) {
					best = d;
					nearest[i] = j;
				}
			}
		}
		for (int i = 0; i < cuts.size(); i++) {
			int j = nearest[i];
			if (j > i && nearest[j] == i) {
				double[] p = pieces.get(bridges.get(cuts.get(i)[0])).points();
				double[] q = pieces.get(bridges.get(cuts.get(j)[0])).points();
				int a = cuts.get(i)[1];
				int b = cuts.get(j)[1];
				p[a] = q[b] = (p[a] + q[b]) / 2;
				p[a + 1] = q[b + 1] = (p[a + 1] + q[b + 1]) / 2;
			}
		}
	}

	/**
	 * Works out the decks of the runs near a chunk and the ramps up to them. {@code near} tells which
	 * runs matter (those within reach of the chunk); their decks need the elevation along them.
	 */
	static Network network(TerrainField field, Map<Long, ProcessedTile> tiles, List<OsmWater.Way> ways, List<Run> runs,
		java.util.function.Predicate<Run> near, OsmDetail.BridgeTile overlay) {
		Network net = new Network();
		net.overlay = overlay;
		// Longest first, so a fork can start from the run it leaves.
		List<Run> wanted = new ArrayList<>();
		for (Run r : runs) {
			if (near.test(r)) {
				wanted.add(r);
			}
		}
		wanted.sort(Comparator.comparingDouble(Run::length).reversed().thenComparingDouble(r -> r.minX).thenComparingDouble(r -> r.minY));
		// A run that ends on another run's span (a ramp forking off it) starts from that run's height,
		// so it waits for that run; otherwise longest first. A cycle of waits is broken longest first.
		Map<Long, List<Run>> through = new HashMap<>();
		for (Run r : wanted) {
			for (int i = 0; i < r.points.length / 2; i++) {
				List<Run> here = through.computeIfAbsent(node(r.points[2 * i], r.points[2 * i + 1]), k -> new ArrayList<>());
				if (!here.contains(r)) {
					here.add(r);
				}
			}
		}
		// Each matched bridge's longest run is its main one; the others in its outline follow its deck.
		Map<String, Run> mains = new HashMap<>();
		for (Run r : wanted) {
			match(r, overlay);
			if (r.facts != null) {
				mains.merge(r.facts.osm(), r, (a, b) -> b.length() > a.length() ? b : a);
			}
		}
		for (Run r : wanted) {
			r.main = r.facts == null ? null : mains.get(r.facts.osm());
		}
		Map<Long, double[]> onDecks = new HashMap<>();
		List<Run> mapMains = new ArrayList<>();
		double[] q = new double[2];
		List<Run> pending = new ArrayList<>(wanted);
		while (!pending.isEmpty()) {
			Run next = null;
			for (Run r : pending) {
				if (!waits(r, pending, through)) {
					next = r;
					break;
				}
			}
			if (next == null) {
				next = pending.get(0);
			}
			if (next.main != null && next.main != next && pending.contains(next.main)) {
				next = next.main;
			}
			pending.remove(next);
			Run r = next;
			if (r.main == r) {
				r.spans = layout(r, tiles);
				profile(field, tiles, ways, r, onDecks);
				if (r.facts.osm().startsWith("map:")) {
					mapMains.add(r);
				}
			} else if (r.main != null) {
				follow(field, r, r.main);
			} else {
				// An end inside a bridge the map outlines (a way forking off its deck to the bank, road version 11) starts on its deck.
				for (int end = 0; end < 2; end++) {
					double x = r.points[end == 0 ? 0 : r.points.length - 2];
					double y = r.points[end == 0 ? 1 : r.points.length - 1];
					for (Run m : mapMains) {
						if (!onDecks.containsKey(node(x, y)) && BuildingPlan.contains(m.facts.rings(), x, y)) {
							project(m, x, y, q);
							onDecks.put(node(x, y), new double[] {m.heightAt(q[0])});
						}
					}
				}
				profile(field, tiles, ways, r, onDecks);
			}
			for (int i = 0; i < r.points.length / 2; i++) {
				onDecks.putIfAbsent(node(r.points[2 * i], r.points[2 * i + 1]), new double[] {r.heightAt(r.arc[i])});
			}
			r.members.keySet().forEach(w -> net.runOf.put(w, r));
		}

		// Ramps: from each run end held above the ground, down the roads that meet it.
		Map<Long, List<OsmWater.Way>> ends = new HashMap<>();
		for (OsmWater.Way w : ways) {
			if (!w.bridge() && w.points().length >= 4) {
				double[] p = w.points();
				ends.computeIfAbsent(node(p[0], p[1]), k -> new ArrayList<>()).add(w);
				ends.computeIfAbsent(node(p[p.length - 2], p[p.length - 1]), k -> new ArrayList<>()).add(w);
			}
		}
		ArrayDeque<long[]> queue = new ArrayDeque<>();
		Map<Long, Boolean> railNode = new HashMap<>();
		for (Run r : wanted) {
			for (int end = 0; end < 2; end++) {
				double x = end == 0 ? r.points[0] : r.points[r.points.length - 2];
				double y = end == 0 ? r.points[1] : r.points[r.points.length - 1];
				double h = r.heightAt(end == 0 ? 0 : r.length());
				double g = TerrainField.splineHeight(tiles, x, y);
				if (!Double.isNaN(h) && !Double.isNaN(g) && h > g + 0.25 / field.projection().blocksPerMeter(Mercator.latitudeOfSampleY(y))) {
					long key = node(x, y);
					if (h > net.anchors.getOrDefault(key, Double.NEGATIVE_INFINITY)) {
						net.anchors.put(key, h);
						railNode.put(key, r.rail);
						queue.add(new long[] {key});
					}
				}
			}
		}
		for (int guard = 0; !queue.isEmpty() && guard < 4096; guard++) {
			long key = queue.poll()[0];
			double h = net.anchors.get(key);
			boolean rail = railNode.get(key);
			for (OsmWater.Way w : ends.getOrDefault(key, List.of())) {
				if (railFamily(w.kind()) != rail) {
					continue;
				}
				double[] p = w.points();
				boolean fromStart = node(p[0], p[1]) == key;
				double fx = fromStart ? p[p.length - 2] : p[0];
				double fy = fromStart ? p[p.length - 1] : p[1];
				double meters = 0;
				for (int i = 0; i + 3 < p.length; i += 2) {
					meters += Math.hypot(p[i + 2] - p[i], p[i + 3] - p[i + 1]);
				}
				meters *= Mercator.sampleSpacingMeters(Mercator.latitudeOfSampleY(fy));
				double far = h - GRADIENT * meters;
				double g = TerrainField.splineHeight(tiles, fx, fy);
				long farKey = node(fx, fy);
				if (!Double.isNaN(g) && far > g && far > net.anchors.getOrDefault(farKey, Double.NEGATIVE_INFINITY) && !onDecks.containsKey(farKey)) {
					net.anchors.put(farKey, far);
					railNode.put(farKey, rail);
					queue.add(new long[] {farKey});
				}
			}
		}
		return net;
	}

	/** Whether an end of {@code r} lies on the span (not an end) of a run still to be laid. */
	private static boolean waits(Run r, List<Run> pending, Map<Long, List<Run>> through) {
		for (int end = 0; end < 2; end++) {
			long key = end == 0 ? node(r.points[0], r.points[1]) : node(r.points[r.points.length - 2], r.points[r.points.length - 1]);
			for (Run o : through.getOrDefault(key, List.of())) {
				if (o != r && pending.contains(o) && key != node(o.points[0], o.points[1])
					&& key != node(o.points[o.points.length - 2], o.points[o.points.length - 1])) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * A rounded crest: how much of a lift demanded at one point is still owed {@code distance} away.
	 * Level on top for {@code crest}, easing out to {@code gradient} beyond it and on down to nothing.
	 */
	static double crestLift(double need, double distance, double crest, double gradient) {
		double drop = distance <= crest ? gradient * distance * distance / (2 * crest) : gradient * (distance - crest * 0.5);
		return need - drop;
	}

	private static void profile(TerrainField field, Map<Long, ProcessedTile> tiles, List<OsmWater.Way> ways, Run r, Map<Long, double[]> onDecks) {
		String key = r.key() + (r.facts == null ? "" : "|" + r.facts.osm() + "|" + r.measured.size());
		double[][] cached = PROFILES.get(key);
		if (cached != null) {
			r.profileArc = cached[0];
			r.profileMeters = cached[1];
			return;
		}
		double lat = Mercator.latitudeOfSampleY((r.minY + r.maxY) / 2);
		double spacing = Mercator.sampleSpacingMeters(lat);
		double perMeter = field.projection().blocksPerMeter(lat);
		double blocks = r.length() * spacing * perMeter;
		int n = Math.max(2, (int) Math.ceil(blocks / STEP_BLOCKS) + 1);
		double[] at = new double[2];

		// The ends: where a run starts on another deck (a ramp forking off), that deck's height; else
		// the ground.
		double[] startOnDeck = onDecks.get(node(r.points[0], r.points[1]));
		double[] endOnDeck = onDecks.get(node(r.points[r.points.length - 2], r.points[r.points.length - 1]));
		double entry = startOnDeck != null ? startOnDeck[0] : TerrainField.splineHeight(tiles, r.points[0], r.points[1]);
		double exit = endOnDeck != null ? endOnDeck[0] : TerrainField.splineHeight(tiles, r.points[r.points.length - 2], r.points[r.points.length - 1]);
		if (Double.isNaN(entry) || Double.isNaN(exit)) {
			// No elevation under an end: lay it level with whichever end there is, or not at all.
			double either = Double.isNaN(entry) ? exit : entry;
			if (Double.isNaN(either)) {
				return;
			}
			entry = Double.isNaN(entry) ? either : entry;
			exit = Double.isNaN(exit) ? either : exit;
		}

		double[] arcAt = new double[n];
		double[] line = new double[n];
		double[] need = new double[n];
		double abutment = Math.min(ABUTMENT_FRACTION * r.length(), MAX_ABUTMENT_BLOCKS / perMeter / spacing);
		double waterClear = WATER_CLEARANCE_BLOCKS / perMeter;
		double wayClear = WAY_CLEARANCE_BLOCKS / perMeter;
		// A bridge only the map outlines (road version 11) has no height or clearance in its data: its deck is laid as one the
		// overlays don't describe (a guess from its water's width suits one bridge and not the next: Victoria Bridge, Centralbron).
		boolean plain = r.facts == null || r.facts.osm().startsWith("map:");
		double maxLift = plain ? MAX_LIFT_BLOCKS / perMeter : Double.MAX_VALUE;
		double gradient = plain ? GRADIENT : r.rail ? RAIL_GRADIENT : ROAD_GRADIENT;
		// A cited clearance holds over the middle of the longest span, under its deck and the structure at its crown.
		Span main = r.spans.stream().max(Comparator.comparingDouble(Span::length)).orElse(null);
		double clearance = plain || main == null ? 0 : r.facts.clearanceMeters() + crownDepth(main.length());
		for (int i = 0; i < n; i++) {
			double s = r.length() * i / (n - 1);
			arcAt[i] = s;
			line[i] = entry + (exit - entry) * i / (n - 1);
			if (i == 0 || i == n - 1) {
				continue;
			}
			r.pointAt(s, at);
			double rise = 0;
			double water = TerrainField.waterSurface(tiles, at[0], at[1]);
			if (!Double.isNaN(water)) {
				rise = Math.max(rise, water + waterClear - line[i]);
			}
			if (s >= abutment && s <= r.length() - abutment) {
				double ground = TerrainField.splineHeight(tiles, at[0], at[1]);
				if (!Double.isNaN(ground)) {
					rise = Math.max(rise, ground - line[i]);
					// A way beneath is laid at its own centreline's height, which can be well off the
					// ground right here: clear that.
					double beneath = wayBeneath(tiles, ways, at[0], at[1], spacing);
					if (!Double.isNaN(beneath)) {
						rise = Math.max(rise, Math.max(ground, beneath) + wayClear - line[i]);
					}
				}
			}
			double m = s * spacing;
			if (clearance > 0 && m > main.from() + main.length() / 3 && m < main.to() - main.length() / 3) {
				double below = Double.isNaN(water) ? TerrainField.splineHeight(tiles, at[0], at[1]) : water;
				if (!Double.isNaN(below)) {
					rise = Math.max(rise, below + clearance - line[i]);
				}
			}
			need[i] = Math.min(maxLift, Math.max(0, rise));
		}

		// Lifted where it is needed and no further: each demand holds the deck up in a rounded crest.
		double crest = CREST_BLOCKS / perMeter;
		double[] meters = new double[n];
		for (int i = 0; i < n; i++) {
			double lift = 0;
			for (int j = 0; j < n; j++) {
				if (need[j] > lift) {
					lift = Math.max(lift, crestLift(need[j], Math.abs(arcAt[i] - arcAt[j]) * spacing, crest, gradient));
				}
			}
			meters[i] = line[i] + lift;
			// An end on another deck is held there: the run leaves that deck at its height and climbs or
			// falls from it no faster than the gradient.
			double fromStart = arcAt[i] * spacing;
			double fromEnd = (r.length() - arcAt[i]) * spacing;
			if (startOnDeck != null) {
				meters[i] = Math.max(entry - gradient * fromStart, Math.min(entry + gradient * fromStart, meters[i]));
			}
			if (endOnDeck != null) {
				meters[i] = Math.max(exit - gradient * fromEnd, Math.min(exit + gradient * fromEnd, meters[i]));
			}
		}
		measuredProfile(r, arcAt, meters);
		r.profileArc = arcAt;
		r.profileMeters = meters;
		if (PROFILES.size() > 8192) {
			PROFILES.clear();
		}
		PROFILES.put(key, new double[][] {arcAt, meters});
	}

	/** A span of a bridge the overlay describes: from and to, metres along its run from its start, and whether an arch carries it. */
	record Span(double from, double to, boolean arch) {
		double length() {
			return to - from;
		}
	}

	/** The overlays' tiles under a chunk's bridges as one, each bridge and part once (a polygon is in every tile it reaches). */
	static OsmDetail.BridgeTile merge(List<OsmDetail.BridgeTile> tiles) {
		Map<String, OsmDetail.Bridge> bridges = new java.util.LinkedHashMap<>();
		Map<String, OsmDetail.BridgePart> parts = new java.util.LinkedHashMap<>();
		List<double[]> heights = new ArrayList<>();
		for (OsmDetail.BridgeTile t : tiles) {
			t.bridges().forEach(b -> bridges.putIfAbsent(b.osm() + "@" + b.minX() + "," + b.minY(), b));
			t.parts().forEach(p -> parts.putIfAbsent(p.kind() + "@" + p.minX() + "," + p.minY() + "," + p.maxX(), p));
			heights.addAll(t.heights());
		}
		return new OsmDetail.BridgeTile(List.copyOf(bridges.values()), List.copyOf(parts.values()), heights);
	}

	/**
	 * Road version 11: the map's own bridge outlines (OsmWater.BridgeArea) as bridges where no overlay has one, so every bridge
	 * the map outlines is built whole: its deck filling its outline, girders and piers under it, railings along its edge only.
	 * Nothing else is known of them, so each is a concrete beam bridge. The pieces of an outline cut by map tiles' edges, on one
	 * layer and meeting along an edge, are one bridge.
	 */
	static OsmDetail.BridgeTile withMapOutlines(OsmDetail.BridgeTile overlay, List<OsmWater.Shapes> shapes) {
		List<OsmWater.BridgeArea> pieces = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (OsmWater.Shapes s : shapes) {
			for (OsmWater.BridgeArea a : s.bridges()) {
				if (seen.add(a.layer() + "@" + a.minX() + "," + a.minY() + "," + a.maxX() + "," + a.maxY())) {
					pieces.add(a);
				}
			}
		}
		int[] group = new int[pieces.size()];
		for (int i = 0; i < group.length; i++) {
			group[i] = i;
		}
		for (int i = 0; i < pieces.size(); i++) {
			for (int j = i + 1; j < pieces.size(); j++) {
				if (meet(pieces.get(i), pieces.get(j))) {
					int a = root(group, i);
					int b = root(group, j);
					group[Math.max(a, b)] = Math.min(a, b);
				}
			}
		}
		List<OsmDetail.Bridge> bridges = new ArrayList<>(overlay.bridges());
		for (int i = 0; i < pieces.size(); i++) {
			if (root(group, i) != i) {
				continue;
			}
			List<double[]> rings = new ArrayList<>();
			double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
			for (int j = i; j < pieces.size(); j++) {
				OsmWater.BridgeArea a = pieces.get(j);
				if (root(group, j) == i) {
					rings.addAll(a.rings());
					box = new double[] {Math.min(box[0], a.minX()), Math.min(box[1], a.minY()), Math.max(box[2], a.maxX()), Math.max(box[3], a.maxY())};
				}
			}
			OsmWater.BridgeArea first = pieces.get(i);
			double mx = (first.minX() + first.maxX()) / 2;
			double my = (first.minY() + first.maxY()) / 2;
			if (overlay.bridges().stream().anyMatch(b -> BuildingPlan.contains(b.rings(), mx, my))) {
				continue; // the overlay's, with what it knows
			}
			bridges.add(new OsmDetail.Bridge(List.copyOf(rings), "", "", "", "", 0, 0, 0, new double[0], 0, 0, "", 0, first.layer(),
				"map:" + first.layer() + "@" + Math.round(box[0]) + "," + Math.round(box[1]), box[0], box[1], box[2], box[3]));
		}
		return new OsmDetail.BridgeTile(List.copyOf(bridges), overlay.parts(), overlay.heights());
	}

	private static int root(int[] group, int i) {
		while (group[i] != i) {
			i = group[i];
		}
		return i;
	}

	/** Whether two pieces of the map's bridge outlines are one bridge cut by a tile's edge: on one layer, sharing a stretch of the edge. */
	static boolean meet(OsmWater.BridgeArea a, OsmWater.BridgeArea b) {
		if (a.layer() != b.layer()) {
			return false;
		}
		double[][] lines = {{0, a.maxX()}, {0, a.minX()}, {1, a.maxY()}, {1, a.minY()}};
		for (double[] line : lines) {
			double[] p = onLine(a.rings(), (int) line[0], line[1]);
			double[] q = onLine(b.rings(), (int) line[0], line[1]);
			if (p != null && q != null && Math.min(p[1], q[1]) - Math.max(p[0], q[0]) > 1e-6) {
				return true;
			}
		}
		return false;
	}

	/** The extent along a line (x = value for axis 0, y = value for axis 1) of rings' points lying on it, or null for none. */
	private static double[] onLine(List<double[]> rings, int axis, double value) {
		double lo = Double.MAX_VALUE;
		double hi = -Double.MAX_VALUE;
		for (double[] ring : rings) {
			for (int i = 0; i + 1 < ring.length; i += 2) {
				if (Math.abs(ring[i + axis] - value) < 1e-6) {
					lo = Math.min(lo, ring[i + 1 - axis]);
					hi = Math.max(hi, ring[i + 1 - axis]);
				}
			}
		}
		return lo <= hi ? new double[] {lo, hi} : null;
	}

	/**
	 * Matches a run to the overlay's bridge whose outline most of it lies in (three tenths at least; a map outline's seven tenths,
	 * or where it crosses most of it), and takes the measured deck tops in that outline and the parts round it.
	 */
	private static void match(Run r, OsmDetail.BridgeTile overlay) {
		if (overlay == null || overlay.bridges().isEmpty()) {
			return;
		}
		double[] at = new double[2];
		int steps = Math.max(4, (int) Math.ceil(r.length()));
		OsmDetail.Bridge best = null;
		int bestIn = 0;
		for (OsmDetail.Bridge b : overlay.bridges()) {
			if (b.maxX() < r.minX || b.minX() > r.maxX || b.maxY() < r.minY || b.minY() > r.maxY) {
				continue;
			}
			if (b.osm().startsWith("map:") && b.layer() != r.layer) {
				continue; // the map's outline of a bridge over or under it
			}
			int in = 0;
			for (int i = 0; i <= steps; i++) {
				r.pointAt(r.length() * i / steps, at);
				in += BuildingPlan.contains(b.rings(), at[0], at[1]) ? 1 : 0;
			}
			if (in > bestIn) {
				bestIn = in;
				best = b;
			}
		}
		if (best == null) {
			return;
		}
		// A map outline's (road version 11): a run mostly in it (seven tenths; a way forking off it to the bank keeps its own deck,
		// Strömsborgsbron off Vasabron), or one going on over other bridges that crosses most of it (a busway on a string of them).
		boolean map = best.osm().startsWith("map:");
		double across = Math.hypot(best.maxX() - best.minX(), best.maxY() - best.minY());
		boolean crosses = map && r.length() * bestIn / (steps + 1) >= 0.6 * across;
		if (bestIn * 10 < (steps + 1) * (map ? 7 : 3) && !crosses) {
			return;
		}
		OsmDetail.Bridge b = best;
		r.facts = b;
		r.measured = overlay.heights().stream().filter(h -> BuildingPlan.contains(b.rings(), h[0], h[1])).toList();
		double margin = 3 / Mercator.sampleSpacingMeters(Mercator.latitudeOfSampleY(b.minY()));
		List<OsmDetail.BridgePart> near = overlay.parts().stream().filter(p -> p.maxX() >= b.minX() - margin && p.minX() <= b.maxX() + margin
			&& p.maxY() >= b.minY() - margin && p.minY() <= b.maxY() + margin).toList();
		// A truss's strip (BridgeTrusses) holds nothing up.
		r.parts = near.stream().filter(p -> !p.kind().equals("deck") && !p.kind().equals("truss")).toList();
		r.decks = near.stream().filter(p -> p.kind().equals("deck")).toList();
		// Half the deck's width: the outline's area (all its rings: the map's pieces add up, holes take away) over the length of
		// run inside it.
		double area = 0;
		for (double[] ring : b.rings()) {
			for (int i = 0, j = ring.length - 2; i < ring.length; j = i, i += 2) {
				area += ring[j] * ring[i + 1] - ring[i] * ring[j + 1];
			}
		}
		r.halfWidth = Math.abs(area) / 2 / Math.max(1e-9, r.length() * bestIn / (steps + 1)) / 2;
	}

	/** How deep a bridge's structure is at the crown of a span, metres: its deck and an arch's ring there (BridgeStructure). */
	static double crownDepth(double spanMeters) {
		return 1 + Math.max(1, spanMeters / 60);
	}

	/** The span a bridge usually has between piers, metres, where the data gives none: by its structure and material. */
	static double usualSpan(OsmDetail.Bridge f) {
		return f.structure().equals("humpback") || f.material().equals("stone") || f.material().equals("brick") ? 8
			: f.material().equals("wood") ? 6 : f.material().equals("steel") ? 30 : 20;
	}

	/**
	 * A matched bridge's spans, metres along its run. Where its supports are surveyed or mapped, what lies between them
	 * (arches over the water of an arch bridge). Else the cited ones (from the north or west end) over the water they fit
	 * best; an arch over each other stretch of water if it is an arch bridge, beams of its usual span over it if not; and
	 * beams of its usual span the rest of the way. The boundaries between them are its piers.
	 */
	static List<Span> layout(Run r, Map<Long, ProcessedTile> tiles) {
		OsmDetail.Bridge f = r.facts;
		double spacing = Mercator.sampleSpacingMeters(Mercator.latitudeOfSampleY((r.minY + r.maxY) / 2));
		double total = r.length() * spacing;
		boolean arches = f.structure().equals("arch") || f.structure().equals("humpback");
		List<Span> surveyed = between(r, tiles, spacing, total, arches);
		if (!surveyed.isEmpty()) {
			return surveyed;
		}
		List<double[]> wet = new ArrayList<>();
		double[] at = new double[2];
		double from = -1;
		for (int i = 0, steps = (int) Math.ceil(total); i <= steps; i++) {
			double m = Math.min(total, i);
			r.pointAt(m / spacing, at);
			boolean water = !Double.isNaN(TerrainField.waterSurface(tiles, at[0], at[1]));
			if (water && from < 0) {
				from = m;
			}
			if ((!water || i == steps) && from >= 0) {
				double to = water ? m : m - 1;
				if (!wet.isEmpty() && from - wet.get(wet.size() - 1)[1] <= 3) {
					wet.get(wet.size() - 1)[1] = to;
				} else if (to - from >= 2) {
					wet.add(new double[] {from, to});
				}
				from = -1;
			}
		}
		double usual = usualSpan(f);
		List<Span> placed = new ArrayList<>();
		double[] cited = f.spans().length > 0 ? f.spans() : f.mainSpanMeters() > 0 ? new double[] {f.mainSpanMeters()} : new double[0];
		if (cited.length > 0) {
			double dx = r.points[r.points.length - 2] - r.points[0];
			double dy = r.points[r.points.length - 1] - r.points[1];
			// Sample y grows southward: the run starts at the north (or west) end if its other end lies further south (east).
			boolean fromNorth = Math.abs(dy) >= Math.abs(dx) ? dy > 0 : dx > 0;
			double length = java.util.Arrays.stream(cited).sum();
			double[] home = wet.stream().max(Comparator.comparingDouble(w -> w[1] - w[0])).orElse(new double[] {0, total});
			double a = Math.max(0, Math.min(total - length, (home[0] + home[1] - length) / 2));
			for (int i = 0; i < cited.length; i++) {
				double len = cited[fromNorth ? i : cited.length - 1 - i];
				placed.add(new Span(a, Math.min(total, a + len), arches));
				a += len;
			}
		}
		for (double[] w : wet) {
			double covered = 0;
			for (Span s : placed) {
				covered += Math.max(0, Math.min(s.to(), w[1]) - Math.max(s.from(), w[0]));
			}
			if (covered * 2 >= w[1] - w[0]) {
				continue;
			}
			if (arches) {
				placed.add(new Span(Math.max(0, w[0] - 0.5), Math.min(total, w[1] + 0.5), true));
			} else {
				beams(placed, w[0], w[1], usual);
			}
		}
		placed.sort(Comparator.comparingDouble(Span::from));
		List<Span> all = new ArrayList<>();
		double reached = 0;
		for (Span s : placed) {
			if (s.to() <= reached) {
				continue;
			}
			beams(all, reached, s.from(), usual);
			all.add(new Span(Math.max(reached, s.from()), s.to(), s.arch()));
			reached = s.to();
		}
		beams(all, reached, total, usual);
		return List.copyOf(all);
	}

	/**
	 * The spans between a bridge's surveyed or mapped supports (metres along its run, each support where its outline lies
	 * along it, a mapped point a metre and a half thick), an arch where an arch bridge's span is over water; none without.
	 */
	private static List<Span> between(Run r, Map<Long, ProcessedTile> tiles, double spacing, double total, boolean arches) {
		List<double[]> held = new ArrayList<>();
		double[] q = new double[2];
		for (OsmDetail.BridgePart p : r.parts) {
			double lo = Double.MAX_VALUE;
			double hi = -Double.MAX_VALUE;
			boolean near = false;
			for (double[] ring : p.rings()) {
				for (int i = 0; i + 1 < ring.length; i += 2) {
					project(r, ring[i], ring[i + 1], q);
					lo = Math.min(lo, q[0] * spacing);
					hi = Math.max(hi, q[0] * spacing);
					near |= (q[1] - r.halfWidth) * spacing <= 2;
				}
			}
			if (near) {
				held.add(new double[] {Math.max(0, Math.min(lo, hi - 1.5)), Math.min(total, Math.max(hi, lo + 1.5))});
			}
		}
		held.sort(Comparator.comparingDouble(h -> h[0]));
		List<Span> spans = new ArrayList<>();
		double reached = 0;
		double[] at = new double[2];
		for (int i = 0; i <= held.size(); i++) {
			double to = i < held.size() ? held.get(i)[0] : total;
			if (to - reached >= 0.5) {
				r.pointAt((reached + to) / 2 / spacing, at);
				spans.add(new Span(reached, to, arches && !Double.isNaN(TerrainField.waterSurface(tiles, at[0], at[1]))));
			}
			reached = Math.max(reached, i < held.size() ? held.get(i)[1] : total);
		}
		return held.isEmpty() ? List.of() : List.copyOf(spans);
	}

	/** Beams from a to b (metres), as few as keep each within {@code usual}. */
	private static void beams(List<Span> out, double a, double b, double usual) {
		if (b - a < 0.5) {
			return;
		}
		int n = (int) Math.max(1, Math.ceil((b - a) / usual));
		for (int i = 0; i < n; i++) {
			out.add(new Span(a + (b - a) * i / n, a + (b - a) * (i + 1) / n, false));
		}
	}

	/**
	 * Where a matched bridge's deck is measured (AHN, through the overlay) along most of its run, the deck is laid there:
	 * the lower quarter of the measured tops across it at each step (railings and trees over it read high, see
	 * {@link #deckTop}), joined straight where a step has none, and on to the run's own ends.
	 */
	private static void measuredProfile(Run r, double[] arcAt, double[] meters) {
		int n = arcAt.length;
		if (r.measured.isEmpty() || n < 2) {
			return;
		}
		List<List<Double>> bins = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			bins.add(new ArrayList<>());
		}
		double[] q = new double[2];
		for (double[] h : r.measured) {
			project(r, h[0], h[1], q);
			bins.get((int) Math.max(0, Math.min(n - 1, Math.round(q[0] / r.length() * (n - 1))))).add(h[2]);
		}
		double[] known = new double[n];
		int count = 0;
		for (int i = 0; i < n; i++) {
			List<Double> b = bins.get(i);
			known[i] = Double.NaN;
			if (!b.isEmpty()) {
				b.sort(null);
				known[i] = b.get(b.size() / 4);
				count++;
			}
		}
		if (count * 10 < n * 3) {
			return;
		}
		// The run's own ends stay where they are (on the ground, or on another deck); between, the measurements.
		known[0] = Double.isNaN(known[0]) ? meters[0] : known[0];
		known[n - 1] = Double.isNaN(known[n - 1]) ? meters[n - 1] : known[n - 1];
		int last = 0;
		for (int i = 1; i < n; i++) {
			if (!Double.isNaN(known[i])) {
				for (int j = last + 1; j < i; j++) {
					known[j] = known[last] + (known[i] - known[last]) * (arcAt[j] - arcAt[last]) / (arcAt[i] - arcAt[last]);
				}
				last = i;
			}
		}
		System.arraycopy(known, 0, meters, 0, n);
	}

	/** A run on a bridge whose main run is laid: its deck that one's, where each of its points lies along it. */
	private static void follow(TerrainField field, Run r, Run main) {
		double lat = Mercator.latitudeOfSampleY((r.minY + r.maxY) / 2);
		double blocks = r.length() * Mercator.sampleSpacingMeters(lat) * field.projection().blocksPerMeter(lat);
		int n = Math.max(2, (int) Math.ceil(blocks / STEP_BLOCKS) + 1);
		double[] arcAt = new double[n];
		double[] meters = new double[n];
		double[] at = new double[2];
		double[] q = new double[2];
		for (int i = 0; i < n; i++) {
			arcAt[i] = r.length() * i / (n - 1);
			r.pointAt(arcAt[i], at);
			project(main, at[0], at[1], q);
			meters[i] = main.heightAt(q[0]);
		}
		r.spans = main.spans;
		r.halfWidth = main.halfWidth;
		r.profileArc = arcAt;
		r.profileMeters = meters;
	}

	/**
	 * A measured deck's top at (x, y), metres: the lowest of its measured tops within {@code reach} samples, else the nearest.
	 * A surface model reads the highest thing in each cell, so railings at a deck's edges and trees over it read above the
	 * deck and never below it.
	 */
	static double deckTop(List<double[]> tops, double x, double y, double reach) {
		double lowest = Double.NaN;
		double nearest = Double.NaN;
		double best = Double.MAX_VALUE;
		for (double[] h : tops) {
			double d = Math.hypot(h[0] - x, h[1] - y);
			if (d <= reach && !(h[2] >= lowest)) {
				lowest = h[2];
			}
			if (d < best) {
				best = d;
				nearest = h[2];
			}
		}
		return Double.isNaN(lowest) ? nearest : lowest;
	}

	/** Where (x, y) lies along a run: out[0] the arc length (samples) of its nearest point, out[1] the distance to it. */
	static void project(Run r, double x, double y, double[] out) {
		double best = Double.MAX_VALUE;
		double along = 0;
		for (int i = 0; i + 3 < r.points.length; i += 2) {
			double ax = r.points[i];
			double ay = r.points[i + 1];
			double dx = r.points[i + 2] - ax;
			double dy = r.points[i + 3] - ay;
			double len2 = dx * dx + dy * dy;
			double u = len2 > 0 ? Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len2)) : 0;
			double d = Math.hypot(x - ax - u * dx, y - ay - u * dy);
			if (d < best) {
				best = d;
				along = r.arc[i / 2] + u * Math.sqrt(len2);
			}
		}
		out[0] = along;
		out[1] = best;
	}

	/**
	 * The height a way on the ground (not a bridge) passing under (x, y) is laid at -- its centreline's
	 * ground at the nearest point, as {@link WayRaster} levels it -- the highest of them, or NaN for none.
	 */
	private static double wayBeneath(Map<Long, ProcessedTile> tiles, List<OsmWater.Way> ways, double x, double y, double spacing) {
		double highest = Double.NaN;
		for (OsmWater.Way w : ways) {
			if (w.bridge()) {
				continue;
			}
			double half = (w.widthMeters() / 2 + 0.5) / spacing;
			if (x < w.minX() - half || x > w.maxX() + half || y < w.minY() - half || y > w.maxY() + half) {
				continue;
			}
			double[] p = w.points();
			for (int i = 0; i + 3 < p.length; i += 2) {
				double dx = p[i + 2] - p[i];
				double dy = p[i + 3] - p[i + 1];
				double len2 = dx * dx + dy * dy;
				double u = len2 > 0 ? Math.max(0, Math.min(1, ((x - p[i]) * dx + (y - p[i + 1]) * dy) / len2)) : 0;
				if (Math.hypot(x - p[i] - u * dx, y - p[i + 1] - u * dy) <= half) {
					double h = TerrainField.splineHeight(tiles, p[i] + u * dx, p[i + 1] + u * dy);
					if (!Double.isNaN(h) && !(h <= highest)) {
						highest = h;
					}
				}
			}
		}
		return highest;
	}
}
