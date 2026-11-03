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
 * The single trees OpenStreetMap maps ({@code natural=tree}): street trees, park trees, trees in gardens and fields,
 * where the land cover shows no forest. Read from the Overture Maps base tiles (its land layer's "tree" points, as
 * vector tiles in one PMTiles archive per release, the same release as the building detail), a z13 tile at a time,
 * and kept on disk for good.
 */
public final class OsmTrees {
	/** The tiles' zoom: the archive's deepest. */
	public static final int ZOOM = 13;

	/** Leaf types, as OSM's leaf_type tags them: not given, broadleaved, needleleaved. */
	public static final byte ANY = 0;
	public static final byte BROAD = 1;
	public static final byte NEEDLE = 2;

	/** A tree, in global z15 sample coordinates. */
	public record Tree(double x, double y, byte leaves) {
	}

	/** How a crossing is painted, as OSM's crossing:markings say: zebra bars, or two lines across the road. */
	public static final byte ZEBRA = 1;
	public static final byte LINES = 2;

	/** A marked pedestrian crossing, in global z15 sample coordinates. */
	public record Crossing(double x, double y, byte markings) {
	}

	/** Street furniture the map has: what it is (Overture's class, OSM's: street_lamp, bench, ...) and its name, if any. */
	public record Furniture(double x, double y, String what, String name) {
	}

	/** The street furniture read, as OSM tags it. */
	static final Set<String> FURNITURE = Set.of("street_lamp", "bench", "waste_basket", "bollard", "fire_hydrant", "bicycle_parking", "bus_stop",
		"drinking_water");

	/** A tile's trees, its marked crossings and its street furniture (the base theme's infrastructure points). */
	public record Tile(List<Tree> trees, List<Crossing> crossings, List<Furniture> furniture, boolean failed) {
		public static final Tile EMPTY = new Tile(List.of(), List.of(), List.of(), false);
		public static final Tile FAILED = new Tile(List.of(), List.of(), List.of(), true);
	}

	private final OvertureTiles<Tile> tiles;

	/**
	 * @param template the archive's URL with {release} for the release's name
	 * @param detail the building detail, whose release this follows
	 */
	OsmTrees(String template, OsmDetail detail, Path root) {
		tiles = new OvertureTiles<>("mapped trees", template, detail, root, ZOOM, Set.of("land", "infrastructure"), OsmTrees::parse, Tile.EMPTY,
			Tile.FAILED);
	}

	/** The z13 tile holding a global z15 sample position. */
	public static long tileOf(double sx, double sy) {
		return OvertureTiles.tileOf(ZOOM, sx, sy);
	}

	/** The trees and crossings of a z13 tile ({@link #tileOf}): from disk, else read from the archive and kept. */
	public CompletableFuture<Tile> tile(long key) {
		return tiles.tile(key);
	}

	static Tile parse(List<VectorTile.Feature> features, int tileX, int tileY) {
		double samplesPerTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - ZOOM));
		List<Tree> trees = new ArrayList<>();
		List<Crossing> crossings = new ArrayList<>();
		List<Furniture> furniture = new ArrayList<>();
		boolean americas = tileX < (1 << ZOOM) * (180 - 30) / 360.0 && tileX > (1 << ZOOM) * (180 - 170) / 360.0;
		for (VectorTile.Feature f : features) {
			if (f.type() == VectorTile.POINT && FURNITURE.contains(f.string("class"))) {
				double scale = samplesPerTile / f.extent();
				for (int[] p : f.parts()) {
					for (int i = 0; i + 1 < p.length; i += 2) {
						furniture.add(new Furniture(tileX * samplesPerTile + p[i] * scale - 0.5, tileY * samplesPerTile + p[i + 1] * scale - 0.5,
							f.string("class"), f.string("@name")));
					}
				}
				continue;
			}
			if (f.type() == VectorTile.POINT && "crossing".equals(f.string("class"))) {
				byte markings = markings(f.string("source_tags"), americas);
				double scale = samplesPerTile / f.extent();
				for (int[] p : f.parts()) {
					for (int i = 0; markings != 0 && i + 1 < p.length; i += 2) {
						crossings.add(new Crossing(tileX * samplesPerTile + p[i] * scale - 0.5, tileY * samplesPerTile + p[i + 1] * scale - 0.5, markings));
					}
				}
				continue;
			}
			if (f.type() != VectorTile.POINT || !"tree".equals(f.string("class"))) {
				continue;
			}
			double scale = samplesPerTile / f.extent();
			byte leaves = leaves(f.string("source_tags"));
			for (int[] p : f.parts()) {
				for (int i = 0; i + 1 < p.length; i += 2) {
					trees.add(new Tree(tileX * samplesPerTile + p[i] * scale - 0.5, tileY * samplesPerTile + p[i + 1] * scale - 0.5, leaves));
				}
			}
		}
		return trees.isEmpty() && crossings.isEmpty() && furniture.isEmpty() ? Tile.EMPTY
			: new Tile(List.copyOf(trees), List.copyOf(crossings), List.copyOf(furniture), false);
	}

	/**
	 * How a crossing is painted, from its OSM tags (a JSON object): crossing:markings zebra, ladder or yes as zebra bars,
	 * lines as two lines; none where unmarked. Where the markings aren't mapped, the country's usual: lines in the
	 * Americas, zebra bars elsewhere.
	 */
	static byte markings(String sourceTags, boolean americas) {
		String crossing = "";
		String markings = "";
		try {
			var tags = JsonParser.parseString(sourceTags == null ? "{}" : sourceTags).getAsJsonObject();
			crossing = tags.has("crossing") ? tags.get("crossing").getAsString() : "";
			markings = tags.has("crossing:markings") ? tags.get("crossing:markings").getAsString() : "";
		} catch (RuntimeException e) {
			// unreadable tags: the country's usual
		}
		if (markings.equals("no") || crossing.equals("unmarked") || crossing.equals("no") || crossing.equals("informal")) {
			return 0;
		}
		if (markings.startsWith("lines") || markings.equals("surface")) {
			return LINES;
		}
		if (markings.startsWith("zebra") || markings.startsWith("ladder") || markings.equals("yes") || crossing.equals("zebra")) {
			return ZEBRA;
		}
		return americas ? LINES : ZEBRA;
	}

	/** The leaf type in a feature's OSM tags (a JSON object), ANY where not given. */
	static byte leaves(String sourceTags) {
		if (sourceTags == null || !sourceTags.contains("leaf_type")) {
			return ANY;
		}
		try {
			JsonElement type = JsonParser.parseString(sourceTags).getAsJsonObject().get("leaf_type");
			String s = type == null ? "" : type.getAsString();
			return s.equals("needleleaved") ? NEEDLE : s.equals("broadleaved") ? BROAD : ANY;
		} catch (RuntimeException e) {
			return ANY;
		}
	}

	/** Stops asking for trees; for a server shutting down. */
	public void close() {
		tiles.close();
	}
}
