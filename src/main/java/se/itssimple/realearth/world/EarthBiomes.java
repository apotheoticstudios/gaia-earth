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
 * ecoregion -- the Sierra Nevada temperate conifer forest, the Serengeti tropical savanna -- or a sub-biome: the landscape
 * its ecoregion is known for (the temperate rainforests of the Pacific coast, Chile, New Zealand and Tasmania, the
 * redwoods, the hemiboreal pine and spruce of southern Sweden and the Baltic, eastern Siberia's larch, Atlantic moorland,
 * forest-steppe, oak savanna, tallgrass prairie, pine savanna, fynbos, miombo, cerrado, thorn and cactus scrub, sagebrush,
 * the Karoo, spinifex and mulga, paramo and puna, salt flats, polar desert, tropical swamp forest, laurel forest,
 * Australia's eucalypts, beechwoods, mountain birch, monsoon forest, Mediterranean conifers, dehesa, mallee, chaparral,
 * shortgrass and mixed-grass prairie, the pampas, Patagonian steppe, tussock, desert steppe, succulent thicket, alpine
 * tundra and shrubland, xeric woodland, the llanos, the Sahel, forest-savanna mosaic, mopane, bushveld, the Kalahari,
 * the Highveld), or one by place: cloud forest on tropical mountains, mixed forest where the winters are continental
 * and in the montane belt, subalpine forest below the treeline, Mediterranean scrub on its open land, heath on Atlantic
 * Europe's lowland shrubland, forest-tundra where the tundra has trees, peat bog in the north's wetlands, cypress swamp
 * in the US South's, salt marsh on low temperate coasts.
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
	// Sub-biomes: the landscapes some ecoregions are known for, and wetlands, scrub and polar desert by place.
	public static final ResourceKey<Biome> REDWOOD_FOREST = add("redwood_forest", "spruce:95 oak:5");
	public static final ResourceKey<Biome> LARCH_TAIGA = add("larch_taiga", "spruce:85 birch:15");
	public static final ResourceKey<Biome> PEAT_BOG = add("peat_bog", "spruce:70 birch:30");
	public static final ResourceKey<Biome> MOORLAND = add("moorland", "birch:55 spruce:45");
	public static final ResourceKey<Biome> FOREST_STEPPE = add("forest_steppe", "birch:50 spruce:30 oak:20");
	public static final ResourceKey<Biome> OAK_SAVANNA = add("oak_savanna", "oak:80 dark_oak:10 birch:10");
	public static final ResourceKey<Biome> TALLGRASS_PRAIRIE = add("tallgrass_prairie", "oak:65 birch:35");
	public static final ResourceKey<Biome> PINE_SAVANNA = add("pine_savanna", "spruce:85 oak:15");
	public static final ResourceKey<Biome> MEDITERRANEAN_SCRUB = add("mediterranean_scrub", "oak:55 spruce:30 acacia:15");
	public static final ResourceKey<Biome> FYNBOS = add("fynbos", "oak:60 acacia:40");
	public static final ResourceKey<Biome> MIOMBO_WOODLAND = add("miombo_woodland", "oak:40 acacia:40 jungle:20");
	public static final ResourceKey<Biome> CERRADO = add("cerrado", "acacia:50 oak:50");
	public static final ResourceKey<Biome> THORN_SCRUB = add("thorn_scrub", "acacia:90 oak:10");
	public static final ResourceKey<Biome> CACTUS_SCRUB = add("cactus_scrub", "acacia:75 oak:25");
	public static final ResourceKey<Biome> SAGEBRUSH_STEPPE = add("sagebrush_steppe", "spruce:60 acacia:25 oak:15");
	public static final ResourceKey<Biome> KAROO = add("karoo", "acacia:80 oak:20");
	public static final ResourceKey<Biome> SPINIFEX_GRASSLAND = add("spinifex_grassland", "acacia:55 oak:45");
	public static final ResourceKey<Biome> MULGA_WOODLAND = add("mulga_woodland", "acacia:85 oak:15");
	public static final ResourceKey<Biome> PARAMO = add("paramo", "oak:50 birch:30 spruce:20");
	public static final ResourceKey<Biome> PUNA = add("puna", "oak:60 birch:40");
	public static final ResourceKey<Biome> SWAMP_FOREST = add("swamp_forest", "jungle:85 swamp:15");
	public static final ResourceKey<Biome> SALT_MARSH = add("salt_marsh", "swamp:100");
	public static final ResourceKey<Biome> POLAR_DESERT = add("polar_desert", "birch:100");
	public static final ResourceKey<Biome> BEECH_FOREST = add("beech_forest", "dark_oak:55 oak:35 birch:10");
	public static final ResourceKey<Biome> MOUNTAIN_BIRCH_FOREST = add("mountain_birch_forest", "birch:95 spruce:5");
	public static final ResourceKey<Biome> MONSOON_FOREST = add("monsoon_forest", "jungle:60 acacia:25 oak:15");
	public static final ResourceKey<Biome> MEDITERRANEAN_CONIFER_FOREST = add("mediterranean_conifer_forest", "spruce:75 oak:25");
	public static final ResourceKey<Biome> DEHESA = add("dehesa", "oak:95 spruce:5");
	public static final ResourceKey<Biome> MALLEE = add("mallee", "oak:60 birch:40");
	public static final ResourceKey<Biome> CHAPARRAL = add("chaparral", "oak:70 spruce:30");
	public static final ResourceKey<Biome> SHORTGRASS_PRAIRIE = add("shortgrass_prairie", "oak:50 birch:30 spruce:20");
	public static final ResourceKey<Biome> MIXED_GRASS_PRAIRIE = add("mixed_grass_prairie", "oak:55 birch:30 spruce:15");
	public static final ResourceKey<Biome> PAMPAS = add("pampas", "oak:60 acacia:40");
	public static final ResourceKey<Biome> PATAGONIAN_STEPPE = add("patagonian_steppe", "oak:50 birch:50");
	public static final ResourceKey<Biome> TUSSOCK_GRASSLAND = add("tussock_grassland", "oak:50 birch:30 spruce:20");
	public static final ResourceKey<Biome> DESERT_STEPPE = add("desert_steppe", "acacia:60 oak:25 birch:15");
	public static final ResourceKey<Biome> SUCCULENT_THICKET = add("succulent_thicket", "acacia:70 oak:30");
	public static final ResourceKey<Biome> ALPINE_TUNDRA = add("alpine_tundra", "birch:70 spruce:30");
	public static final ResourceKey<Biome> ALPINE_SHRUBLAND = add("alpine_shrubland", "spruce:50 birch:30 oak:20");
	public static final ResourceKey<Biome> XERIC_WOODLAND = add("xeric_woodland", "acacia:45 spruce:30 oak:25");
	public static final ResourceKey<Biome> LLANOS = add("llanos", "jungle:55 acacia:45");
	public static final ResourceKey<Biome> SAHEL = add("sahel", "acacia:95 oak:5");
	public static final ResourceKey<Biome> FOREST_SAVANNA_MOSAIC = add("forest_savanna_mosaic", "jungle:50 acacia:30 oak:20");
	public static final ResourceKey<Biome> MOPANE_WOODLAND = add("mopane_woodland", "acacia:70 oak:30");
	public static final ResourceKey<Biome> BUSHVELD = add("bushveld", "acacia:75 oak:25");
	public static final ResourceKey<Biome> KALAHARI_SAVANNA = add("kalahari_savanna", "acacia:85 oak:15");
	public static final ResourceKey<Biome> HIGHVELD_GRASSLAND = add("highveld_grassland", "oak:50 acacia:50");
	public static final ResourceKey<Biome> FOREST_TUNDRA = add("forest_tundra", "spruce:60 birch:40");
	public static final ResourceKey<Biome> SUBALPINE_FOREST = add("subalpine_forest", "spruce:90 birch:10");
	public static final ResourceKey<Biome> HEATHLAND = add("heathland", "birch:50 spruce:30 oak:20");
	public static final ResourceKey<Biome> CYPRESS_SWAMP = add("cypress_swamp", "swamp:65 spruce:35");
	public static final ResourceKey<Biome> OAK_WOODLAND = add("oak_woodland", "oak:90 spruce:10");
	public static final ResourceKey<Biome> HIMALAYAN_BROADLEAF_FOREST = add("himalayan_broadleaf_forest", "oak:60 dark_oak:25 spruce:15");
	public static final ResourceKey<Biome> PONDEROSA_PINE_FOREST = add("ponderosa_pine_forest", "spruce:90 oak:10");
	public static final ResourceKey<Biome> MITCHELL_GRASS_DOWNS = add("mitchell_grass_downs", "acacia:60 oak:40");
	public static final ResourceKey<Biome> TERAI_GRASSLAND = add("terai_grassland", "jungle:50 acacia:30 oak:20");
	public static final ResourceKey<Biome> MONTE_SCRUB = add("monte_scrub", "acacia:85 oak:15");
	public static final ResourceKey<Biome> ALPINE_DESERT = add("alpine_desert", "birch:100");
	public static final ResourceKey<Biome> AFROMONTANE_GRASSLAND = add("afromontane_grassland", "oak:50 jungle:30 spruce:20");
	public static final ResourceKey<Biome> SNOW_GUM_WOODLAND = add("snow_gum_woodland", "oak:50 birch:50");
	public static final ResourceKey<Biome> ARGAN_WOODLAND = add("argan_woodland", "acacia:70 oak:30");
	public static final ResourceKey<Biome> KWONGAN_HEATH = add("kwongan_heath", "oak:50 birch:30 acacia:20");
	public static final ResourceKey<Biome> MOJAVE_SCRUB = add("mojave_scrub", "acacia:60 spruce:25 oak:15");
	public static final ResourceKey<Biome> SALTBUSH_SHRUBLAND = add("saltbush_shrubland", "acacia:70 oak:30");
	public static final ResourceKey<Biome> ATLANTIC_FOREST = add("atlantic_forest", "jungle:80 oak:20");
	public static final ResourceKey<Biome> TEPUI = add("tepui", "jungle:60 oak:40");
	public static final ResourceKey<Biome> HEATH_FOREST = add("heath_forest", "jungle:70 spruce:30");
	public static final ResourceKey<Biome> WET_TUNDRA = add("wet_tundra", "birch:70 spruce:30");
	public static final ResourceKey<Biome> BRIGALOW_SCRUB = add("brigalow_scrub", "acacia:80 oak:20");
	public static final ResourceKey<Biome> TROPICAL_ALPINE_GRASSLAND = add("tropical_alpine_grassland", "spruce:50 oak:30 birch:20");
	public static final ResourceKey<Biome> SAWGRASS_MARSH = add("sawgrass_marsh", "swamp:60 spruce:40");
	public static final ResourceKey<Biome> PAPYRUS_SWAMP = add("papyrus_swamp", "swamp:70 jungle:30");
	public static final ResourceKey<Biome> WET_MEADOW = add("wet_meadow", "birch:50 oak:50");
	public static final ResourceKey<Biome> PALOUSE_PRAIRIE = add("palouse_prairie", "oak:40 spruce:40 birch:20");
	public static final ResourceKey<Biome> DARK_TAIGA = add("dark_taiga", "spruce:95 birch:5");

	static {
		SOUTH.put(TEMPERATE_RAINFOREST, Mix.of("spruce:35 dark_oak:35 oak:30"));
	}

	/** Every Earth biome; the biome source declares them up front, and tools/biomes writes one for each. */
	public static final List<ResourceKey<Biome>> ALL = List.copyOf(MIXES.keySet());

	/** Cloud forest from this height on tropical mountains, rainforest below. */
	public static final double CLOUD_FOREST_METERS = 1500;
	/** The montane belt begins at this share of the treeline's height. */
	private static final double MONTANE = 0.4;
	/** Wetland this low on a temperate coast is salt marsh. */
	private static final double SALT_MARSH_METERS = 3;
	/** Grassland in Atlantic Europe's moorland ecoregions is moor from this height; lower it is fields and pasture. */
	private static final double MOOR_METERS = 250;
	/** Tundra this near the poles is polar desert, as is bare tundra anywhere. */
	private static final double POLAR_DESERT_LATITUDE = 74;
	/** The subalpine belt, of krummholz, stone pine, larch and fir below the treeline, begins at this share of its height. */
	private static final double SUBALPINE = 0.75;
	/** Wetland lower than this on the US South's coast is salt marsh; above, cypress swamp. */
	private static final double CYPRESS_METERS = 1;

	/** Vanilla's biomes that stand for natural land: forest, scrub, grass, tundra, wetland, grass above the treeline. */
	private static final Set<ResourceKey<Biome>> NATURAL = Set.of(Biomes.FOREST, Biomes.FLOWER_FOREST, Biomes.BIRCH_FOREST,
		Biomes.DARK_FOREST, Biomes.TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA, Biomes.SNOWY_TAIGA, Biomes.JUNGLE, Biomes.BAMBOO_JUNGLE,
		Biomes.SPARSE_JUNGLE, Biomes.SAVANNA, Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.SNOWY_PLAINS, Biomes.SWAMP, Biomes.MEADOW);

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
		if (region == null) {
			return base;
		}
		String sub = region.sub();
		int biome = region.biome();
		// Grass above the treeline is the high grassland's or the tundra's where those are the ecoregion (the paramo, the
		// puna, Tibet's steppe); elsewhere, as in the Alps, vanilla's alpine meadow.
		if (base == Biomes.MEADOW && biome != Ecoregions.MONTANE_GRASSLAND && biome != Ecoregions.TUNDRA) {
			return base;
		}
		// Wetlands: a bog in the cold north and on Atlantic moors, swamp forest in the tropics' swamp ecoregions, salt marsh on
		// a low temperate coast and in the salt flats' ecoregions, the flooded grasslands' own; else vanilla's swamp.
		if (base == Biomes.SWAMP) {
			if (biome == Ecoregions.FLOODED_GRASSLAND) {
				return flooded(sub);
			}
			if (biome == Ecoregions.BOREAL_FOREST || biome == Ecoregions.TUNDRA || Set.of("hemiboreal", "moorland", "larch").contains(sub)) {
				return PEAT_BOG;
			}
			if (sub.equals("swamp")) {
				return SWAMP_FOREST;
			}
			// The US South's swamps of bald cypress and tupelo, the Atchafalaya's and the Okefenokee's.
			if (region.realm().equals("Nearctic") && Koppen.code(climate).equals("Cfa") && meters >= CYPRESS_METERS) {
				return CYPRESS_SWAMP;
			}
			return meters >= 0 && meters < SALT_MARSH_METERS && !Koppen.tropical(climate) ? SALT_MARSH : base;
		}
		boolean open = cover == WorldCover.GRASS || cover == WorldCover.SHRUBS || cover == WorldCover.MOSS;
		// Moor is the open upland of its ecoregions (heather, bog, rough grazing), and all their open land in the Faroes and
		// Iceland; not the grass of the British Isles' lowland fields.
		boolean moor = sub.equals("moorland") && open && (cover != WorldCover.GRASS || meters >= MOOR_METERS
			|| biome != Ecoregions.TEMPERATE_BROADLEAF_FOREST && biome != Ecoregions.TEMPERATE_CONIFER_FOREST);
		// Heath: the shrubland of Atlantic Europe's lowlands, heather on sand -- the Luneburg Heath, Dorset, Jutland, the Kempen.
		boolean heath = !moor && cover == WorldCover.SHRUBS && region.realm().equals("Palearctic")
			&& (Koppen.code(climate).equals("Cfb") || Koppen.code(climate).equals("Cfc"));
		boolean temperateForest = biome == Ecoregions.TEMPERATE_BROADLEAF_FOREST || biome == Ecoregions.TEMPERATE_CONIFER_FOREST;
		double treeline = temperateForest ? BiomePicker.treelineMeters(latitude, longitude) : 0;
		boolean subalpine = temperateForest && meters >= SUBALPINE * treeline;
		boolean montane = temperateForest && meters >= MONTANE * treeline;
		return switch (biome) {
			// Subtropical moist forest where the climate is temperate (southern China, Brazil's Araucaria highlands) is laurel forest.
			case Ecoregions.TROPICAL_MOIST_FOREST -> switch (sub) {
				case "swamp" -> SWAMP_FOREST;
				case "tepui" -> TEPUI;
				case "heath_forest" -> HEATH_FOREST;
				default -> meters >= CLOUD_FOREST_METERS ? CLOUD_FOREST : sub.equals("monsoon") ? MONSOON_FOREST
					: sub.equals("atlantic") ? ATLANTIC_FOREST : Koppen.temperate(climate) ? LAUREL_FOREST : TROPICAL_RAINFOREST;
			};
			case Ecoregions.TROPICAL_DRY_FOREST -> sub.equals("thorn") ? THORN_SCRUB : sub.equals("monsoon") ? MONSOON_FOREST : TROPICAL_DRY_FOREST;
			case Ecoregions.TROPICAL_CONIFER_FOREST -> TROPICAL_PINE_FOREST;
			case Ecoregions.TEMPERATE_BROADLEAF_FOREST -> switch (sub) {
				case "rainforest" -> subalpine ? SUBALPINE_FOREST : TEMPERATE_RAINFOREST;
				case "eucalypt" -> EUCALYPT_FOREST;
				case "hemiboreal" -> HEMIBOREAL_FOREST;
				case "forest_steppe" -> FOREST_STEPPE;
				case "oak_savanna" -> OAK_SAVANNA;
				case "oak_woodland" -> OAK_WOODLAND;
				case "himalayan_broadleaf" -> subalpine ? SUBALPINE_FOREST : HIMALAYAN_BROADLEAF_FOREST;
				// Beechwoods below the montane belt; broadleaf forest gives way to mixed in it, as beech to beech, fir and
				// spruce in the Black Forest's heights, and to the subalpine forest below the treeline.
				default -> moor ? MOORLAND : heath ? HEATHLAND : subalpine ? SUBALPINE_FOREST
					: sub.equals("beech") && !montane ? BEECH_FOREST
					: Koppen.continental(climate) || montane ? TEMPERATE_MIXED_FOREST
					: sub.equals("laurel") ? LAUREL_FOREST : TEMPERATE_DECIDUOUS_FOREST;
			};
			case Ecoregions.TEMPERATE_CONIFER_FOREST -> switch (sub) {
				case "redwood" -> REDWOOD_FOREST;
				case "larch" -> LARCH_TAIGA;
				case "pine_savanna" -> PINE_SAVANNA;
				case "eucalypt" -> EUCALYPT_FOREST;
				case "rainforest" -> subalpine ? SUBALPINE_FOREST : TEMPERATE_RAINFOREST;
				case "med_conifer" -> subalpine ? SUBALPINE_FOREST : MEDITERRANEAN_CONIFER_FOREST;
				case "ponderosa" -> subalpine ? SUBALPINE_FOREST : PONDEROSA_PINE_FOREST;
				case "dark_taiga" -> subalpine ? SUBALPINE_FOREST : DARK_TAIGA;
				default -> moor ? MOORLAND : heath ? HEATHLAND : subalpine ? SUBALPINE_FOREST : TEMPERATE_CONIFER_FOREST;
			};
			case Ecoregions.BOREAL_FOREST -> sub.equals("larch") ? LARCH_TAIGA : sub.equals("mountain_birch") ? MOUNTAIN_BIRCH_FOREST
				: sub.equals("dark_taiga") ? DARK_TAIGA : moor ? MOORLAND : BOREAL_FOREST;
			case Ecoregions.TROPICAL_GRASSLAND -> switch (sub) {
				case "eucalypt" -> EUCALYPT_SAVANNA;
				case "miombo" -> MIOMBO_WOODLAND;
				case "cerrado" -> CERRADO;
				case "thorn" -> THORN_SCRUB;
				case "pine_savanna" -> PINE_SAVANNA;
				case "llanos" -> LLANOS;
				case "sahel" -> SAHEL;
				case "forest_savanna" -> FOREST_SAVANNA_MOSAIC;
				case "mopane" -> MOPANE_WOODLAND;
				case "bushveld" -> BUSHVELD;
				case "kalahari" -> KALAHARI_SAVANNA;
				case "pampas" -> PAMPAS;
				case "highveld" -> HIGHVELD_GRASSLAND;
				case "mitchell" -> MITCHELL_GRASS_DOWNS;
				case "terai" -> TERAI_GRASSLAND;
				case "brigalow" -> BRIGALOW_SCRUB;
				default -> TROPICAL_SAVANNA;
			};
			case Ecoregions.TEMPERATE_GRASSLAND -> switch (sub) {
				case "eucalypt" -> EUCALYPT_SAVANNA;
				case "mulga" -> MULGA_WOODLAND;
				case "forest_steppe" -> FOREST_STEPPE;
				case "oak_savanna" -> OAK_SAVANNA;
				case "prairie" -> TALLGRASS_PRAIRIE;
				case "shortgrass" -> SHORTGRASS_PRAIRIE;
				case "mixed_grass" -> MIXED_GRASS_PRAIRIE;
				case "pine_savanna" -> PINE_SAVANNA;
				case "pampas" -> PAMPAS;
				case "patagonian" -> PATAGONIAN_STEPPE;
				case "tussock" -> TUSSOCK_GRASSLAND;
				case "desert_steppe" -> DESERT_STEPPE;
				case "xeric_woodland" -> XERIC_WOODLAND;
				case "monte" -> MONTE_SCRUB;
				case "palouse" -> PALOUSE_PRAIRIE;
				default -> moor ? MOORLAND : TEMPERATE_STEPPE;
			};
			case Ecoregions.FLOODED_GRASSLAND -> flooded(sub);
			case Ecoregions.MONTANE_GRASSLAND -> switch (sub) {
				case "paramo" -> PARAMO;
				case "puna" -> PUNA;
				case "alpine_tundra" -> ALPINE_TUNDRA;
				case "alpine_shrub" -> ALPINE_SHRUBLAND;
				case "highveld" -> HIGHVELD_GRASSLAND;
				case "tussock" -> TUSSOCK_GRASSLAND;
				case "patagonian" -> PATAGONIAN_STEPPE;
				case "xeric_woodland" -> XERIC_WOODLAND;
				case "monte" -> MONTE_SCRUB;
				case "alpine_desert" -> ALPINE_DESERT;
				case "afromontane" -> AFROMONTANE_GRASSLAND;
				case "snow_gum" -> SNOW_GUM_WOODLAND;
				case "tropical_alpine" -> TROPICAL_ALPINE_GRASSLAND;
				default -> MONTANE_GRASSLAND;
			};
			// Tundra with trees is forest-tundra: the stunted spruce, larch and birch at the edge of the taiga.
			case Ecoregions.TUNDRA -> sub.equals("polar") || Math.abs(latitude) >= POLAR_DESERT_LATITUDE || cover == WorldCover.BARE
				? POLAR_DESERT : sub.equals("mountain_birch") ? MOUNTAIN_BIRCH_FOREST : sub.equals("alpine_tundra") ? ALPINE_TUNDRA
				: sub.equals("tussock") ? TUSSOCK_GRASSLAND : cover == WorldCover.TREES ? FOREST_TUNDRA
				: sub.equals("wet_tundra") ? WET_TUNDRA : TUNDRA;
			// Open land in the Mediterranean is scrub: maquis, garrigue, matorral; California's chaparral, the Cape's fynbos,
			// southwest Iberia's dehesa.
			case Ecoregions.MEDITERRANEAN -> switch (sub) {
				case "fynbos" -> FYNBOS;
				case "mallee" -> MALLEE;
				case "eucalypt" -> EUCALYPT_FOREST;
				case "succulent" -> SUCCULENT_THICKET;
				case "dehesa" -> open ? DEHESA : MEDITERRANEAN_WOODLAND;
				// California's woodland among the chaparral is oak woodland: blue oak, valley oak, coast live oak.
				case "chaparral" -> open ? CHAPARRAL : OAK_WOODLAND;
				case "argan" -> ARGAN_WOODLAND;
				case "kwongan" -> open ? KWONGAN_HEATH : EUCALYPT_FOREST;
				case "med_conifer" -> open ? MEDITERRANEAN_SCRUB : MEDITERRANEAN_CONIFER_FOREST;
				default -> open ? MEDITERRANEAN_SCRUB : MEDITERRANEAN_WOODLAND;
			};
			case Ecoregions.DESERT -> switch (sub) {
				case "cactus" -> CACTUS_SCRUB;
				case "sagebrush" -> SAGEBRUSH_STEPPE;
				case "karoo" -> KAROO;
				case "spinifex" -> SPINIFEX_GRASSLAND;
				case "mulga" -> MULGA_WOODLAND;
				case "thorn" -> THORN_SCRUB;
				case "desert_steppe" -> DESERT_STEPPE;
				case "succulent" -> SUCCULENT_THICKET;
				case "xeric_woodland" -> XERIC_WOODLAND;
				case "kalahari" -> KALAHARI_SAVANNA;
				case "mojave" -> MOJAVE_SCRUB;
				case "saltbush" -> SALTBUSH_SHRUBLAND;
				default -> XERIC_SHRUBLAND;
			};
			default -> base; // mangroves (the land cover has its own), rock and ice
		};
	}

	/** A flooded grassland's ecoregion's own: salt flats, the Everglades' sawgrass, the Sudd's papyrus, East Asia's wet meadows. */
	private static ResourceKey<Biome> flooded(String sub) {
		return switch (sub) {
			case "salt" -> SALT_MARSH;
			case "sawgrass" -> SAWGRASS_MARSH;
			case "papyrus" -> PAPYRUS_SWAMP;
			case "wet_meadow" -> WET_MEADOW;
			default -> FLOODED_GRASSLAND;
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
