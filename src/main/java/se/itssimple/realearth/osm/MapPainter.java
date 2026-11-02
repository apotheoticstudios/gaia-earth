package se.itssimple.realearth.osm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import se.itssimple.realearth.climate.Koppen;
import se.itssimple.realearth.geo.Mercator;

/**
 * A picture of one web-map tile, for maps of places nobody has been to yet (Xaero's World Map): land,
 * water, land cover and use, buildings, roads, railways and borders, in flat colours, from an
 * OpenMapTiles vector tile. A low-zoom tile carries only the bigger things (seas, lakes, motorways,
 * glaciers), so the picture keeps to what fits its scale by itself. Beyond zoom 14, the data's last,
 * a part of a zoom-14 tile is painted bigger.
 *
 * Each pixel gets the kind of the highest thing drawn over it ({@link Rasterizer}'s rule), then that
 * kind's colour, or for bare land its climate's ({@link #land}); roads are at least a pixel wide, else
 * they would vanish when zoomed out. Intermittent water is left out, as the terrain leaves it out.
 */
public final class MapPainter {
	/** Pixels on a side. */
	public static final int SIZE = 256;
	private static final Set<String> LAYERS = Set.of("water", "waterway", "landcover", "landuse", "building", "transportation", "boundary");

	// What a pixel shows, in drawing order: each is drawn over the ones before it.
	private static final byte LAND = 0;
	private static final byte FARMLAND = 1;
	private static final byte GRASS = 2;
	private static final byte WOOD = 3;
	private static final byte WETLAND = 4;
	private static final byte SAND = 5;
	private static final byte ROCK = 6;
	private static final byte ICE = 7;
	private static final byte RESIDENTIAL = 8;
	private static final byte COMMERCIAL = 9;
	private static final byte INDUSTRIAL = 10;
	private static final byte WATER = 11;
	private static final byte BUILDING = 12;
	private static final byte BORDER = 13;
	private static final byte PATH = 14;
	private static final byte MINOR = 15;
	private static final byte MAJOR = 16;
	private static final byte MOTORWAY = 17;
	private static final byte RAIL = 18;
	/** ARGB per kind; near the colours the same things have in Minecraft, so the explored map fits in. */
	private static final int[] COLOURS = {
		0xFF8DB360, 0xFFB5B86E, 0xFF79AE4F, 0xFF4E8336, 0xFF6E9A72, 0xFFDDD4A2, 0xFF9A9A94, 0xFFEEF3F8,
		0xFFB8B0A2, 0xFFC4ADA4, 0xFFAAA49E, 0xFF4470D8, 0xFF8F8173, 0xFF8A5AA0, 0xFFC8B89A, 0xFFF2F0EA,
		0xFFF4DC8C, 0xFFE89A4A, 0xFF4A4A4A};

	private MapPainter() {
	}

	/** The colour this paints water, for checking a picture. */
	public static int water() {
		return COLOURS[WATER];
	}

	/**
	 * Paints tile {@code zoom/x/y} from {@code data}, the vector tile at {@code dataZoom} (at most
	 * {@code zoom}) that holds it. ARGB, row by row from the north-west corner.
	 */
	public static int[] paint(byte[] data, int dataZoom, int zoom, int x, int y) throws IOException {
		int levels = zoom - dataZoom;
		int parts = 1 << levels;
		double lat = Mercator.latitudeOfSampleY(Math.scalb((y + 0.5) * Mercator.TILE_SIZE, Mercator.ZOOM - zoom) - 0.5);
		double metersPerPixel = Mercator.sampleSpacingMeters(lat) * Math.scalb((double) Mercator.TILE_SIZE / SIZE, Mercator.ZOOM - zoom);
		byte[] kinds = new byte[SIZE * SIZE];
		for (VectorTile.Feature f : VectorTile.decode(data, LAYERS)) {
			if (f.flag("intermittent")) {
				continue; // dry much of the year, and dry in the terrain too
			}
			// Tile units of the data tile; this picture is the part (x, y) mod parts of it.
			double side = (double) f.extent() / parts;
			double originX = (x & (parts - 1)) * side;
			double originY = (y & (parts - 1)) * side;
			double perUnit = SIZE / side;
			String cls = f.string("class");
			if (f.type() == VectorTile.POLYGON) {
				byte kind = area(f.layer(), cls);
				if (kind != LAND) {
					Rasterizer.fill(rings(f), originX, originY, SIZE, perUnit, kinds, kind);
				}
			} else if (f.type() == VectorTile.LINE && !"tunnel".equals(f.string("brunnel"))) {
				byte kind = LAND;
				double meters = 0;
				double minPixels = 1;
				switch (f.layer()) {
					case "waterway" -> {
						kind = WATER;
						meters = switch (cls == null ? "" : cls) {
							case "river" -> 12.0;
							case "canal" -> 8.0;
							case "stream" -> 3.0;
							default -> 0.0;
						};
						// Streams only once they are a fair part of a pixel; rivers always.
						minPixels = "river".equals(cls) ? 1 : 0;
					}
					case "boundary" -> {
						if (f.number("admin_level") == 2 && !f.flag("maritime")) {
							kind = BORDER;
						}
					}
					case "transportation" -> {
						byte way = Ways.kind(cls, f.string("subclass"));
						kind = switch (way) {
							case Ways.MOTORWAY -> MOTORWAY;
							case Ways.MAJOR -> MAJOR;
							case Ways.MINOR, Ways.SERVICE -> MINOR;
							case Ways.TRACK, Ways.PATH -> PATH;
							case Ways.RAIL, Ways.TRAM -> RAIL;
							default -> LAND;
						};
						meters = Ways.widthMeters(way, cls);
						// Small ways only where they would show at their own width.
						minPixels = way == Ways.SERVICE || way == Ways.TRACK || way == Ways.PATH ? 0 : 1;
					}
					default -> {
					}
				}
				double pixels = Math.max(minPixels, meters / metersPerPixel);
				if (kind != LAND && pixels >= 0.5) {
					for (int[] p : f.parts()) {
						Rasterizer.stroke(toDouble(p), pixels / 2 / perUnit, originX, originY, SIZE, perUnit, kinds, kind);
					}
				}
			}
		}
		Koppen climate = Koppen.get();
		int[] argb = new int[SIZE * SIZE];
		for (int row = 0; row < SIZE; row++) {
			double rowLat = Mercator.latitudeOfSampleY(Math.scalb((y * SIZE + row + 0.5) * Mercator.TILE_SIZE / SIZE, Mercator.ZOOM - zoom) - 0.5);
			int lastLand = COLOURS[LAND];
			for (int col = 0; col < SIZE; col++) {
				int i = row * SIZE + col;
				if (kinds[i] == LAND && climate != null) {
					int c = climate.classAt(rowLat, (x * SIZE + col + 0.5) / ((double) SIZE * (1 << zoom)) * 360.0 - 180.0);
					// Land the 1 km climate map calls sea (a spit, an islet, the shore) takes its neighbour's.
					lastLand = c == Koppen.SEA ? lastLand : land(c);
					argb[i] = lastLand;
				} else {
					argb[i] = COLOURS[kinds[i]];
				}
			}
		}
		return argb;
	}

	/**
	 * The colour of land with nothing mapped on it, by its Köppen class: the map's land cover is patchy,
	 * and without this deserts and tundra would be green. Follows the same climate as the biomes.
	 */
	public static int land(int climate) {
		if (Koppen.desert(climate)) {
			return 0xFFDCCB98;
		}
		if (Koppen.arid(climate)) {
			return 0xFFBDB57A;
		}
		if (Koppen.iceCap(climate)) {
			return COLOURS[ICE];
		}
		if (Koppen.polar(climate)) {
			return 0xFFA9B49A;
		}
		if (climate == 3) { // Aw: savanna
			return 0xFFA3B55C;
		}
		if (Koppen.tropical(climate)) {
			return 0xFF5E9A3C;
		}
		if (Koppen.subarctic(climate)) {
			return 0xFF7FA466;
		}
		return COLOURS[LAND];
	}

	private static byte area(String layer, String cls) {
		if (cls == null) {
			return layer.equals("building") ? BUILDING : LAND;
		}
		return switch (layer) {
			case "water" -> "swimming_pool".equals(cls) ? LAND : WATER;
			case "building" -> BUILDING;
			case "landcover" -> switch (cls) {
				case "farmland" -> FARMLAND;
				case "grass" -> GRASS;
				case "wood" -> WOOD;
				case "wetland" -> WETLAND;
				case "sand" -> SAND;
				case "rock" -> ROCK;
				case "ice" -> ICE;
				default -> LAND;
			};
			case "landuse" -> switch (cls) {
				case "residential" -> RESIDENTIAL;
				case "commercial", "retail" -> COMMERCIAL;
				case "industrial", "railway", "garages" -> INDUSTRIAL;
				case "cemetery", "pitch", "playground", "stadium" -> GRASS;
				default -> LAND;
			};
			default -> LAND;
		};
	}

	private static List<double[]> rings(VectorTile.Feature f) {
		List<double[]> rings = new ArrayList<>(f.parts().size());
		for (int[] p : f.parts()) {
			rings.add(toDouble(p));
		}
		return rings;
	}

	private static double[] toDouble(int[] p) {
		double[] d = new double[p.length];
		for (int i = 0; i < p.length; i++) {
			d[i] = p[i];
		}
		return d;
	}
}
