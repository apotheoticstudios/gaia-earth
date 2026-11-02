package se.itssimple.realearth.data;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.geo.Mercator;

/**
 * Raw Terrarium tiles at zoom 15: downloaded once, kept on disk, decoded into memory on demand.
 *
 * Disk keys are geographic (z/x/y), so every world and every origin shares the same cache, and a
 * warm area needs no network at all. A tile is only written to disk once it is known to decode.
 *
 * When zoom 15 is missing (404) the nearest ancestor tile is upsampled instead, so terrain never
 * has holes. When the network is down, tiles already on disk -- at any zoom -- are still used, and
 * the rest come back flat at sea level rather than stalling world generation forever.
 *
 * With a lidar source (Mapterhorn), a zoom-15 tile comes from its zoom-14 tile instead wherever that
 * exists: 512 px, so a quarter of it is exactly one zoom-15 tile, sample for sample. It exists only
 * where national lidar does, and there it is bare ground surveyed at 0.25-10 m, where the Terrarium
 * tiles are 30 m radar or surface models in many places (Stockholm's city reads 26 m of rooftops).
 */
public final class TileStore implements AutoCloseable {
	public static final int SIZE = Mercator.TILE_SIZE;

	private static final int MAX_ATTEMPTS = 4;
	private static final int DEEPEST_FALLBACK = 7;
	private static final long OFFLINE_BACKOFF_MS = 30_000;

	private final Path root;
	private final Source terrarium;
	/** Null without a lidar source. */
	private final Source lidar;
	private final HttpClient http;
	private final ExecutorService downloads;
	private final Map<Long, CompletableFuture<float[]>> cache;
	private final AtomicInteger downloaded = new AtomicInteger();
	private final AtomicInteger fromDisk = new AtomicInteger();
	private final AtomicInteger flatFallbacks = new AtomicInteger();
	private final AtomicInteger degradedTiles = new AtomicInteger();
	/**
	 * Tiles that are stand-ins because the network was down: a flat sea, or an ancestor read from
	 * disk while the real tile could not be asked for. Arrays compare by identity, so a weak set of
	 * them marks exactly the instances handed out.
	 */
	private final Set<float[]> degraded = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	/** Where tiles come from and are kept, how big they are, and its own pause after failures. */
	private record Source(String url, Path root, String extension, int size, AtomicLong offlineUntil) {
		Source(String url, Path root, String extension, int size) {
			this(url, root, extension, size, new AtomicLong());
		}

		float[] decode(byte[] body) throws IOException {
			return extension.equals(".webp") ? TerrariumWebp.decode(body, size) : TerrariumPng.decode(body, size);
		}
	}

	public TileStore(String urlTemplate, Path root, int threads, int maxTilesInMemory) {
		this(urlTemplate, null, root, threads, maxTilesInMemory);
	}

	/** @param lidarUrl lidar tiles (see EarthConfig.lidarUrl), kept beside {@code root}; null or empty for none */
	public TileStore(String urlTemplate, String lidarUrl, Path root, int threads, int maxTilesInMemory) {
		this.root = root;
		this.terrarium = new Source(urlTemplate, root, ".png", SIZE);
		this.lidar = lidarUrl == null || lidarUrl.isBlank() ? null : new Source(lidarUrl, root.resolveSibling("mapterhorn"), ".webp", 2 * SIZE);
		AtomicInteger n = new AtomicInteger();
		this.downloads = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
			Thread t = new Thread(r, "RealEarth-Download-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
		Net.pool("elevation", downloads);
		this.http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
		this.cache = new LinkedHashMap<>(256, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<float[]>> eldest) {
				return size() > maxTilesInMemory;
			}
		};
	}

	/** Heights of one zoom-15 tile, row-major, SIZE * SIZE metres. X wraps around the antimeridian. */
	public CompletableFuture<float[]> tile(int tx, int ty) {
		return tile(Mercator.ZOOM, tx, ty);
	}

	/**
	 * Heights of one tile at any zoom. Below zoom 15 there is no further fallback: a coarse tile that
	 * cannot be had comes back flat at sea level.
	 */
	public CompletableFuture<float[]> tile(int zoom, int tx, int ty) {
		int n = 1 << zoom;
		int x = Math.floorMod(tx, n);
		int y = Math.max(0, Math.min(n - 1, ty));
		long key = ((long) zoom << 58) | ((long) x << 29) | y;
		synchronized (cache) {
			CompletableFuture<float[]> existing = cache.get(key);
			if (existing != null) {
				return existing;
			}
			CompletableFuture<float[]> created = CompletableFuture.supplyAsync(() -> {
				if (zoom == Mercator.ZOOM) {
					return load(x, y);
				}
				Fetch f = fetch(terrarium, zoom, x, y, true);
				return f.heights != null ? f.heights : f.status == Status.MISSING ? new float[SIZE * SIZE] : degrade(new float[SIZE * SIZE]);
			}, downloads);
			cache.put(key, created);
			// A stand-in is not remembered: the next request, after the offline pause, tries again.
			created.thenAccept(heights -> {
				if (isDegraded(heights)) {
					synchronized (cache) {
						cache.remove(key, created);
					}
				}
			});
			return created;
		}
	}

	/** True when this world's zoom-15 tiles come from lidar where there is some. */
	public boolean hasLidar() {
		return lidar != null;
	}

	/** True for a tile that is only a stand-in because its data could not be downloaded. */
	public boolean isDegraded(float[] heights) {
		return degraded.contains(heights);
	}

	private float[] degrade(float[] heights) {
		degraded.add(heights);
		degradedTiles.incrementAndGet();
		return heights;
	}

	/** True while downloads are paused after failures. */
	public boolean offline() {
		long now = System.currentTimeMillis();
		return now < terrarium.offlineUntil.get() || lidar != null && now < lidar.offlineUntil.get();
	}

	public Path root() {
		return root;
	}

	/** What {@link #prefetch} did. */
	public enum Prefetch { CACHED, DOWNLOADED, FAILED }

	/**
	 * Makes sure a tile is on disk, without keeping it in memory: for downloading an area in advance.
	 * A tile the server does not have counts as cached (its marker is on disk). Blocking.
	 */
	public Prefetch prefetch(int zoom, int tx, int ty) {
		int n = 1 << zoom;
		int x = Math.floorMod(tx, n);
		int y = Math.max(0, Math.min(n - 1, ty));
		if (zoom == Mercator.ZOOM && lidar != null) {
			// Where there is lidar the Terrarium tile is never read, so it is not fetched.
			Prefetch fromLidar = prefetch(lidar, zoom - 1, x >> 1, y >> 1);
			if (fromLidar != Prefetch.FAILED && Files.exists(file(lidar, zoom - 1, x >> 1, y >> 1))) {
				return fromLidar;
			}
			Prefetch fromTerrarium = prefetch(terrarium, zoom, x, y);
			return fromLidar == Prefetch.FAILED ? Prefetch.FAILED : fromTerrarium;
		}
		return prefetch(terrarium, zoom, x, y);
	}

	/** Where a tile is kept on disk: the lidar tile one zoom up where there is lidar, else the Terrarium one. */
	public Path fileOf(int zoom, int x, int y) {
		Path lidarFile = lidar != null && zoom == Mercator.ZOOM ? file(lidar, zoom - 1, x >> 1, y >> 1) : null;
		return lidarFile != null && Files.exists(lidarFile) ? lidarFile : file(terrarium, zoom, x, y);
	}

	private static Path file(Source src, int zoom, int x, int y) {
		return src.root.resolve(Integer.toString(zoom)).resolve(Integer.toString(x)).resolve(y + src.extension);
	}

	private Prefetch prefetch(Source src, int zoom, int x, int y) {
		Path dir = src.root.resolve(Integer.toString(zoom)).resolve(Integer.toString(x));
		if (Files.exists(dir.resolve(y + src.extension)) || Files.exists(dir.resolve(y + ".missing"))) {
			return Prefetch.CACHED;
		}
		Fetch f = fetch(src, zoom, x, y, true);
		return f.status == Status.FAILED ? Prefetch.FAILED : Prefetch.DOWNLOADED;
	}

	public String stats() {
		return downloaded.get() + " downloaded, " + fromDisk.get() + " from disk, " + degradedTiles.get() + " stand-ins while offline";
	}

	private float[] load(int x, int y) {
		if (lidar != null) {
			// shortcut: lidar only at zoom 15 (chunks and the distant terrain next to them); coarser zooms stay
			// Terrarium, which reads higher over cities and forests -- upgrade if far terrain shows a step.
			Fetch f = fetch(lidar, Mercator.ZOOM - 1, x >> 1, y >> 1, true);
			if (f.heights != null) {
				return quarter(f.heights, x & 1, y & 1);
			}
			if (f.status == Status.FAILED) {
				// Whether there is lidar here is unknown until it can be asked, so this is only a stand-in.
				float[] heights = terrarium(x, y);
				return isDegraded(heights) ? heights : degrade(heights);
			}
		}
		return terrarium(x, y);
	}

	/** One zoom-15 tile out of a 512 px tile one zoom up: the same samples, a quarter of them. */
	private static float[] quarter(float[] big, int qx, int qy) {
		float[] out = new float[SIZE * SIZE];
		for (int j = 0; j < SIZE; j++) {
			System.arraycopy(big, (qy * SIZE + j) * 2 * SIZE + qx * SIZE, out, j * SIZE, SIZE);
		}
		return out;
	}

	private float[] terrarium(int x, int y) {
		Fetch direct = fetch(terrarium, Mercator.ZOOM, x, y, true);
		if (direct.heights != null) {
			return direct.heights;
		}

		// Missing at z15 (or unreachable): use the nearest ancestor. Over the network only if the
		// tile is genuinely absent; if the network is down, ancestors are only taken from disk.
		boolean network = direct.status == Status.MISSING;
		for (int k = 1; k <= DEEPEST_FALLBACK; k++) {
			Fetch parent = fetch(terrarium, Mercator.ZOOM - k, x >> k, y >> k, network);
			if (parent.heights != null) {
				float[] up = upsample(parent.heights, k, x, y);
				// An ancestor is the real answer when the tile is genuinely absent; when the tile was
				// merely unreachable it is a stand-in.
				return network ? up : degrade(up);
			}
			if (parent.status == Status.FAILED) {
				network = false;
			}
		}

		flatFallbacks.incrementAndGet();
		if (network) {
			// Absent at every zoom: there is nothing better to wait for.
			return new float[SIZE * SIZE];
		}
		RealEarth.LOGGER.warn("No elevation available for tile 15/{}/{}; a flat stand-in is used until it can be downloaded", x, y);
		return degrade(new float[SIZE * SIZE]);
	}

	private enum Status { OK, MISSING, FAILED }

	private record Fetch(Status status, float[] heights) {
	}

	private Fetch fetch(Source src, int z, int x, int y, boolean allowNetwork) {
		Path dir = src.root.resolve(Integer.toString(z)).resolve(Integer.toString(x));
		Path file = dir.resolve(y + src.extension);
		Path missing = dir.resolve(y + ".missing");

		if (Files.exists(missing)) {
			return new Fetch(Status.MISSING, null);
		}

		if (Files.exists(file)) {
			try {
				float[] heights = src.decode(Files.readAllBytes(file));
				DiskCache.used(file);
				fromDisk.incrementAndGet();
				return new Fetch(Status.OK, heights);
			} catch (IOException e) {
				// A cached tile that will not decode would fail identically forever.
				RealEarth.LOGGER.warn("Discarding unreadable cached tile {}: {}", file, e.getMessage());
				try {
					Files.deleteIfExists(file);
				} catch (IOException ignored) {
				}
			}
		}

		if (!allowNetwork) {
			return new Fetch(Status.FAILED, null);
		}
		String url = src.url
			.replace("{z}", Integer.toString(z))
			.replace("{x}", Integer.toString(x))
			.replace("{y}", Integer.toString(y));
		String source = Net.sourceOf(url);
		if (System.currentTimeMillis() < src.offlineUntil.get()) {
			Net.paused(source);
			return new Fetch(Status.FAILED, null);
		}
		// One download per tile at a time: a second ask while it is on its way gets the same answer.
		try {
			return Net.once(url, source, () -> download(src, url, dir, file, missing, source));
		} catch (IOException e) {
			return new Fetch(Status.FAILED, null);
		}
	}

	private Fetch download(Source src, String url, Path dir, Path file, Path missing, String source) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(30))
			.header("User-Agent", "RealEarth-Minecraft-Mod/" + RealEarth.VERSION)
			.GET()
			.build();

		String lastError = "";
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			if (attempt > 0) {
				sleep(500L << attempt);
			}
			long started = System.nanoTime();
			try {
				HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
				int code = response.statusCode();
				Net.attempt(source, code, System.nanoTime() - started, response.body() == null ? 0 : response.body().length);
				if (code == 200) {
					byte[] body = response.body();
					float[] heights = src.decode(body);
					Files.createDirectories(dir);
					Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
					Files.write(tmp, body);
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
					DiskCache.written(body.length);
					downloaded.incrementAndGet();
					return new Fetch(Status.OK, heights);
				}
				if (code == 404 || code == 403) {
					// S3 answers 403 for absent keys when listing is not allowed. Remembered on disk
					// so an absent tile is asked about once, not once per chunk.
					Files.createDirectories(dir);
					Files.writeString(missing, Integer.toString(code));
					return new Fetch(Status.MISSING, null);
				}
				lastError = "HTTP " + code;
			} catch (IOException e) {
				Net.attempt(source, -1, System.nanoTime() - started, 0);
				lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return new Fetch(Status.FAILED, null);
			}
		}

		Net.failed(source);
		src.offlineUntil.set(System.currentTimeMillis() + OFFLINE_BACKOFF_MS);
		RealEarth.LOGGER.warn("Could not download {} after {} attempts ({}); pausing downloads for {} s",
			url, MAX_ATTEMPTS, lastError, OFFLINE_BACKOFF_MS / 1000);
		return new Fetch(Status.FAILED, null);
	}

	/**
	 * Builds a zoom-15 tile from an ancestor k levels up, bilinearly, in the ancestor's own sample
	 * grid (pixel centres at +0.5, as everywhere else).
	 */
	private static float[] upsample(float[] parent, int k, int x, int y) {
		float[] out = new float[SIZE * SIZE];
		int scale = 1 << k;
		int parentOriginX = (x >> k) * SIZE;
		int parentOriginY = (y >> k) * SIZE;
		for (int j = 0; j < SIZE; j++) {
			double py = ((long) y * SIZE + j + 0.5) / scale - 0.5 - parentOriginY;
			int y0 = (int) Math.floor(py);
			double fy = py - y0;
			int ya = clamp(y0);
			int yb = clamp(y0 + 1);
			for (int i = 0; i < SIZE; i++) {
				double px = ((long) x * SIZE + i + 0.5) / scale - 0.5 - parentOriginX;
				int x0 = (int) Math.floor(px);
				double fx = px - x0;
				int xa = clamp(x0);
				int xb = clamp(x0 + 1);
				double top = parent[ya * SIZE + xa] * (1 - fx) + parent[ya * SIZE + xb] * fx;
				double bottom = parent[yb * SIZE + xa] * (1 - fx) + parent[yb * SIZE + xb] * fx;
				out[j * SIZE + i] = (float) (top * (1 - fy) + bottom * fy);
			}
		}
		return out;
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(SIZE - 1, v));
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void close() {
		downloads.shutdownNow();
		http.close();
	}
}
