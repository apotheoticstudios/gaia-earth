package net.apotheoticstudios.gaiaearth.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.apotheoticstudios.gaiaearth.climate.Koppen;
import net.apotheoticstudios.gaiaearth.landcover.TreeDensity;
import net.apotheoticstudios.gaiaearth.landcover.WorldCover;
import net.apotheoticstudios.gaiaearth.mixin.StructureTemplateAccessor;
import net.apotheoticstudios.gaiaearth.osm.OsmTrees;
import net.apotheoticstudios.gaiaearth.terrain.ChunkTerrain;
import net.apotheoticstudios.gaiaearth.terrain.TerrainField;

/**
 * Earth's trees: where they stand, how close together, and of which species, from real data.
 *
 * Where: in the woods and forests the map has, on the columns the land cover ({@link WorldCover}, 10 m) shows as trees
 * -- both, as the land cover alone counts any square a tenth under canopy as tree cover, and a mapped wood takes in
 * its clearings -- and at the single trees the map has elsewhere; vanilla's own tree spots are left empty. How close: as many to a hectare as
 * stand there in the real world ({@link TreeDensity}) -- about 3.5 m apart in a Swedish forest, 9 m in the
 * savanna -- scattered on a jittered grid, and where they stand close, the species' slimmer trees grow.
 *
 * Which: the biome (picked from real land cover and climate, see {@link BiomePicker}) gives the kind of tree
 * vanilla would grow there -- spruce, birch, oak ... -- and realearth/trees/species.tsv the species of that kind
 * that really grows there, by climate (Köppen codes, by prefix), height against the treeline and place, built from
 * a builder's schematic (converted by tools/trees into gaia-earth:tree/...). Most trees in a stand about 32 m across
 * are one species, as real forests mostly are. Where no species fits, or the trees aren't installed, a vanilla tree
 * of that kind grows instead.
 *
 * Only during world generation: saplings players plant grow vanilla trees.
 */
public final class EarthTrees {
	/** Share of trees that follow their stand's species rather than picking their own. */
	private static final double STAND = 0.7;
	/** Stands are 32 blocks across. */
	private static final int STAND_SHIFT = 5;
	/** Trees a hectare where the density map has none but the land cover shows trees (towns, its coasts): 10 m apart. */
	private static final double DEFAULT_PER_HECTARE = 100;
	/** Spacing in blocks: never closer than trunks can stand, never so far that a wood is empty. */
	public static final double MIN_SPACING = 2.5;
	private static final double MAX_SPACING = 40;
	/** In close stands only trees whose crowns reach at most this many spacings from the trunk grow. */
	private static final double REACH_PER_SPACING = 1.5;
	/** No other trunk within this share of the spacing: the grid's jitter mustn't stand two trees side by side. */
	private static final double CLEARANCE = 0.5;
	/** Below this many metres under the treeline a place is "mid" (montane); within 200 m of it, "high". */
	private static final double TREELINE_BAND = 200.0;
	/** At the treeline trees grow stunted: this share of their usual height. */
	private static final double TREELINE_HEIGHT = 0.6;
	/** A tree grown taller gets bare trunk up to this share of its height: the map's trees, grown in the open. */
	private static final double MAX_BOLE = 0.55;
	/** And a forest's, grown close: shaded, they shed their low branches, as a kauri or a beech stands clear for half its height and more. */
	private static final double FOREST_BOLE = 0.65;
	/** A tree grown shorter keeps at least this much bare trunk under its crown. */
	private static final int MIN_BOLE = 2;
	private static final Map<String, List<ResourceKey<ConfiguredFeature<?, ?>>>> VANILLA = Map.of(
		"spruce", List.of(TreeFeatures.SPRUCE, TreeFeatures.PINE),
		"birch", List.of(TreeFeatures.BIRCH),
		"oak", List.of(TreeFeatures.OAK, TreeFeatures.FANCY_OAK),
		"dark_oak", List.of(TreeFeatures.DARK_OAK),
		"acacia", List.of(TreeFeatures.ACACIA),
		"jungle", List.of(TreeFeatures.JUNGLE_TREE),
		"swamp", List.of(TreeFeatures.SWAMP_OAK),
		"mangrove", List.of(TreeFeatures.MANGROVE),
		"cherry", List.of(TreeFeatures.CHERRY));

	private static final Map<String, LongAdder> PLACED = new ConcurrentHashMap<>();
	private static final Map<String, LongAdder> HEIGHTS = new ConcurrentHashMap<>();
	private static final Map<Identifier, Shape> SHAPES = new ConcurrentHashMap<>();
	/** Set while Earth grows a vanilla tree itself, so it isn't turned away as one of vanilla's own. */
	private static final ThreadLocal<Boolean> GROWING = new ThreadLocal<>();
	private static volatile Index index;

	private EarthTrees() {
	}

	/** One converted tree: its template, where its trunk meets the ground inside it, and how far its crown reaches. */
	private record Tree(Identifier id, BlockPos base, int height, int reach) {
	}

	/**
	 * Where a tree's crown starts -- its lowest leaves above the ground, in the template -- and the trunk blocks just
	 * under it, which a tree grown taller repeats to lift its crown.
	 */
	private record Shape(int crownBase, List<StructureTemplate.StructureBlockInfo> trunk) {
	}

	/** One line of species.tsv; heights in metres, the species' usual height when grown. */
	private record Row(String species, Set<String> kinds, List<String> climates, Set<String> zones,
		double lat0, double lat1, double lon0, double lon1, int weight, double height0, double height1) {

		/** A row for this kind of tree here; any kind but mangroves alone for a null kind. */
		boolean matches(String kind, String climate, String zone, double lat, double lon) {
			return (kind == null ? !kinds.equals(Set.of("mangrove")) : kinds.contains(kind)) && (zones.contains("*") || zones.contains(zone))
				&& lat >= lat0 && lat <= lat1 && lon >= lon0 && lon <= lon1
				&& (climates.contains("*") || climates.stream().anyMatch(climate::startsWith));
		}
	}

	private record Index(MinecraftServer server, Map<String, List<Tree>> trees, List<Row> rows) {
	}

	/**
	 * How many trees world generation has grown: of each species, "vanilla <kind>" where no species fits; "no room"
	 * for trees without room for their trunk, and "clipped" for blocks out of world generation's reach. "spot" counts
	 * the grid's spots, and "spot: ..." those turned away, and why.
	 */
	public static Map<String, Long> placed() {
		Map<String, Long> out = new HashMap<>();
		PLACED.forEach((k, v) -> out.put(k, v.sum()));
		return out;
	}

	/** Blocks of height grown so far, all trees of each species together. */
	public static Map<String, Long> heights() {
		Map<String, Long> out = new HashMap<>();
		HEIGHTS.forEach((k, v) -> out.put(k, v.sum()));
		return out;
	}

	/**
	 * Vanilla's own trees on Earth: false to leave the spot empty, as Earth grows its trees itself ({@link #fill}),
	 * or null to let it grow -- the vanilla trees Earth grows, saplings, and azaleas over lush caves.
	 */
	public static Boolean grow(FeaturePlaceContext<TreeConfiguration> context) {
		if (GROWING.get() != null || !(context.level() instanceof WorldGenRegion) || !(context.chunkGenerator() instanceof EarthChunkGenerator)
			|| RealEarth.terrain() == null
			|| context.config().foliageProvider.getState(context.level(), context.random(), context.origin()).is(Blocks.AZALEA_LEAVES)) {
			return null;
		}
		return false;
	}

	/**
	 * Blocks a metre for trees: the map's, but never less than one. Where the map is drawn smaller than 1:1 (south of its
	 * scale latitude) trees keep their real height and spacing, as a building keeps a storey the player fits in: a forest
	 * shrunk with the map is short and crowded to walk through.
	 */
	public static double scale(double blocksPerMeter) {
		return Math.max(1.0, blocksPerMeter);
	}

	/** Grows a chunk's trees; before vanilla's decoration, so its snow settles on them. */
	public static void fill(WorldGenLevel level, ChunkGenerator generator, ChunkAccess chunk) {
		TerrainField field = RealEarth.terrain();
		ChunkPos pos = chunk.getPos();
		ChunkTerrain t = field == null ? null : field.chunkIfReady(pos.x(), pos.z());
		if (t == null || !(level instanceof WorldGenRegion region)) {
			return;
		}
		int centre = ChunkTerrain.index(8, 8);
		TreeDensity density = TreeDensity.get();
		double perHectare = density == null ? 0 : density.perHectare(t.latitude[centre], t.longitude[centre]);
		double metres = Math.sqrt(10_000 / (perHectare > 0 ? perHectare : DEFAULT_PER_HECTARE));
		double scale = scale(field.projection().blocksPerMeter(t.latitude[centre]));
		double spacing = Math.clamp(metres * scale, MIN_SPACING, MAX_SPACING);
		Index trees = index(region.getServer());
		int x0 = pos.getMinBlockX();
		int z0 = pos.getMinBlockZ();
		// One spot in each cell of a grid laid over the world, so neighbouring chunks agree.
		for (int i = (int) Math.floor(x0 / spacing); i <= (int) Math.floor((x0 + 15) / spacing); i++) {
			for (int j = (int) Math.floor(z0 / spacing); j <= (int) Math.floor((z0 + 15) / spacing); j++) {
				SplittableRandom cell = new SplittableRandom(region.getSeed() ^ (i * 341873128712L + j * 132897987541L));
				int x = (int) Math.floor((i + cell.nextDouble()) * spacing);
				int z = (int) Math.floor((j + cell.nextDouble()) * spacing);
				if (x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16) {
					growAt(region, generator, t, trees, x, z, spacing, scale, RandomSource.create(cell.nextLong()), null);
				}
			}
		}
		// And the single trees the map has, outside its forests: street trees, park trees.
		for (ChunkTerrain.MappedTree tree : t.mappedTrees) {
			growAt(region, generator, t, trees, tree.x(), tree.z(), spacing, scale,
				RandomSource.create(region.getSeed() ^ (tree.x() * 341873128712L + tree.z() * 132897987541L)), tree);
		}
	}

	/**
	 * A tree at a spot of the grid, if it is in a forest (a mapped wood the land cover shows as trees), or at a tree the map has
	 * ({@code map}; null for a spot of the grid), if it isn't; and if the ground takes one.
	 */
	private static void growAt(WorldGenRegion region, ChunkGenerator generator, ChunkTerrain t, Index trees, int x, int z, double spacing,
		double blocksPerMeter, RandomSource random, ChunkTerrain.MappedTree map) {
		int lx = x & 15;
		int lz = z & 15;
		int k = ChunkTerrain.index(lx, lz);
		int cover = t.cover[k] & 0xFF;
		boolean mapped = map != null;
		byte leaves = mapped ? map.leaves() : -1;
		boolean treeCover = cover == WorldCover.TREES || cover == WorldCover.MANGROVES;
		// A forest is where the land cover sees trees and the map has a wood: either alone puts trees where there are none.
		boolean forest = treeCover && t.wood[k];
		String spot = mapped ? "spot mapped" : "spot";
		count(spot);
		// A mapped tree may stand on a footpath or a square, as trees do in promenades and plazas.
		boolean onWay = t.way[k] != 0 && !(mapped && t.way[k] == net.apotheoticstudios.gaiaearth.osm.Ways.PATH);
		if (forest == mapped || onWay || t.building[k] != 0 || t.hasWater(lx, lz)) {
			// A forest's trees stand on the grid, at its real density; a mapped tree there would be one more.
			count(spot + ": " + (forest == mapped ? (mapped ? "in a forest" : treeCover ? "no mapped wood" : "cover " + cover) : "way/building/water"));
			return;
		}
		BlockPos pos = new BlockPos(x, t.topSolidY[k] + 1, z);
		BlockState ground = region.getBlockState(pos.below());
		boolean pit = false;
		if (!ground.is(BlockTags.SUBSTRATE_OVERWORLD) && !ground.is(BlockTags.SAND)) {
			// Paving part of a block high (Saro's, where a street climbs) takes a pit too: it fills the block.
			boolean paving = ground.isCollisionShapeFullBlock(region, pos.below()) || RoadBlocks.high(ground, 16) != ground;
			if (!mapped || !paving || ground.is(BlockTags.LOGS) || ground.is(BlockTags.LEAVES)) {
				count("spot: ground " + ground.getBlock().getDescriptionId());
				return;
			}
			pit = true; // a street tree in paving or gravel: a pit of soil under its trunk, the paving left round it
		}
		int clear = mapped ? 1 : (int) Math.min(4, spacing * CLEARANCE); // mapped trees stand where they really are
		for (int dx = -clear; dx <= clear; dx++) {
			for (int dz = -clear; dz <= clear; dz++) {
				if (region.getBlockState(pos.offset(dx, 1, dz)).is(BlockTags.LOGS)) {
					count("spot: too close");
					return;
				}
			}
		}
		// Kinds come in stands, as species do: a mixed forest is groves of conifers and of broadleaves, not a tree of each in turn.
		double along = random.nextDouble() < STAND ? new SplittableRandom(region.getSeed() ^ ((long) (x >> STAND_SHIFT) * 341873128712L
			+ (long) (z >> STAND_SHIFT) * 132897987541L) ^ 0x6b696e64L).nextDouble() : random.nextDouble();
		String kind = cover == WorldCover.MANGROVES ? "mangrove" : leaves == OsmTrees.NEEDLE ? "spruce"
			: kind(region.getBiome(pos), random, along, t.latitude[k]);
		if (leaves == OsmTrees.BROAD && kind.equals("spruce")) {
			kind = "oak";
		}
		double lat = t.latitude[k];
		double lon = t.longitude[k];
		double treeline = BiomePicker.treelineMeters(lat, lon);
		double meters = t.groundMeters[k];
		String zone = meters >= treeline - TREELINE_BAND ? "high" : meters >= treeline / 2 ? "mid" : "low";
		String climate = Koppen.code(t.climate[k] & 0xFF);
		// A tree the map names grows as that species, or one of its genus, at the height it was measured if it was.
		String species = mapped && map.taxon() != null ? named(trees, map.taxon(), climate, zone, lat, lon, random) : null;
		Row row = species == null ? null : rowOf(trees, species, climate, zone, lat, lon);
		double metres = species == null ? Double.NaN : !Float.isNaN(map.meters()) ? map.meters()
			: row != null ? row.height0 + random.nextDouble() * (row.height1 - row.height0) : Double.NaN;
		if (species != null) {
			count("spot mapped: named");
		}
		List<Row> rows = new ArrayList<>();
		for (int i = 0; species == null && i < trees.rows.size(); i++) {
			Row r = trees.rows.get(i);
			if (r.matches(kind, climate, zone, lat, lon)) {
				rows.add(r);
			}
		}
		// No species here stands in for the biome's kind of tree: any that grows here, before a vanilla tree; mangroves stay mangroves.
		if (species == null && rows.isEmpty() && !kind.equals("mangrove")) {
			for (Row r : trees.rows) {
				if (r.matches(null, climate, zone, lat, lon)) {
					rows.add(r);
				}
			}
		}
		if (species == null && !rows.isEmpty()) {
			long stand = region.getSeed() ^ ((long) (x >> STAND_SHIFT) * 341873128712L + (long) (z >> STAND_SHIFT) * 132897987541L + kind.hashCode());
			row = pick(rows, random.nextDouble() < STAND ? new SplittableRandom(stand).nextDouble() : random.nextDouble());
			species = row.species;
			metres = row.height0 + random.nextDouble() * (row.height1 - row.height0);
		}
		if (mapped && Float.isNaN(map.meters()) && cover == WorldCover.BUILT && row != null) {
			// A town's trees with no measured height stand as its measured ones do: 0.4 to 0.8 of their species' grown height
			// (the quartiles of Paris's 1,678 measured trees of species in the table).
			metres = (row.height0 + row.height1) / 2 * (0.4 + 0.4 * random.nextDouble());
		}
		if (species != null) {
			List<Tree> versions = trees.trees.get(species);
			// A tree measured keeps its height; one of a species with neither a measure nor a row, its versions' middling height.
			if (Double.isNaN(metres)) {
				metres = versions.stream().mapToInt(Tree::height).sorted().toArray()[versions.size() / 2];
			}
			int height = (int) Math.round(metres * blocksPerMeter * (zone.equals("high") && (!mapped || Float.isNaN(map.meters())) ? TREELINE_HEIGHT : 1));
			// World generation writes one chunk beyond this one: a crown may reach 16 blocks past the nearer edge.
			int room = 16 + Math.min(Math.min(lx, 15 - lx), Math.min(lz, 15 - lz));
			Tree tree = variant(versions, spacing, height, room, random);
			StructureTemplate template = tree == null ? null : region.getServer().getStructureManager().get(tree.id).orElse(null);
			if (tree == null) {
				count("too wide here");
				return;
			}
			if (template != null) {
				int grown = place(region, template, tree, pos, height, mapped ? MAX_BOLE : FOREST_BOLE, random);
				if (pit && grown > 0) {
					region.setBlock(pos.below(), net.minecraft.world.level.block.Blocks.COARSE_DIRT.defaultBlockState(), 2);
				}
				count(grown > 0 ? species : "no room");
				if (mapped && grown > 0) {
					count("spot mapped: grown");
				}
				HEIGHTS.computeIfAbsent(species, s -> new LongAdder()).add(grown);
				return;
			}
		}
		if (pit) {
			region.setBlock(pos.below(), net.minecraft.world.level.block.Blocks.COARSE_DIRT.defaultBlockState(), 2);
		}
		if (!growVanilla(region, generator, kind, pos, random) && pit) {
			region.setBlock(pos.below(), ground, 2); // no room after all: the paving stays whole
		}
	}

	/**
	 * The kind of tree a biome grows: spruce (spruces and pines), birch, oak, swamp (oaks in a swamp), dark_oak, acacia,
	 * jungle, mangrove, cherry. An Earth biome's from its own mix ({@link EarthBiomes#kind}, {@code along} it); in
	 * vanilla's, mixed as vanilla mixes them.
	 */
	private static String kind(Holder<Biome> biome, RandomSource random, double along, double latitude) {
		String earth = EarthBiomes.kind(biome, along, latitude);
		if (earth != null) {
			return earth;
		}
		if (biome.is(BiomeTags.IS_TAIGA) || biome.is(Biomes.GROVE) || biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.SNOWY_SLOPES)) {
			return "spruce";
		}
		if (biome.is(Biomes.WINDSWEPT_FOREST) || biome.is(Biomes.WINDSWEPT_HILLS)) {
			return random.nextInt(3) < 2 ? "spruce" : "oak";
		}
		if (biome.is(Biomes.BIRCH_FOREST) || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)) {
			return "birch";
		}
		if (biome.is(Biomes.DARK_FOREST)) {
			return random.nextFloat() < 0.7f ? "dark_oak" : "oak";
		}
		if (biome.is(BiomeTags.IS_SAVANNA)) {
			return random.nextFloat() < 0.8f ? "acacia" : "oak";
		}
		if (biome.is(BiomeTags.IS_JUNGLE)) {
			return "jungle";
		}
		if (biome.is(Biomes.SWAMP)) {
			return "swamp";
		}
		if (biome.is(Biomes.MANGROVE_SWAMP)) {
			return "mangrove";
		}
		if (biome.is(Biomes.CHERRY_GROVE)) {
			return "cherry";
		}
		return random.nextFloat() < 0.2f ? "birch" : "oak";
	}

	/**
	 * The installed species a tree the map names is ({@code taxon} as OsmTrees.Tree has it): the very one, else one of its
	 * genus the table grows here, else the one of its genus whose range is nearest (a London plane in Paris grows as an
	 * oriental plane, not Arizona's), else any of its genus; null if none is installed.
	 */
	private static String named(Index trees, String taxon, String climate, String zone, double lat, double lon, RandomSource random) {
		if (trees.trees.containsKey(taxon)) {
			return taxon;
		}
		String genus = (taxon.indexOf('_') < 0 ? taxon : taxon.substring(0, taxon.indexOf('_'))) + "_";
		List<Row> here = new ArrayList<>();
		String nearest = null;
		double distance = Double.MAX_VALUE;
		for (Row row : trees.rows) {
			if (!row.species.startsWith(genus)) {
				continue;
			}
			if (row.matches(null, climate, zone, lat, lon)) {
				here.add(row);
			}
			double dLat = Math.max(0, Math.max(row.lat0 - lat, lat - row.lat1));
			double dLon = Math.max(0, Math.max(row.lon0 - lon, lon - row.lon1));
			double d = Math.hypot(dLat, Math.min(dLon, 360 - dLon) * Math.cos(Math.toRadians(lat)));
			if (d < distance) {
				distance = d;
				nearest = row.species;
			}
		}
		if (!here.isEmpty()) {
			return pick(here, random.nextDouble()).species;
		}
		if (nearest != null) {
			return nearest;
		}
		List<String> any = trees.trees.keySet().stream().filter(s -> s.startsWith(genus)).sorted().toList();
		return any.isEmpty() ? null : any.get(random.nextInt(any.size()));
	}

	/** A row of the table for a species, for its height: one for here if there is one, else any; null if it has none. */
	private static Row rowOf(Index trees, String species, String climate, String zone, double lat, double lon) {
		Row any = null;
		for (Row row : trees.rows) {
			if (row.species.equals(species)) {
				if (row.matches(null, climate, zone, lat, lon)) {
					return row;
				}
				any = any == null ? row : any;
			}
		}
		return any;
	}

	private static Row pick(List<Row> rows, double u) {
		int total = 0;
		for (Row row : rows) {
			total += row.weight;
		}
		double at = u * total;
		for (Row row : rows) {
			at -= row.weight;
			if (at < 0) {
				return row;
			}
		}
		return rows.get(rows.size() - 1);
	}

	/**
	 * One of a species' trees whose crown suits the spacing (or its slimmest, if none does), and fits the room
	 * world generation leaves round the spot: any about as near the height wanted as the nearest, at least the two
	 * nearest, so a stand isn't one tree over and over. Null if none fits the room, as the widest trees only fit with
	 * their trunk mid-chunk.
	 */
	private static Tree variant(List<Tree> variants, double spacing, int height, int room, RandomSource random) {
		List<Tree> fit = new ArrayList<>();
		Tree slimmest = null;
		for (Tree tree : variants) {
			if (tree.reach > room) {
				continue;
			}
			if (tree.reach <= REACH_PER_SPACING * spacing) {
				fit.add(tree);
			}
			if (slimmest == null || tree.reach < slimmest.reach) {
				slimmest = tree;
			}
		}
		if (fit.isEmpty()) {
			return slimmest;
		}
		fit.sort(java.util.Comparator.comparingInt(tree -> Math.abs(tree.height - height)));
		int near = Math.min(2, fit.size());
		int within = Math.abs(fit.get(0).height - height) + Math.max(2, height / 6);
		while (near < fit.size() && Math.abs(fit.get(near).height - height) <= within) {
			near++;
		}
		return fit.get(random.nextInt(near));
	}

	/** A vanilla tree of the kind, where no species fits or none is installed; whether it grew. */
	private static boolean growVanilla(WorldGenRegion region, ChunkGenerator generator, String kind, BlockPos pos, RandomSource random) {
		List<ResourceKey<ConfiguredFeature<?, ?>>> keys = VANILLA.get(kind);
		var feature = region.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE).get(keys.get(random.nextInt(keys.size())));
		if (feature.isEmpty()) {
			return false;
		}
		GROWING.set(Boolean.TRUE);
		try {
			boolean grown = feature.get().value().place(region, generator, random, pos);
			count(grown ? "vanilla " + kind : "no room");
			return grown;
		} finally {
			GROWING.remove();
		}
	}

	/**
	 * Builds the tree with its trunk's foot at {@code pos}, turned and mirrored at random, and grown towards
	 * {@code height} blocks: a tree shorter than that gets a longer trunk under its crown, bare for at most
	 * {@code maxBole} of its height; a taller one loses trunk under its crown, down to {@link #MIN_BOLE} -- a stunted
	 * tree at the treeline. Like a vanilla
	 * tree it needs room for its trunk, and
	 * only fills air, plants, and leaves where it has wood; its roots only go into natural ground. Returns its
	 * height, or 0 when it had no room.
	 */
	private static int place(WorldGenRegion region, StructureTemplate template, Tree tree, BlockPos pos, int height, double maxBole,
		RandomSource random) {
		List<StructureTemplate.StructureBlockInfo> blocks = ((StructureTemplateAccessor) template).realearth$palettes().get(0).blocks();
		Shape shape = SHAPES.computeIfAbsent(tree.id, id -> shape(blocks, tree.base.getY()));
		int bole = shape.crownBase - tree.base.getY();
		int extra = height < tree.height ? -Math.min(tree.height - height, Math.max(0, bole - MIN_BOLE))
			: shape.trunk.isEmpty() ? 0 : (int) Math.clamp((maxBole * tree.height - bole) / (1 - maxBole), 0, height - tree.height);
		for (int dy = 0; dy < Math.min(tree.height + extra, 6); dy++) {
			if (!TreeFeature.validTreePos(region, pos.above(dy))) {
				return 0;
			}
		}
		Rotation rotation = Rotation.getRandom(random);
		Mirror mirror = random.nextBoolean() ? Mirror.NONE : Mirror.LEFT_RIGHT;
		// The game mirrors about 0, not the pivot, so turn both and keep the trunk's foot where it was.
		BlockPos foot = StructureTemplate.transform(tree.base, mirror, rotation, BlockPos.ZERO);
		for (StructureTemplate.StructureBlockInfo info : blocks) {
			int y = info.pos().getY();
			if (y < shape.crownBase && y >= shape.crownBase + extra) {
				continue; // the trunk a shortened tree loses
			}
			BlockPos at = pos.offset(StructureTemplate.transform(info.pos(), mirror, rotation, BlockPos.ZERO).subtract(foot));
			set(region, at.above(y >= shape.crownBase ? extra : 0), info, tree, mirror, rotation);
		}
		for (StructureTemplate.StructureBlockInfo info : shape.trunk) {
			BlockPos at = pos.offset(StructureTemplate.transform(info.pos(), mirror, rotation, BlockPos.ZERO).subtract(foot));
			for (int dy = 1; dy <= extra; dy++) {
				set(region, at.above(dy), info, tree, mirror, rotation);
			}
		}
		return tree.height + extra;
	}

	private static void set(WorldGenRegion region, BlockPos at, StructureTemplate.StructureBlockInfo info, Tree tree, Mirror mirror,
		Rotation rotation) {
		ChunkPos centre = region.getCenter();
		if (Math.abs((at.getX() >> 4) - centre.x()) > 1 || Math.abs((at.getZ() >> 4) - centre.z()) > 1) {
			count("clipped");
			return;
		}
		BlockState there = region.getBlockState(at);
		boolean fits = info.pos().getY() < tree.base.getY() ? natural(there)
			: there.isAir() || there.is(BlockTags.REPLACEABLE_BY_TREES) || there.is(BlockTags.LEAVES) && !info.state().is(BlockTags.LEAVES);
		if (fits) {
			// Known shape: leaves keep the distances tools/trees worked out, so they decay like a sapling's tree.
			region.setBlock(at, info.state().mirror(mirror).rotate(rotation), 19);
		}
	}

	/** The crown's lowest leaves above the ground, and the trunk under them: blocks that carry more wood straight above. */
	private static Shape shape(List<StructureTemplate.StructureBlockInfo> blocks, int ground) {
		Map<BlockPos, BlockState> at = new HashMap<>();
		int crown = Integer.MAX_VALUE;
		for (StructureTemplate.StructureBlockInfo info : blocks) {
			at.put(info.pos(), info.state());
			if (info.state().is(BlockTags.LEAVES) && info.pos().getY() > ground) {
				crown = Math.min(crown, info.pos().getY());
			}
		}
		List<StructureTemplate.StructureBlockInfo> trunk = new ArrayList<>();
		for (StructureTemplate.StructureBlockInfo info : blocks) {
			BlockState above = at.get(info.pos().above());
			if (info.pos().getY() == crown - 1 && !info.state().is(BlockTags.LEAVES) && above != null && !above.is(BlockTags.LEAVES)) {
				trunk.add(info);
			}
		}
		return new Shape(crown, trunk);
	}

	private static boolean natural(BlockState state) {
		return state.isAir() || state.is(BlockTags.SUBSTRATE_OVERWORLD) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SAND)
			|| state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(BlockTags.REPLACEABLE_BY_TREES);
	}

	private static void count(String what) {
		PLACED.computeIfAbsent(what, k -> new LongAdder()).increment();
	}

	// shortcut: read once per server, so a /reload doesn't pick up new trees; reload them on data reload if that's needed.
	/**
	 * The species a biome grows at a place, each with its share of the trees, as world generation picks them (stands
	 * aside): through the biome's kinds of tree, else any that grows there, else vanilla's ("vanilla &lt;kind&gt;"). For the
	 * self-test's look at every biome's trees.
	 */
	public static Map<String, Double> speciesAt(MinecraftServer server, Holder<Biome> biome, int climate, double lat, double lon, double meters) {
		Index trees = index(server);
		double treeline = BiomePicker.treelineMeters(lat, lon);
		String zone = meters >= treeline - TREELINE_BAND ? "high" : meters >= treeline / 2 ? "mid" : "low";
		String code = Koppen.code(climate);
		RandomSource random = RandomSource.create(1);
		Map<String, Double> out = new java.util.TreeMap<>();
		int steps = 200;
		for (int i = 0; i < steps; i++) {
			String kind = kind(biome, random, (i + 0.5) / steps, lat);
			List<Row> rows = trees.rows.stream().filter(r -> r.matches(kind, code, zone, lat, lon)).toList();
			if (rows.isEmpty() && !kind.equals("mangrove")) {
				rows = trees.rows.stream().filter(r -> r.matches(null, code, zone, lat, lon)).toList();
			}
			if (rows.isEmpty()) {
				out.merge("vanilla " + kind, 1.0 / steps, Double::sum);
				continue;
			}
			double total = rows.stream().mapToDouble(Row::weight).sum();
			for (Row r : rows) {
				out.merge(r.species, r.weight / total / steps, Double::sum);
			}
		}
		return out;
	}

	private static Index index(MinecraftServer server) {
		Index i = index;
		if (i == null || i.server != server) {
			synchronized (EarthTrees.class) {
				i = index;
				if (i == null || i.server != server) {
					index = i = load(server);
				}
			}
		}
		return i;
	}

	private static Index load(MinecraftServer server) {
		Map<String, List<Tree>> trees = new HashMap<>();
		var packs = server.getResourceManager().listResources("structure/tree", id -> id.getPath().endsWith("/trees.json"));
		for (var entry : packs.entrySet()) {
			Identifier json = entry.getKey();
			String folder = json.getPath().substring("structure/".length(), json.getPath().length() - "trees.json".length());
			try (BufferedReader in = entry.getValue().openAsReader()) {
				for (JsonElement e : JsonParser.parseReader(in).getAsJsonObject().getAsJsonArray("trees")) {
					JsonObject t = e.getAsJsonObject();
					String file = t.get("file").getAsString();
					JsonArray base = t.getAsJsonArray("base");
					Identifier id = Identifier.fromNamespaceAndPath(json.getNamespace(), folder + file.substring(0, file.length() - ".nbt".length()));
					trees.computeIfAbsent(species(file), k -> new ArrayList<>()).add(new Tree(id,
						new BlockPos(base.get(0).getAsInt(), base.get(1).getAsInt(), base.get(2).getAsInt()), t.get("height").getAsInt(),
						t.get("reach").getAsInt()));
				}
			} catch (IOException | RuntimeException e) {
				RealEarth.LOGGER.warn("Could not read the tree list {}: {}", json, e.toString());
			}
		}
		List<Row> rows = new ArrayList<>();
		try (InputStream raw = EarthTrees.class.getResourceAsStream("/realearth/trees/species.tsv");
			 BufferedReader in = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
			in.readLine();
			Set<String> joined = new java.util.HashSet<>();
			for (String line; (line = in.readLine()) != null; ) {
				String[] f = line.split("\t");
				if (f.length < 8) {
					continue;
				}
				// "picea_abies,norwayspruce": the first names the species, the others add their trees to it.
				String[] names = f[0].split(",");
				for (int k = 1; k < names.length; k++) {
					if (joined.add(names[0] + "," + names[k]) && trees.containsKey(names[k])) {
						trees.computeIfAbsent(names[0], s -> new ArrayList<>()).addAll(trees.get(names[k]));
					}
				}
				if (!trees.containsKey(names[0])) {
					continue;
				}
				String[] lat = f[4].split("\\.\\."), lon = f[5].split("\\.\\.");
				rows.add(new Row(names[0], Set.of(f[1].split(",")), List.of(f[2].split(",")), Set.of(f[3].split(",")),
					Double.parseDouble(lat[0]), Double.parseDouble(lat[1]), Double.parseDouble(lon[0]), Double.parseDouble(lon[1]),
					Integer.parseInt(f[6]), Double.parseDouble(f[7].split("\\.\\.")[0]), Double.parseDouble(f[7].split("\\.\\.")[1])));
			}
		} catch (IOException | RuntimeException e) {
			RealEarth.LOGGER.warn("Could not read the tree species table: {}", e.toString());
		}
		RealEarth.LOGGER.info("Real trees: {} trees of {} species installed, {} species placed by the table", trees.values().stream()
			.mapToInt(List::size).sum(), trees.size(), rows.stream().map(Row::species).distinct().count());
		return new Index(server, trees, rows);
	}

	/** A tree's species from its file: "paleozoey-europe/quercus_robur3.nbt" and "quercus_alba__3_.nbt" by their names. */
	static String species(String file) {
		String stem = file.substring(file.lastIndexOf('/') + 1, file.length() - ".nbt".length());
		return stem.replaceAll("[-_0-9]+$", "");
	}
}
