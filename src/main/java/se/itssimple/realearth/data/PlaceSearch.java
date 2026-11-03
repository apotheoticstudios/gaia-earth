package se.itssimple.realearth.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import se.itssimple.realearth.RealEarth;

/**
 * Finds a place by name, for {@code /earth go}, through OpenStreetMap's Nominatim, within its usage
 * policy: an identifying User-Agent ({@link Http}), at most one request a second (one worker, which
 * waits between requests), and every answer cached, in memory and in the download cache, so a name is
 * only ever asked for once; a name that finds nothing is remembered too. Names in English where the
 * data has them. Its results are OpenStreetMap data, credited with the map's.
 */
public final class PlaceSearch {
	private static final String URL = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&accept-language=en&q=";
	private static final long MIN_GAP_MS = 1100;

	/** What a search found: a name to show, and where. */
	public record Hit(String name, double latitude, double longitude) {
	}

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "RealEarth-PlaceSearch");
		t.setDaemon(true);
		return t;
	});
	private static final Map<String, Optional<Hit>> CACHE = new ConcurrentHashMap<>();
	/** When the last request went out; only the worker touches it. */
	private static long last;

	private PlaceSearch() {
	}

	/** The best match for {@code query}, or empty when there is none; fails when Nominatim can't be reached. */
	public static CompletableFuture<Optional<Hit>> find(String query) {
		String key = query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
		Optional<Hit> known = CACHE.get(key);
		if (known != null) {
			return CompletableFuture.completedFuture(known);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				Optional<Hit> hit = CACHE.containsKey(key) ? CACHE.get(key) : load(key);
				CACHE.put(key, hit);
				return hit;
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}, WORKER);
	}

	private static Optional<Hit> load(String key) throws IOException {
		Path file = FabricLoader.getInstance().getGameDir().resolve(RealEarth.config().cacheDirectory).resolve("nominatim")
			.resolve(Integer.toHexString(key.hashCode()) + "-" + key.length() + ".json");
		byte[] data = Http.readCache(file);
		if (data != null && !key.equals(JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject().get("query").getAsString())) {
			data = null; // another name with the same hash: asked for afresh, and the file becomes this one's
		}
		if (data == null) {
			long wait = last + MIN_GAP_MS - System.currentTimeMillis();
			if (wait > 0) {
				try {
					Thread.sleep(wait);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("interrupted");
				}
			}
			last = System.currentTimeMillis();
			data = Http.get(URL + URLEncoder.encode(key, StandardCharsets.UTF_8), 0, 0);
			// Kept with the query, so two names with the same hash never take each other's answer.
			JsonObject cached = new JsonObject();
			cached.addProperty("query", key);
			cached.add("results", JsonParser.parseString(new String(data, StandardCharsets.UTF_8)));
			data = cached.toString().getBytes(StandardCharsets.UTF_8);
			Http.writeCache(file, data);
		}
		return parse(key, new String(data, StandardCharsets.UTF_8));
	}

	/** The first result of a cached answer; empty when there is none. */
	public static Optional<Hit> parse(String key, String json) {
		JsonArray results = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("results");
		if (results == null || results.isEmpty()) {
			return Optional.empty();
		}
		JsonObject first = results.get(0).getAsJsonObject();
		String name = first.has("display_name") ? first.get("display_name").getAsString() : key;
		return Optional.of(new Hit(name, first.get("lat").getAsDouble(), first.get("lon").getAsDouble()));
	}
}
