package net.apotheoticstudios.gaiaearth.compat.xaero;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import net.apotheoticstudios.gaiaearth.EarthConfig;
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.apotheoticstudios.gaiaearth.client.ClientHere;
import net.apotheoticstudios.gaiaearth.data.DiskCache;
import net.apotheoticstudios.gaiaearth.data.Http;
import net.apotheoticstudios.gaiaearth.data.TerrariumPng;
import net.apotheoticstudios.gaiaearth.geo.Projection;
import net.apotheoticstudios.gaiaearth.osm.MapPainter;
import net.apotheoticstudios.gaiaearth.osm.OsmWater;
import net.apotheoticstudios.gaiaearth.world.ClimateLink;
import net.apotheoticstudios.gaiaearth.world.MapWaypoints;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer;
import xaero.map.gui.GuiMap;

/**
 * The real map under Xaero's World Map on the Earth, where nothing has been explored: the world map
 * alone draws only the chunks the player has seen, and black everywhere else.
 *
 * <p>The client fetches OpenFreeMap's vector tiles itself (so it works in multiplayer, where the
 * server's data is not here), at the zoom that fits the map's scale, paints each into a picture
 * ({@link MapPainter}) on a worker thread, and draws the pictures as the first quads of the map's own
 * batch, so the explored map, drawn after them, covers them; the map's shader skips its empty pixels,
 * which leaves the picture showing there. A tile still on its way shows the nearest bigger tile's
 * part, stretched; where there is none, a tile a few zooms out is fetched first. Downloads go to the
 * same cache as the server's map data (in single player they are shared), under the same size limit,
 * and only for tiles on the screen, a few at a time. Leaving the world frees the textures.
 *
 * <p>Land is hill-shaded from the same elevation tiles the terrain is made from (and the same cache),
 * fetched with each picture. The tiles' place names in English appear as one zooms in, as on web maps
 * ({@link MapPainter.Place#showsAt}),
 * are written over the map at screen size after it is drawn, the most important first, and none over
 * another.
 *
 * <p>Only for the Mercator projection, where web-map tiles are squares of blocks; called from
 * {@code GuiMapMixin}. The map data's credit is shown in the corner while a picture is.
 */
public final class XaeroMapBackground {
	/** The map data's last zoom; beyond it its tiles are painted bigger, down to about a block a pixel. */
	private static final int DATA_ZOOM = 14;
	private static final int MAX_ZOOM = 16;
	/** The elevation tiles' last zoom; deeper pictures are shaded from a part of one, stretched. */
	private static final int ELEVATION_ZOOM = 15;
	/** Most names written on the screen at once. */
	private static final int MAX_NAMES = 40;
	/** Clear space kept round each name, in GUI pixels, so the map stays readable between them. */
	private static final int NAME_SPACE = 24;
	/** The map's own buttons down each side, and its readouts along the top and bottom, in GUI pixels: no names there. */
	private static final int SIDE_MARGIN = 24;
	private static final int TOP_MARGIN = 26;
	private static final int BOTTOM_MARGIN = 20;
	/** How many zooms out the stand-in fetched first for a view with nothing in it is. */
	private static final int COARSE_STEPS = 3;
	/** Pictures kept on the GPU, a quarter of a megabyte each; a screen at most zoom levels needs under a hundred. */
	private static final int MAX_TEXTURES = 256;
	/**
	 * Tiles fetched and painted at once, each on its own worker so none waits in a queue while the view moves on.
	 * Mostly waiting on the network: 8 filled a new view about a third sooner than 4, painting being a few ms.
	 */
	private static final int MAX_LOADING = 8;
	private static final int UPLOADS_PER_FRAME = 4;
	private static final long RETRY_MS = 30_000;
	private static final String CREDIT = "OpenFreeMap © OpenMapTiles · Data © OpenStreetMap contributors";

	private static final ExecutorService WORKERS = Executors.newFixedThreadPool(MAX_LOADING, r -> {
		Thread t = new Thread(r, "RealEarth-MapBackground");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	/** Textures dropped from the cache, closed a frame later, once nothing drawn with them is pending. */
	private static final List<DynamicTexture> retired = new ArrayList<>();
	/** Pictures by tile, least recently drawn first; null for a tile the map has no data for. */
	private static final Map<Long, DynamicTexture> textures = new LinkedHashMap<>(MAX_TEXTURES, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, DynamicTexture> eldest) {
			if (size() <= MAX_TEXTURES) {
				return false;
			}
			if (eldest.getValue() != null) {
				retired.add(eldest.getValue());
			}
			names.remove(eldest.getKey());
			return true;
		}
	};
	/** A painted tile: its picture and its place names. */
	private record Painted(int[] argb, List<MapPainter.Place> places) {
	}

	/** A place name on the map, at its block position. */
	private record Name(MapPainter.Place place, double x, double z) {
	}

	private static final Map<Long, CompletableFuture<Painted>> loading = new HashMap<>();
	/** Place names by tile, for the tiles in {@link #textures}. */
	private static final Map<Long, List<MapPainter.Place>> names = new HashMap<>();
	/** The names under the pictures drawn this frame. */
	private static final List<Name> onScreen = new ArrayList<>();
	private static final Map<Long, Long> failedUntil = new HashMap<>();
	private static OsmWater source;
	private static boolean warned;
	/** Whether the map shows the player's own dimension, where Project Gaia: Earth's waypoint marks are. */
	private static boolean marksShown;
	/** Whether the last frame drew a picture, for the credit. */
	private static boolean shown;

	static {
		// Leaving the world gives the GPU memory back: the next world may be elsewhere, or have no Earth.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(XaeroMapBackground::clear));
	}

	private XaeroMapBackground() {
	}

	private static void clear() {
		textures.values().forEach(t -> {
			if (t != null) {
				t.close();
			}
		});
		textures.clear();
		names.clear();
		onScreen.clear();
		retired.forEach(DynamicTexture::close);
		retired.clear();
		// Pictures still being painted finish on their own and are dropped.
		loading.clear();
		failedUntil.clear();
	}

	/**
	 * Adds the pictures under the map's visible part, {@code left..right} by {@code top..bottom} in
	 * blocks, to {@code renderer}, before the map adds its own. {@code matrix} draws a block at its
	 * offset from {@code cameraX, cameraZ}; {@code scale} is screen pixels per block. Render thread.
	 */
	public static void draw(Matrix4f matrix, MultiTextureRenderTypeRenderer renderer, int cameraX, int cameraZ, double left, double right, double top,
		double bottom, double scale, ResourceKey<Level> dimension, boolean caves) {
		shown = false;
		onScreen.clear();
		retired.forEach(DynamicTexture::close);
		retired.clear();
		Projection.WebMercator mercator = earth(dimension);
		if (mercator == null || caves) {
			return;
		}
		marksShown = dimension == Minecraft.getInstance().level.dimension();
		upload();
		double world = mercator.periodX();
		// The zoom whose tiles come out 256 to 512 pixels wide on the screen.
		int zoom = Math.clamp((int) Math.floor(Math.log(world * scale / MapPainter.SIZE) / Math.log(2)), 0, MAX_ZOOM);
		int n = 1 << zoom;
		double side = world / n;
		int x0 = (int) Math.floor((left + world / 2) / side);
		int x1 = (int) Math.floor((right + world / 2) / side);
		int y0 = Math.max(0, (int) Math.floor((top + world / 2) / side));
		int y1 = Math.min(n - 1, (int) Math.floor((bottom + world / 2) / side));
		double middleX = (x0 + x1) / 2.0;
		double middleY = (y0 + y1) / 2.0;
		List<long[]> missing = new ArrayList<>();
		for (int ty = y0; ty <= y1; ty++) {
			// Each edge from its own tile number, so neighbours meet exactly.
			float pz = (float) (ty * side - world / 2 - cameraZ);
			float pz1 = (float) ((ty + 1) * side - world / 2 - cameraZ);
			for (int tx = x0; tx <= x1; tx++) {
				int wx = Math.floorMod(tx, n);
				float px = (float) (tx * side - world / 2 - cameraX);
				float px1 = (float) ((tx + 1) * side - world / 2 - cameraX);
				// The tile's own picture, else the nearest bigger tile's part of one.
				int up = 0;
				while (up <= zoom && !textures.containsKey(key(zoom - up, wx >> up, ty >> up))) {
					up++;
				}
				if (up > 0) {
					missing.add(new long[] {key(zoom, wx, ty), (long) ((tx - middleX) * (tx - middleX) + (ty - middleY) * (ty - middleY))});
					if (up > zoom) {
						// Nothing here at all yet: first a tile a few zooms out, which covers many and comes quickly.
						int coarse = Math.min(zoom, COARSE_STEPS);
						missing.add(new long[] {key(zoom - coarse, wx >> coarse, ty >> coarse), -1});
						continue;
					}
				}
				DynamicTexture texture = textures.get(key(zoom - up, wx >> up, ty >> up));
				if (texture != null) {
					int parts = 1 << up;
					float u = (float) (wx & parts - 1) / parts;
					float v = (float) (ty & parts - 1) / parts;
					GuiMap.buildTexturedModalSubRectWithLighting(matrix, renderer.begin(texture.getTextureView()), px, pz, u, v, u + 1.0F / parts,
						v + 1.0F / parts, px1 - px, pz1 - pz);
					shown = true;
					if (up == 0) {
						for (MapPainter.Place p : names.getOrDefault(key(zoom, wx, ty), List.of())) {
							if (!p.showsAt(zoom)) {
								continue;
							}
							onScreen.add(new Name(p, tx * side - world / 2 + p.u() * side, ty * side - world / 2 + p.v() * side));
						}
					}
				}
			}
		}
		// Nearest the middle of the screen first.
		missing.sort(Comparator.comparingLong(m -> m[1]));
		long now = System.currentTimeMillis();
		for (long[] m : missing) {
			if (loading.size() >= MAX_LOADING) {
				break;
			}
			if (!loading.containsKey(m[0]) && failedUntil.getOrDefault(m[0], 0L) < now) {
				request(m[0]);
			}
		}
	}

	/** The Earth's projection when the map shows the Earth and its picture is on, else null. */
	private static Projection.WebMercator earth(ResourceKey<Level> dimension) {
		Minecraft mc = Minecraft.getInstance();
		ClimateLink.Shape shape = ClimateLink.current();
		// shortcut: nothing under the sinusoidal projection of old worlds, whose tiles are not squares of blocks; it would need resampling.
		if (!RealEarth.config().mapBackground || mc.level == null || shape == null || !(shape.projection() instanceof Projection.WebMercator mercator)
			|| !(dimension == RealEarth.EARTH || dimension == mc.level.dimension() && RealEarth.isEarth(mc.level))) {
			return null;
		}
		return mercator;
	}

	/**
	 * The map's furthest zoom out, given its own ({@code limit}, in its zoom units): on the Earth, the whole
	 * globe, which the picture covers; elsewhere its own.
	 */
	public static double minZoom(double limit, ResourceKey<Level> dimension) {
		Projection.WebMercator mercator = earth(dimension);
		if (mercator == null) {
			return limit;
		}
		var window = Minecraft.getInstance().getWindow();
		int side = Math.min(window.getWidth(), window.getHeight());
		// The map's zoom times this is screen pixels per block (as in GuiMap.getScaleMultiplier).
		double multiplier = side <= 1100 ? 1.0 : side / 1080.0;
		// The Mercator world is a square: all of it within the window's short side, with a margin.
		return Math.min(limit, 0.9 * side / (multiplier * mercator.periodX()));
	}

	/**
	 * Over the drawn map: the place names, most important first and none over another, and the map
	 * data's credit at the bottom right above the map's zoom, while the map shows a picture. The camera
	 * is in blocks, {@code scale} screen pixels per block, as the map has them.
	 */
	public static void overlay(GuiGraphicsExtractor graphics, int width, int height, double cameraX, double cameraZ, double scale) {
		var font = Minecraft.getInstance().font;
		var window = Minecraft.getInstance().getWindow();
		double gui = window.getGuiScale();
		// Names at two screen pixels a font pixel, smaller than the map's buttons at a big GUI scale.
		float k = (float) Math.min(1, 2 / gui);
		onScreen.sort(Comparator.comparingInt(n -> n.place().priority()));
		List<int[]> taken = new ArrayList<>();
		if (!onScreen.isEmpty() && marksShown) {
			// The waypoint marks first: a name must not cover one (a waypoint's flag stands above its spot).
			for (MapWaypoints.Mark m : ClientHere.marks()) {
				int mx = (int) (((m.x() + 0.5 - cameraX) * scale + window.getWidth() / 2.0) / gui);
				int my = (int) (((m.z() + 0.5 - cameraZ) * scale + window.getHeight() / 2.0) / gui);
				taken.add(new int[] {mx - 8, my - 16, mx + 8, my + 6});
			}
		}
		int written = 0;
		for (Name n : onScreen) {
			if (written >= MAX_NAMES) {
				break;
			}
			int kind = n.place().kind();
			String text = n.place().name();
			int w = (int) Math.ceil(font.width(text) * k);
			int x = (int) (((n.x() - cameraX) * scale + window.getWidth() / 2.0) / gui) - w / 2;
			int y = (int) (((n.z() - cameraZ) * scale + window.getHeight() / 2.0) / gui) - (int) (4 * k);
			int[] box = {x - NAME_SPACE, y - NAME_SPACE / 2, x + w + NAME_SPACE, y + (int) Math.ceil(9 * k) + NAME_SPACE / 2};
			int h = (int) Math.ceil(9 * k);
			if (x < SIDE_MARGIN || x + w > width - SIDE_MARGIN || y < TOP_MARGIN || y + h > height - BOTTOM_MARGIN || taken.stream().anyMatch(t -> t[0] < box[2] && box[0] < t[2] && t[1] < box[3] && box[1] < t[3])) {
				continue;
			}
			taken.add(box);
			written++;
			int colour = switch (kind) {
				case MapPainter.Place.STATE -> 0xFFC8C8C8;
				case MapPainter.Place.CITY, MapPainter.Place.TOWN -> 0xFFFFFFFF;
				default -> 0xFFDADADA;
			};
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(k, k);
			graphics.text(font, text, 0, 0, colour, true);
			graphics.pose().popMatrix();
		}
		if (shown) {
			graphics.pose().pushMatrix();
			graphics.pose().translate(width - 26 - font.width(CREDIT) * k, height - 12 - 9 * k);
			graphics.pose().scale(k, k);
			graphics.text(font, CREDIT, 0, 0, 0xFFC0C0C0);
			graphics.pose().popMatrix();
		}
		// Until the map draws again: not on its loading screen.
		shown = false;
		onScreen.clear();
	}

	private static long key(int zoom, int x, int y) {
		return (long) zoom << 58 | (long) x << 29 | y;
	}

	private static void request(long key) {
		int zoom = (int) (key >>> 58);
		int x = (int) (key >>> 29 & (1 << 29) - 1);
		int y = (int) (key & (1 << 29) - 1);
		int dataZoom = Math.min(zoom, DATA_ZOOM);
		int shift = zoom - dataZoom;
		OsmWater map = source();
		loading.put(key, CompletableFuture.supplyAsync(() -> {
			try {
				byte[] data = map.raw(dataZoom, x >> shift, y >> shift);
				return new Painted(MapPainter.paint(data, dataZoom, zoom, x, y, relief(zoom, x, y)), MapPainter.places(data, dataZoom, zoom, x, y));
			} catch (Http.Missing e) {
				return null;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}, WORKERS));
	}

	/** Puts the pictures painted since the last frame on the GPU, a few a frame. */
	private static void upload() {
		int uploaded = 0;
		for (Iterator<Map.Entry<Long, CompletableFuture<Painted>>> it = loading.entrySet().iterator(); it.hasNext() && uploaded < UPLOADS_PER_FRAME;) {
			Map.Entry<Long, CompletableFuture<Painted>> e = it.next();
			if (!e.getValue().isDone()) {
				continue;
			}
			it.remove();
			Painted painted;
			try {
				painted = e.getValue().join();
			} catch (RuntimeException failure) {
				failedUntil.put(e.getKey(), System.currentTimeMillis() + RETRY_MS);
				if (!warned) {
					warned = true;
					RealEarth.LOGGER.warn("Map tiles for Xaero's World Map unavailable for now; retrying every {} s: {}", RETRY_MS / 1000, failure.toString());
				}
				continue;
			}
			DynamicTexture texture = null;
			if (painted != null) {
				int[] argb = painted.argb();
				names.put(e.getKey(), painted.places());
				NativeImage image = new NativeImage(MapPainter.SIZE, MapPainter.SIZE, false);
				for (int y = 0; y < MapPainter.SIZE; y++) {
					for (int x = 0; x < MapPainter.SIZE; x++) {
						// The map's shader reads alpha as block light: none, so the picture dims at night with the map.
						image.setPixel(x, y, argb[y * MapPainter.SIZE + x] & 0xFFFFFF);
					}
				}
				long key = e.getKey();
				texture = new DynamicTexture(() -> "Project Gaia: Earth map tile " + Long.toHexString(key), image);
				uploaded++;
			}
			textures.put(e.getKey(), texture);
		}
	}

	/**
	 * The ground's height under each pixel of picture {@code zoom/x/y}, from the terrain's elevation tiles
	 * and cache; null when they can't be had, and the picture goes unshaded.
	 */
	private static float[] relief(int zoom, int x, int y) {
		int ez = Math.min(zoom, ELEVATION_ZOOM);
		int shift = zoom - ez;
		int tx = x >> shift;
		int ty = y >> shift;
		Path file = cache().resolve("terrarium").resolve(Integer.toString(ez)).resolve(Integer.toString(tx)).resolve(ty + ".png");
		try {
			byte[] png = Http.readCache(file);
			if (png == null) {
				png = Http.get(RealEarth.config().tileUrl.replace("{z}", Integer.toString(ez)).replace("{x}", Integer.toString(tx))
					.replace("{y}", Integer.toString(ty)), 0, 0);
				Http.writeCache(file, png);
			}
			float[] m = TerrariumPng.decode(png, MapPainter.SIZE);
			if (shift == 0) {
				return m;
			}
			int n = MapPainter.SIZE;
			int part = n >> shift;
			int ox = (x & (1 << shift) - 1) * part;
			int oy = (y & (1 << shift) - 1) * part;
			float[] out = new float[n * n];
			// Bilinear, between the coarser tile's pixel centres: stretched pixel by pixel, the shading would come out in steps.
			for (int r = 0; r < n; r++) {
				double sy = Math.clamp(oy + (r + 0.5) / (1 << shift) - 0.5, 0, n - 1);
				int y0 = (int) sy;
				int y1 = Math.min(y0 + 1, n - 1);
				double fy = sy - y0;
				for (int c = 0; c < n; c++) {
					double sx = Math.clamp(ox + (c + 0.5) / (1 << shift) - 0.5, 0, n - 1);
					int x0 = (int) sx;
					int x1 = Math.min(x0 + 1, n - 1);
					double fx = sx - x0;
					double top = m[y0 * n + x0] * (1 - fx) + m[y0 * n + x1] * fx;
					double bottom = m[y1 * n + x0] * (1 - fx) + m[y1 * n + x1] * fx;
					out[r * n + c] = (float) (top * (1 - fy) + bottom * fy);
				}
			}
			return out;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static Path cache() {
		return FabricLoader.getInstance().getGameDir().resolve(RealEarth.config().cacheDirectory);
	}

	private static OsmWater source() {
		EarthConfig config = RealEarth.config();
		Path cache = cache();
		if (DiskCache.current() == null) {
			// In multiplayer no server here keeps the cache under its limit.
			DiskCache.install(cache, config.cacheLimitMegabytes << 20);
		}
		if (source == null) {
			source = new OsmWater(config.mapTileJson, cache.resolve("openfreemap"), WORKERS);
		}
		return source;
	}
}
