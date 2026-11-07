package net.apotheoticstudios.gaiaearth.osm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.apotheoticstudios.gaiaearth.data.Http;
import net.apotheoticstudios.gaiaearth.data.PmTiles;
import net.apotheoticstudios.gaiaearth.geo.Mercator;

/**
 * One theme of the Overture Maps tiles (a PMTiles archive per release, the building detail's release), read a tile at
 * a time, parsed, and kept on disk for good: the base theme's trees and crossings (OsmTrees), the transportation
 * theme's road surfaces (OsmRoads).
 */
final class OvertureTiles<T> {
	interface Parser<T> {
		T parse(List<VectorTile.Feature> features, int tileX, int tileY);
	}

	private final String what;
	private final String template;
	private final OsmDetail detail;
	private final Path root;
	private final int zoom;
	private final Set<String> layers;
	private final Parser<T> parser;
	private final T empty;
	private final T failed;
	private final ExecutorService downloads;
	private volatile PmTiles archive;
	private final Map<Long, CompletableFuture<T>> cache = new LinkedHashMap<>(32, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<T>> eldest) {
			return size() > 32;
		}
	};

	/**
	 * @param what what the theme gives, for logs ("mapped trees")
	 * @param template the archive's URL with {release} for the release's name
	 * @param detail the building detail, whose release this follows
	 */
	OvertureTiles(String what, String template, OsmDetail detail, Path root, int zoom, Set<String> layers, Parser<T> parser, T empty, T failed) {
		this.what = what;
		this.template = template;
		this.detail = detail;
		this.root = root;
		this.zoom = zoom;
		this.layers = layers;
		this.parser = parser;
		this.empty = empty;
		this.failed = failed;
		this.downloads = Executors.newFixedThreadPool(1, r -> {
			Thread t = new Thread(r, "RealEarth-" + what);
			t.setDaemon(true);
			return t;
		});
		net.apotheoticstudios.gaiaearth.data.Net.pool(what, downloads);
	}

	/** The tile at {@code zoom} holding a global z15 sample position, as x << 32 | y. */
	static long tileOf(int zoom, double sx, double sy) {
		double perTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - zoom));
		int n = 1 << zoom;
		int x = Math.floorMod((int) Math.floor((sx + 0.5) / perTile), n);
		int y = Math.max(0, Math.min(n - 1, (int) Math.floor((sy + 0.5) / perTile)));
		return ((long) x << 32) | y;
	}

	/** A tile ({@link #tileOf}): from disk, else read from the archive and kept. */
	CompletableFuture<T> tile(long key) {
		synchronized (cache) {
			return cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> load((int) (k >>> 32), (int) (long) k), downloads));
		}
	}

	private T load(int x, int y) {
		Path file = root.resolve(Integer.toString(zoom)).resolve(Integer.toString(x)).resolve(y + ".mvt");
		byte[] data = Http.readCache(file);
		if (data == null) {
			try {
				PmTiles a = archive;
				if (a == null) {
					a = archive = new PmTiles(template.replace("{release}", detail.release()));
				}
				data = a.tile(zoom, x, y);
			} catch (Http.Missing e) {
				archive = null; // the release is gone from the bucket: the building detail looks for the newest
				return failed(x, y, e);
			} catch (IOException | RuntimeException e) {
				return failed(x, y, e);
			}
			// No tile: nothing there. Kept as an empty file, so it is not asked for again.
			data = data == null ? new byte[0] : data;
			Http.writeCache(file, data);
		}
		if (data.length == 0) {
			return empty;
		}
		try {
			return parser.parse(VectorTile.decode(data, layers), x, y);
		} catch (IOException | RuntimeException e) {
			RealEarth.LOGGER.warn("Unreadable {} {}; discarding: {}", what, file, e.toString());
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
			}
			return failed(x, y, e);
		}
	}

	private T failed(int x, int y, Exception e) {
		RealEarth.LOGGER.warn("{} for tile {}/{}/{} unavailable ({})", what, zoom, x, y, e.getMessage());
		synchronized (cache) {
			cache.remove(((long) x << 32) | y); // asked again another time
		}
		return failed;
	}

	void close() {
		downloads.shutdownNow();
	}
}
