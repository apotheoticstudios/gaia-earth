package net.apotheoticstudios.gaiaearth.osm;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.apotheoticstudios.gaiaearth.data.Http;
import net.apotheoticstudios.gaiaearth.data.TileStore;
import net.apotheoticstudios.gaiaearth.geo.Mercator;

/**
 * Where the water is, from OpenStreetMap: seas, lakes, ponds, riverbanks and docks (the "water"
 * layer), and rivers, canals, streams and ditches drawn as lines ("waterway"). Read from OpenFreeMap's
 * vector tiles at zoom 14 (OpenMapTiles schema; data (c) OpenStreetMap contributors, ODbL).
 *
 * This says only WHERE water is. How high it stands is worked out from the ground around it (see
 * ProcessedTile), because no map knows a lake's surface elevation.
 *
 * Shapes are returned in the global zoom-15 sample grid the terrain uses, so they rasterise straight
 * onto it.
 */
public final class OsmWater {
	public static final int ZOOM = 14;
	private static final Set<String> LAYERS = Set.of("water", "waterway", "transportation", "building", "poi", "landuse", "place",
		"transportation_name", "landcover");
	/** Land cover kinds kept as parks and greens: the town's green for people (parks), and what is grown or laid out there. */
	private static final Set<String> PARKS = Set.of("park", "garden", "village_green", "recreation_ground", "playground", "allotments", "flowerbed");
	/** Land use kinds kept with them. */
	private static final Set<String> GREEN_LANDUSE = Set.of("playground", "cemetery", "pitch");
	private static final Set<String> PLACE_LAYER = Set.of("place");

	/** A water area: rings of interleaved x,y in global z15 sample coordinates. Even-odd filled. */
	public record Area(List<double[]> rings, boolean sea, boolean river, double minX, double minY, double maxX, double maxY) {
		private static Area of(List<double[]> rings, String cls) {
			double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
			double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
			for (double[] ring : rings) {
				for (int i = 0; i < ring.length; i += 2) {
					minX = Math.min(minX, ring[i]);
					minY = Math.min(minY, ring[i + 1]);
					maxX = Math.max(maxX, ring[i]);
					maxY = Math.max(maxY, ring[i + 1]);
				}
			}
			return new Area(rings, "ocean".equals(cls), "river".equals(cls), minX, minY, maxX, maxY);
		}
	}

	/** A waterway centreline, and how wide to draw it. */
	public record Line(double[] points, double widthMeters, boolean naturalFlow) {
		public Line(double[] points, double widthMeters) {
			this(points, widthMeters, true);
		}
	}

	/**
	 * A road, track, path or railway centreline, in global z15 sample coordinates, with its bounding
	 * box and the map tile it was read from. Tunnels are kept apart (Shapes.tunnels): they run inside
	 * the ground.
	 */
	public record Way(double[] points, double widthMeters, byte kind, boolean bridge, double minX, double minY, double maxX, double maxY, int tileX,
		int tileY, byte markings, byte surface, byte sides, byte layer) {
		/** A way with no markings (see Markings.style), no mapped surface and no layer. */
		public Way(double[] points, double widthMeters, byte kind, boolean bridge, double minX, double minY, double maxX, double maxY, int tileX,
			int tileY) {
			this(points, widthMeters, kind, bridge, minX, minY, maxX, maxY, tileX, tileY, (byte) 0, (byte) 0, (byte) 0, (byte) 0);
		}
	}

	/**
	 * A bridge's outline as the map draws it (OSM's man_made=bridge, OpenMapTiles' transportation class "bridge"): rings of
	 * interleaved x, y in global z15 sample coordinates (even-odd), cut to its map tile, and the layer it is on. A bridge
	 * reaching into the next tile comes as a piece in each, meeting at the tile's edge.
	 */
	public record BridgeArea(List<double[]> rings, int layer, double minX, double minY, double maxX, double maxY) {
	}

	/**
	 * A building footprint: rings of interleaved x,y in global z15 sample coordinates (even-odd), its
	 * height in metres (NaN when the map doesn't say), its colour as 0xRRGGBB (Colours.NONE when the map
	 * doesn't say; else its own or its material's), and its bounding box. Its index in the tile is
	 * stable, so tile and index identify it.
	 */
	public record Building(List<double[]> rings, double heightMeters, int colour, int index, double minX, double minY, double maxX, double maxY) {
	}

	/**
	 * A raised part of a building (OSM's building:part with a min_height): a tower's upper stages, a
	 * storey over a passage. Rings like a Building's, from minMeters up to heightMeters above the ground.
	 */
	public record Part(List<double[]> rings, double minMeters, double heightMeters, int colour, double minX, double minY, double maxX, double maxY) {
	}

	/**
	 * A point of interest -- a shop, café, church, school -- in global z15 sample coordinates, with its
	 * OpenMapTiles class and subclass, name (or null), and floor (0 for the ground floor or unknown). Names
	 * here, a place's and a street's too, are in English where the map has an English one, else its
	 * international one in Latin letters, else its own ({@link VectorTile.Feature#english}).
	 */
	public record Poi(double x, double y, String cls, String subclass, String name, int level) {
	}

	/**
	 * A named place: a city, town or village, or a district of one (suburb, quarter, neighbourhood), in
	 * global z15 sample coordinates. Lower rank is more important. {@code capital} is the admin level the
	 * place is the capital of (2 a country's), or 0.
	 */
	public record Place(double x, double y, String cls, String name, int rank, int capital) {
	}

	/** A named street or path: its centreline in global z15 sample coordinates, its name, and its class. */
	public record Street(double[] points, String name, String cls, double minX, double minY, double maxX, double maxY) {
	}

	/** What land is used for (residential, commercial, industrial, school...): rings like an Area's, and its box. */
	public record Land(List<double[]> rings, String cls, double minX, double minY, double maxX, double maxY) {
	}

	/**
	 * The water, ways, buildings, points of interest and land use in one zoom-14 tile. {@code known} is
	 * false when the map has no tile or could not be fetched.
	 */
	public record Shapes(List<Area> areas, List<Line> lines, boolean known, List<Way> ways, List<Building> buildings, List<Poi> pois,
		List<Land> landuse, List<Place> places, List<Street> streets, List<Part> parts, List<Land> parks, List<Way> tunnels, List<BridgeArea> bridges) {
		/** The map has no such tile: water comes from elevation, and that is final. */
		public static final Shapes UNKNOWN = new Shapes(List.of(), List.of(), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
			List.of(), List.of(), List.of());
		/** The tile could not be downloaded (compared by identity): water comes from elevation for now. */
		public static final Shapes FAILED = new Shapes(List.of(), List.of(), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
			List.of(), List.of(), List.of());

		public boolean failed() {
			return this == FAILED;
		}
	}

	private final String tileJsonUrl;
	private final Path root;
	private final Executor downloads;
	private volatile String template;
	private final Map<Long, CompletableFuture<Shapes>> cache = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<Shapes>> eldest) {
			return size() > 96;
		}
	};

	private volatile OsmDetail detail;
	private volatile int roadVersion;

	/** How roads are drawn in this world (WorldSettings.roadVersion), so a world made before keeps its roads. */
	public void setRoadVersion(int version) {
		roadVersion = version;
	}

	public int roadVersion() {
		return roadVersion;
	}

	public OsmWater(String tileJsonUrl, Path root, Executor downloads) {
		this.tileJsonUrl = tileJsonUrl;
		this.root = root;
		this.downloads = downloads;
	}

	/** Reads the buildings' roofs and parts too (see OsmDetail), kept under {@code detailRoot}. */
	public void enableDetail(String template, String release, String catalog, Path detailRoot) {
		detail = new OsmDetail(template, release, catalog, detailRoot);
	}

	/** The buildings' roofs and parts, or null when not asked for. */
	public OsmDetail detail() {
		return detail;
	}

	private volatile OsmTrees trees;

	/** Reads the single trees mapped too (see OsmTrees), in the building detail's release, kept under {@code treesRoot}. */
	public void enableTrees(String template, Path treesRoot) {
		if (detail != null) {
			trees = new OsmTrees(template, detail, treesRoot);
		}
	}

	/** The single trees mapped, or null when not asked for. */
	public OsmTrees trees() {
		return trees;
	}

	private volatile OsmRoads roads;

	/** Reads the road surfaces mapped too (see OsmRoads), in the building detail's release, kept under {@code roadsRoot}. */
	public void enableRoads(String template, Path roadsRoot) {
		if (detail != null) {
			roads = new OsmRoads(template, detail, roadsRoot);
		}
	}

	/** The road surfaces mapped, or null when not asked for. */
	public OsmRoads roads() {
		return roads;
	}

	/** Stops asking for detail and trees; for a server shutting down. */
	public void close() {
		OsmDetail d = detail;
		if (d != null) {
			d.close();
		}
		OsmTrees t = trees;
		if (t != null) {
			t.close();
		}
		OsmRoads r = roads;
		if (r != null) {
			r.close();
		}
	}

	public CompletableFuture<Shapes> tile(int x, int y) {
		int n = 1 << ZOOM;
		int wx = Math.floorMod(x, n);
		int wy = Math.max(0, Math.min(n - 1, y));
		long key = ((long) wx << 32) | wy;
		synchronized (cache) {
			return cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> load(wx, wy), downloads));
		}
	}

	/** The current tile URL template. OpenFreeMap versions its planet, so it is read from the TileJSON. */
	private String template() throws IOException {
		String t = template;
		if (t == null) {
			synchronized (this) {
				if (template == null) {
					Path cached = root.resolve("tilejson-template.txt");
					try {
						byte[] json = Http.get(tileJsonUrl, 0, 0);
						template = JsonParser.parseString(new String(json, StandardCharsets.UTF_8))
							.getAsJsonObject().getAsJsonArray("tiles").get(0).getAsString();
						Http.writeCache(cached, template.getBytes(StandardCharsets.UTF_8));
					} catch (IOException | RuntimeException e) {
						byte[] old = Http.readCache(cached);
						if (old == null) {
							throw e instanceof IOException io ? io : new IOException(e);
						}
						template = new String(old, StandardCharsets.UTF_8);
					}
				}
				t = template;
			}
		}
		return t;
	}

	private Path file(int zoom, int x, int y) {
		return root.resolve(Integer.toString(zoom)).resolve(Integer.toString(x)).resolve(y + ".pbf");
	}

	private String url(int zoom, int x, int y) throws IOException {
		return template().replace("{z}", Integer.toString(zoom)).replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y));
	}

	/**
	 * A vector tile at any zoom up to 14, undecoded: from the cache, else downloaded into it. Throws
	 * {@link Http.Missing} when the map has no such tile. Blocking.
	 */
	public byte[] raw(int zoom, int x, int y) throws IOException {
		Path file = file(zoom, x, y);
		byte[] data = Http.readCache(file);
		if (data == null) {
			data = Http.get(url(zoom, x, y), 0, 0);
			Http.writeCache(file, data);
		}
		return data;
	}

	private Shapes load(int x, int y) {
		Path file = file(ZOOM, x, y);
		byte[] data;
		try {
			data = raw(ZOOM, x, y);
		} catch (Http.Missing e) {
			return Shapes.UNKNOWN;
		} catch (IOException e) {
			RealEarth.LOGGER.warn("Map data for tile {}/{}/{} unavailable ({}); water there comes from elevation alone", ZOOM, x, y, e.getMessage());
			forget(x, y);
			return Shapes.FAILED;
		}
		try {
			return convert(VectorTile.decode(data, LAYERS), x, y, roadVersion);
		} catch (IOException | RuntimeException e) {
			RealEarth.LOGGER.warn("Unreadable map tile {}; discarding: {}", file, e.toString());
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
			}
			forget(x, y);
			return Shapes.FAILED;
		}
	}

	private void forget(int x, int y) {
		synchronized (cache) {
			cache.remove(((long) x << 32) | y);
		}
	}

	/** Makes sure one tile is on disk, without decoding it: for downloading an area in advance. Blocking. */
	public TileStore.Prefetch prefetch(int x, int y) {
		int n = 1 << ZOOM;
		int wx = Math.floorMod(x, n);
		int wy = Math.max(0, Math.min(n - 1, y));
		Path file = file(ZOOM, wx, wy);
		if (Files.exists(file)) {
			return TileStore.Prefetch.CACHED;
		}
		try {
			Http.writeCache(file, Http.get(url(ZOOM, wx, wy), 0, 0));
			return TileStore.Prefetch.DOWNLOADED;
		} catch (Http.Missing e) {
			return TileStore.Prefetch.CACHED;
		} catch (IOException e) {
			return TileStore.Prefetch.FAILED;
		}
	}

	/** Whether a landcover feature is one of the map's woods: a wood or forest, a mangrove, or a swamp (wooded wetland). */
	public static boolean wood(String cls, String subclass) {
		return "wood".equals(cls) || "mangrove".equals(subclass) || "swamp".equals(subclass);
	}

	/** What the map says the ground is (landcover): its woods, and the kinds {@link #ground} names. */
	public static final byte WOOD = 1;
	public static final byte BEACH = 2;
	public static final byte SAND = 3;
	public static final byte DUNE = 4;
	public static final byte ROCK = 5;
	public static final byte SCREE = 6;
	public static final byte TIDAL_FLAT = 7;
	public static final byte MARSH = 8;

	/**
	 * The ground a landcover feature's subclass stands for: beaches, sand, dunes, bare rock, scree, tidal flats, and open
	 * wetland (marsh, salt marsh, reed beds, wet meadows, bogs); 0 for the rest, whose ground the land cover decides.
	 */
	public static byte ground(String subclass) {
		if (subclass == null) {
			return 0;
		}
		return switch (subclass) {
			case "beach" -> BEACH;
			case "sand" -> SAND;
			case "dune" -> DUNE;
			case "bare_rock" -> ROCK;
			case "scree" -> SCREE;
			case "tidalflat" -> TIDAL_FLAT;
			case "wetland", "marsh", "saltmarsh", "reedbed", "wet_meadow", "bog", "fen" -> MARSH;
			default -> 0;
		};
	}

	private static Shapes convert(List<VectorTile.Feature> features, int tileX, int tileY, int roadVersion) {
		double samplesPerTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - ZOOM));
		List<Area> areas = new ArrayList<>();
		List<Line> lines = new ArrayList<>();
		List<Way> ways = new ArrayList<>();
		List<Building> buildings = new ArrayList<>();
		List<Poi> pois = new ArrayList<>();
		List<Land> landuse = new ArrayList<>();
		List<Place> places = new ArrayList<>();
		List<Street> streets = new ArrayList<>();
		List<Part> raised = new ArrayList<>();
		List<Land> parks = new ArrayList<>();
		List<Way> tunnels = new ArrayList<>();
		List<BridgeArea> bridgeAreas = new ArrayList<>();
		// The tile's middle, for what depends on the country: road widths and markings.
		double centreLon = (tileX + 0.5) / (1 << ZOOM) * 360 - 180;
		double centreLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (tileY + 0.5) / (1 << ZOOM)))));
		for (VectorTile.Feature f : features) {
			boolean tunnel = "tunnel".equals(f.string("brunnel"));
			if (f.flag("intermittent") || tunnel && !f.layer().equals("transportation")) {
				continue; // culverts: water underground
			}
			String cls = f.string("class");
			double scale = samplesPerTile / f.extent();
			List<double[]> parts = new ArrayList<>();
			for (int[] p : f.parts()) {
				double[] d = new double[p.length];
				for (int i = 0; i < p.length; i += 2) {
					d[i] = tileX * samplesPerTile + p[i] * scale - 0.5;
					d[i + 1] = tileY * samplesPerTile + p[i + 1] * scale - 0.5;
				}
				parts.add(d);
			}
			if (f.layer().equals("building") && f.type() == VectorTile.POLYGON) {
				// Parts that float (render_min_height above 0) are upper storeys of something else: kept
				// apart, as the towers and the like of the buildings they stand on.
				double base = f.number("render_min_height");
				if (f.flag("hide_3d")) {
					continue;
				}
				if (!Double.isNaN(base) && base > 0.5) {
					addPart(raised, parts, base, f.number("render_height"), Colours.parse(f.string("colour")));
					continue;
				}
				// At this zoom the map merges all of a tile's buildings of the same height into one
				// feature: split it back into buildings, each an outer ring with the holes after it
				// (outer rings wind one way, holes the other).
				int colour = Colours.parse(f.string("colour"));
				List<double[]> current = null;
				for (double[] ring : parts) {
					if (current == null || signedArea(ring) > 0) {
						if (current != null) {
							addBuilding(buildings, current, f.number("render_height"), colour);
						}
						current = new ArrayList<>();
					}
					current.add(ring);
				}
				if (current != null) {
					addBuilding(buildings, current, f.number("render_height"), colour);
				}
				continue;
			}
			if (f.layer().equals("place") && f.type() == VectorTile.POINT) {
				addPlaces(places, f, parts);
				continue;
			}
			if (f.layer().equals("transportation_name") && f.type() == VectorTile.LINE && f.english() != null) {
				for (double[] part : parts) {
					if (part.length < 4) {
						continue;
					}
					double minX = Double.MAX_VALUE;
					double minY = Double.MAX_VALUE;
					double maxX = -Double.MAX_VALUE;
					double maxY = -Double.MAX_VALUE;
					for (int i = 0; i < part.length; i += 2) {
						minX = Math.min(minX, part[i]);
						maxX = Math.max(maxX, part[i]);
						minY = Math.min(minY, part[i + 1]);
						maxY = Math.max(maxY, part[i + 1]);
					}
					streets.add(new Street(part, f.english(), cls, minX, minY, maxX, maxY));
				}
				continue;
			}
			if (f.layer().equals("poi") && f.type() == VectorTile.POINT) {
				double level = f.number("level");
				for (double[] p : parts) {
					pois.add(new Poi(p[0], p[1], cls, f.string("subclass"), f.english(), Double.isNaN(level) ? 0 : (int) level));
				}
				continue;
			}
			String subclass = f.string("subclass");
			// The map's woods, kept with the parks as "wood": forest trees grow only where they and the land cover agree.
			boolean wood = f.layer().equals("landcover") && wood(cls, subclass);
			// So is the ground it draws (beaches, rock, marsh...), kept by its subclass: the chunk's ground follows it.
			boolean park = wood || f.layer().equals("landcover") && (PARKS.contains(subclass) || ground(subclass) != 0)
				|| f.layer().equals("landuse") && GREEN_LANDUSE.contains(cls);
			if (f.layer().equals("landcover") && !park) {
				continue;
			}
			if (park && f.type() == VectorTile.POLYGON) {
				double minX = Double.MAX_VALUE;
				double minY = Double.MAX_VALUE;
				double maxX = -Double.MAX_VALUE;
				double maxY = -Double.MAX_VALUE;
				for (double[] ring : parts) {
					for (int i = 0; i < ring.length; i += 2) {
						minX = Math.min(minX, ring[i]);
						maxX = Math.max(maxX, ring[i]);
						minY = Math.min(minY, ring[i + 1]);
						maxY = Math.max(maxY, ring[i + 1]);
					}
				}
				parks.add(new Land(parts, f.layer().equals("landuse") ? cls : wood ? "wood" : subclass, minX, minY, maxX, maxY));
				if (f.layer().equals("landcover")) {
					continue;
				}
			}
			if (f.layer().equals("landuse") && f.type() == VectorTile.POLYGON && cls != null) {
				double minX = Double.MAX_VALUE;
				double minY = Double.MAX_VALUE;
				double maxX = -Double.MAX_VALUE;
				double maxY = -Double.MAX_VALUE;
				for (double[] ring : parts) {
					for (int i = 0; i < ring.length; i += 2) {
						minX = Math.min(minX, ring[i]);
						maxX = Math.max(maxX, ring[i]);
						minY = Math.min(minY, ring[i + 1]);
						maxY = Math.max(maxY, ring[i + 1]);
					}
				}
				landuse.add(new Land(parts, cls, minX, minY, maxX, maxY));
				continue;
			}
			if (f.layer().equals("water") && f.type() == VectorTile.POLYGON) {
				if ("swimming_pool".equals(cls)) {
					continue;
				}
				areas.add(Area.of(parts, cls));
			} else if (f.layer().equals("transportation") && f.type() == VectorTile.LINE) {
				byte kind = Ways.kind(cls, f.string("subclass"));
				if (kind != Ways.NONE) {
					boolean bridge = "bridge".equals(f.string("brunnel"));
					boolean oneway = f.number("oneway") == 1 || f.number("oneway") == -1;
					double width = roadVersion >= 1 ? Ways.widthMeters(kind, cls, oneway, centreLat, centreLon) : Ways.widthMeters(kind, cls);
					byte markings = roadVersion >= 1 ? net.apotheoticstudios.gaiaearth.terrain.Markings.style(cls, oneway, f.number("ramp") == 1, centreLat, centreLon) : 0;
					byte surface = roadVersion >= 2 ? Ways.surface(kind, f.string("subclass"), f.string("surface")) : 0;
					byte sides = roadVersion >= 3 && !bridge ? Ways.sides(kind, cls, centreLat, centreLon) : 0;
					for (double[] part : parts) {
						if (part.length >= 4) {
							double minX = Double.MAX_VALUE;
							double minY = Double.MAX_VALUE;
							double maxX = -Double.MAX_VALUE;
							double maxY = -Double.MAX_VALUE;
							for (int i = 0; i < part.length; i += 2) {
								minX = Math.min(minX, part[i]);
								maxX = Math.max(maxX, part[i]);
								minY = Math.min(minY, part[i + 1]);
								maxY = Math.max(maxY, part[i + 1]);
							}
							byte layer = (byte) (Double.isNaN(f.number("layer")) ? 0 : f.number("layer"));
							(tunnel ? tunnels : ways).add(new Way(part, width, kind, bridge, minX, minY, maxX, maxY, tileX, tileY, markings, surface, sides, layer));
						}
					}
				}
			} else if (f.layer().equals("transportation") && f.type() == VectorTile.POLYGON && "bridge".equals(cls)) {
				// Each outer ring and the holes after it (outer rings wind one way, holes the other), cut to the tile itself: the
				// map draws a little way into the next tile, which has its own piece.
				int layer = Double.isNaN(f.number("layer")) ? 0 : (int) f.number("layer");
				double x0 = tileX * samplesPerTile - 0.5;
				double y0 = tileY * samplesPerTile - 0.5;
				List<double[]> current = null;
				for (double[] ring : parts) {
					double[] cut = clip(ring, x0, y0, x0 + samplesPerTile, y0 + samplesPerTile);
					if (current == null || signedArea(ring) > 0) {
						addBridgeArea(bridgeAreas, current, layer);
						current = new ArrayList<>();
					}
					if (cut.length >= 6) {
						current.add(cut);
					}
				}
				addBridgeArea(bridgeAreas, current, layer);
			} else if (f.layer().equals("waterway") && f.type() == VectorTile.LINE) {
				double width = switch (cls == null ? "" : cls) {
					case "river" -> 12.0;
					case "canal" -> 8.0;
					case "stream" -> 3.0;
					case "drain" -> 2.0;
					case "ditch" -> 1.5;
					default -> 0.0;
				};
				if (width > 0) {
					for (double[] part : parts) {
						lines.add(new Line(part, width, "river".equals(cls) || "stream".equals(cls)));
					}
				}
			}
		}
		return new Shapes(areas, lines, true, ways, buildings, pois, landuse, places, streets, raised, parks, tunnels, bridgeAreas);
	}

	private static void addBridgeArea(List<BridgeArea> out, List<double[]> rings, int layer) {
		if (rings == null || rings.isEmpty()) {
			return;
		}
		double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		for (int i = 0; i < rings.get(0).length; i += 2) {
			box[0] = Math.min(box[0], rings.get(0)[i]);
			box[1] = Math.min(box[1], rings.get(0)[i + 1]);
			box[2] = Math.max(box[2], rings.get(0)[i]);
			box[3] = Math.max(box[3], rings.get(0)[i + 1]);
		}
		out.add(new BridgeArea(List.copyOf(rings), layer, box[0], box[1], box[2], box[3]));
	}

	/** A ring (interleaved x, y) cut to a box, edge by edge (Sutherland-Hodgman); empty where none of it is inside. */
	static double[] clip(double[] ring, double x0, double y0, double x1, double y1) {
		double[] pts = ring;
		double[][] edges = {{0, x0, 1}, {0, x1, -1}, {1, y0, 1}, {1, y1, -1}}; // axis, value, side kept (1: >=, -1: <=)
		for (double[] e : edges) {
			int axis = (int) e[0];
			List<double[]> out = new ArrayList<>();
			int n = pts.length / 2;
			for (int i = 0; i < n; i++) {
				double ax = pts[2 * ((i + n - 1) % n)];
				double ay = pts[2 * ((i + n - 1) % n) + 1];
				double bx = pts[2 * i];
				double by = pts[2 * i + 1];
				double av = (axis == 0 ? ax : ay) - e[1];
				double bv = (axis == 0 ? bx : by) - e[1];
				boolean aIn = av * e[2] >= 0;
				boolean bIn = bv * e[2] >= 0;
				if (aIn != bIn) {
					double t = av / (av - bv);
					out.add(new double[] {ax + (bx - ax) * t, ay + (by - ay) * t});
				}
				if (bIn) {
					out.add(new double[] {bx, by});
				}
			}
			pts = new double[out.size() * 2];
			for (int i = 0; i < out.size(); i++) {
				pts[2 * i] = out.get(i)[0];
				pts[2 * i + 1] = out.get(i)[1];
			}
		}
		return pts;
	}

	/** Cities, towns, villages and their districts; not islands, countries, states or lone farms. */
	private static void addPlaces(List<Place> places, VectorTile.Feature f, List<double[]> parts) {
		String cls = f.string("class");
		String name = f.english();
		if (name == null || cls == null) {
			return;
		}
		switch (cls) {
			case "city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood" -> {
				double rank = f.number("rank");
				double capital = f.number("capital");
				for (double[] p : parts) {
					places.add(new Place(p[0], p[1], cls, name, Double.isNaN(rank) ? 99 : (int) rank, Double.isNaN(capital) ? 0 : (int) capital));
				}
			}
			default -> {
			}
		}
	}

	private final Map<Long, CompletableFuture<List<Place>>> placeCache = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<List<Place>>> eldest) {
			return size() > 64;
		}
	};

	/**
	 * The named places in a tile at any zoom up to 14. A low zoom tile covers a wide area and keeps only
	 * its bigger places, cities and towns: that is how far a town's name reaches. At 14, the tile's own.
	 */
	public CompletableFuture<List<Place>> places(int zoom, int x, int y) {
		if (zoom >= ZOOM) {
			return tile(x, y).thenApply(Shapes::places);
		}
		int n = 1 << zoom;
		int wx = Math.floorMod(x, n);
		int wy = Math.max(0, Math.min(n - 1, y));
		long key = (long) zoom << 56 | (long) wx << 28 | wy;
		synchronized (placeCache) {
			return placeCache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> loadPlaces(zoom, wx, wy), downloads));
		}
	}

	private List<Place> loadPlaces(int zoom, int x, int y) {
		try {
			byte[] data = raw(zoom, x, y);
			double samplesPerTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - zoom));
			List<Place> places = new ArrayList<>();
			for (VectorTile.Feature f : VectorTile.decode(data, PLACE_LAYER)) {
				if (f.type() != VectorTile.POINT) {
					continue;
				}
				double scale = samplesPerTile / f.extent();
				List<double[]> parts = new ArrayList<>();
				for (int[] p : f.parts()) {
					parts.add(new double[] {x * samplesPerTile + p[0] * scale - 0.5, y * samplesPerTile + p[1] * scale - 0.5});
				}
				addPlaces(places, f, parts);
			}
			return places;
		} catch (Http.Missing e) {
			return List.of();
		} catch (IOException | RuntimeException e) {
			synchronized (placeCache) {
				placeCache.remove((long) zoom << 56 | (long) x << 28 | y);
			}
			return List.of();
		}
	}

	private static void addPart(List<Part> raised, List<double[]> rings, double min, double height, int colour) {
		if (Double.isNaN(height) || height <= min) {
			return;
		}
		// Merged like buildings: an outer ring and its holes per part.
		List<double[]> current = null;
		for (double[] ring : rings) {
			if (current == null || signedArea(ring) > 0) {
				if (current != null) {
					raised.add(part(current, min, height, colour));
				}
				current = new ArrayList<>();
			}
			current.add(ring);
		}
		if (current != null) {
			raised.add(part(current, min, height, colour));
		}
	}

	private static Part part(List<double[]> rings, double min, double height, int colour) {
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (double[] ring : rings) {
			for (int i = 0; i < ring.length; i += 2) {
				minX = Math.min(minX, ring[i]);
				maxX = Math.max(maxX, ring[i]);
				minY = Math.min(minY, ring[i + 1]);
				maxY = Math.max(maxY, ring[i + 1]);
			}
		}
		return new Part(rings, min, height, colour, minX, minY, maxX, maxY);
	}

	private static void addBuilding(List<Building> buildings, List<double[]> rings, double height, int colour) {
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (double[] ring : rings) {
			for (int i = 0; i < ring.length; i += 2) {
				minX = Math.min(minX, ring[i]);
				maxX = Math.max(maxX, ring[i]);
				minY = Math.min(minY, ring[i + 1]);
				maxY = Math.max(maxY, ring[i + 1]);
			}
		}
		buildings.add(new Building(rings, height, colour, buildings.size(), minX, minY, maxX, maxY));
	}

	/** Surveyor's formula, in tile orientation (y down): positive for an outer ring, as vector tiles define it. */
	private static double signedArea(double[] ring) {
		double a = 0;
		int n = ring.length / 2;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			a += ring[j * 2] * ring[i * 2 + 1] - ring[i * 2] * ring[j * 2 + 1];
		}
		return a / 2;
	}

	/** The zoom-14 tiles covering a range of global z15 sample coordinates. */
	public static List<int[]> tilesCovering(long minX, long minY, long maxX, long maxY) {
		int per = Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - ZOOM));
		List<int[]> out = new ArrayList<>();
		Map<Long, Boolean> seen = new HashMap<>();
		for (long ty = Math.floorDiv(minY, per); ty <= Math.floorDiv(maxY, per); ty++) {
			for (long tx = Math.floorDiv(minX, per); tx <= Math.floorDiv(maxX, per); tx++) {
				if (seen.putIfAbsent((tx << 32) | (ty & 0xFFFFFFFFL), Boolean.TRUE) == null) {
					out.add(new int[] {(int) tx, (int) ty});
				}
			}
		}
		return out;
	}
}
