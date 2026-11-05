package se.itssimple.realearth.osm;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.data.Http;
import se.itssimple.realearth.data.PmTiles;
import se.itssimple.realearth.geo.Mercator;

/**
 * What OpenStreetMap says of a map tile's buildings beyond their outlines -- roof shapes, roof heights
 * and colours, facade colours, and the parts a landmark is mapped in (a tower, its spire, a dome), each
 * with its own height and roof -- which the OpenFreeMap tiles leave out. Read from the Overture Maps
 * buildings tiles (OpenStreetMap's buildings and building parts with their 3D tags, as vector tiles in
 * one PMTiles archive per monthly release), a tile at a time, and kept on disk for good.
 */
public final class OsmDetail {
	/** A building or a building part with 3D detail, in global z15 sample coordinates. */
	public record Shape(List<double[]> rings, boolean part, Roof roof, double roofMeters, int roofColour, int colour, double heightMeters,
		double minMeters, double minX, double minY, double maxX, double maxY) {
	}

	/**
	 * A surveyed surface from an overlay (see tools/overlay/surfaces_nl.py): a carriageway, pavement, cycle path, square,
	 * step or parking space, in global z15 sample coordinates; {@code code} its kind * 8 + its material.
	 */
	public record Surface(List<double[]> rings, byte code, double minX, double minY, double maxX, double maxY) {
	}

	/**
	 * A bridge from an overlay (tools/overlay/bridges.py): its outline in global z15 sample coordinates and what the data
	 * says of it, "" or 0 where nothing does: structure (beam, arch, humpback, truss, suspension, cable_stayed...), how it
	 * opens (bascule, lift, swing), material, sizes in metres, and its spans' lengths from its north or west end; a truss
	 * bridge's truss pattern (warren, pratt, howe, k, lattice; "deck" for a deck truss) and its trusses' top over its deck;
	 * the layer it is on. From road version 11 the map's own bridge outlines are bridges too, with nothing else known (their
	 * {@code osm} starts "map:", see Bridges.withMapOutlines).
	 */
	public record Bridge(List<double[]> rings, String name, String structure, String movable, String material, double lengthMeters,
		double heightMeters, double clearanceMeters, double[] spans, double mainSpanMeters, double widthMeters, String truss,
		double trussHeightMeters, int layer, String osm, double minX, double minY, double maxX, double maxY) {
	}

	/** A bridge's part from an overlay: a deck, pier, abutment or sill as surveyed, or a mapped pier (a point: one x, y). */
	public record BridgePart(List<double[]> rings, String kind, boolean movable, double minX, double minY, double maxX, double maxY) {
	}

	/** The overlays' bridges reaching into a z14 tile, their parts, and measured deck tops: x, y in samples, metres above sea level. */
	public record BridgeTile(List<Bridge> bridges, List<BridgePart> parts, List<double[]> heights) {
		public static final BridgeTile EMPTY = new BridgeTile(List.of(), List.of(), List.of());
	}

	/** An area whose water a water board holds at a fixed level, from an overlay (tools/overlay/water_levels_nl.py), metres. */
	public record WaterLevel(List<double[]> rings, double meters, double minX, double minY, double maxX, double maxY) {
	}

	/** Surface kinds. */
	public static final int ROAD = 1, FOOTWAY = 2, CYCLEWAY = 3, PARKING = 4, STEPS = 5;
	/** Surface materials. */
	public static final int ASPHALT = 1, CONCRETE = 2, BRICKS = 3, PAVERS = 4, TILES = 5, GRAVEL = 6, UNPAVED = 7;

	/** Roof shapes, as OSM's roof:shape values are grouped here. */
	public enum Roof { NONE, FLAT, GABLED, HIPPED, PYRAMIDAL, SKILLION, DOME, ONION, CONE, ROUND, MANSARD }

	/** A tile's detail, and the buildings measured in it by the overlays (see EarthConfig.buildingOverlays). */
	public record Tile(List<Shape> shapes, List<Shape> measured, boolean failed) {
		public static final Tile EMPTY = new Tile(List.of(), List.of(), false);
		public static final Tile FAILED = new Tile(List.of(), List.of(), true);
	}

	private static final Set<String> LAYERS = Set.of("building", "building_part");

	private final String template;
	private final String release;
	private final String catalog;
	private final Path root;
	private final ExecutorService downloads;
	private volatile PmTiles archive;
	private volatile String releaseName;
	private volatile List<PmTiles> overlays = List.of();
	private final Map<Long, CompletableFuture<List<Surface>>> surfaceCache = new LinkedHashMap<>(32, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<List<Surface>>> eldest) {
			return size() > 32;
		}
	};
	private final Map<Long, CompletableFuture<List<WaterLevel>>> levelCache = new LinkedHashMap<>(32, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<List<WaterLevel>>> eldest) {
			return size() > 32;
		}
	};
	private final Map<Long, CompletableFuture<BridgeTile>> bridgeCache = new LinkedHashMap<>(32, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<BridgeTile>> eldest) {
			return size() > 32;
		}
	};
	private final Map<Long, CompletableFuture<Tile>> cache = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<Tile>> eldest) {
			return size() > 64;
		}
	};

	/**
	 * @param template the archive's URL with {release} for the release's name
	 * @param release a release ("2026-10-21.0"), or "latest" for the newest in {@code catalog}
	 * @param catalog Overture's release catalogue (STAC), listing the releases
	 */
	OsmDetail(String template, String release, String catalog, Path root) {
		this.template = template;
		this.release = release;
		this.catalog = catalog;
		this.root = root;
		AtomicInteger n = new AtomicInteger();
		this.downloads = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "RealEarth-Buildings-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
		se.itssimple.realearth.data.Net.pool("building detail", downloads);
	}

	/** The detail of a z14 map tile: from disk, else read from the archive and kept. */
	public CompletableFuture<Tile> tile(int x, int y) {
		int n = 1 << OsmWater.ZOOM;
		int wx = Math.floorMod(x, n);
		int wy = Math.max(0, Math.min(n - 1, y));
		long key = ((long) wx << 32) | wy;
		synchronized (cache) {
			return cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> load(wx, wy), downloads));
		}
	}

	private Path file(int x, int y) {
		return root.resolve(Integer.toString(OsmWater.ZOOM)).resolve(Integer.toString(x)).resolve(y + ".mvt");
	}

	/** Reads measured buildings too, from Gaia overlays (comma-separated URLs; see tools/overlay). */
	public void useOverlays(String urls) {
		List<PmTiles> list = new ArrayList<>();
		for (String url : urls == null ? new String[0] : urls.split(",")) {
			if (!url.isBlank()) {
				list.add(new PmTiles(url.strip()));
			}
		}
		overlays = List.copyOf(list);
	}

	/** The overlays' surveyed surfaces in a z14 map tile; none, at once, without overlays. */
	public CompletableFuture<List<Surface>> surfaces(int x, int y) {
		List<PmTiles> list = overlays;
		if (list.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}
		int n = 1 << OsmWater.ZOOM;
		int wx = Math.floorMod(x, n);
		int wy = Math.max(0, Math.min(n - 1, y));
		synchronized (surfaceCache) {
			return surfaceCache.computeIfAbsent(((long) wx << 32) | wy, k -> CompletableFuture.supplyAsync(() -> {
				List<Surface> out = new ArrayList<>();
				// shortcut: every overlay is asked for every layer, so a buildings-only archive is read twice a tile; fine
				// while overlays are local files or few, split archives by layer in the manifest if hosting makes it costly.
				for (PmTiles overlay : list) {
					try {
						byte[] data = overlay.tile(OsmWater.ZOOM, wx, wy);
						if (data != null) {
							out.addAll(parseSurfaces(VectorTile.decode(data, Set.of("surfaces")), wx, wy));
						}
					} catch (IOException | RuntimeException e) {
						RealEarth.LOGGER.warn("Surface overlay {} unreadable for map tile {}/{}/{}: {}", overlay.url(), OsmWater.ZOOM, wx, wy, e.toString());
					}
				}
				return List.copyOf(out);
			}, downloads));
		}
	}

	/** The overlays' fixed water levels in a z14 map tile; none, at once, without overlays. */
	public CompletableFuture<List<WaterLevel>> waterLevels(int x, int y) {
		List<PmTiles> list = overlays;
		if (list.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}
		int n = 1 << OsmWater.ZOOM;
		int wx = Math.floorMod(x, n);
		int wy = Math.max(0, Math.min(n - 1, y));
		synchronized (levelCache) {
			return levelCache.computeIfAbsent(((long) wx << 32) | wy, k -> CompletableFuture.supplyAsync(() -> {
				List<WaterLevel> out = new ArrayList<>();
				for (PmTiles overlay : list) {
					try {
						byte[] data = overlay.tile(OsmWater.ZOOM, wx, wy);
						for (VectorTile.Feature f : data == null ? List.<VectorTile.Feature>of() : VectorTile.decode(data, Set.of("water_levels"))) {
							List<double[]> rings = rings(f, wx, wy);
							double[] box = box(rings);
							if (f.type() == VectorTile.POLYGON && !Double.isNaN(f.number("level"))) {
								out.add(new WaterLevel(rings, f.number("level"), box[0], box[1], box[2], box[3]));
							}
						}
					} catch (IOException | RuntimeException e) {
						RealEarth.LOGGER.warn("Water level overlay {} unreadable for map tile {}/{}/{}: {}", overlay.url(), OsmWater.ZOOM, wx, wy, e.toString());
					}
				}
				return List.copyOf(out);
			}, downloads));
		}
	}

	/** The overlays' bridges in a z14 map tile (see {@link Bridge}); none, at once, without overlays. */
	public CompletableFuture<BridgeTile> bridges(int x, int y) {
		List<PmTiles> list = overlays;
		if (list.isEmpty()) {
			return CompletableFuture.completedFuture(BridgeTile.EMPTY);
		}
		int n = 1 << OsmWater.ZOOM;
		int wx = Math.floorMod(x, n);
		int wy = Math.max(0, Math.min(n - 1, y));
		synchronized (bridgeCache) {
			return bridgeCache.computeIfAbsent(((long) wx << 32) | wy, k -> CompletableFuture.supplyAsync(() -> {
				List<Bridge> bridges = new ArrayList<>();
				List<BridgePart> parts = new ArrayList<>();
				List<double[]> heights = new ArrayList<>();
				for (PmTiles overlay : list) {
					try {
						byte[] data = overlay.tile(OsmWater.ZOOM, wx, wy);
						if (data != null) {
							BridgeTile t = parseBridges(VectorTile.decode(data, Set.of("bridges", "bridge_parts", "bridge_heights")), wx, wy);
							bridges.addAll(t.bridges());
							parts.addAll(t.parts());
							heights.addAll(t.heights());
						}
					} catch (IOException | RuntimeException e) {
						RealEarth.LOGGER.warn("Bridge overlay {} unreadable for map tile {}/{}/{}: {}", overlay.url(), OsmWater.ZOOM, wx, wy, e.toString());
					}
				}
				return new BridgeTile(List.copyOf(bridges), List.copyOf(parts), List.copyOf(heights));
			}, downloads));
		}
	}

	/** An overlay's bridges, their parts and measured deck tops. */
	static BridgeTile parseBridges(List<VectorTile.Feature> features, int tileX, int tileY) {
		List<Bridge> bridges = new ArrayList<>();
		List<BridgePart> parts = new ArrayList<>();
		List<double[]> heights = new ArrayList<>();
		for (VectorTile.Feature f : features) {
			List<double[]> rings = rings(f, tileX, tileY);
			if (rings.isEmpty()) {
				continue;
			}
			double[] box = box(rings);
			switch (f.layer()) {
				case "bridges" -> {
					String spans = String.valueOf(f.string("spans"));
					double[] lengths = spans.isBlank() || spans.equals("null") ? new double[0]
						: java.util.Arrays.stream(spans.split(";")).mapToDouble(Double::parseDouble).toArray();
					bridges.add(new Bridge(rings, text(f, "name"), text(f, "structure"), text(f, "movable"), text(f, "material"), metres(f, "length"),
						metres(f, "height"), metres(f, "clearance"), lengths, metres(f, "main_span"), metres(f, "width"), text(f, "truss"),
						metres(f, "truss_height"), (int) metres(f, "layer"), text(f, "osm"), box[0], box[1], box[2], box[3]));
				}
				case "bridge_parts" -> parts.add(new BridgePart(rings, text(f, "kind"), "yes".equals(f.string("movable")), box[0], box[1], box[2], box[3]));
				case "bridge_heights" -> heights.add(new double[] {rings.get(0)[0], rings.get(0)[1], f.number("h")});
				default -> {
				}
			}
		}
		return new BridgeTile(bridges, parts, heights);
	}

	/** A size in metres, 0 where the overlay has none. */
	private static double metres(VectorTile.Feature f, String key) {
		double v = f.number(key);
		return Double.isNaN(v) ? 0 : v;
	}

	private static String text(VectorTile.Feature f, String key) {
		String s = f.string(key);
		return s == null ? "" : s;
	}

	/** The box round rings of interleaved x, y: min x, min y, max x, max y. */
	private static double[] box(List<double[]> rings) {
		double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		for (double[] r : rings) {
			for (int i = 0; i + 1 < r.length; i += 2) {
				box[0] = Math.min(box[0], r[i]);
				box[1] = Math.min(box[1], r[i + 1]);
				box[2] = Math.max(box[2], r[i]);
				box[3] = Math.max(box[3], r[i + 1]);
			}
		}
		return box;
	}

	/** An overlay's surfaces, by their kind and material names. */
	static List<Surface> parseSurfaces(List<VectorTile.Feature> features, int tileX, int tileY) {
		List<Surface> out = new ArrayList<>();
		for (VectorTile.Feature f : features) {
			int kind = switch (String.valueOf(f.string("kind"))) {
				case "road" -> ROAD;
				case "footway" -> FOOTWAY;
				case "cycleway" -> CYCLEWAY;
				case "parking" -> PARKING;
				case "steps" -> STEPS;
				default -> 0;
			};
			int material = switch (String.valueOf(f.string("material"))) {
				case "asphalt" -> ASPHALT;
				case "concrete" -> CONCRETE;
				case "bricks" -> BRICKS;
				case "pavers" -> PAVERS;
				case "tiles" -> TILES;
				case "gravel" -> GRAVEL;
				default -> UNPAVED;
			};
			if (f.type() != VectorTile.POLYGON || kind == 0) {
				continue;
			}
			List<double[]> rings = rings(f, tileX, tileY);
			double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
			for (double[] r : rings) {
				for (int i = 0; i < r.length; i += 2) {
					box[0] = Math.min(box[0], r[i]);
					box[1] = Math.min(box[1], r[i + 1]);
					box[2] = Math.max(box[2], r[i]);
					box[3] = Math.max(box[3], r[i + 1]);
				}
			}
			out.add(new Surface(rings, (byte) (kind * 8 + material), box[0], box[1], box[2], box[3]));
		}
		return out;
	}

	/** A feature's rings in global z15 sample coordinates, from a z14 tile's. */
	private static List<double[]> rings(VectorTile.Feature f, int tileX, int tileY) {
		double samplesPerTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - OsmWater.ZOOM));
		double scale = samplesPerTile / f.extent();
		List<double[]> rings = new ArrayList<>();
		for (int[] p : f.parts()) {
			double[] ring = new double[p.length];
			for (int i = 0; i < p.length; i += 2) {
				ring[i] = tileX * samplesPerTile + p[i] * scale - 0.5;
				ring[i + 1] = tileY * samplesPerTile + p[i + 1] * scale - 0.5;
			}
			rings.add(ring);
		}
		return rings;
	}

	private Tile load(int x, int y) {
		Tile overture = loadOverture(x, y);
		List<Shape> measured = new ArrayList<>();
		for (PmTiles overlay : overlays) {
			try {
				byte[] data = overlay.tile(OsmWater.ZOOM, x, y);
				if (data != null) {
					measured.addAll(parseMeasured(VectorTile.decode(data, Set.of("buildings")), x, y));
				}
			} catch (IOException | RuntimeException e) {
				RealEarth.LOGGER.warn("Building overlay {} unreadable for map tile {}/{}/{}: {}", overlay.url(), OsmWater.ZOOM, x, y, e.toString());
			}
		}
		return measured.isEmpty() ? overture : new Tile(overture.shapes(), List.copyOf(measured), overture.failed());
	}

	/** An overlay's buildings: footprints with measured heights (metres), roof heights and "slanted" or "flat" roofs. */
	static List<Shape> parseMeasured(List<VectorTile.Feature> features, int tileX, int tileY) {
		List<Shape> shapes = new ArrayList<>();
		for (VectorTile.Feature f : features) {
			double height = f.number("height");
			if (f.type() != VectorTile.POLYGON || !(height > 0)) {
				continue;
			}
			boolean slanted = "slanted".equals(f.string("roof"));
			shapes.add(shape(rings(f, tileX, tileY), false, slanted ? Roof.GABLED : Roof.FLAT, slanted ? f.number("roof_height") : 0, Colours.NONE, Colours.NONE,
				height, 0));
		}
		return shapes;
	}

	private Tile loadOverture(int x, int y) {
		Path file = file(x, y);
		byte[] data = Http.readCache(file);
		if (data == null) {
			try {
				data = archive().tile(OsmWater.ZOOM, x, y);
			} catch (Http.Missing e) {
				// The release is gone from the bucket (they are kept for a while): look for the newest.
				archive = null;
				return failed(x, y, e);
			} catch (IOException | RuntimeException e) {
				return failed(x, y, e);
			}
			// No tile: no buildings there. Kept as an empty file, so it is not asked for again.
			data = data == null ? new byte[0] : data;
			Http.writeCache(file, data);
		}
		if (data.length == 0) {
			return Tile.EMPTY;
		}
		try {
			return parse(VectorTile.decode(data, LAYERS), x, y);
		} catch (IOException | RuntimeException e) {
			RealEarth.LOGGER.warn("Unreadable building detail {}; discarding: {}", file, e.toString());
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
			}
			return failed(x, y, e);
		}
	}

	private Tile failed(int x, int y, Exception e) {
		RealEarth.LOGGER.warn("Building detail for map tile {}/{}/{} unavailable ({}); its buildings are built from their outlines",
			OsmWater.ZOOM, x, y, e.getMessage());
		synchronized (cache) {
			cache.remove(((long) x << 32) | y); // asked again another time
		}
		return Tile.FAILED;
	}

	/** The archive of the release in use: the configured one, or the newest in the catalogue (remembered on disk). */
	private synchronized PmTiles archive() throws IOException {
		PmTiles a = archive;
		if (a != null) {
			return a;
		}
		String name = release;
		if (name == null || name.isBlank() || name.equalsIgnoreCase("latest")) {
			Path known = root.resolve("release.txt");
			try {
				name = newest(new String(Http.get(catalog, 0, 0), StandardCharsets.UTF_8));
				Http.writeCache(known, name.getBytes(StandardCharsets.UTF_8));
			} catch (IOException | RuntimeException e) {
				byte[] old = Http.readCache(known);
				if (old == null) {
					throw e instanceof IOException io ? io : new IOException(e);
				}
				name = new String(old, StandardCharsets.UTF_8).strip();
			}
		}
		a = new PmTiles(template.replace("{release}", name));
		releaseName = name;
		archive = a;
		RealEarth.LOGGER.info("Building detail from Overture Maps release {}", name);
		return a;
	}

	/** The release in use, found as the archive is. */
	String release() throws IOException {
		archive();
		return releaseName;
	}

	/** The newest release a STAC catalogue links to: its child catalogues are named by release, which sort by date. */
	static String newest(String catalogJson) throws IOException {
		String best = null;
		for (JsonElement link : JsonParser.parseString(catalogJson).getAsJsonObject().getAsJsonArray("links")) {
			String href = link.getAsJsonObject().get("href").getAsString();
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("/(\\d{4}-\\d{2}-\\d{2}\\.\\d+)/").matcher(href);
			if (m.find() && (best == null || m.group(1).compareTo(best) > 0)) {
				best = m.group(1);
			}
		}
		if (best == null) {
			throw new IOException("no release in the catalogue");
		}
		return best;
	}

	/** A tile's buildings and parts: each an outer ring and its holes, with what is known of it. */
	static Tile parse(List<VectorTile.Feature> features, int tileX, int tileY) {
		double samplesPerTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - OsmWater.ZOOM));
		List<Shape> shapes = new ArrayList<>();
		for (VectorTile.Feature f : features) {
			if (f.type() != VectorTile.POLYGON || f.flag("is_underground")) {
				continue;
			}
			double scale = samplesPerTile / f.extent();
			boolean part = f.layer().equals("building_part");
			double roofMeters = f.number("roof_height");
			double height = f.number("height");
			if (Double.isNaN(height)) {
				double floors = f.number("num_floors");
				height = Double.isNaN(floors) ? Double.NaN : floors * 3 + (Double.isNaN(roofMeters) ? 0 : roofMeters);
			}
			double min = f.number("min_height");
			if (Double.isNaN(min)) {
				double floor = f.number("min_floor");
				min = Double.isNaN(floor) ? 0 : floor * 3;
			}
			int roofColour = Colours.parse(f.string("roof_color"));
			if (roofColour == Colours.NONE) {
				roofColour = roofMaterial(f.string("roof_material"));
			}
			Roof roof = roof(f.string("roof_shape"));
			int colour = Colours.parse(f.string("facade_color"));
			List<double[]> current = null;
			for (int[] p : f.parts()) {
				double[] ring = new double[p.length];
				for (int i = 0; i < p.length; i += 2) {
					ring[i] = tileX * samplesPerTile + p[i] * scale - 0.5;
					ring[i + 1] = tileY * samplesPerTile + p[i + 1] * scale - 0.5;
				}
				if (current == null || signedArea(ring) > 0) {
					if (current != null) {
						shapes.add(shape(current, part, roof, roofMeters, roofColour, colour, height, min));
					}
					current = new ArrayList<>();
				}
				current.add(ring);
			}
			if (current != null) {
				shapes.add(shape(current, part, roof, roofMeters, roofColour, colour, height, min));
			}
		}
		return new Tile(List.copyOf(shapes), List.of(), false);
	}

	private static Shape shape(List<double[]> rings, boolean part, Roof roof, double roofMeters, int roofColour, int colour, double height, double min) {
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (double[] r : rings) {
			for (int i = 0; i < r.length; i += 2) {
				minX = Math.min(minX, r[i]);
				maxX = Math.max(maxX, r[i]);
				minY = Math.min(minY, r[i + 1]);
				maxY = Math.max(maxY, r[i + 1]);
			}
		}
		return new Shape(rings, part, roof, roofMeters, roofColour, colour, height, min, minX, minY, maxX, maxY);
	}

	/** Surveyor's formula, in tile orientation (y down): positive for an outer ring. */
	private static double signedArea(double[] ring) {
		double a = 0;
		int n = ring.length / 2;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			a += ring[j * 2] * ring[i * 2 + 1] - ring[i * 2] * ring[j * 2 + 1];
		}
		return a / 2;
	}

	static Roof roof(String shape) {
		if (shape == null) {
			return Roof.NONE;
		}
		return switch (shape.strip().toLowerCase(Locale.ROOT)) {
			case "flat" -> Roof.FLAT;
			case "gabled", "saltbox", "double_saltbox", "quadruple_saltbox", "gambrel", "side_hipped" -> Roof.GABLED;
			case "hipped", "half_hipped", "half-hipped", "many", "crosspitched" -> Roof.HIPPED;
			case "pyramidal", "pyramid" -> Roof.PYRAMIDAL;
			case "skillion", "lean_to" -> Roof.SKILLION;
			case "dome" -> Roof.DOME;
			case "onion" -> Roof.ONION;
			case "cone", "conical", "spherical" -> Roof.CONE;
			case "round", "barrel" -> Roof.ROUND;
			case "mansard" -> Roof.MANSARD;
			default -> Roof.NONE;
		};
	}

	/** A roof's colour from what it is made of, where no colour is given. */
	private static int roofMaterial(String material) {
		if (material == null) {
			return Colours.NONE;
		}
		return switch (material.strip().toLowerCase(Locale.ROOT)) {
			case "roof_tiles", "tile", "tiles", "clay" -> 0xa0442b;
			case "slate", "eternit", "tar_paper" -> 0x4a4e55;
			case "copper" -> 0x5fa58a;
			case "metal", "tin", "zinc", "steel" -> 0x8a8f96;
			case "glass" -> Colours.GLASS;
			case "grass", "plants" -> 0x4f7a32;
			case "thatch" -> 0xb59a5a;
			case "wood" -> 0x6b4a2b;
			case "concrete", "gravel", "stone" -> 0x9a9a96;
			default -> Colours.NONE;
		};
	}

	/** Stops reading; for a server shutting down. */
	public void close() {
		downloads.shutdownNow();
	}
}
