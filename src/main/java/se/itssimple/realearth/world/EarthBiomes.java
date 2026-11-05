package se.itssimple.realearth.world;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.climate.Ecoregions;
import se.itssimple.realearth.climate.Koppen;
import se.itssimple.realearth.landcover.WorldCover;

/**
 * Earth's own biomes: the real world's, from its ecoregions ({@link Ecoregions}). Natural land takes the biome of its
 * ecoregion -- the Sierra Nevada temperate conifer forest, the Serengeti tropical savanna -- or the forest its ecoregion
 * is known for: the temperate rainforests of the Pacific coast, Chile, New Zealand and Tasmania, the hemiboreal pine and
 * spruce of southern Sweden and the Baltic, East Asia's laurel forests, Australia's eucalypts, cloud forest on tropical
 * mountains, mixed forest where the winters are continental and in the montane belt.
 * Each grows its own mix of trees (EarthTrees), and has the colours of the real landscape (tools/biomes).
 *
 * Towns, fields, water, rock, ice and snow keep vanilla's biome ({@link BiomePicker}), which also still decides the
 * ground's blocks everywhere.
 */
public final class EarthBiomes {
	private EarthBiomes() {
	}

	/** Kinds of tree (EarthTrees': spruce, oak, birch, dark_oak, acacia, jungle, swamp, mangrove) and their cumulative shares. */
	private record Mix(String[] kinds, double[] upTo) {
		static Mix of(String spec) {
			String[] parts = spec.split(" ");
			String[] kinds = new String[parts.length];
			double[] upTo = new double[parts.length];
			double total = 0;
			for (int i = 0; i < parts.length; i++) {
				String[] kw = parts[i].split(":");
				kinds[i] = kw[0];
				total += Double.parseDouble(kw[1]);
				upTo[i] = total;
			}
			for (int i = 0; i < upTo.length; i++) {
				upTo[i] /= total;
			}
			return new Mix(kinds, upTo);
		}

		String pick(double u) {
			for (int i = 0; i < kinds.length - 1; i++) {
				if (u < upTo[i]) {
					return kinds[i];
				}
			}
			return kinds[kinds.length - 1];
		}
	}

	private static final Map<ResourceKey<Biome>, Mix> MIXES = new LinkedHashMap<>();
	/** Where the southern hemisphere's forest of a biome is another mix: its temperate rainforests are southern beeches and podocarps. */
	private static final Map<ResourceKey<Biome>, Mix> SOUTH = new LinkedHashMap<>();

	public static final ResourceKey<Biome> TROPICAL_RAINFOREST = add("tropical_rainforest", "jungle:100");
	public static final ResourceKey<Biome> CLOUD_FOREST = add("cloud_forest", "jungle:75 oak:15 spruce:10");
	public static final ResourceKey<Biome> TROPICAL_DRY_FOREST = add("tropical_dry_forest", "jungle:55 acacia:45");
	public static final ResourceKey<Biome> TROPICAL_PINE_FOREST = add("tropical_pine_forest", "spruce:70 oak:30");
	public static final ResourceKey<Biome> TEMPERATE_DECIDUOUS_FOREST = add("temperate_deciduous_forest", "oak:65 birch:17 dark_oak:18");
	public static final ResourceKey<Biome> TEMPERATE_MIXED_FOREST = add("temperate_mixed_forest", "spruce:40 oak:30 birch:25 dark_oak:5");
	public static final ResourceKey<Biome> TEMPERATE_RAINFOREST = add("temperate_rainforest", "spruce:85 oak:10 birch:5");
	public static final ResourceKey<Biome> HEMIBOREAL_FOREST = add("hemiboreal_forest", "spruce:75 birch:15 oak:10");
	public static final ResourceKey<Biome> LAUREL_FOREST = add("laurel_forest", "oak:55 dark_oak:35 spruce:10");
	public static final ResourceKey<Biome> EUCALYPT_FOREST = add("eucalypt_forest", "oak:50 birch:35 dark_oak:15");
	public static final ResourceKey<Biome> TEMPERATE_CONIFER_FOREST = add("temperate_conifer_forest", "spruce:85 birch:8 oak:7");
	public static final ResourceKey<Biome> BOREAL_FOREST = add("boreal_forest", "spruce:75 birch:25");
	public static final ResourceKey<Biome> MEDITERRANEAN_WOODLAND = add("mediterranean_woodland", "oak:60 spruce:35 acacia:5");
	public static final ResourceKey<Biome> TROPICAL_SAVANNA = add("tropical_savanna", "acacia:80 oak:20");
	public static final ResourceKey<Biome> EUCALYPT_SAVANNA = add("eucalypt_savanna", "oak:45 acacia:30 birch:25");
	public static final ResourceKey<Biome> TEMPERATE_STEPPE = add("temperate_steppe", "oak:45 birch:35 spruce:20");
	public static final ResourceKey<Biome> FLOODED_GRASSLAND = add("flooded_grassland", "swamp:70 oak:30");
	public static final ResourceKey<Biome> MONTANE_GRASSLAND = add("montane_grassland", "spruce:50 oak:30 birch:20");
	public static final ResourceKey<Biome> TUNDRA = add("tundra", "birch:60 spruce:40");
	public static final ResourceKey<Biome> XERIC_SHRUBLAND = add("xeric_shrubland", "acacia:75 oak:25");

	static {
		SOUTH.put(TEMPERATE_RAINFOREST, Mix.of("spruce:35 dark_oak:35 oak:30"));
	}

	/** Every Earth biome; the biome source declares them up front, and tools/biomes writes one for each. */
	public static final List<ResourceKey<Biome>> ALL = List.copyOf(MIXES.keySet());

	/** Cloud forest from this height on tropical mountains, rainforest below. */
	public static final double CLOUD_FOREST_METERS = 1500;
	/** The montane belt begins at this share of the treeline's height. */
	private static final double MONTANE = 0.4;

	/** Vanilla's biomes that stand for natural land: forest, scrub, grass, tundra, the wetlands of a flooded grassland. */
	private static final Set<ResourceKey<Biome>> NATURAL = Set.of(Biomes.FOREST, Biomes.FLOWER_FOREST, Biomes.BIRCH_FOREST,
		Biomes.DARK_FOREST, Biomes.TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA, Biomes.SNOWY_TAIGA, Biomes.JUNGLE, Biomes.BAMBOO_JUNGLE,
		Biomes.SPARSE_JUNGLE, Biomes.SAVANNA, Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.SNOWY_PLAINS, Biomes.SWAMP);

	private static ResourceKey<Biome> add(String name, String mix) {
		ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, RealEarth.id(name));
		MIXES.put(key, Mix.of(mix));
		return key;
	}

	/**
	 * The biome of a place: its ecoregion's where vanilla's ({@code base}, from {@link BiomePicker#pick}) is natural land,
	 * else vanilla's -- and vanilla's where the ecoregion map has nothing (the sea, or the map unread).
	 */
	public static ResourceKey<Biome> of(ResourceKey<Biome> base, int cover, int climate, double latitude, double longitude, double meters) {
		if (!NATURAL.contains(base) || cover == WorldCover.CROPS || cover == WorldCover.BUILT || cover == WorldCover.SNOW_ICE
			|| Koppen.iceCap(climate)) {
			return base;
		}
		Ecoregions map = Ecoregions.get();
		Ecoregions.Region region = map == null ? null : map.at(latitude, longitude);
		if (region == null || base == Biomes.SWAMP && region.biome() != Ecoregions.FLOODED_GRASSLAND) {
			return base;
		}
		boolean rainforest = region.sub().equals("rainforest");
		boolean eucalypt = region.sub().equals("eucalypt");
		return switch (region.biome()) {
			// Subtropical moist forest where the climate is temperate (southern China, Brazil's Araucaria highlands) is laurel forest.
			case Ecoregions.TROPICAL_MOIST_FOREST -> meters >= CLOUD_FOREST_METERS ? CLOUD_FOREST
				: Koppen.temperate(climate) ? LAUREL_FOREST : TROPICAL_RAINFOREST;
			case Ecoregions.TROPICAL_DRY_FOREST -> TROPICAL_DRY_FOREST;
			case Ecoregions.TROPICAL_CONIFER_FOREST -> TROPICAL_PINE_FOREST;
			// Broadleaf forest gives way to mixed in the montane belt, as beech to beech, fir and spruce in the Black Forest's heights.
			case Ecoregions.TEMPERATE_BROADLEAF_FOREST -> rainforest ? TEMPERATE_RAINFOREST : eucalypt ? EUCALYPT_FOREST
				: region.sub().equals("hemiboreal") ? HEMIBOREAL_FOREST
				: Koppen.continental(climate) || meters >= MONTANE * BiomePicker.treelineMeters(latitude, longitude) ? TEMPERATE_MIXED_FOREST
				: region.sub().equals("laurel") ? LAUREL_FOREST : TEMPERATE_DECIDUOUS_FOREST;
			case Ecoregions.TEMPERATE_CONIFER_FOREST -> rainforest ? TEMPERATE_RAINFOREST : eucalypt ? EUCALYPT_FOREST : TEMPERATE_CONIFER_FOREST;
			case Ecoregions.BOREAL_FOREST -> BOREAL_FOREST;
			case Ecoregions.TROPICAL_GRASSLAND -> eucalypt ? EUCALYPT_SAVANNA : TROPICAL_SAVANNA;
			case Ecoregions.TEMPERATE_GRASSLAND -> eucalypt ? EUCALYPT_SAVANNA : TEMPERATE_STEPPE;
			case Ecoregions.FLOODED_GRASSLAND -> FLOODED_GRASSLAND;
			case Ecoregions.MONTANE_GRASSLAND -> MONTANE_GRASSLAND;
			case Ecoregions.TUNDRA -> TUNDRA;
			case Ecoregions.MEDITERRANEAN -> eucalypt ? EUCALYPT_FOREST : MEDITERRANEAN_WOODLAND;
			case Ecoregions.DESERT -> XERIC_SHRUBLAND;
			default -> base; // mangroves (the land cover has its own), rock and ice
		};
	}

	/** The kind of tree an Earth biome grows, {@code u} (0 to 1) along its mix; null for vanilla's biomes. */
	public static String kind(Holder<Biome> biome, double u, double latitude) {
		ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
		Mix mix = latitude < 0 && SOUTH.containsKey(key) ? SOUTH.get(key) : MIXES.get(key);
		return mix == null ? null : mix.pick(u);
	}

	/** The leaves of an Earth biome's commonest tree, for distant forest canopies; null for vanilla's biomes. */
	public static String leaves(ResourceKey<Biome> key, double latitude) {
		Mix mix = latitude < 0 && SOUTH.containsKey(key) ? SOUTH.get(key) : MIXES.get(key);
		if (mix == null) {
			return null;
		}
		String kind = mix.kinds[0];
		return "minecraft:" + switch (kind) {
			case "swamp" -> "oak";
			default -> kind;
		} + "_leaves";
	}
}
