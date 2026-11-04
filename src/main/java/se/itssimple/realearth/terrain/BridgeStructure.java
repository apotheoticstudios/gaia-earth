package se.itssimple.realearth.terrain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.osm.OsmDetail;
import se.itssimple.realearth.osm.Ways;

/**
 * Road version 7: what holds up a bridge the overlay describes (docs/BRIDGES-PLAN.md), worked out per column from its run,
 * its facts and its spans, so every chunk builds the same bridge. Under the deck a slab, or girders as deep as a
 * twentieth of their span; piers where the bridge's parts are surveyed or mapped, else between its spans; and over each
 * arch span its arch: under the deck if it is masonry (a barrel filled up to the deck) or the deck stands a tenth of the
 * span or more above the arch's feet (two steel or concrete ribs on posts), else over it (ribs at the deck's edges,
 * hangers down to it). In the bridge's material. The result is {@link ChunkTerrain#bridgeFill}; the generator
 * builds it, and the railings.
 *
 * From road version 8 a bridge that opens has its leaf: the cited opening span (else its longest), a slab on piers at
 * its ends. A drawbridge (the Dutch ophaalbrug) also has its lifting gear, built closed: a portal over the road at the
 * leaf's hinge with the counterweight on it, two balance beams on it along the deck's sides reaching over the leaf, and
 * rods from their front ends down to the leaf's tip.
 */
final class BridgeStructure {
	private static final int STEEL = ChunkTerrain.STEEL, CONCRETE = ChunkTerrain.CONCRETE, STONE = ChunkTerrain.STONE, BRICK = ChunkTerrain.BRICK,
		WOOD = ChunkTerrain.WOOD;
	/** Posts between a deck arch's ribs and its deck, at least this far apart (metres). */
	private static final double POST_SPACING = 4;
	/** Hangers from a through arch down to its deck, this far apart (metres). */
	private static final double HANGER_SPACING = 5;
	/**
	 * A drawbridge's balance beams stand this share of its leaf's length above the deck, at least DRAWBRIDGE_MIN_HEIGHT
	 * metres for headroom under the portal.
	 */
	private static final double DRAWBRIDGE_HEIGHT = 0.55;
	private static final double DRAWBRIDGE_MIN_HEIGHT = 6;

	private BridgeStructure() {
	}

	/** A bridge's material: as the data says, else brick for a humpback (a masonry arch by definition), else concrete. */
	static int material(OsmDetail.Bridge f) {
		return switch (f.material()) {
			case "steel", "iron" -> STEEL;
			case "stone" -> STONE;
			case "brick" -> BRICK;
			case "wood" -> WOOD;
			case "concrete" -> CONCRETE;
			default -> f.structure().equals("humpback") ? BRICK : CONCRETE;
		};
	}

	static void apply(TerrainField field, ChunkTerrain t, Map<Long, ProcessedTile> tiles, Bridges.Network net, double[] sx, double[] sy,
		boolean[] onGlobe) {
		List<Bridges.Run> runs = new ArrayList<>();
		for (Bridges.Run r : new LinkedHashSet<>(net.runOf.values())) {
			if (r.facts != null && r.main == r && !r.spans.isEmpty()) {
				runs.add(r);
			}
		}
		if (runs.isEmpty()) {
			return;
		}
		double[] q = new double[2];
		for (int k = 0; k < ChunkTerrain.SPAN * ChunkTerrain.SPAN; k++) {
			if (!onGlobe[k] || t.deckY[k] == Integer.MIN_VALUE || t.deckWay[k] == Ways.NONE) {
				continue;
			}
			// The bridge this deck is: the matched run whose outline (or deck surveyed round it) holds it, nearest.
			Bridges.Run r = null;
			double s = 0;
			double across = Double.MAX_VALUE;
			for (Bridges.Run c : runs) {
				int kk = k;
				if (!BuildingPlan.contains(c.facts.rings(), sx[k], sy[k]) && c.decks.stream().noneMatch(p -> BuildingPlan.contains(p.rings(), sx[kk], sy[kk]))) {
					continue;
				}
				Bridges.project(c, sx[k], sy[k], q);
				if (q[1] < across) {
					r = c;
					s = q[0];
					across = q[1];
				}
			}
			if (r == null) {
				continue;
			}
			double lat = t.latitude[k];
			double spacing = Mercator.sampleSpacingMeters(lat);
			double perMeter = field.projection().blocksPerMeter(lat);
			double m = s * spacing;
			Bridges.Span span = null;
			for (Bridges.Span c : r.spans) {
				if (m >= c.from() && m <= c.to()) {
					span = c;
				}
			}
			if (span == null) {
				continue;
			}
			int material = material(r.facts);
			t.bridgeMaterial[k] = (byte) material;
			int top = t.deckY[k];
			int under = top - 1;
			int bed = t.topSolidY[k] + 1;
			List<int[]> fill = new ArrayList<>();
			double width = Math.max(1e-9, r.halfWidth);
			double side = across / width;
			// Piers and abutments: as surveyed or mapped; else at the bridges' span ends, a fortieth of the span thick.
			boolean pier = false;
			if (!r.parts.isEmpty()) {
				for (OsmDetail.BridgePart p : r.parts) {
					double[] first = p.rings().get(0);
					pier |= first.length == 2 ? Math.hypot(sx[k] - first[0], sy[k] - first[1]) * spacing <= 1.5
						: BuildingPlan.contains(p.rings(), sx[k], sy[k]);
				}
			} else {
				for (int i = 1; i < r.spans.size(); i++) {
					Bridges.Span a = r.spans.get(i - 1);
					Bridges.Span b = r.spans.get(i);
					double half = Math.max(0.75, Math.max(a.length(), b.length()) / 80);
					pier |= Math.abs(m - b.from()) <= half && side <= 0.9;
				}
			}
			if (pier && t.way[k] == Ways.NONE && bed <= under) {
				fill.add(new int[] {bed, under, material});
			}
			Bridges.Span leaf = field.map() != null && field.map().roadVersion() >= 8 ? leaf(r) : null;
			if (leaf != null && r.facts.movable().equals("drawbridge")) {
				drawbridge(leaf, m, edge(t, k), perMeter, top, material, fill);
			}
			if (span == leaf) {
				fill.add(new int[] {under, under, material}); // the leaf: a slab, no girders
			} else if (!span.arch()) {
				// Girders or a slab under the deck.
				int depth = (int) Math.max(1, Math.round(span.length() / 20 * perMeter));
				fill.add(new int[] {Math.max(bed, under - depth + 1), under, material});
			} else {
				arch(field, tiles, t, k, r, span, m, side, perMeter, top, under, bed, material, fill);
			}
			int[] flat = new int[fill.size() * 3];
			for (int i = 0; i < fill.size(); i++) {
				System.arraycopy(fill.get(i), 0, flat, i * 3, 3);
			}
			t.bridgeFill[k] = flat.length == 0 ? null : flat;
		}
	}

	/** Where a bridge opens, the span that does: its cited opening span, else its longest; null for a bridge that doesn't. */
	private static Bridges.Span leaf(Bridges.Run r) {
		if (r.facts.movable().isEmpty()) {
			return null;
		}
		double cited = r.facts.mainSpanMeters();
		return r.spans.stream().min(java.util.Comparator.comparingDouble(c -> cited > 0 ? Math.abs(c.length() - cited) : -c.length())).orElse(null);
	}

	/**
	 * Whether a deck column lies along its deck's side: beside neither more deck about as high nor a way on the ground it
	 * carries on to (as the generator's railings do).
	 */
	private static boolean edge(ChunkTerrain t, int k) {
		int x = k % ChunkTerrain.SPAN;
		for (int n : new int[] {x > 0 ? k - 1 : -1, x < ChunkTerrain.SPAN - 1 ? k + 1 : -1, k - ChunkTerrain.SPAN, k + ChunkTerrain.SPAN}) {
			if (n < 0 || n >= ChunkTerrain.SPAN * ChunkTerrain.SPAN) {
				continue;
			}
			boolean deck = t.deckY[n] != Integer.MIN_VALUE && t.deckWay[n] != Ways.NONE && Math.abs(t.deckY[n] - t.deckY[k]) <= 1;
			boolean road = t.way[n] != Ways.NONE && t.topSolidY[n] >= t.deckY[k] - 1;
			if (!deck && !road) {
				return true;
			}
		}
		return false;
	}

	/** A closed drawbridge's lifting gear over a column (see the class), metres {@code m} along its run. */
	private static void drawbridge(Bridges.Span leaf, double m, boolean edge, double perMeter, int top, int material, List<int[]> fill) {
		// shortcut: which end a drawbridge is hinged at is in none of its data; its end nearer the run's start, until an
		// overlay records it. And its gear stands on the leaf, the portal on its first metre and a half rather than on the
		// abutment behind, the beams not reaching back past it: the structure is built over deck columns only.
		double hinge = leaf.from();
		int beam = top + (int) Math.round(Math.max(DRAWBRIDGE_MIN_HEIGHT, DRAWBRIDGE_HEIGHT * leaf.length()) * perMeter);
		// A block and a half along, so a deck lying across the block grid has a column of each in every band.
		double block = 1.5 / perMeter;
		if (m >= hinge && m <= hinge + block) {
			if (edge) {
				fill.add(new int[] {top + 1, beam, material}); // the portal's posts
			}
			fill.add(new int[] {beam, beam + 2, material}); // its crossbar, the counterweight on it
		}
		if (edge && m >= hinge && m <= leaf.to()) {
			fill.add(new int[] {beam + 1, beam + 1, material}); // a balance beam
		}
		if (edge && m >= leaf.to() - block && m <= leaf.to()) {
			fill.add(new int[] {top + 1, beam, material}); // a rod down to the leaf's tip
		}
	}

	/** An arch span's share of a column: under the deck or over it (see the class). */
	private static void arch(TerrainField field, Map<Long, ProcessedTile> tiles, ChunkTerrain t, int k, Bridges.Run r, Bridges.Span span, double m,
		double side, double perMeter, int top, int under, int bed, int material, List<int[]> fill) {
		double spacing = Mercator.sampleSpacingMeters(t.latitude[k]);
		double mid = (span.from() + span.to()) / 2;
		double u = (m - mid) / (span.length() / 2);
		// Its feet: the water under its middle, else the ground at its ends.
		double[] at = new double[2];
		r.pointAt(mid / spacing, at);
		double feet = TerrainField.waterSurface(tiles, at[0], at[1]);
		if (Double.isNaN(feet)) {
			double[] end = new double[2];
			r.pointAt(span.from() / spacing, at);
			r.pointAt(span.to() / spacing, end);
			feet = Math.max(TerrainField.splineHeight(tiles, at[0], at[1]), TerrainField.splineHeight(tiles, end[0], end[1]));
		}
		if (Double.isNaN(feet)) {
			return;
		}
		// Its feet on the first block above the water (or the ground); its crown just under the deck: a masonry arch's
		// opening up to the deck (the deck its ring there, where a canal bridge has no room for more), a rib's top touching it.
		double springing = Math.round(field.toY(feet, t.latitude[k]));
		double crownUnder = Math.round(field.toY(r.heightAt(mid / spacing), t.latitude[k])) - 2;
		double spanBlocks = span.length() * perMeter;
		boolean masonry = material == STONE || material == BRICK;
		int ring = (int) Math.max(1, Math.round(spanBlocks / (masonry ? 15 : 60)));
		if (masonry || crownUnder - springing >= spanBlocks / 10) {
			// Under the deck.
			double rise = Math.max(0, (masonry ? crownUnder + 1 : crownUnder - ring + 1) - springing);
			int intrados = (int) Math.round(springing + rise * (1 - u * u));
			if (masonry) {
				fill.add(new int[] {Math.max(bed, intrados), under, material});
			} else if (side >= 0.2 && side <= 0.6) {
				fill.add(new int[] {Math.max(bed, intrados), Math.min(under, intrados + ring - 1), material});
				double posts = Math.max(POST_SPACING, span.length() / 12);
				if ((m - span.from()) % posts < 1 / perMeter) {
					fill.add(new int[] {intrados + ring, under, material});
				}
				fill.add(new int[] {under, under, material}); // its deck's slab
			} else {
				fill.add(new int[] {under, under, material});
			}
		} else {
			// Over the deck: ribs along its edges, hangers down to it.
			fill.add(new int[] {under, under, material});
			if (side >= 0.75) {
				int crown = (int) Math.round(top + 1 + spanBlocks / 6 * (1 - u * u));
				fill.add(new int[] {crown - ring + 1, crown, material});
				if ((m - span.from()) % HANGER_SPACING < 1 / perMeter) {
					fill.add(new int[] {top + 1, crown - ring, material});
				}
			}
		}
	}
}
