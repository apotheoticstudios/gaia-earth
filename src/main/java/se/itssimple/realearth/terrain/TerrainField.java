package se.itssimple.realearth.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import se.itssimple.realearth.EarthConfig;
import se.itssimple.realearth.climate.Koppen;
import se.itssimple.realearth.landcover.WorldCover;
import se.itssimple.realearth.osm.OsmDetail;
import se.itssimple.realearth.osm.OsmRoads;
import se.itssimple.realearth.osm.OsmTrees;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.data.TileStore;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.geo.Projection;
import se.itssimple.realearth.hydrology.CurrentAngle;
import se.itssimple.realearth.hydrology.MappedCurrents;

/**
 * The terrain of the Earth dimension: block columns in, ground and water heights out.
 *
 * Everything is asynchronous. A chunk asks for the processed tiles under it, which ask for the raw
 * tiles around them, which come from disk or the network; world generation is handed a future and
 * never blocks a worker on I/O.
 *
 * Interpolation from the source grid to one-metre blocks is a uniform cubic B-spline. Unlike
 * bilinear filtering it is C2-continuous, so slopes change smoothly instead of showing the grid as
 * creases every few metres, and unlike Catmull-Rom it never overshoots, so it cannot invent ridges
 * or pits next to cliffs.
 */
public final class TerrainField implements AutoCloseable {
	private final TileStore store;
	private final EarthConfig config;
	private final Projection projection;
	private final VerticalScale scale;
	private final int waterSurfaceVersion;
	private final ExecutorService workers;
	private final Map<Long, CompletableFuture<ProcessedTile>> processed;
	private final Map<Long, CompletableFuture<ChunkTerrain>> chunks;

	private final OsmWater map;
	private final WorldCover cover;

	/** How long a result built from stand-ins is reused before the data is asked for again. */
	private static final long RETRY_DEGRADED_MS = 30_000;

	/**
	 * @param map   OpenStreetMap water, or null to take water from elevation alone
	 * @param cover ESA land cover, or null to leave the ground cover to climate alone
	 */
	public TerrainField(TileStore store, EarthConfig config, Projection projection, VerticalScale scale, OsmWater map, WorldCover cover) {
		this(store, config, projection, scale, map, cover, 0);
	}

	public TerrainField(TileStore store, EarthConfig config, Projection projection, VerticalScale scale, OsmWater map, WorldCover cover,
		int waterSurfaceVersion) {
		this.map = map;
		this.cover = cover;
		this.store = store;
		this.config = config;
		this.projection = projection;
		this.scale = scale;
		this.waterSurfaceVersion = waterSurfaceVersion;
		AtomicInteger n = new AtomicInteger();
		int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
		this.workers = Executors.newFixedThreadPool(threads, r -> {
			Thread t = new Thread(r, "RealEarth-Terrain-" + n.incrementAndGet());
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			return t;
		});
		se.itssimple.realearth.data.Net.pool("terrain", workers);
		this.processed = lru(48);
		Buildings.clearCache();
		this.chunks = lru(2048);
	}

	private static <V> Map<Long, V> lru(int max) {
		return new LinkedHashMap<>(max, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
				return size() > max;
			}
		};
	}

	public Projection projection() {
		return projection;
	}

	public VerticalScale scale() {
		return scale;
	}

	private volatile int startX;
	private volatile int startZ;

	/** Sets where players start; Overworld portals lead to positions relative to it. */
	public void setStart(double latitude, double longitude) {
		startX = (int) Math.floor(projection.blockX(latitude, longitude));
		startZ = (int) Math.floor(projection.blockZ(latitude));
	}

	public int startX() {
		return startX;
	}

	public int startZ() {
		return startZ;
	}

	/**
	 * Block Y of an elevation in metres at a latitude. Heights follow the projection's horizontal
	 * scale, so terrain keeps its true proportions wherever the map is enlarged or shrunk.
	 */
	public double toY(double meters, double latitude) {
		return scale.toY(meters * projection.blocksPerMeter(latitude));
	}

	/** Elevation in metres of a block Y at a latitude: the inverse of {@link #toY}. */
	public double toElevation(double y, double latitude) {
		return scale.toElevation(y) / projection.blocksPerMeter(latitude);
	}

	public TileStore store() {
		return store;
	}

	private static long key(int a, int b) {
		return ((long) a << 32) | (b & 0xFFFFFFFFL);
	}

	private CompletableFuture<ProcessedTile> processedTile(int tx, int ty) {
		int x = Math.floorMod(tx, Mercator.TILES_PER_AXIS);
		int y = Math.max(0, Math.min(Mercator.TILES_PER_AXIS - 1, ty));
		synchronized (processed) {
			CompletableFuture<ProcessedTile> existing = processed.get(key(x, y));
			if (existing != null && isStale(existing)) {
				processed.remove(key(x, y));
			}
			return processed.computeIfAbsent(key(x, y), k -> {
				@SuppressWarnings("unchecked")
				CompletableFuture<float[]>[] raw = new CompletableFuture[9];
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						raw[(dy + 1) * 3 + (dx + 1)] = store.tile(x + dx, y + dy);
					}
				}
				List<CompletableFuture<OsmWater.Shapes>> shapes = new ArrayList<>();
				if (map != null) {
					int margin = 48;
					for (int[] t : OsmWater.tilesCovering((long) x * Mercator.TILE_SIZE - margin, (long) y * Mercator.TILE_SIZE - margin,
						(long) (x + 1) * Mercator.TILE_SIZE + margin, (long) (y + 1) * Mercator.TILE_SIZE + margin)) {
						shapes.add(map.tile(t[0], t[1]));
					}
				}
				CompletableFuture<?>[] all = new CompletableFuture[9 + shapes.size()];
				System.arraycopy(raw, 0, all, 0, 9);
				for (int i = 0; i < shapes.size(); i++) {
					all[9 + i] = shapes.get(i);
				}
				return CompletableFuture.allOf(all).thenApplyAsync(v -> {
					float[][] tiles = new float[9][];
					for (int i = 0; i < 9; i++) {
						tiles[i] = raw[i].join();
					}
					List<OsmWater.Shapes> water = shapes.stream().map(CompletableFuture::join).toList();
					ProcessedTile tile = ProcessedTile.build(tiles, x, y, config, map == null ? null : water, waterSurfaceVersion);
					tile.degraded = water.stream().anyMatch(OsmWater.Shapes::failed);
					for (float[] t : tiles) {
						tile.degraded |= store.isDegraded(t);
					}
					return tile;
				}, workers);
			});
		}
	}

	/** The terrain of a chunk, computed once and remembered for a while. */
	public CompletableFuture<ChunkTerrain> chunk(int chunkX, int chunkZ) {
		synchronized (chunks) {
			CompletableFuture<ChunkTerrain> existing = chunks.get(key(chunkX, chunkZ));
			if (existing != null && !existing.isCompletedExceptionally() && !isStale(existing)) {
				return existing;
			}
			CompletableFuture<ChunkTerrain> created = computeChunk(chunkX, chunkZ);
			chunks.put(key(chunkX, chunkZ), created);
			return created;
		}
	}

	/** True for a finished result built from stand-ins long enough ago that the data is worth asking for again. */
	private static boolean isStale(CompletableFuture<?> f) {
		if (!f.isDone() || f.isCompletedExceptionally()) {
			return false;
		}
		Object v = f.join();
		long now = System.currentTimeMillis();
		if (v instanceof ChunkTerrain t) {
			return t.degraded && now - t.createdAt > RETRY_DEGRADED_MS;
		}
		if (v instanceof ProcessedTile t) {
			return t.degraded && now - t.createdAt > RETRY_DEGRADED_MS;
		}
		return false;
	}

	/** Forgets a chunk's terrain, so the next request computes it afresh. */
	public void forgetChunk(int chunkX, int chunkZ) {
		synchronized (chunks) {
			chunks.remove(key(chunkX, chunkZ));
		}
	}

	public OsmWater map() {
		return map;
	}

	/**
	 * The nearest column to (x, z), within {@code radius}, that is open dry ground: no building, no
	 * water. For putting arrivals on the street rather than on a roof. Uses only terrain already known;
	 * returns (x, z) itself when nothing better is known.
	 */
	public int[] openGround(int x, int z, int radius) {
		for (int r = 0; r <= radius; r++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					int wx = x + dx;
					int wz = z + dz;
					ChunkTerrain t = chunkIfReady(wx >> 4, wz >> 4);
					if (t == null) {
						continue;
					}
					int k = ChunkTerrain.index(wx & 15, wz & 15);
					if (t.building[k] == 0 && t.waterTopY[k] == Integer.MIN_VALUE && t.deckY[k] == Integer.MIN_VALUE) {
						return new int[] {wx, wz};
					}
				}
			}
		}
		return new int[] {x, z};
	}

	/**
	 * The nearest dry land to a block position, for a world's first spawn: the position itself if it is
	 * dry; else, in rings growing outward to 1,000 km, the nearest spot the coarse elevation puts clearly
	 * above the sea, then the nearest open dry column there at full detail. Returns {x, z}, or the
	 * position itself when no land is found. Blocks on downloads: for world creation only.
	 */
	public int[] landNear(int x, int z) {
		int[] here = dryColumn(x, z);
		if (here != null) {
			return here;
		}
		for (double r = 256; r <= 1_000_000 * projection.blocksPerMeter(projection.latitudeAt(z)); r *= 1.5) {
			int points = (int) Math.min(512, Math.max(16, 2 * Math.PI * r / 400));
			double bestTurn = -1;
			for (int i = 0; i < points; i++) {
				double turn = 2 * Math.PI * i / points;
				double bx = x + r * Math.sin(turn);
				double bz = z + r * Math.cos(turn);
				double lat = projection.latitudeAt(bz);
				double lon = projection.longitudeAt(bx, lat);
				if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 80) {
					continue;
				}
				if (coarseElevationMeters(lat, Projection.wrapLongitude(lon)) > 5) {
					int[] land = dryColumn((int) bx, (int) bz);
					if (land != null) {
						se.itssimple.realearth.RealEarth.LOGGER.info("The start point is in water; spawning on land {} km away", Math.round(r / 1000));
						return land;
					}
				}
			}
		}
		return new int[] {x, z};
	}

	/** An open dry column within a chunk or so of (x, z), at full detail, or null. */
	private int[] dryColumn(int x, int z) {
		try {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					chunkBlocking((x >> 4) + dx, (z >> 4) + dz, 120);
				}
			}
		} catch (Exception e) {
			return null;
		}
		int[] open = openGround(x, z, 24);
		ChunkTerrain t = chunkIfReady(open[0] >> 4, open[1] >> 4);
		int k = ChunkTerrain.index(open[0] & 15, open[1] & 15);
		return t != null && t.waterTopY[k] == Integer.MIN_VALUE && t.building[k] == 0 ? open : null;
	}

	/** Real ground elevation in metres at a block column, if its terrain is known; NaN otherwise. */
	public double groundMetersAt(int x, int z) {
		ChunkTerrain t = chunkIfReady(x >> 4, z >> 4);
		return t == null ? Double.NaN : t.groundMeters[ChunkTerrain.index(x & 15, z & 15)];
	}

	/** The chunk's terrain if it is already known, without starting any work. */
	public ChunkTerrain chunkIfReady(int chunkX, int chunkZ) {
		CompletableFuture<ChunkTerrain> f;
		synchronized (chunks) {
			f = chunks.get(key(chunkX, chunkZ));
		}
		return f != null && f.isDone() && !f.isCompletedExceptionally() ? f.join() : null;
	}

	/** Zoom of the coarse elevation used for quick, approximate answers. ~1.2 km per sample. */
	private static final int COARSE_ZOOM = 7;

	/**
	 * Approximate ground elevation from a zoom-7 tile (one tile covers ~300 km), for questions asked
	 * far from where chunks are being generated -- /locate, explorer maps, stronghold placement --
	 * where fetching full-resolution tiles would mean thousands of downloads. Blocks only on the first
	 * question about a region; those tiles are few and cached.
	 */
	public double coarseElevationMeters(double latitude, double longitude) {
		double scale = (double) (1 << COARSE_ZOOM) / (1 << Mercator.ZOOM);
		double sx = (Mercator.sampleX(longitude) + 0.5) * scale - 0.5;
		double sy = (Mercator.sampleY(latitude) + 0.5) * scale - 0.5;
		int px = (int) Math.round(sx);
		int py = (int) Math.round(sy);
		int size = Mercator.TILE_SIZE;
		try {
			float[] tile = store.tile(COARSE_ZOOM, Math.floorDiv(px, size), Math.floorDiv(py, size)).get(30, TimeUnit.SECONDS);
			return tile[Math.floorMod(py, size) * size + Math.floorMod(px, size)];
		} catch (Exception e) {
			return 100.0;
		}
	}

	/** Thrown instead of answering from stand-ins, by callers that would rather wait for the real data. */
	public static final class Unavailable extends Exception {
		public Unavailable() {
			super("data not downloaded yet");
		}
	}

	/**
	 * Raw elevation at any zoom, bilinear between sample centres. Throws {@link Unavailable} rather
	 * than answer from a stand-in tile. Blocks on the tile fetch; meant for
	 * worker threads that need terrain far from any chunk (level-of-detail rendering).
	 */
	public double elevationAt(double latitude, double longitude, int zoom) throws Exception {
		double scale = (double) (1 << zoom) / (1 << Mercator.ZOOM);
		double sx = (Mercator.sampleX(longitude) + 0.5) * scale - 0.5;
		double sy = (Mercator.sampleY(latitude) + 0.5) * scale - 0.5;
		long x0 = (long) Math.floor(sx);
		long y0 = (long) Math.floor(sy);
		double fx = sx - x0;
		double fy = sy - y0;
		int size = Mercator.TILE_SIZE;
		long last = (long) size << zoom;
		double[] v = new double[4];
		for (int i = 0; i < 4; i++) {
			long gx = Math.floorMod(x0 + (i & 1), last);
			long gy = Math.max(0, Math.min(last - 1, y0 + (i >> 1)));
			float[] tile = store.tile(zoom, (int) (gx / size), (int) (gy / size)).get(60, TimeUnit.SECONDS);
			if (store.isDegraded(tile)) {
				throw new Unavailable();
			}
			v[i] = tile[(int) (gy % size) * size + (int) (gx % size)];
		}
		return (v[0] * (1 - fx) + v[1] * fx) * (1 - fy) + (v[2] * (1 - fx) + v[3] * fx) * fy;
	}

	/**
	 * Smoothed ground height (the processed z15 grid chunks are built from), bilinear. For distant
	 * terrain near the player, so it meets the real chunks without a step.
	 */
	public double smoothedElevationAt(double latitude, double longitude) throws Exception {
		double sx = Mercator.sampleX(longitude);
		double sy = Mercator.sampleY(latitude);
		long x0 = (long) Math.floor(sx);
		long y0 = (long) Math.floor(sy);
		double fx = sx - x0;
		double fy = sy - y0;
		double[] v = new double[4];
		for (int i = 0; i < 4; i++) {
			long gx = Math.floorMod(x0 + (i & 1), Mercator.SAMPLES_PER_AXIS);
			long gy = Math.max(0, Math.min(Mercator.SAMPLES_PER_AXIS - 1, y0 + (i >> 1)));
			ProcessedTile tile = processedTile((int) (gx / Mercator.TILE_SIZE), (int) (gy / Mercator.TILE_SIZE)).get(120, TimeUnit.SECONDS);
			if (tile.degraded) {
				throw new Unavailable();
			}
			v[i] = tile.height[indexIn(gx, gy)];
		}
		return (v[0] * (1 - fx) + v[1] * fx) * (1 - fy) + (v[2] * (1 - fx) + v[3] * fx) * fy;
	}

	public WorldCover landCover() {
		return cover;
	}

	/** Blocks until the chunk is known. For commands and portals, never for world generation. */
	public ChunkTerrain chunkBlocking(int chunkX, int chunkZ, long timeoutSeconds) throws Exception {
		return chunk(chunkX, chunkZ).get(timeoutSeconds, TimeUnit.SECONDS);
	}

	private static final int CURRENT_SLOPE_REACH = 8;
	// Covers the widest line-only channel (12 metres) even at Mercator's latitude limit.
	private static final int LOCAL_WATERWAY_REACH = 24;

	/** Read only the water geometry and elevation needed for currents, including in old chunks. */
	public CompletableFuture<MappedCurrents.Field> mappedCurrents(int chunkX, int chunkZ) {
		if (map == null) return CompletableFuture.completedFuture(MappedCurrents.EMPTY);
		double nearX = chunkX * 16 + 8;
		double midLat = projection.latitudeAt(chunkZ * 16 + 8);
		if (!projection.onGlobe(midLat, projection.longitudeOffsetAt(nearX, midLat))
			|| Math.abs(midLat) >= Mercator.MAX_LATITUDE) return CompletableFuture.completedFuture(MappedCurrents.EMPTY);
		double reference = Mercator.sampleX(projection.longitudeAt(nearX, midLat));
		double[] x = new double[256], y = new double[256], latitude = new double[256];
		double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < 256; i++) {
			latitude[i] = projection.latitudeAt(chunkZ * 16 + (i >> 4) + 0.5);
			x[i] = MappedCurrents.near(Mercator.sampleX(projection.longitudeAt(chunkX * 16 + (i & 15) + 0.5, latitude[i])), reference);
			y[i] = Mercator.sampleY(latitude[i]);
			minX = Math.min(minX, x[i]); maxX = Math.max(maxX, x[i]);
			minY = Math.min(minY, y[i]); maxY = Math.max(maxY, y[i]);
		}
		long left = (long) Math.floor(minX), right = (long) Math.ceil(maxX);
		long top = (long) Math.floor(minY), bottom = (long) Math.ceil(maxY);
		long haloMinX = (long) Math.floor(minX) - CURRENT_SLOPE_REACH - 1;
		long haloMaxX = (long) Math.ceil(maxX) + CURRENT_SLOPE_REACH + 1;
		long haloMinY = (long) Math.floor(minY) - CURRENT_SLOPE_REACH - 1;
		long haloMaxY = (long) Math.ceil(maxY) + CURRENT_SLOPE_REACH + 1;
		// Only mapped river polygons can need a distant centreline. An ocean or narrow stream
		// chunk should not fetch the same outer ring of map tiles as a wide river.
		var geometry = currentGeometry(left, top, right, bottom, LOCAL_WATERWAY_REACH).thenComposeAsync(local ->
			MappedCurrents.intersectsRiverArea(local, left, top, right, bottom)
				? currentGeometry(left, top, right, bottom, MappedCurrents.REACH_SAMPLES)
				: CompletableFuture.completedFuture(local), workers);
		return geometry.thenComposeAsync(shapes -> {
			if (shapes.stream().anyMatch(OsmWater.Shapes::failed)) return CompletableFuture.completedFuture(null);
			var directions = MappedCurrents.directions(projection, nearX, x, y, shapes);
			byte[] angles = directions.world();
			Map<Long, CompletableFuture<ProcessedTile>> elevations = new HashMap<>();
			for (int i = 0; i < 256; i++) {
				if (angles[i] == CurrentAngle.NONE) continue;
				long sx = (long) Math.floor(x[i]), sy = (long) Math.floor(y[i]);
				addTiles(elevations, sx - 1, sx + 2, sy - 1, sy + 2);
			}
			if (elevations.isEmpty()) return CompletableFuture.completedFuture(MappedCurrents.EMPTY);
			var required = java.util.Set.copyOf(elevations.keySet());
			addTiles(elevations, haloMinX, haloMaxX, haloMinY, haloMaxY);
			return CompletableFuture.allOf(elevations.values().toArray(CompletableFuture[]::new)).thenApplyAsync(done -> {
				Map<Long, ProcessedTile> tiles = new HashMap<>();
				elevations.forEach((key, tile) -> tiles.put(key, tile.join()));
				if (required.stream().map(tiles::get).anyMatch(tile -> tile.degraded || tile.mapWater == null)) return null;
				int[] surface = new int[256];
				byte[] speeds = new byte[256];
				byte[] depths = new byte[256];
				float[] sample = new float[4];
				for (int i = 0; i < 256; i++) {
					if (angles[i] == CurrentAngle.NONE) continue;
					sample(tiles, x[i], y[i], sample);
					if (Float.isNaN(sample[1])) angles[i] = CurrentAngle.NONE;
					else {
						surface[i] = (int) Math.round(toY(sample[1], latitude[i])) - 1;
						int bed = (int) Math.round(toY(sample[0], latitude[i])) - 1;
						depths[i] = (byte) Math.clamp(surface[i] - bed, 1, 16);
						var direction = MappedCurrents.direction(directions.map()[i]);
						double dx = direction.x * CURRENT_SLOPE_REACH;
						double dy = direction.z * CURRENT_SLOPE_REACH;
						double slope = Double.NaN;
						// A straight probe around a bend may leave the channel. Only use a pair of
						// levels when the intervening stations are water too.
						if (mappedWaterAt(tiles, x[i] - dx / 2, y[i] - dy / 2) == 1
							&& mappedWaterAt(tiles, x[i] + dx / 2, y[i] + dy / 2) == 1) {
							double upstream = mappedLevelAt(tiles, x[i] - dx, y[i] - dy);
							double downstream = mappedLevelAt(tiles, x[i] + dx, y[i] + dy);
							slope = (upstream - downstream) / (2 * CURRENT_SLOPE_REACH * Mercator.sampleSpacingMeters(latitude[i]));
						}
						speeds[i] = MappedCurrents.speed(sample[3], slope);
						// An estuary loses its downstream push as the flow spreads into the sea.
						// A dry station stops the probe so a river behind a dune keeps its current.
						if (sample[1] <= 3) {
							for (int step = 1; step <= CURRENT_SLOPE_REACH; step++) {
								double fraction = step / (double) CURRENT_SLOPE_REACH;
								byte water = mappedWaterAt(tiles, x[i] + dx * fraction, y[i] + dy * fraction);
								if (water == 2) speeds[i] = MappedCurrents.taperOutlet(speeds[i], fraction);
								if (water != 1) break;
							}
						}
					}
				}
				return MappedCurrents.of(angles, surface, speeds, depths);
			}, workers);
		}, workers);
	}

	private CompletableFuture<List<OsmWater.Shapes>> currentGeometry(long left, long top, long right, long bottom, int reach) {
		var tiles = OsmWater.tilesCovering(left - reach, top - reach, right + reach, bottom + reach)
			.stream().map(tile -> map.tile(tile[0], tile[1])).toList();
		return CompletableFuture.allOf(tiles.toArray(CompletableFuture[]::new))
			.thenApply(done -> tiles.stream().map(CompletableFuture::join).toList());
	}

	private static double mappedLevelAt(Map<Long, ProcessedTile> tiles, double sx, double sy) {
		byte water = mappedWaterAt(tiles, sx, sy);
		if (water == 2) return 0;
		if (water != 1) return Double.NaN;
		long x = (long) Math.floor(sx), y = (long) Math.floor(sy);
		double dx = sx - x, dy = sy - y;
		double sum = 0, weight = 0;
		for (int b = 0; b < 2; b++) {
			for (int a = 0; a < 2; a++) {
				ProcessedTile tile = tileFor(tiles, x + a, y + b);
				if (tile == null || tile.degraded) continue;
				float level = tile.lakeLevel[indexIn(x + a, y + b)];
				if (Float.isNaN(level)) continue;
				double w = (a == 0 ? 1 - dx : dx) * (b == 0 ? 1 - dy : dy);
				sum += w * level;
				weight += w;
			}
		}
		return weight > 0 ? sum / weight : Double.NaN;
	}

	private static byte mappedWaterAt(Map<Long, ProcessedTile> tiles, double sx, double sy) {
		ProcessedTile tile = tileFor(tiles, (long) Math.floor(sx + 0.5), (long) Math.floor(sy + 0.5));
		byte kind = mappedKindAt(tile, sx, sy);
		return kind >= 0 && tile.waterKinds != null ? WaterSurfaceProfiles.wetMask(kind) : kind;
	}

	private static byte mappedKindAt(ProcessedTile tile, double sx, double sy) {
		double x = sx + 0.5, y = sy + 0.5;
		long ix = (long) Math.floor(x), iy = (long) Math.floor(y);
		if (tile == null || tile.mapWater == null) return -1;
		long localX = Math.floorMod(ix, Mercator.TILE_SIZE);
		long localY = Math.clamp(iy, 0, Mercator.SAMPLES_PER_AXIS - 1) % Mercator.TILE_SIZE;
		int fineX = Math.min(ProcessedTile.FINE_SIZE - 1, (int) ((localX + (x - ix)) * ProcessedTile.FINE));
		int fineY = Math.min(ProcessedTile.FINE_SIZE - 1, (int) ((localY + (y - iy)) * ProcessedTile.FINE));
		return tile.mapWater[fineY * ProcessedTile.FINE_SIZE + fineX];
	}

	private CompletableFuture<ChunkTerrain> computeChunk(int chunkX, int chunkZ) {
		// When the chunk was first asked for: how long it then waits for each kind of data, for /earth perf.
		long asked = System.nanoTime();
		int n = ChunkTerrain.SPAN;
		double[] sx = new double[n * n];
		double[] sy = new double[n * n];
		boolean[] onGlobe = new boolean[n * n];
		ChunkTerrain terrain = new ChunkTerrain();

		long minX = Long.MAX_VALUE;
		long maxX = Long.MIN_VALUE;
		long minY = Long.MAX_VALUE;
		long maxY = Long.MIN_VALUE;
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				int k = j * n + i;
				double bx = chunkX * 16 + (i - 1) + 0.5;
				double bz = chunkZ * 16 + (j - 1) + 0.5;
				double lat = projection.latitudeAt(bz);
				double lonOffset = projection.longitudeOffsetAt(bx, lat);
				onGlobe[k] = projection.onGlobe(lat, lonOffset);
				double lon = Projection.wrapLongitude(projection.originLongitude() + lonOffset);
				terrain.latitude[k] = Math.max(-90, Math.min(90, lat));
				terrain.longitude[k] = lon;
				if (!onGlobe[k]) {
					continue;
				}
				sx[k] = Mercator.sampleX(lon);
				sy[k] = Mercator.sampleY(lat);
				long fx = (long) Math.floor(sx[k]);
				long fy = (long) Math.floor(sy[k]);
				minX = Math.min(minX, fx - 1);
				maxX = Math.max(maxX, fx + 2);
				minY = Math.min(minY, fy - 1);
				maxY = Math.max(maxY, fy + 2);
			}
		}

		Map<Long, CompletableFuture<ProcessedTile>> needed = new HashMap<>();
		if (minX <= maxX) {
			// A chunk straddling the antimeridian spans the whole sample range; collect per column.
			if (maxX - minX > 4L * Mercator.TILE_SIZE) {
				for (int k = 0; k < n * n; k++) {
					if (onGlobe[k]) {
						addTiles(needed, (long) Math.floor(sx[k]) - 1, (long) Math.floor(sx[k]) + 2,
							(long) Math.floor(sy[k]) - 1, (long) Math.floor(sy[k]) + 2);
					}
				}
			} else {
				// A few samples more than the spline needs: roads level to their centreline, which
				// may lie just outside the chunk.
				addTiles(needed, minX - 4, maxX + 4, minY - 4, maxY + 4);
			}
		}

		List<CompletableFuture<OsmWater.Shapes>> wayTiles = new ArrayList<>();
		List<int[]> wayTileCoords = new ArrayList<>();
		if (map != null && (config.useRoads || config.useRailways || config.useBuildings) && minX <= maxX && maxX - minX <= 4L * Mercator.TILE_SIZE) {
			// Bridges within reach of the chunk too, for their ramps.
			int reach = config.useRoads || config.useRailways ? BRIDGE_REACH_SAMPLES : 4;
			for (int[] w : OsmWater.tilesCovering(minX - reach, minY - reach, maxX + reach, maxY + reach)) {
				wayTiles.add(map.tile(w[0], w[1]));
				wayTileCoords.add(new int[] {Math.floorMod(w[0], 1 << OsmWater.ZOOM), w[1]});
			}
		}
		long chunkMinX = minX;
		long chunkMinY = minY;
		long chunkMaxX = maxX;
		long chunkMaxY = maxY;

		CompletableFuture<WorldCover.Lookup> coverLookup = cover == null
			? CompletableFuture.completedFuture((lat, lon) -> WorldCover.NONE)
			: cover.prepare(terrain.latitude, terrain.longitude, 0).exceptionally(e -> new WorldCover.Lookup() {
				@Override
				public int classAt(double lat, double lon) {
					return WorldCover.NONE;
				}

				@Override
				public boolean degraded() {
					return true;
				}
			});

		CompletableFuture<?>[] all = new CompletableFuture[needed.size() + 1 + wayTiles.size()];
		int a = 0;
		for (CompletableFuture<ProcessedTile> f : needed.values()) {
			all[a++] = f;
		}
		all[a++] = coverLookup;
		for (CompletableFuture<OsmWater.Shapes> f : wayTiles) {
			all[a++] = f;
		}
		CompletableFuture.allOf(needed.values().toArray(CompletableFuture[]::new)).whenComplete((v, e) ->
			se.itssimple.realearth.data.Net.waited("elevation", System.nanoTime() - asked, e != null || needed.values().stream().anyMatch(f -> f.join().degraded)));
		coverLookup.whenComplete((l, e) -> se.itssimple.realearth.data.Net.waited("land cover", System.nanoTime() - asked, l == null || l.degraded()));
		if (!wayTiles.isEmpty()) {
			CompletableFuture.allOf(wayTiles.toArray(CompletableFuture[]::new)).whenComplete((v, e) ->
				se.itssimple.realearth.data.Net.waited("map", System.nanoTime() - asked, e != null || wayTiles.stream().anyMatch(f -> f.join().failed())));
		}
		return CompletableFuture.allOf(all).thenCompose(v -> {
			// Buildings stand on the ground under their whole footprint, which may reach well beyond
			// this chunk: fetch the terrain there too before going on.
			List<OsmWater.Shapes> shapes = wayTiles.stream().map(CompletableFuture::join).toList();
			long nearing = se.itssimple.realearth.Perf.start();
			List<Buildings.Candidate> candidates = config.useBuildings
				? Buildings.near(this, wayTileCoords, shapes, chunkX, chunkZ, chunkMinX, chunkMinY, chunkMaxX, chunkMaxY)
				: List.of();
			se.itssimple.realearth.Perf.stop("terrain.buildings.near", nearing);
			Map<Long, CompletableFuture<ProcessedTile>> withBuildings = new HashMap<>(needed);
			// Bridges are laid as whole runs, and ramps reach a little way out from their ends: the
			// elevation along every run within reach of the chunk.
			List<Bridges.Run> runs = List.of();
			if (config.useRoads || config.useRailways) {
				List<OsmWater.Way> mapWays = new ArrayList<>();
				shapes.forEach(s -> mapWays.addAll(s.ways()));
				runs = Bridges.runs(mapWays);
				for (Bridges.Run r : runs) {
					if (nearChunk(r, chunkMinX, chunkMinY, chunkMaxX, chunkMaxY)) {
						addTiles(withBuildings, (long) Math.floor(r.minX) - BRIDGE_REACH_SAMPLES, (long) Math.floor(r.maxX) + BRIDGE_REACH_SAMPLES,
							(long) Math.floor(r.minY) - BRIDGE_REACH_SAMPLES, (long) Math.floor(r.maxY) + BRIDGE_REACH_SAMPLES);
					}
				}
			}
			List<Bridges.Run> chunkRuns = runs;
			// The roofs and parts of the buildings still to plan, from their map tiles' detail. Overpass
			// can be slow: not waited for past DETAIL_WAIT_S (a copy times out, not the shared request);
			// a chunk built without it is listed for repair.
			Map<Long, CompletableFuture<OsmDetail.Tile>> detailTiles = new HashMap<>();
			OsmDetail detail = map == null ? null : map.detail();
			for (Buildings.Candidate c : candidates) {
				if (detail != null && !c.planned()) {
					detailTiles.computeIfAbsent(((long) c.tileX() << 32) | c.tileY(),
						k -> detail.tile(c.tileX(), c.tileY()).copy().completeOnTimeout(OsmDetail.Tile.FAILED, DETAIL_WAIT_S, TimeUnit.SECONDS));
				}
			}
			for (Buildings.Candidate c : candidates) {
				if (c.planned()) {
					continue;
				}
				for (double[] p : Buildings.heightPoints(c)) {
					addTiles(withBuildings, (long) Math.floor(p[0]) - 2, (long) Math.floor(p[0]) + 3, (long) Math.floor(p[1]) - 2, (long) Math.floor(p[1]) + 3);
				}
			}
			if (!detailTiles.isEmpty()) {
				CompletableFuture.allOf(detailTiles.values().toArray(CompletableFuture[]::new)).whenComplete((d, e) ->
					se.itssimple.realearth.data.Net.waited("building detail", System.nanoTime() - asked, e != null
						|| detailTiles.values().stream().anyMatch(f -> f.join().failed())));
			}
			// The single trees mapped in the chunk's z13 tiles, waited for as the building detail is.
			Map<Long, CompletableFuture<OsmTrees.Tile>> treeTiles = new HashMap<>();
			OsmTrees mappedTrees = map == null ? null : map.trees();
			int span = ChunkTerrain.SPAN;
			for (int k : new int[] {0, span - 1, span * (span - 1), span * span - 1}) {
				if (mappedTrees != null && onGlobe[k]) {
					treeTiles.computeIfAbsent(OsmTrees.tileOf(sx[k], sy[k]),
						key -> mappedTrees.tile(key).copy().completeOnTimeout(OsmTrees.Tile.FAILED, DETAIL_WAIT_S, TimeUnit.SECONDS));
				}
			}
			// The overlays' surveyed surfaces in the chunk's z14 map tiles, waited for as the building detail is.
			Map<Long, CompletableFuture<List<OsmDetail.Surface>>> surfaceTiles = new HashMap<>();
			double perMapTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - OsmWater.ZOOM));
			for (int k : new int[] {0, span - 1, span * (span - 1), span * span - 1}) {
				if (detail != null && config.useRoads && onGlobe[k]) {
					int tx = (int) Math.floor((sx[k] + 0.5) / perMapTile);
					int ty = (int) Math.floor((sy[k] + 0.5) / perMapTile);
					surfaceTiles.computeIfAbsent(((long) tx << 32) | (ty & 0xFFFFFFFFL),
						key -> detail.surfaces(tx, ty).copy().completeOnTimeout(List.of(), DETAIL_WAIT_S, TimeUnit.SECONDS));
				}
			}
			// And the road surfaces mapped in the chunk's z14 tiles, where this world's roads use them.
			Map<Long, CompletableFuture<OsmRoads.Tile>> roadTiles = new HashMap<>();
			OsmRoads mappedRoads = map == null || map.roadVersion() < 2 ? null : map.roads();
			for (int k : new int[] {0, span - 1, span * (span - 1), span * span - 1}) {
				if (mappedRoads != null && onGlobe[k]) {
					roadTiles.computeIfAbsent(OsmRoads.tileOf(sx[k], sy[k]),
						key -> mappedRoads.tile(key).copy().completeOnTimeout(OsmRoads.Tile.FAILED, DETAIL_WAIT_S, TimeUnit.SECONDS));
				}
			}
			List<CompletableFuture<?>> waits = new ArrayList<>(withBuildings.values());
			waits.addAll(detailTiles.values());
			waits.addAll(treeTiles.values());
			waits.addAll(roadTiles.values());
			waits.addAll(surfaceTiles.values());
			return CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new)).thenApplyAsync(w -> {
				Map<Long, ProcessedTile> all2 = new HashMap<>();
				withBuildings.forEach((key, f) -> all2.put(key, f.join()));
				Map<Long, OsmDetail.Tile> details = new HashMap<>();
				detailTiles.forEach((key, f) -> details.put(key, f.join()));
				long started = se.itssimple.realearth.Perf.start();
				List<OsmTrees.Tile> trees = treeTiles.values().stream().map(CompletableFuture::join).toList();
				List<OsmRoads.Segment> segments = roadTiles.values().stream().flatMap(f -> f.join().segments().stream()).toList();
				List<OsmDetail.Surface> surfaces = surfaceTiles.values().stream().flatMap(f -> f.join().stream()).toList();
				ChunkTerrain built = build(terrain, all2, needed.keySet(), coverLookup.join(), shapes, candidates, details, trees, surfaces, segments,
					chunkRuns, sx, sy, onGlobe, chunkX, chunkZ);
				se.itssimple.realearth.Perf.stop("terrain", started);
				se.itssimple.realearth.data.Net.waited("everything (chunk ready)", System.nanoTime() - asked, built.degraded);
				return built;
			}, workers);
		});
	}

	/** The chunk's terrain from everything fetched for it. */
	private ChunkTerrain build(ChunkTerrain terrain, Map<Long, ProcessedTile> tiles, java.util.Set<Long> own, WorldCover.Lookup landCover,
		List<OsmWater.Shapes> shapes, List<Buildings.Candidate> candidates, Map<Long, OsmDetail.Tile> details, List<OsmTrees.Tile> trees,
		List<OsmDetail.Surface> surfaces, List<OsmRoads.Segment> segments, List<Bridges.Run> runs, double[] sx, double[] sy, boolean[] onGlobe,
		int chunkX, int chunkZ) {
		int n = ChunkTerrain.SPAN;
		{
			terrain.degraded = landCover.degraded() || tiles.values().stream().anyMatch(t -> t.degraded)
				|| details.values().stream().anyMatch(OsmDetail.Tile::failed);
			Koppen climate = Koppen.get();
			for (int k = 0; k < n * n; k++) {
				terrain.cover[k] = (byte) landCover.classAt(terrain.latitude[k], terrain.longitude[k]);
				terrain.climate[k] = (byte) (climate == null ? 0 : climate.classAt(terrain.latitude[k], terrain.longitude[k]));
				if (onGlobe[k]) {
					sampleColumn(tiles, sx[k], sy[k], terrain, k);
				} else {
					// Corners of the projection beyond the antimeridian: open ocean.
					terrain.groundMeters[k] = -3000f;
					terrain.waterMeters[k] = 0f;
				}
			}
			if (!shapes.isEmpty()) {
				List<OsmWater.Way> ways = new ArrayList<>();
				double sx0 = Double.MAX_VALUE;
				double sy0 = Double.MAX_VALUE;
				double sx1 = -Double.MAX_VALUE;
				double sy1 = -Double.MAX_VALUE;
				for (int k = 0; k < n * n; k++) {
					if (onGlobe[k]) {
						sx0 = Math.min(sx0, sx[k]);
						sx1 = Math.max(sx1, sx[k]);
						sy0 = Math.min(sy0, sy[k]);
						sy1 = Math.max(sy1, sy[k]);
					}
				}
				List<OsmWater.Street> streets = new ArrayList<>();
				for (OsmWater.Shapes s : shapes) {
					terrain.degraded |= s.failed();
					ways.addAll(s.ways());
					for (OsmWater.Street st : s.streets()) {
						if (st.maxX() >= sx0 - 4 && st.minX() <= sx1 + 4 && st.maxY() >= sy0 - 4 && st.minY() <= sy1 + 4) {
							streets.add(st);
						}
					}
				}
				terrain.streets = List.copyOf(streets);
				// The map's points of interest in the chunk, and its parks, for the life between the buildings.
				List<ChunkTerrain.Poi> pois = new ArrayList<>();
				int baseX = chunkX * 16;
				int baseZ = chunkZ * 16;
				for (OsmWater.Shapes s : shapes) {
					for (OsmWater.Poi poi : s.pois()) {
						if (poi.x() < sx0 - 2 || poi.x() > sx1 + 2 || poi.y() < sy0 - 2 || poi.y() > sy1 + 2) {
							continue;
						}
						double[] b = Buildings.toBlock(projection, poi.x(), poi.y(), baseX + 8);
						int bx = (int) Math.floor(b[0]);
						int bz = (int) Math.floor(b[1]);
						if (bx >= baseX && bx < baseX + 16 && bz >= baseZ && bz < baseZ + 16) {
							pois.add(new ChunkTerrain.Poi(bx, bz, poi.cls(), poi.subclass(), poi.name()));
						}
					}
					for (OsmWater.Land park : s.parks()) {
						if (park.maxX() < sx0 || park.minX() > sx1 || park.maxY() < sy0 || park.minY() > sy1) {
							continue;
						}
						byte ground = switch (park.cls()) {
							case "cemetery" -> ChunkTerrain.CEMETERY;
							case "allotments" -> ChunkTerrain.ALLOTMENT;
							case "flowerbed" -> ChunkTerrain.FLOWERBED;
							case "pitch" -> ChunkTerrain.PITCH;
							default -> 0;
						};
						for (int k = 0; k < n * n; k++) {
							if (!onGlobe[k] || (ground == 0 ? terrain.park[k] : terrain.grounds[k] != 0) || !BuildingPlan.contains(park.rings(), sx[k], sy[k])) {
								continue;
							}
							if (ground == 0) {
								terrain.park[k] = true;
							} else {
								terrain.grounds[k] = ground;
							}
						}
					}
				}
				terrain.pois = List.copyOf(pois);
				List<ChunkTerrain.MappedTree> mapped = new ArrayList<>();
				for (OsmTrees.Tile tile : trees) {
					for (OsmTrees.Tree tree : tile.trees()) {
						if (tree.x() < sx0 - 2 || tree.x() > sx1 + 2 || tree.y() < sy0 - 2 || tree.y() > sy1 + 2) {
							continue;
						}
						double[] b = Buildings.toBlock(projection, tree.x(), tree.y(), baseX + 8);
						int bx = (int) Math.floor(b[0]);
						int bz = (int) Math.floor(b[1]);
						if (bx >= baseX && bx < baseX + 16 && bz >= baseZ && bz < baseZ + 16) {
							mapped.add(new ChunkTerrain.MappedTree(bx, bz, tree.leaves()));
						}
					}
				}
				terrain.mappedTrees = List.copyOf(mapped);
				List<ChunkTerrain.Crossing> crossings = new ArrayList<>();
				for (OsmTrees.Tile tile : map != null && map.roadVersion() >= 2 ? trees : List.<OsmTrees.Tile>of()) {
					for (OsmTrees.Crossing c : tile.crossings()) {
						if (c.x() < sx0 - 4 || c.x() > sx1 + 4 || c.y() < sy0 - 4 || c.y() > sy1 + 4) {
							continue;
						}
						double[] b = Buildings.toBlock(projection, c.x(), c.y(), baseX + 8);
						int bx = (int) Math.floor(b[0]);
						int bz = (int) Math.floor(b[1]);
						if (bx >= baseX - 6 && bx < baseX + 22 && bz >= baseZ - 6 && bz < baseZ + 22) {
							crossings.add(new ChunkTerrain.Crossing(bx, bz, c.markings()));
						}
					}
				}
				terrain.crossings = List.copyOf(crossings);
				// The street furniture the map has, as more points of interest (see StreetLife), where this world's roads use it.
				if (map != null && map.roadVersion() >= 4) {
					List<ChunkTerrain.Poi> furnished = new ArrayList<>(terrain.pois);
					for (OsmTrees.Tile tile : trees) {
						for (OsmTrees.Furniture f : tile.furniture()) {
							if (f.x() < sx0 - 24 || f.x() > sx1 + 24 || f.y() < sy0 - 24 || f.y() > sy1 + 24) {
								continue;
							}
							double[] b = Buildings.toBlock(projection, f.x(), f.y(), baseX + 8);
							int bx = (int) Math.floor(b[0]);
							int bz = (int) Math.floor(b[1]);
							terrain.mappedLamps |= f.what().equals("street_lamp");
							boolean inside = bx >= baseX && bx < baseX + 16 && bz >= baseZ && bz < baseZ + 16;
							// The same thing in OpenFreeMap's points (a bus stop, a bin) stays as that.
							if (inside && terrain.pois.stream().noneMatch(p -> f.what().equals(p.subclass()) && Math.abs(p.x() - bx) <= 2 && Math.abs(p.z() - bz) <= 2)) {
								furnished.add(new ChunkTerrain.Poi(bx, bz, "overture", f.what(), f.name()));
							}
						}
					}
					terrain.pois = List.copyOf(furnished);
				}
				if (config.useRoads || config.useRailways) {
					long started = se.itssimple.realearth.Perf.start();
					long minX = Long.MAX_VALUE;
					long minY = Long.MAX_VALUE;
					long maxX = Long.MIN_VALUE;
					long maxY = Long.MIN_VALUE;
					for (int k = 0; k < n * n; k++) {
						if (onGlobe[k]) {
							minX = Math.min(minX, (long) Math.floor(sx[k]));
							maxX = Math.max(maxX, (long) Math.ceil(sx[k]));
							minY = Math.min(minY, (long) Math.floor(sy[k]));
							maxY = Math.max(maxY, (long) Math.ceil(sy[k]));
						}
					}
					long x0 = minX;
					long y0 = minY;
					long x1 = maxX;
					long y1 = maxY;
					Bridges.Network bridges = Bridges.network(this, tiles, ways, runs, r -> nearChunk(r, x0, y0, x1, y1));
					// Where each line of track on the ground ends, its rail's Y: a tunnel's track comes out to meet it.
					Map<Long, Integer> railEnds = new HashMap<>();
					WayRaster.apply(this, terrain, tiles, sx, sy, onGlobe, ways, segments, bridges, chunkX, chunkZ, config.useRoads, config.useRailways,
						railEnds);
					List<Tunnels.Run> tunnels = new ArrayList<>(Tunnels.near(this, shapes, x0, y0, x1, y1, 8));
					tunnels.removeIf(r -> r.rail() ? !config.useRailways : !config.useRoads);
					if (!tunnels.isEmpty()) {
						Tunnels.apply(this, terrain, tunnels, sx, sy, onGlobe, chunkX, chunkZ, railEnds);
					}
					if (config.useRoads && !surfaces.isEmpty()) {
						Surfaces.apply(terrain, surfaces, sx, sy, onGlobe);
					}
					se.itssimple.realearth.Perf.stop("terrain.ways", started);
				}
			}
			for (int k = 0; k < n * n; k++) {
				finishColumn(terrain, k);
			}
			if (!candidates.isEmpty()) {
				long started = se.itssimple.realearth.Perf.start();
				Buildings.plan(this, terrain, candidates, tiles, details, sx, sy, onGlobe, chunkX, chunkZ, config.storeyMeters, config.maxFloors);
				se.itssimple.realearth.Perf.stop("terrain.buildings", started);
			}
			return terrain;
		}
	}

	private void addTiles(Map<Long, CompletableFuture<ProcessedTile>> out, long minX, long maxX, long minY, long maxY) {
		long lastY = Mercator.SAMPLES_PER_AXIS - 1;
		int tx0 = (int) Math.floorDiv(minX, Mercator.TILE_SIZE);
		int tx1 = (int) Math.floorDiv(maxX, Mercator.TILE_SIZE);
		int ty0 = (int) Math.floorDiv(Math.max(0, Math.min(lastY, minY)), Mercator.TILE_SIZE);
		int ty1 = (int) Math.floorDiv(Math.max(0, Math.min(lastY, maxY)), Mercator.TILE_SIZE);
		for (int ty = ty0; ty <= ty1; ty++) {
			int row = ty;
			for (int tx = tx0; tx <= tx1; tx++) {
				int wx = Math.floorMod(tx, Mercator.TILES_PER_AXIS);
				out.computeIfAbsent(key(wx, row), k -> processedTile(wx, row));
			}
		}
	}

	/** Longest a chunk waits for its buildings' detail from Overpass, in seconds, before it is built without. */
	private static final int DETAIL_WAIT_S = 120;
	/** How far, in samples, past a chunk a bridge can be and still matter to it: its ramps reach this far. */
	private static final int BRIDGE_REACH_SAMPLES = 40;

	private static boolean nearChunk(Bridges.Run r, long minX, long minY, long maxX, long maxY) {
		return r.maxX >= minX - BRIDGE_REACH_SAMPLES && r.minX <= maxX + BRIDGE_REACH_SAMPLES && r.maxY >= minY - BRIDGE_REACH_SAMPLES
			&& r.minY <= maxY + BRIDGE_REACH_SAMPLES;
	}

	private static ProcessedTile tileFor(Map<Long, ProcessedTile> tiles, long gx, long gy) {
		long wx = Math.floorMod(gx, Mercator.SAMPLES_PER_AXIS);
		long wy = Math.max(0, Math.min(Mercator.SAMPLES_PER_AXIS - 1, gy));
		return tiles.get(key((int) (wx / Mercator.TILE_SIZE), (int) (wy / Mercator.TILE_SIZE)));
	}

	private static int indexIn(long gx, long gy) {
		long wx = Math.floorMod(gx, Mercator.SAMPLES_PER_AXIS);
		long wy = Math.max(0, Math.min(Mercator.SAMPLES_PER_AXIS - 1, gy));
		return (int) ((wy % Mercator.TILE_SIZE) * Mercator.TILE_SIZE + (wx % Mercator.TILE_SIZE));
	}

	/** Cubic B-spline basis weights for the four samples around a fractional position t in [0, 1). */
	private static void bspline(double t, double[] w) {
		double t2 = t * t;
		double t3 = t2 * t;
		double u = 1.0 - t;
		w[0] = u * u * u / 6.0;
		w[1] = (3.0 * t3 - 6.0 * t2 + 4.0) / 6.0;
		w[2] = (-3.0 * t3 + 3.0 * t2 + 3.0 * t + 1.0) / 6.0;
		w[3] = t3 / 6.0;
	}

	/** Smoothed ground height at a sample position, by the same B-spline as columns; NaN off the tiles at hand. */
	static double splineHeight(Map<Long, ProcessedTile> tiles, double sx, double sy) {
		long x0 = (long) Math.floor(sx);
		long y0 = (long) Math.floor(sy);
		double[] wx = new double[4];
		double[] wy = new double[4];
		bspline(sx - x0, wx);
		bspline(sy - y0, wy);
		double height = 0;
		for (int b = 0; b < 4; b++) {
			double row = 0;
			for (int a = 0; a < 4; a++) {
				long gx = x0 - 1 + a;
				long gy = y0 - 1 + b;
				ProcessedTile tile = tileFor(tiles, gx, gy);
				if (tile == null) {
					return Double.NaN;
				}
				row += wx[a] * tile.height[indexIn(gx, gy)];
			}
			height += wy[b] * row;
		}
		return height;
	}

	private void sampleColumn(Map<Long, ProcessedTile> tiles, double sx, double sy, ChunkTerrain out, int k) {
		float[] g = new float[3];
		sample(tiles, sx, sy, g);
		out.groundMeters[k] = g[0];
		out.waterMeters[k] = g[1];
		if (g[2] != 0) {
			out.lake[k] = true;
		}
	}

	/**
	 * Water surface in metres at a sample position, or NaN where it is dry (or the elevation there isn't
	 * loaded): the same answer the column there gets.
	 */
	static double waterSurface(Map<Long, ProcessedTile> tiles, double sx, double sy) {
		long x0 = (long) Math.floor(sx);
		long y0 = (long) Math.floor(sy);
		for (int b = -1; b <= 2; b++) {
			for (int a = -1; a <= 2; a++) {
				if (tileFor(tiles, x0 + a, y0 + b) == null) {
					return Double.NaN;
				}
			}
		}
		float[] g = new float[3];
		sample(tiles, sx, sy, g);
		return g[1];
	}

	/** Ground (out[0]) and water surface (out[1], NaN where dry) in metres, and out[2] 1 for a lake. */
	private static void sample(Map<Long, ProcessedTile> tiles, double sx, double sy, float[] out) {
		ProcessedTile own = tileFor(tiles, (long) Math.floor(sx + 0.5), (long) Math.floor(sy + 0.5));
		byte kind = mappedKindAt(own, sx, sy);
		boolean fitted = kind >= 0 && own.waterKinds != null;
		int mapped = fitted ? WaterSurfaceProfiles.wetMask(kind) : kind;
		long x0 = (long) Math.floor(sx);
		long y0 = (long) Math.floor(sy);
		double[] wx = new double[4];
		double[] wy = new double[4];
		bspline(sx - x0, wx);
		bspline(sy - y0, wy);

		double height = 0;
		for (int b = 0; b < 4; b++) {
			double row = 0;
			for (int a = 0; a < 4; a++) {
				long gx = x0 - 1 + a;
				long gy = y0 - 1 + b;
				row += wx[a] * tileFor(tiles, gx, gy).height[indexIn(gx, gy)];
			}
			height += wy[b] * row;
		}

		// Lakes: bilinear coverage of the four nearest samples. More than half wet is water.
		double fx = sx - x0;
		double fy = sy - y0;
		double wet = 0;
		double depth = 0;
		double levelSum = 0;
		float level = Float.NaN;
		for (int b = 0; b < 2; b++) {
			for (int a = 0; a < 2; a++) {
				long gx = x0 + a;
				long gy = y0 + b;
				ProcessedTile tile = tileFor(tiles, gx, gy);
				int idx = indexIn(gx, gy);
				if (fitted && (tile.waterKinds == null || !WaterSurfaceProfiles.sameSurface(kind, tile.waterKinds[idx]))) continue;
				float l = tile.lakeLevel[idx];
				if (!Float.isNaN(l)) {
					double w = (a == 0 ? 1 - fx : fx) * (b == 0 ? 1 - fy : fy);
					wet += w;
					depth += w * tile.lakeDepth[idx];
					levelSum += w * l;
					level = Float.isNaN(level) ? l : Math.max(level, l);
				}
			}
		}

		// With OpenStreetMap water: the map decides, block by block, from its 4x-resolution raster.
		if (mapped >= 0) {
			if (mapped == 0) {
				out[1] = Float.NaN;
				out[0] = (float) height;
				return;
			}
			double surface = mapped == 2 ? 0.0 : wet > 0 ? levelSum / wet : height;
			double below = wet > 0 ? Math.max(1.0, depth / wet) : 1.0;
			if (out.length > 3) out[3] = (float) below;
			if (mapped == 2 || !fitted && surface <= 0.5) {
				out[1] = 0f;
				out[0] = (float) Math.min(height, -below);
			} else {
				out[2] = 1;
				out[1] = (float) surface;
				out[0] = (float) Math.min(height, surface - below);
			}
			return;
		}

		if (wet >= 0.5 && level > 0.5f) {
			out[2] = 1;
			out[1] = level;
			out[0] = (float) Math.min(height, level - depth / wet);
		} else if (wet >= 0.5) {
			// Flat at or below sea level is sea, not a lake: coastal water the source flattened to 0,
			// or deep ocean whose coarse bathymetry was upsampled into perfectly flat patches.
			out[1] = 0f;
			out[0] = (float) Math.min(height, -depth / wet);
		} else if (height < 0) {
			out[1] = 0f;
			out[0] = (float) height;
		} else {
			out[1] = Float.NaN;
			out[0] = (float) height;
		}
	}

	/** Metres to blocks. The top solid block's upper face sits at the nearest whole Y to the ground. */
	private void finishColumn(ChunkTerrain t, int k) {
		int top = (int) Math.round(toY(t.groundMeters[k], t.latitude[k])) - 1;
		if (!Float.isNaN(t.deckMeters[k])) {
			int deck = (int) Math.round(toY(t.deckMeters[k], t.latitude[k])) - 1;
			int below = Math.max(top, t.waterMeters[k] != t.waterMeters[k] ? top : (int) Math.round(toY(t.waterMeters[k], t.latitude[k])) - 1);
			// A deck clears what is under it by at least two blocks, or it is no bridge.
			t.deckY[k] = deck > below + 2 ? deck : below + 3;
		}
		t.topSolidY[k] = top;
		if (!Float.isNaN(t.waterMeters[k])) {
			int water = (int) Math.round(toY(t.waterMeters[k], t.latitude[k])) - 1;
			// Where the map is shrunk, a metre of water is less than a block and could round away;
			// mapped water always keeps at least one block.
			if (water <= top) {
				double perMeter = projection.blocksPerMeter(t.latitude[k]);
				t.groundMeters[k] = (float) Math.min(t.groundMeters[k], t.waterMeters[k] - 1.0 / perMeter);
				top = Math.min(water - 1, (int) Math.round(toY(t.groundMeters[k], t.latitude[k])) - 1);
				t.topSolidY[k] = top;
			}
			t.waterTopY[k] = water;
		} else {
			t.waterTopY[k] = Integer.MIN_VALUE;
		}
	}

	/**
	 * Asks for the building detail of the map tiles round a block position ahead of need, so chunks
	 * there rarely wait for Overpass: for each player now and then.
	 */
	public void prefetchDetail(double blockX, double blockZ) {
		OsmDetail detail = map == null ? null : map.detail();
		if (detail == null || !config.useBuildings) {
			return;
		}
		double lat = projection.latitudeAt(blockZ);
		double lon = projection.longitudeAt(blockX, lat);
		if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 85) {
			return;
		}
		double perTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - OsmWater.ZOOM));
		int tx = (int) Math.floor((Mercator.sampleX(Projection.wrapLongitude(lon)) + 0.5) / perTile);
		int ty = (int) Math.floor((Mercator.sampleY(lat) + 0.5) / perTile);
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				detail.tile(tx + dx, ty + dy);
			}
		}
	}

	@Override
	public void close() {
		workers.shutdownNow();
		store.close();
		if (map != null) {
			map.close();
		}
	}
}
