package se.itssimple.realearth.world;

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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.climate.Koppen;
import se.itssimple.realearth.mixin.StructureTemplateAccessor;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * Real trees on Earth: where world generation grows a vanilla tree, a tree of a species that really grows there
 * takes its place, built from a builder's schematic (converted by tools/trees into realearth:tree/...).
 *
 * Vanilla still decides where trees grow and how many -- its biomes already follow real land cover and climate
 * (see {@link BiomePicker}) -- and the kind of tree it was about to grow (spruce, birch, oak ...) chooses between
 * a place's conifers and broadleaves. The species comes from realearth/trees/species.tsv: per species, the
 * vanilla kinds it stands in for, the climates (Köppen codes, by prefix) and heights against the treeline it
 * grows at, and where. Most trees in a stand about 32 m across are one species, as real forests mostly are.
 *
 * Only during world generation: saplings players plant grow vanilla trees. Where no species fits, or the
 * trees aren't installed, vanilla's tree grows.
 */
public final class EarthTrees {
	/** Share of trees that follow their stand's species rather than picking their own. */
	private static final double STAND = 0.7;
	/** Stands are 32 blocks across. */
	private static final int STAND_SHIFT = 5;
	private static final Set<String> WOODS = Set.of("spruce", "birch", "dark_oak", "acacia", "jungle", "mangrove", "cherry");
	/** Below this many metres under the treeline a place is "mid" (montane); within 200 m of it, "high". */
	private static final double TREELINE_BAND = 200.0;

	private static final Map<String, LongAdder> PLACED = new ConcurrentHashMap<>();
	private static volatile Index index;

	private EarthTrees() {
	}

	/** One converted tree: its template and where its trunk meets the ground inside it. */
	private record Tree(Identifier id, BlockPos base, int height) {
	}

	/** One line of species.tsv. */
	private record Row(String species, Set<String> kinds, List<String> climates, Set<String> zones,
		double lat0, double lat1, double lon0, double lon1, int weight) {

		boolean matches(String kind, String climate, String zone, double lat, double lon) {
			return kinds.contains(kind) && (zones.contains("*") || zones.contains(zone))
				&& lat >= lat0 && lat <= lat1 && lon >= lon0 && lon <= lon1
				&& (climates.contains("*") || climates.stream().anyMatch(climate::startsWith));
		}
	}

	private record Index(MinecraftServer server, Map<String, List<Tree>> trees, List<Row> rows) {
	}

	/**
	 * How many trees of each species world generation has grown; "vanilla" for those left as they were, "no room"
	 * for trees without room for their trunk, and "clipped" for blocks out of world generation's reach.
	 */
	public static Map<String, Long> placed() {
		Map<String, Long> out = new HashMap<>();
		PLACED.forEach((k, v) -> out.put(k, v.sum()));
		return out;
	}

	/**
	 * Grows a real tree in place of a vanilla one: true or false for whether one grew, or null to leave the
	 * vanilla tree to grow.
	 */
	public static Boolean grow(FeaturePlaceContext<TreeConfiguration> context) {
		if (!(context.level() instanceof WorldGenRegion region) || !(context.chunkGenerator() instanceof EarthChunkGenerator)) {
			return null;
		}
		TerrainField field = RealEarth.terrain();
		BlockPos pos = context.origin();
		String kind = field == null ? null : kind(context);
		Index trees = kind == null ? null : index(region.getServer());
		if (trees == null || trees.rows.isEmpty()) {
			return null;
		}
		double lat = field.projection().latitudeAt(pos.getZ() + 0.5);
		double lon = field.projection().longitudeAt(pos.getX() + 0.5, lat);
		Koppen koppen = Koppen.get();
		String climate = Koppen.code(koppen == null ? Koppen.SEA : koppen.classAt(lat, lon));
		double treeline = BiomePicker.treelineMeters(lat);
		double meters = field.toElevation(pos.getY(), lat);
		String zone = meters >= treeline - TREELINE_BAND ? "high" : meters >= treeline / 2 ? "mid" : "low";
		List<Row> rows = new ArrayList<>();
		for (Row row : trees.rows) {
			if (row.matches(kind, climate, zone, lat, lon)) {
				rows.add(row);
			}
		}
		if (rows.isEmpty()) {
			count("vanilla");
			return null;
		}
		RandomSource random = context.random();
		long stand = region.getSeed() ^ ((long) (pos.getX() >> STAND_SHIFT) * 341873128712L + (long) (pos.getZ() >> STAND_SHIFT) * 132897987541L
			+ kind.hashCode());
		Row row = pick(rows, random.nextDouble() < STAND ? new SplittableRandom(stand).nextDouble() : random.nextDouble());
		List<Tree> variants = trees.trees.get(row.species);
		Tree tree = variants.get(random.nextInt(variants.size()));
		StructureTemplate template = region.getServer().getStructureManager().get(tree.id).orElse(null);
		if (template == null) {
			return null;
		}
		boolean grown = place(region, template, tree, pos, random);
		count(grown ? row.species : "no room");
		return grown;
	}

	/**
	 * The vanilla tree about to grow, by its trunk: spruce (spruces and pines), birch, oak, swamp (oaks in a
	 * swamp), dark_oak, acacia, jungle, mangrove, cherry. Null for anything else (azaleas, pale oaks).
	 */
	private static String kind(FeaturePlaceContext<TreeConfiguration> context) {
		TreeConfiguration config = context.config();
		BlockPos pos = context.origin();
		BlockState trunk = config.trunkProvider.getState(context.level(), context.random(), pos);
		if (config.foliageProvider.getState(context.level(), context.random(), pos).is(Blocks.AZALEA_LEAVES)) {
			return null;
		}
		if (trunk.is(Blocks.OAK_LOG)) {
			return context.level().getBiome(pos).is(Biomes.SWAMP) ? "swamp" : "oak";
		}
		String wood = BuiltInRegistries.BLOCK.getKey(trunk.getBlock()).getPath().replace("_log", "");
		return WOODS.contains(wood) ? wood : null;
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
	 * Builds the tree with its trunk's foot at {@code pos}, turned and mirrored at random. Like a vanilla tree it
	 * needs room for its trunk, and only fills air, leaves and plants; its roots only go into natural ground.
	 */
	private static boolean place(WorldGenRegion region, StructureTemplate template, Tree tree, BlockPos pos, RandomSource random) {
		for (int dy = 0; dy < Math.min(tree.height, 6); dy++) {
			if (!TreeFeature.validTreePos(region, pos.above(dy))) {
				return false;
			}
		}
		Rotation rotation = Rotation.getRandom(random);
		Mirror mirror = random.nextBoolean() ? Mirror.NONE : Mirror.LEFT_RIGHT;
		// The game mirrors about 0, not the pivot, so turn both and keep the trunk's foot where it was.
		BlockPos foot = StructureTemplate.transform(tree.base, mirror, rotation, BlockPos.ZERO);
		ChunkPos centre = region.getCenter();
		for (StructureTemplate.StructureBlockInfo info : ((StructureTemplateAccessor) template).realearth$palettes().get(0).blocks()) {
			BlockPos at = pos.offset(StructureTemplate.transform(info.pos(), mirror, rotation, BlockPos.ZERO).subtract(foot));
			if (Math.abs((at.getX() >> 4) - centre.x()) > 1 || Math.abs((at.getZ() >> 4) - centre.z()) > 1) {
				count("clipped");
				continue;
			}
			boolean root = info.pos().getY() < tree.base.getY();
			if (root ? natural(region.getBlockState(at)) : TreeFeature.validTreePos(region, at)) {
				// Known shape: leaves keep the distances tools/trees worked out, so they decay like a sapling's tree.
				region.setBlock(at, info.state().mirror(mirror).rotate(rotation), 19);
			}
		}
		return true;
	}

	private static boolean natural(BlockState state) {
		return state.isAir() || state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SAND)
			|| state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(BlockTags.REPLACEABLE_BY_TREES);
	}

	private static void count(String what) {
		PLACED.computeIfAbsent(what, k -> new LongAdder()).increment();
	}

	// shortcut: read once per server, so a /reload doesn't pick up new trees; reload them on data reload if that's needed.
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
						new BlockPos(base.get(0).getAsInt(), base.get(1).getAsInt(), base.get(2).getAsInt()), t.get("height").getAsInt()));
				}
			} catch (IOException | RuntimeException e) {
				RealEarth.LOGGER.warn("Could not read the tree list {}: {}", json, e.toString());
			}
		}
		List<Row> rows = new ArrayList<>();
		try (InputStream raw = EarthTrees.class.getResourceAsStream("/realearth/trees/species.tsv");
			 BufferedReader in = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
			in.readLine();
			for (String line; (line = in.readLine()) != null; ) {
				String[] f = line.split("\t");
				if (f.length < 7 || !trees.containsKey(f[0])) {
					continue;
				}
				String[] lat = f[4].split("\\.\\."), lon = f[5].split("\\.\\.");
				rows.add(new Row(f[0], Set.of(f[1].split(",")), List.of(f[2].split(",")), Set.of(f[3].split(",")),
					Double.parseDouble(lat[0]), Double.parseDouble(lat[1]), Double.parseDouble(lon[0]), Double.parseDouble(lon[1]),
					Integer.parseInt(f[6])));
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
