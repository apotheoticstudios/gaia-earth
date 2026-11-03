package se.itssimple.realearth.osm;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.data.Http;
import se.itssimple.realearth.data.PmTiles;
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

	/** A tile's trees. */
	public record Tile(List<Tree> trees, boolean failed) {
		public static final Tile EMPTY = new Tile(List.of(), false);
		public static final Tile FAILED = new Tile(List.of(), true);
	}

	private final String template;
	private final OsmDetail detail;
	private final Path root;
	private final ExecutorService downloads;
	private volatile PmTiles archive;
	private final Map<Long, CompletableFuture<Tile>> cache = new LinkedHashMap<>(32, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<Tile>> eldest) {
			return size() > 32;
		}
	};

	/**
	 * @param template the archive's URL with {release} for the release's name
	 * @param detail the building detail, whose release this follows
	 */
	OsmTrees(String template, OsmDetail detail, Path root) {
		this.template = template;
		this.detail = detail;
		this.root = root;
		this.downloads = Executors.newFixedThreadPool(1, r -> {
			Thread t = new Thread(r, "RealEarth-Trees");
			t.setDaemon(true);
			return t;
		});
		se.itssimple.realearth.data.Net.pool("mapped trees", downloads);
	}

	/** The z13 tile holding a global z15 sample position. */
	public static long tileOf(double sx, double sy) {
		double perTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - ZOOM));
		int n = 1 << ZOOM;
		int x = Math.floorMod((int) Math.floor((sx + 0.5) / perTile), n);
		int y = Math.max(0, Math.min(n - 1, (int) Math.floor((sy + 0.5) / perTile)));
		return ((long) x << 32) | y;
	}

	/** The trees of a z13 tile ({@link #tileOf}): from disk, else read from the archive and kept. */
	public CompletableFuture<Tile> tile(long key) {
		synchronized (cache) {
			return cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> load((int) (k >>> 32), (int) (long) k), downloads));
		}
	}

	private Tile load(int x, int y) {
		Path file = root.resolve(Integer.toString(ZOOM)).resolve(Integer.toString(x)).resolve(y + ".mvt");
		byte[] data = Http.readCache(file);
		if (data == null) {
			try {
				PmTiles a = archive;
				if (a == null) {
					a = archive = new PmTiles(template.replace("{release}", detail.release()));
				}
				data = a.tile(ZOOM, x, y);
			} catch (Http.Missing e) {
				archive = null; // the release is gone from the bucket: the building detail looks for the newest
				return failed(x, y, e);
			} catch (IOException | RuntimeException e) {
				return failed(x, y, e);
			}
			// No tile: no trees there. Kept as an empty file, so it is not asked for again.
			Http.writeCache(file, data == null ? new byte[0] : data);
		}
		if (data.length == 0) {
			return Tile.EMPTY;
		}
		try {
			return parse(VectorTile.decode(data, Set.of("land")), x, y);
		} catch (IOException | RuntimeException e) {
			RealEarth.LOGGER.warn("Unreadable mapped trees {}; discarding: {}", file, e.toString());
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
			}
			return failed(x, y, e);
		}
	}

	private Tile failed(int x, int y, Exception e) {
		RealEarth.LOGGER.warn("Mapped trees for tile {}/{}/{} unavailable ({}); only forests grow trees there", ZOOM, x, y, e.getMessage());
		synchronized (cache) {
			cache.remove(((long) x << 32) | y); // asked again another time
		}
		return Tile.FAILED;
	}

	static Tile parse(List<VectorTile.Feature> features, int tileX, int tileY) {
		double samplesPerTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - ZOOM));
		List<Tree> trees = new ArrayList<>();
		for (VectorTile.Feature f : features) {
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
		return trees.isEmpty() ? Tile.EMPTY : new Tile(List.copyOf(trees), false);
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
		downloads.shutdownNow();
	}
}
