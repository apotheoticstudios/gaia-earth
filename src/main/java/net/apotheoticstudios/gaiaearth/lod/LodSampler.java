package net.apotheoticstudios.gaiaearth.lod;

import java.util.concurrent.TimeUnit;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.apotheoticstudios.gaiaearth.climate.Koppen;
import net.apotheoticstudios.gaiaearth.data.Http;
import net.apotheoticstudios.gaiaearth.landcover.WorldCover;
import net.apotheoticstudios.gaiaearth.osm.MapPainter;
import net.apotheoticstudios.gaiaearth.osm.OsmWater;
import net.apotheoticstudios.gaiaearth.terrain.TerrainField;
import net.apotheoticstudios.gaiaearth.world.BiomePicker;
import net.apotheoticstudios.gaiaearth.world.EarthBiomes;

/**
 * Distant terrain, without generating a single chunk: for each column of a level-of-detail patch,
 * the ground height, water, surface block, tree canopy and biome, read straight from the data sources
 * at a resolution that matches the column. A 64 m column reads full-resolution elevation and 80 m land
 * cover; a 1 km column reads zoom-11 elevation and 640 m land cover. So the horizon costs a few small tiles, and mountains 50 km
 * away cost no more than the hill next to you.
 *
 * Independent of any LOD mod; adapters (see compat) turn {@link Column}s into their own formats.
 */
public final class LodSampler {
	/** One column: heights are block Y, blocks and biomes are registry ids. waterTop is MIN_VALUE when dry. */
	public record Column(int groundTop, int waterTop, String surface, String under, int canopyTop, String canopy, String biome) {
	}

	/** Columns this narrow or narrower come from exactly the terrain chunks are built from. */
	private static final int EXACT_BELOW = 4;
	/** Columns this narrow or narrower read the smoothed terrain grid. */
	private static final int SMOOTHED_BELOW = 32;
	private static final int CANOPY_FROM = 16;
	private static final boolean CANOPY = !"false".equals(System.getProperty("realearth.lodCanopy"));

	private final TerrainField field;
	/** Column width of the patch being built; canopies are only drawn on coarse columns. */
	private int columnSize = 1;

	public LodSampler(TerrainField field) {
		this.field = field;
	}

	/** Columns of a square patch, row-major: {@code width} columns of {@code columnSize} blocks from (minX, minZ). */
	public Column[] patch(int minX, int minZ, int width, int columnSize) throws Exception {
		this.columnSize = columnSize;
		if (columnSize <= EXACT_BELOW) {
			return exactPatch(minX, minZ, width, columnSize);
		}
		int n = width * width;
		double[] lat = new double[n];
		double[] lon = new double[n];
		for (int j = 0; j < width; j++) {
			for (int i = 0; i < width; i++) {
				double x = minX + (i + 0.5) * columnSize;
				double z = minZ + (j + 0.5) * columnSize;
				lat[j * width + i] = clampLat(field.projection().latitudeAt(z));
				lon[j * width + i] = field.projection().longitudeAt(x, lat[j * width + i]);
			}
		}

		int detail = 31 - Integer.numberOfLeadingZeros(Math.max(1, columnSize));
		// Sample spacing about an eighth of the column or finer: coarse tiles are averages, and on
		// slopes an average is not where the ground is at the column's centre.
		int zoom = detail <= 6 ? 15 : Math.max(6, Math.min(15, 20 - detail));
		int coverLevel = Math.max(0, Math.min(6, 31 - Integer.numberOfLeadingZeros(Math.max(1, columnSize / 10))));

		WorldCover cover = field.landCover();
		WorldCover.Lookup covers = cover == null ? (a, b) -> WorldCover.NONE : cover.prepare(lat, lon, coverLevel).get(120, TimeUnit.SECONDS);
		// Distant terrain is stored by the LOD mod for good: better to fail and be asked again later
		// than to store stand-ins while offline.
		if (covers.degraded()) {
			throw new TerrainField.Unavailable();
		}
		Koppen koppen = Koppen.get();

		// Up to 32 m columns read the smoothed terrain chunks are built from, so the first rings of
		// distant terrain meet the real chunks without a step; further out, raw tiles at a matching zoom.
		double[] elevation = new double[n];
		for (int k = 0; k < n; k++) {
			elevation[k] = columnSize <= SMOOTHED_BELOW ? field.smoothedElevationAt(lat[k], lon[k]) : field.elevationAt(lat[k], lon[k], zoom);
		}

		byte[] land = landcover(lat, lon, columnSize);
		Column[] out = new Column[n];
		for (int j = 0; j < width; j++) {
			for (int i = 0; i < width; i++) {
				int k = j * width + i;
				double e = elevation[k];
				double east = elevation[j * width + Math.min(width - 1, i + 1)];
				double south = elevation[Math.min(width - 1, j + 1) * width + i];
				double rise = Math.max(Math.abs(east - e), Math.abs(south - e)) * field.projection().blocksPerMeter(lat[k]) / columnSize;
				int c = covers.classAt(lat[k], lon[k]);
				int climate = koppen == null ? 0 : koppen.classAt(lat[k], lon[k]);
				boolean sea = e < 0 && (c == WorldCover.NONE || c == WorldCover.WATER);
				boolean inland = !sea && c == WorldCover.WATER;
				// Distant lakes are drawn at least four blocks deep: Distant Horizons shows water as
				// see-through, and a shallower lake reads as the sand under it.
				double ground = sea ? e : inland ? e - Math.max(2.0, 4.0 / field.projection().blocksPerMeter(lat[k])) : e;
				double water = sea ? 0 : inland ? e : Double.NaN;
				out[k] = column(lat[k], lon[k], ground, water, inland, rise, c, climate, land[k], false);
			}
		}
		return out;
	}

	/** The map tiles' land cover masks (MapPainter.landcover) by zoom, x and y, an empty one for a tile the map hasn't: 64 KB each. */
	private static final java.util.Map<Long, byte[]> LANDCOVER = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<Long, byte[]> eldest) {
			return size() > 64;
		}
	});

	/**
	 * What the map says each column's ground is (MapPainter.landcover: its woods, where forest trees grow, beaches, rock...): from
	 * the map's tiles at the zoom whose pixels are about a column across, so a coarse column sees what the map draws at its scale.
	 * Nothing without a map.
	 */
	private byte[] landcover(double[] lat, double[] lon, int columnSize) throws Exception {
		byte[] out = new byte[lat.length];
		var map = field.map();
		if (map == null || lat.length == 0) {
			return out;
		}
		double mid = clampLat(lat[lat.length / 2]);
		double columnMeters = columnSize / field.projection().blocksPerMeter(mid);
		int zoom = (int) Math.max(7, Math.min(14,
			Math.round(Math.log(40_075_016.686 * Math.cos(Math.toRadians(mid)) / (MapPainter.SIZE * columnMeters)) / Math.log(2))));
		int n = 1 << zoom;
		for (int k = 0; k < lat.length; k++) {
			double fx = (lon[k] + 180) / 360 * n;
			double fy = (1 - Math.log(Math.tan(Math.PI / 4 + Math.toRadians(clampLat(lat[k])) / 2)) / Math.PI) / 2 * n;
			int tx = Math.floorMod((int) Math.floor(fx), n);
			int ty = Math.max(0, Math.min(n - 1, (int) Math.floor(fy)));
			long key = ((long) zoom << 40) | ((long) tx << 20) | ty;
			byte[] mask = LANDCOVER.get(key);
			if (mask == null) {
				try {
					mask = MapPainter.landcover(map.raw(zoom, tx, ty), zoom, zoom, tx, ty);
				} catch (Http.Missing e) {
					mask = new byte[0];
				} catch (java.io.IOException e) {
					throw new TerrainField.Unavailable(); // stored for good by the LOD mod: asked again later rather than forest left out
				}
				LANDCOVER.put(key, mask);
			}
			if (mask.length > 0) {
				int px = Math.min(MapPainter.SIZE - 1, (int) ((fx - Math.floor(fx)) * MapPainter.SIZE));
				int py = Math.min(MapPainter.SIZE - 1, (int) ((fy - Math.floor(fy)) * MapPainter.SIZE));
				out[k] = mask[py * MapPainter.SIZE + px];
			}
		}
		return out;
	}

	private static double clampLat(double lat) {
		return Math.max(-89.9, Math.min(89.9, lat));
	}

	/**
	 * Near the player, LOD columns must meet real chunks without a step, so they are read from the
	 * same processed terrain the chunks are generated from -- map water included.
	 */
	private Column[] exactPatch(int minX, int minZ, int width, int columnSize) throws Exception {
		java.util.Map<Long, java.util.concurrent.CompletableFuture<net.apotheoticstudios.gaiaearth.terrain.ChunkTerrain>> chunks = new java.util.HashMap<>();
		int n = width * width;
		int[] bx = new int[n];
		int[] bz = new int[n];
		for (int j = 0; j < width; j++) {
			for (int i = 0; i < width; i++) {
				int k = j * width + i;
				bx[k] = minX + i * columnSize + columnSize / 2;
				bz[k] = minZ + j * columnSize + columnSize / 2;
				long key = ((long) (bx[k] >> 4) << 32) | ((bz[k] >> 4) & 0xFFFFFFFFL);
				int cx = bx[k] >> 4;
				int cz = bz[k] >> 4;
				chunks.computeIfAbsent(key, kk -> field.chunk(cx, cz));
			}
		}
		java.util.concurrent.CompletableFuture.allOf(chunks.values().toArray(java.util.concurrent.CompletableFuture[]::new)).get(180, TimeUnit.SECONDS);
		Column[] out = new Column[n];
		for (int k = 0; k < n; k++) {
			var t = chunks.get(((long) (bx[k] >> 4) << 32) | ((bz[k] >> 4) & 0xFFFFFFFFL)).join();
			if (t.degraded) {
				throw new TerrainField.Unavailable();
			}
			int lx = bx[k] & 15;
			int lz = bz[k] & 15;
			int idx = net.apotheoticstudios.gaiaearth.terrain.ChunkTerrain.index(lx, lz);
			boolean wet = !Float.isNaN(t.waterMeters[idx]) && t.waterTopY[idx] != Integer.MIN_VALUE;
			out[k] = column(t.latitude[idx], t.longitude[idx], t.groundMeters[idx], wet ? t.waterMeters[idx] : Double.NaN, t.lake[idx],
				t.slope(lx, lz), t.cover[idx] & 0xFF, t.climate[idx] & 0xFF, t.wood[idx] ? OsmWater.WOOD : t.landcover[idx], t.riparian[idx]);
			var plan = t.building[idx] == 0 ? null : t.plans.get(t.building[idx] - 1);
			if (plan != null) {
				// Near the player, buildings stand in the distant terrain too, as solid blocks to their roofs.
				String roof = plan.style == net.apotheoticstudios.gaiaearth.terrain.BuildingPlan.Style.DESERT ? "minecraft:smooth_sandstone"
					: plan.roofRise > 0 ? "minecraft:dark_oak_planks" : "minecraft:smooth_stone";
				LodSampler.Column c = out[k];
				out[k] = new Column(t.roofY[idx], Integer.MIN_VALUE, roof, "minecraft:stone_bricks", t.roofY[idx], null, c.biome());
				continue;
			}
			byte way = t.way[idx];
			if (way != net.apotheoticstudios.gaiaearth.osm.Ways.NONE) {
				// Near the player, roads and railways show in the distant terrain too.
				String paved = net.apotheoticstudios.gaiaearth.osm.Ways.isPaved(way) ? "minecraft:gray_concrete"
					: way == net.apotheoticstudios.gaiaearth.osm.Ways.PATH ? "minecraft:dirt_path" : "minecraft:gravel";
				LodSampler.Column c = out[k];
				out[k] = new Column(c.groundTop(), c.waterTop(), paved, "minecraft:stone", c.groundTop(), null, c.biome());
			}
		}
		return out;
	}

	/**
	 * A column; {@code land} is what the map says its ground is (OsmWater.WOOD in its woods, where alone forest trees grow,
	 * EarthTrees; else OsmWater.ground's kinds, or 0), {@code riparian} whether a river or lake is near (known close by only).
	 */
	private Column column(double lat, double lon, double ground, double water, boolean lake, double rise, int cover, int climate,
		byte land, boolean riparian) {
		boolean sea = !Double.isNaN(water) && !lake && water <= 0.5;
		boolean inland = !Double.isNaN(water) && !sea;
		ResourceKey<Biome> biome = BiomePicker.pick(lat, lon, ground, sea || inland, inland, Double.isNaN(water) ? 0 : water, climate, cover);
		// The world's biome, for its colours and its forest's leaves; vanilla's still decides the ground.
		ResourceKey<Biome> earth = EarthBiomes.of(biome, cover, climate, lat, lon, ground, sea || inland ? 0 : land, riparian);
		String earthLeaves = EarthBiomes.leaves(earth, lat);
		int groundTop = (int) Math.round(field.toY(ground, lat, lon)) - 1;
		int waterTop = Double.isNaN(water) ? Integer.MIN_VALUE : (int) Math.round(field.toY(water, lat, lon)) - 1;
		if (waterTop <= groundTop) {
			waterTop = Integer.MIN_VALUE;
		}

		String surface = surface(biome, land == OsmWater.MARSH ? WorldCover.WETLAND : cover, climate, rise, sea, inland, land);
		int canopyHeight = 0;
		String leaves = null;
		// Forest canopies only on coarse columns. Next to real chunks (which have real trees) a solid
		// block of leaves stands up as a wall at the edge of the loaded area.
		boolean forest = cover == WorldCover.TREES && land == OsmWater.WOOD;
		if (CANOPY && columnSize >= CANOPY_FROM && forest && waterTop == Integer.MIN_VALUE && rise < 1.2) {
			canopyHeight = canopyHeight(biome);
			leaves = earthLeaves != null ? earthLeaves : leaves(biome);
		}
		// Finer columns get no canopy (it would stand as a wall next to real chunks), but their forests
		// still look like forest: the leaves lie flat on the ground, the same colour as the canopy beyond.
		if (CANOPY && leaves == null && forest && waterTop == Integer.MIN_VALUE && rise < 1.2) {
			surface = earthLeaves != null ? earthLeaves : leaves(biome);
		}
		String under = switch (surface) {
			case "minecraft:grass_block", "minecraft:farmland", "minecraft:moss_block", "minecraft:mud" -> "minecraft:dirt";
			case String leafBlock when leafBlock.endsWith("_leaves") -> "minecraft:dirt";
			case "minecraft:sand" -> "minecraft:sandstone";
			case "minecraft:red_sand", net.apotheoticstudios.gaiaearth.world.Strata.BANDS -> "minecraft:terracotta";
			case "minecraft:coarse_dirt" -> "minecraft:dirt";
			case "minecraft:snow_block" -> "minecraft:snow_block";
			default -> "minecraft:stone";
		};
		return new Column(groundTop, waterTop, surface, under, groundTop + canopyHeight, leaves, earth.identifier().toString());
	}

	private static String surface(ResourceKey<Biome> biome, int cover, int climate, double rise, boolean sea, boolean inland, byte land) {
		if (sea || inland) {
			return "minecraft:sand";
		}
		if (cover == WorldCover.SNOW_ICE || biome == Biomes.FROZEN_PEAKS || biome == Biomes.SNOWY_SLOPES || biome == Biomes.JAGGED_PEAKS) {
			return rise > 1.0 ? "minecraft:stone" : "minecraft:snow_block";
		}
		boolean sandy = land == OsmWater.BEACH || land == OsmWater.SAND || land == OsmWater.DUNE;
		boolean dunes = sandy;
		if (rise > 1.0 && (Koppen.arid(climate) || biome == Biomes.BADLANDS) && !dunes) {
			return net.apotheoticstudios.gaiaearth.world.Strata.BANDS;
		}
		if (rise > 1.0 && !sandy) {
			return "minecraft:stone";
		}
		// The map's own ground first, as in the chunks (EarthChunkGenerator.column).
		if (sandy) {
			return "minecraft:sand";
		}
		if (land == OsmWater.ROCK) {
			return Koppen.arid(climate) ? net.apotheoticstudios.gaiaearth.world.Strata.BANDS : "minecraft:stone";
		}
		if (land == OsmWater.SCREE) {
			return "minecraft:gravel";
		}
		if (land == OsmWater.TIDAL_FLAT) {
			return "minecraft:mud";
		}
		boolean desert = Koppen.desert(climate);
		if (Koppen.arid(climate) && (cover == WorldCover.SHRUBS || cover == WorldCover.GRASS)) {
			return desert ? (cover == WorldCover.SHRUBS ? "minecraft:red_sand" : "minecraft:coarse_dirt")
				: cover == WorldCover.SHRUBS ? "minecraft:coarse_dirt" : "minecraft:grass_block";
		}
		if (Koppen.arid(climate) && cover == WorldCover.BARE && !Koppen.hotArid(climate)) {
			return "minecraft:coarse_dirt";
		}
		return switch (cover) {
			case WorldCover.CROPS -> "minecraft:farmland";
			// Towns are mostly lawns between their villages (see EarthChunkGenerator), and should look the
			// same from afar as close up; their streets show as the roads they are.
			case WorldCover.BUILT -> Koppen.desert(climate) ? "minecraft:sand" : Koppen.arid(climate) ? "minecraft:coarse_dirt" : "minecraft:grass_block";
			case WorldCover.WETLAND, WorldCover.MANGROVES -> "minecraft:mud";
			case WorldCover.MOSS -> "minecraft:moss_block";
			// Stony desert where the map draws no sand (sandy, above, is the sand seas).
			case WorldCover.BARE -> Koppen.hotArid(climate) || Koppen.tropical(climate) ? "minecraft:coarse_dirt" : "minecraft:gravel";
			default -> {
				if (biome == Biomes.DESERT) {
					yield Koppen.hotArid(climate) ? "minecraft:sand" : "minecraft:gravel";
				}
				if (biome == Biomes.BADLANDS) {
					yield "minecraft:red_sand";
				}
				if (biome == Biomes.STONY_PEAKS || biome == Biomes.WINDSWEPT_GRAVELLY_HILLS) {
					yield "minecraft:stone";
				}
				// Tundra thaws below the snowline (BiomePicker.heightTemperature); an ice cap's climate keeps its snow.
				if ((biome == Biomes.SNOWY_PLAINS || biome == Biomes.SNOWY_TAIGA) && Koppen.iceCap(climate)) {
					yield "minecraft:snow_block";
				}
				yield "minecraft:grass_block";
			}
		};
	}

	private static int canopyHeight(ResourceKey<Biome> b) {
		if (b == Biomes.JUNGLE || b == Biomes.BAMBOO_JUNGLE) {
			return 11;
		}
		if (b == Biomes.OLD_GROWTH_SPRUCE_TAIGA) {
			return 12;
		}
		if (b == Biomes.TAIGA || b == Biomes.SNOWY_TAIGA) {
			return 7;
		}
		if (b == Biomes.SAVANNA || b == Biomes.SPARSE_JUNGLE) {
			return 4;
		}
		return 5;
	}

	private static String leaves(ResourceKey<Biome> b) {
		if (b == Biomes.TAIGA || b == Biomes.SNOWY_TAIGA || b == Biomes.OLD_GROWTH_SPRUCE_TAIGA) {
			return "minecraft:spruce_leaves";
		}
		if (b == Biomes.BIRCH_FOREST) {
			return "minecraft:birch_leaves";
		}
		if (b == Biomes.DARK_FOREST) {
			return "minecraft:dark_oak_leaves";
		}
		if (b == Biomes.JUNGLE || b == Biomes.SPARSE_JUNGLE || b == Biomes.BAMBOO_JUNGLE) {
			return "minecraft:jungle_leaves";
		}
		if (b == Biomes.SAVANNA) {
			return "minecraft:acacia_leaves";
		}
		if (b == Biomes.MANGROVE_SWAMP) {
			return "minecraft:mangrove_leaves";
		}
		return "minecraft:oak_leaves";
	}
}
