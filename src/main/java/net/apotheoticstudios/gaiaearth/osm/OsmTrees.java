package net.apotheoticstudios.gaiaearth.osm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.apotheoticstudios.gaiaearth.geo.Mercator;

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

	/**
	 * A tree, in global z15 sample coordinates, and what the map says of it: its leaf type, its species or else its genus as
	 * the trees' names have them ("acer_pseudoplatanus", "platanus"; null if neither), and its height in metres (NaN if not).
	 */
	public record Tree(double x, double y, byte leaves, String taxon, float meters) {
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
			boolean row = f.type() == VectorTile.LINE && "tree_row".equals(f.string("class"));
			if (!row && (f.type() != VectorTile.POINT || !"tree".equals(f.string("class")))) {
				continue;
			}
			double scale = samplesPerTile / f.extent();
			JsonObject tags = tags(f.string("source_tags"));
			byte leaves = leaves(tags);
			String taxon = taxon(tags);
			float meters = meters(tags);
			// A row of trees, an avenue's or a hedgerow's: a tree every ROW_METERS along it.
			double step = ROW_METERS / (Mercator.sampleSpacingMeters(Mercator.latitudeOfSampleY((tileY + 0.5) * samplesPerTile)) * scale);
			for (int[] p : f.parts()) {
				List<double[]> at = row ? along(p, step, f.extent()) : new ArrayList<>();
				for (int i = 0; !row && i + 1 < p.length; i += 2) {
					at.add(new double[] {p[i], p[i + 1]});
				}
				for (double[] q : at) {
					trees.add(new Tree(tileX * samplesPerTile + q[0] * scale - 0.5, tileY * samplesPerTile + q[1] * scale - 0.5, leaves, taxon, meters));
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

	/** How far apart trees stand along a row the map has, in metres, as an avenue's or a hedgerow's commonly do. */
	public static final double ROW_METERS = 8.0;

	/**
	 * Points every {@code step} along a line ({@code line} as a feature's part, x and y in tile units), from half a step in;
	 * only those inside the tile ({@code extent} square), as the line's part in the tile's margin is a neighbour's.
	 */
	public static List<double[]> along(int[] line, double step, int extent) {
		List<double[]> out = new ArrayList<>();
		double next = step / 2;
		double walked = 0;
		for (int i = 0; i + 3 < line.length; i += 2) {
			double dx = line[i + 2] - line[i];
			double dy = line[i + 3] - line[i + 1];
			double length = Math.hypot(dx, dy);
			for (; next <= walked + length; next += step) {
				double u = (next - walked) / length;
				double x = line[i] + dx * u;
				double y = line[i + 1] + dy * u;
				if (x >= 0 && x < extent && y >= 0 && y < extent) {
					out.add(new double[] {x, y});
				}
			}
			walked += length;
		}
		return out;
	}

	/** A feature's OSM tags, from their JSON; null where there are none or they can't be read. */
	public static JsonObject tags(String sourceTags) {
		if (sourceTags == null || sourceTags.isEmpty()) {
			return null;
		}
		try {
			return JsonParser.parseString(sourceTags).getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String tag(JsonObject tags, String key) {
		JsonElement e = tags == null ? null : tags.get(key);
		return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
	}

	/** The leaf type in a tree's OSM tags, ANY where not given. */
	static byte leaves(JsonObject tags) {
		String s = tag(tags, "leaf_type");
		return "needleleaved".equals(s) ? NEEDLE : "broadleaved".equals(s) ? BROAD : ANY;
	}

	/**
	 * A tree's species, else its genus, from its OSM tags, as the trees' names have them: "Platanus × hispanica" is
	 * platanus_hispanica, "Cedrus libani subsp. atlantica" cedrus_libani, "Quercus sp." quercus; null if neither is given.
	 */
	public static String taxon(JsonObject tags) {
		String species = tag(tags, "species");
		List<String> words = new ArrayList<>();
		for (String w : (species == null ? "" : species.toLowerCase(Locale.ROOT).replace('\u00d7', ' ')).trim().split("\\s+")) {
			if (!w.isEmpty() && !w.equals("x") && words.size() < 2) {
				words.add(w);
			}
		}
		String genus = words.isEmpty() ? tag(tags, "genus") : words.get(0);
		genus = genus == null ? null : genus.toLowerCase(Locale.ROOT);
		if (genus == null || !genus.matches("[a-z]+")) {
			return null;
		}
		String epithet = words.size() > 1 && words.get(1).matches("[a-z][a-z-]+") && !words.get(1).equals("spp") ? words.get(1) : null;
		return (epithet == null ? genus : genus + "_" + epithet.replace('-', '_')).intern();
	}

	private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)");

	/** A tree's height in metres from its OSM tags ("12", "12.5", "12 m"), NaN where not given or not a tree's. */
	public static float meters(JsonObject tags) {
		String h = tag(tags, "height");
		java.util.regex.Matcher m = h == null ? null : NUMBER.matcher(h);
		float v = m != null && m.find() ? Float.parseFloat(m.group(1)) : Float.NaN;
		return v >= 1 && v <= 100 ? v : Float.NaN;
	}

	/** Stops asking for trees; for a server shutting down. */
	public void close() {
		tiles.close();
	}
}
