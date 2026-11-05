package se.itssimple.realearth.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.osm.OsmDetail;

/**
 * Road version 9: a truss bridge's trusses (docs/BRIDGES-PLAN.md), worked out per column from its run and its facts like the
 * rest of its structure (BridgeStructure), so every chunk builds the same trusses.
 *
 * <ul>
 * <li><b>Where.</b> Where the overlay measures them (AHN's surface model, tools/overlay/bridges.py) they stand where it reads
 * them: each truss a strip, as long as measured, its top as high over the deck as measured. Else over each span the data
 * cites (else its longest), along the deck's edges, a tenth of the span high: as deep for its span as the IJsselspoorbrug's
 * trusses measure for theirs.</li>
 * <li><b>Through or deck.</b> A through truss (the default) stands beside the deck, its bottom chord on it and its top chord
 * over it, the deck a slab between them; a deck truss under it, its top chord the deck's bed.</li>
 * <li><b>Pattern.</b> As the data names it, panel by panel between the chords: Warren (the default) its equilateral triangles,
 * Pratt and Howe verticals with diagonals down towards the middle or the ends, K verticals with diagonals meeting halfway up
 * them, lattice diagonals crossing in every panel.</li>
 * </ul>
 *
 * Members keep off railway track; whatever else is under the span (girders, arches) gives way to the truss.
 */
final class BridgeTrusses {
	/** A Warren truss's triangles are equilateral (en.wikipedia.org/wiki/Truss_bridge#Warren_truss): a panel 2 / tan 60° its height. */
	private static final double WARREN_PANEL = 2 / Math.tan(Math.toRadians(60));
	/** The other patterns' panels: about square. */
	private static final double SQUARE_PANEL = 1;
	/** Unmeasured, a truss is this share of its span high: the IJsselspoorbrug's, 9.96 m (AHN) over 95.7 m (Wikidata P2787). */
	private static final double DEPTH = 0.1;
	/** A cited span this close (a share of it) is the span between the piers it names. */
	private static final double SPAN_MATCH = 0.05;

	private BridgeTrusses() {
	}

	/**
	 * A truss plane: measured, the strip it stands in, along its own long axis from (x, y), (ux, uy) a unit in samples (the
	 * bridge's runs can stop short of it where its ways are cut at a map tile's edge); else along its run from {@code from}
	 * metres, at the deck's edges. Its length and its top over the deck in metres.
	 */
	private record Truss(OsmDetail.BridgePart strip, double x, double y, double ux, double uy, double from, double length, double height) {
		/** Where a column at (sx, sy), {@code m} metres along the run, lies along it, metres. */
		double along(double sx, double sy, double m, double spacing) {
			return strip == null ? m - from : ((sx - x) * ux + (sy - y) * uy) * spacing;
		}
	}

	/**
	 * A deck column on a truss bridge: where a truss carries its span, its share of the truss and the deck's slab into
	 * {@code fill}, which becomes the column's {@link ChunkTerrain#bridgeFill}, and true; else false, nothing added.
	 */
	static boolean apply(TerrainField field, OsmDetail.BridgeTile overlay, ChunkTerrain t, int k, Bridges.Run r, Bridges.Span span, double sx,
		double sy, double m, double side, double perMeter, int top, int under, int material, List<int[]> fill) {
		if (field.map() == null || field.map().roadVersion() < 9 || !r.facts.structure().equals("truss")) {
			return false;
		}
		double spacing = Mercator.sampleSpacingMeters(t.latitude[k]);
		boolean carried = false;
		Truss member = null;
		double x = 0;
		for (Truss c : trusses(r, overlay, spacing)) {
			double along = c.along(sx, sy, m, spacing);
			boolean within = along >= 0 && along <= c.length();
			// An unmeasured truss carries its whole span; a measured one from its end to its end (its piers' girders beyond).
			carried |= within || c.strip() == null && span.from() <= c.from() + c.length() / 2 && c.from() + c.length() / 2 <= span.to();
			if (within && (c.strip() == null ? side >= 0.8 : BuildingPlan.contains(c.strip().rings(), sx, sy))) {
				member = c;
				x = along;
			}
		}
		if (!carried) {
			return false;
		}
		String pattern = r.facts.truss();
		fill.add(new int[] {under, under, material}); // the deck's slab
		if (member != null && t.railY[k] == Integer.MIN_VALUE) {
			int high = (int) Math.max(2, Math.round(member.height() * perMeter));
			// Through: the bottom chord on the deck, the top chord's top `high` blocks over the deck's. Deck: the top chord under it.
			int bottom = pattern.contains("deck") ? under - high + 1 : top + 1;
			members(pattern, x, member.length(), member.height(), 0.5 / perMeter, bottom, bottom + high - 1, material, fill);
		}
		t.bridgeFill[k] = fill.stream().flatMapToInt(Arrays::stream).toArray();
		return true;
	}

	/** A run's trusses: those the overlay measures in its outline, else one over each span it cites (else its longest). */
	private static List<Truss> trusses(Bridges.Run r, OsmDetail.BridgeTile overlay, double spacing) {
		List<Truss> out = new ArrayList<>();
		for (OsmDetail.BridgePart p : overlay == null ? List.<OsmDetail.BridgePart>of() : overlay.parts()) {
			if (!p.kind().equals("truss") || !BuildingPlan.contains(r.facts.rings(), (p.minX() + p.maxX()) / 2, (p.minY() + p.maxY()) / 2)) {
				continue;
			}
			// Its long axis: the strip's two corners farthest apart.
			double[] ring = p.rings().get(0);
			double far = 0;
			int a = 0;
			int b = 0;
			for (int i = 0; i + 1 < ring.length; i += 2) {
				for (int j = i + 2; j + 1 < ring.length; j += 2) {
					double d = Math.hypot(ring[i] - ring[j], ring[i + 1] - ring[j + 1]);
					if (d > far) {
						far = d;
						a = i;
						b = j;
					}
				}
			}
			double height = r.facts.trussHeightMeters() > 0 ? r.facts.trussHeightMeters() : DEPTH * far * spacing;
			out.add(new Truss(p, ring[a], ring[a + 1], (ring[b] - ring[a]) / far, (ring[b + 1] - ring[a + 1]) / far, 0, far * spacing, height));
		}
		if (!out.isEmpty()) {
			return out;
		}
		double[] cited = r.facts.spans().length > 0 ? r.facts.spans() : r.facts.mainSpanMeters() > 0 ? new double[] {r.facts.mainSpanMeters()} : null;
		Bridges.Span longest = r.spans.stream().max(java.util.Comparator.comparingDouble(Bridges.Span::length)).orElse(null);
		for (Bridges.Span s : r.spans) {
			if (cited == null ? s == longest : Arrays.stream(cited).anyMatch(c -> Math.abs(s.length() - c) <= c * SPAN_MATCH)) {
				double height = r.facts.trussHeightMeters() > 0 ? r.facts.trussHeightMeters() : DEPTH * s.length();
				out.add(new Truss(null, 0, 0, 0, 0, s.from(), s.length(), height));
			}
		}
		return out;
	}

	/**
	 * A truss's members in a column {@code x} metres along it (of {@code length}, {@code height} metres high, the column
	 * {@code half} metres either side), between its bottom and top chords' blocks: each member a line from node to node,
	 * built over the blocks it passes through in the column.
	 */
	static void members(String pattern, double x, double length, double height, double half, int bottom, int topChord, int material,
		List<int[]> fill) {
		boolean warren = !pattern.contains("pratt") && !pattern.contains("howe") && !pattern.contains("lattice") && !pattern.matches(".*\\bk\\b.*");
		int n = (int) Math.max(1, Math.round(length / ((warren ? WARREN_PANEL : SQUARE_PANEL) * height)));
		double panel = length / n;
		double b = bottom;
		double tp = topChord;
		double mid = (b + tp) / 2;
		List<double[]> lines = new ArrayList<>();
		// Chords: a Warren truss's top chord from its first triangle's apex to its last's.
		lines.add(new double[] {0, b, length, b});
		lines.add(warren ? new double[] {panel / 2, tp, length - panel / 2, tp} : new double[] {0, tp, length, tp});
		for (int i = 0; i < n; i++) {
			double a = i * panel;
			double c = a + panel;
			boolean left = i + 0.5 < n / 2.0;
			boolean right = i + 0.5 > n / 2.0;
			if (warren) {
				lines.add(new double[] {a, b, a + panel / 2, tp});
				lines.add(new double[] {a + panel / 2, tp, c, b});
				continue;
			}
			lines.add(new double[] {a, b, a, tp}); // verticals, and the last at the far end
			if (i == n - 1) {
				lines.add(new double[] {c, b, c, tp});
			}
			if (pattern.contains("lattice")) {
				lines.add(new double[] {a, b, c, tp});
				lines.add(new double[] {a, tp, c, b});
			} else if (pattern.contains("pratt") || pattern.contains("howe")) {
				// Pratt: down towards the middle; Howe: up towards it; the middle panel of an odd number crossed.
				boolean pratt = pattern.contains("pratt");
				if (!right) {
					lines.add(pratt ? new double[] {a, tp, c, b} : new double[] {a, b, c, tp});
				}
				if (!left) {
					lines.add(pratt ? new double[] {a, b, c, tp} : new double[] {a, tp, c, b});
				}
			} else if (left || !right) {
				// K: from halfway up the vertical nearer the middle out to the other's ends.
				lines.add(new double[] {a, tp, c, mid});
				lines.add(new double[] {a, b, c, mid});
			} else {
				lines.add(new double[] {c, tp, a, mid});
				lines.add(new double[] {c, b, a, mid});
			}
		}
		for (double[] l : lines) {
			double x0 = Math.min(l[0], l[2]);
			double x1 = Math.max(l[0], l[2]);
			if (x + half < x0 || x - half > x1) {
				continue;
			}
			double lo;
			double hi;
			if (x1 - x0 < 1e-9) {
				lo = Math.min(l[1], l[3]);
				hi = Math.max(l[1], l[3]);
			} else {
				double ya = l[1] + (l[3] - l[1]) * (Math.max(x0, x - half) - l[0]) / (l[2] - l[0]);
				double yb = l[1] + (l[3] - l[1]) * (Math.min(x1, x + half) - l[0]) / (l[2] - l[0]);
				lo = Math.min(ya, yb);
				hi = Math.max(ya, yb);
			}
			fill.add(new int[] {(int) Math.round(lo), (int) Math.round(hi), material});
		}
	}
}
