package se.itssimple.realearth.osm;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import se.itssimple.realearth.geo.Mercator;

/**
 * What OpenStreetMap says roads and paths are paved with beyond paved or unpaved: paving stones and setts, gravel,
 * dirt. Read from the Overture Maps transportation tiles (its road segments' road_surface), in the building detail's
 * release, a z14 tile at a time, and kept on disk for good. Only segments with such a surface are kept; the ways
 * themselves are still drawn from OpenFreeMap (see WayRaster, which matches a way's columns to the nearest segment).
 */
public final class OsmRoads {
	public static final int ZOOM = 14;

	/** A road or path segment with a mapped surface: its line in global z15 sample coordinates, and an OsmDetail surface code. */
	public record Segment(double[] points, boolean path, byte surface, double minX, double minY, double maxX, double maxY) {
	}

	/** A tile's segments with a surface worth drawing. */
	public record Tile(List<Segment> segments, boolean failed) {
		public static final Tile EMPTY = new Tile(List.of(), false);
		public static final Tile FAILED = new Tile(List.of(), true);
	}

	private static final Set<String> PATHS = Set.of("footway", "cycleway", "path", "steps", "bridleway", "pedestrian");

	private final OvertureTiles<Tile> tiles;

	OsmRoads(String template, OsmDetail detail, Path root) {
		tiles = new OvertureTiles<>("road surfaces", template, detail, root, ZOOM, Set.of("segment"), OsmRoads::parse, Tile.EMPTY, Tile.FAILED);
	}

	/** The z14 tile holding a global z15 sample position. */
	public static long tileOf(double sx, double sy) {
		return OvertureTiles.tileOf(ZOOM, sx, sy);
	}

	public CompletableFuture<Tile> tile(long key) {
		return tiles.tile(key);
	}

	static Tile parse(List<VectorTile.Feature> features, int tileX, int tileY) {
		double samplesPerTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - ZOOM));
		List<Segment> segments = new ArrayList<>();
		for (VectorTile.Feature f : features) {
			if (f.type() != VectorTile.LINE || !"road".equals(f.string("subtype"))) {
				continue;
			}
			boolean path = PATHS.contains(f.string("class"));
			byte surface = surface(f.string("road_surface"), path);
			if (surface == 0) {
				continue;
			}
			double scale = samplesPerTile / f.extent();
			for (int[] p : f.parts()) {
				if (p.length < 4) {
					continue;
				}
				double[] line = new double[p.length];
				double minX = Double.MAX_VALUE;
				double minY = Double.MAX_VALUE;
				double maxX = -Double.MAX_VALUE;
				double maxY = -Double.MAX_VALUE;
				for (int i = 0; i < p.length; i += 2) {
					line[i] = tileX * samplesPerTile + p[i] * scale - 0.5;
					line[i + 1] = tileY * samplesPerTile + p[i + 1] * scale - 0.5;
					minX = Math.min(minX, line[i]);
					maxX = Math.max(maxX, line[i]);
					minY = Math.min(minY, line[i + 1]);
					maxY = Math.max(maxY, line[i + 1]);
				}
				segments.add(new Segment(line, path, surface, minX, minY, maxX, maxY));
			}
		}
		return segments.isEmpty() ? Tile.EMPTY : new Tile(List.copyOf(segments), false);
	}

	/**
	 * The surface code of a segment's road_surface (a JSON list of {value, between} rules), or 0 where it says nothing
	 * the map's paved or unpaved doesn't: paving stones as pavers, gravel, dirt (and an unpaved path) as unpaved, an
	 * unpaved road as gravel.
	 */
	// shortcut: one surface a segment, its longest rule's; split segments by their between ranges if mixed surfaces show.
	static byte surface(String rules, boolean path) {
		if (rules == null || rules.isEmpty()) {
			return 0;
		}
		String value = null;
		double longest = -1;
		try {
			for (JsonElement e : JsonParser.parseString(rules).getAsJsonArray()) {
				var rule = e.getAsJsonObject();
				double share = 1;
				if (rule.has("between")) {
					var b = rule.getAsJsonArray("between");
					share = b.get(1).getAsDouble() - b.get(0).getAsDouble();
				}
				if (share > longest && rule.has("value")) {
					longest = share;
					value = rule.get("value").getAsString();
				}
			}
		} catch (RuntimeException e) {
			return 0;
		}
		int kind = path ? OsmDetail.FOOTWAY : OsmDetail.ROAD;
		int material = switch (value == null ? "" : value) {
			case "paving_stones" -> OsmDetail.PAVERS;
			case "gravel" -> OsmDetail.GRAVEL;
			case "dirt" -> OsmDetail.UNPAVED;
			case "unpaved" -> path ? OsmDetail.UNPAVED : OsmDetail.GRAVEL;
			default -> 0;
		};
		return material == 0 ? 0 : (byte) (kind * 8 + material);
	}

	public void close() {
		tiles.close();
	}
}
