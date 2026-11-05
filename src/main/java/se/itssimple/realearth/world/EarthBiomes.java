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
 * Australia's eucalypts), or one by place: cloud forest on tropical mountains, mixed forest where the winters are
 * continental and in the montane belt, Mediterranean scrub on its open land, peat bog in the north's wetlands, salt
 * marsh on low temperate coasts.
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
				return sub.equals("salt") ? SALT_MARSH : FLOODED_GRASSLAND;
			}
			if (biome == Ecoregions.BOREAL_FOREST || biome == Ecoregions.TUNDRA || Set.of("hemiboreal", "moorland", "larch").contains(sub)) {
				return PEAT_BOG;
			}
			if (sub.equals("swamp")) {
				return SWAMP_FOREST;
			}
			return meters >= 0 && meters < SALT_MARSH_METERS && !Koppen.tropical(climate) ? SALT_MARSH : base;
		}
		boolean open = cover == WorldCover.GRASS || cover == WorldCover.SHRUBS || cover == WorldCover.MOSS;
		// Moor is the open upland of its ecoregions (heather, bog, rough grazing), and all their open land in the Faroes and
		// Iceland; not the grass of the British Isles' lowland fields.
		boolean moor = sub.equals("moorland") && open && (cover != WorldCover.GRASS || meters >= MOOR_METERS
			|| biome != Ecoregions.TEMPERATE_BROADLEAF_FOREST && biome != Ecoregions.TEMPERATE_CONIFER_FOREST);
		return switch (biome) {
			// Subtropical moist forest where the climate is temperate (southern China, Brazil's Araucaria highlands) is laurel forest.
			case Ecoregions.TROPICAL_MOIST_FOREST -> sub.equals("swamp") ? SWAMP_FOREST : meters >= CLOUD_FOREST_METERS ? CLOUD_FOREST
				: Koppen.temperate(climate) ? LAUREL_FOREST : TROPICAL_RAINFOREST;
			case Ecoregions.TROPICAL_DRY_FOREST -> sub.equals("thorn") ? THORN_SCRUB : TROPICAL_DRY_FOREST;
			case Ecoregions.TROPICAL_CONIFER_FOREST -> TROPICAL_PINE_FOREST;
			case Ecoregions.TEMPERATE_BROADLEAF_FOREST -> switch (sub) {
				case "rainforest" -> TEMPERATE_RAINFOREST;
				case "eucalypt" -> EUCALYPT_FOREST;
				case "hemiboreal" -> HEMIBOREAL_FOREST;
				case "forest_steppe" -> FOREST_STEPPE;
				case "oak_savanna" -> OAK_SAVANNA;
				// Broadleaf forest gives way to mixed in the montane belt, as beech to beech, fir and spruce in the Black Forest's heights.
				default -> moor ? MOORLAND
					: Koppen.continental(climate) || meters >= MONTANE * BiomePicker.treelineMeters(latitude, longitude) ? TEMPERATE_MIXED_FOREST
					: sub.equals("laurel") ? LAUREL_FOREST : TEMPERATE_DECIDUOUS_FOREST;
			};
			case Ecoregions.TEMPERATE_CONIFER_FOREST -> switch (sub) {
				case "redwood" -> REDWOOD_FOREST;
				case "rainforest" -> TEMPERATE_RAINFOREST;
				case "larch" -> LARCH_TAIGA;
				case "pine_savanna" -> PINE_SAVANNA;
				case "eucalypt" -> EUCALYPT_FOREST;
				default -> moor ? MOORLAND : TEMPERATE_CONIFER_FOREST;
			};
			case Ecoregions.BOREAL_FOREST -> sub.equals("larch") ? LARCH_TAIGA : moor ? MOORLAND : BOREAL_FOREST;
			case Ecoregions.TROPICAL_GRASSLAND -> switch (sub) {
				case "eucalypt" -> EUCALYPT_SAVANNA;
				case "miombo" -> MIOMBO_WOODLAND;
				case "cerrado" -> CERRADO;
				case "thorn" -> THORN_SCRUB;
				case "pine_savanna" -> PINE_SAVANNA;
				default -> TROPICAL_SAVANNA;
			};
			case Ecoregions.TEMPERATE_GRASSLAND -> switch (sub) {
				case "eucalypt" -> EUCALYPT_SAVANNA;
				case "mulga" -> MULGA_WOODLAND;
				case "forest_steppe" -> FOREST_STEPPE;
				case "oak_savanna" -> OAK_SAVANNA;
				case "prairie" -> TALLGRASS_PRAIRIE;
				case "pine_savanna" -> PINE_SAVANNA;
				default -> moor ? MOORLAND : TEMPERATE_STEPPE;
			};
			case Ecoregions.FLOODED_GRASSLAND -> sub.equals("salt") ? SALT_MARSH : FLOODED_GRASSLAND;
			case Ecoregions.MONTANE_GRASSLAND -> sub.equals("paramo") ? PARAMO : sub.equals("puna") ? PUNA : MONTANE_GRASSLAND;
			case Ecoregions.TUNDRA -> sub.equals("polar") || Math.abs(latitude) >= POLAR_DESERT_LATITUDE || cover == WorldCover.BARE
				? POLAR_DESERT : TUNDRA;
			// Open land in the Mediterranean is scrub: maquis, garrigue, chaparral, matorral; the Cape's is fynbos.
			case Ecoregions.MEDITERRANEAN -> sub.equals("fynbos") ? FYNBOS : sub.equals("eucalypt") ? EUCALYPT_FOREST
				: open ? MEDITERRANEAN_SCRUB : MEDITERRANEAN_WOODLAND;
			case Ecoregions.DESERT -> switch (sub) {
				case "cactus" -> CACTUS_SCRUB;
				case "sagebrush" -> SAGEBRUSH_STEPPE;
				case "karoo" -> KAROO;
				case "spinifex" -> SPINIFEX_GRASSLAND;
				case "mulga" -> MULGA_WOODLAND;
				case "thorn" -> THORN_SCRUB;
				default -> XERIC_SHRUBLAND;
			};
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
