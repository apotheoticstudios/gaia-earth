# More Earth biomes: a plan

Earth's natural land takes the biome of its real ecoregion (RESOLVE Ecoregions 2017, 846 ecoregions in 14 biomes), or
a sub-biome: the landscape its ecoregion is known for, or one decided by place (height, climate, land cover, the map).
114 biomes as of 2026-11-05 (commit 98aed41). 447 ecoregions still have no landscape of their own; this plan lists the
next twenty, split into two halves that can be done apart.

## How a sub-biome is added

1. **By ecoregion:** add its ECO_IDs to `SUBS` in `tools/ecoregions/build_ecoregions.py` (one sub name each, a one-line
   comment saying what the landscape is), then rebuild `ecoregions.tsv` (the grid itself doesn't change):
   `cd tools/ecoregions && python3 build_ecoregions.py data/Ecoregions2017.shp ../../src/main/resources/realearth/`
   (the shapefile download is in `tools/ecoregions/README.md`). `ecoregions.tsv` has every ecoregion's ID, biome, sub,
   realm and name.
2. **The biome itself:** add `(name, vanilla parent, grass colour, foliage colour, display name)` to `BIOMES` in
   `tools/biomes/build_biomes.py` (and `WATER` for water colour), then run
   `python3 tools/biomes/build_biomes.py ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common/1.21.1-*/minecraft-common-1.21.1-*-v2.jar src/main/resources`.
   It writes the biome JSON, its biome tags (the parent's, but no structure tags: Earth generates no structures but its
   own) and its name.
3. **Where it goes and what grows:** in `world/EarthBiomes.java`, `add(name, mix)` (tree kinds and shares: spruce, oak,
   birch, dark_oak, acacia, jungle, swamp, mangrove; each picks species from `species.tsv` rows whose `replaces` has
   it), and send the sub to it in `of()`, under its RESOLVE biome's `case`. Rules by place go before the switch.
4. **Test:** a `new Place(...)` in the "Earth's biomes" self-test for each, at a point at least 0.2 degrees inside its
   ecoregion (the lookup wobbles about a cell, 5.5 km), with the vanilla base biome `BiomePicker.pick` gives there
   (MEADOW above the treeline, SWAMP for wetland, SAVANNA for tropical and hot shrubland, PLAINS for other grass and
   shrubs, TAIGA for trees in arid climates). Run `-Ponly="Earth's biomes,Real trees"` in a scratch worktree (see the
   self-test isolation notes: copy in the git-ignored tree models, `src/main/resources/data/gaia-earth/structure/tree/`,
   or every tree comes out vanilla). Everything must pass.

Things to know:

- Only natural land changes (`NATURAL` in `EarthBiomes`): towns, fields, water, snow, ice and rock keep vanilla's
  biome. Grass above the treeline (base MEADOW) maps only in montane-grassland and tundra ecoregions.
- The map's beaches and dunes come first; `land` is 0 under water.
- An ecoregion under about ten grid cells hardly shows; leave it out (the Serengeti's plains are five).
- Both halves edit the same files (`build_ecoregions.py`, `build_biomes.py`, `EarthBiomes.java`, `SelfTest.java`) and
  regenerate shared ones (`ecoregions.tsv`, the biome tags, `en_us.json`). Half A has landed on `main`;
  start Half B from it (`git pull`), and rerun both tools before committing.

## Half A (done 2026-11-06: 123 biomes)

| # | Biome | Where | How |
|---|---|---|---|
| A1 | Sudanian Savanna | West and East Sudanian savanna (62, 43): the shea and nere parkland south of the Sahel | ecoregion |
| A2 | Dry Dipterocarp Forest | Central Indochina, Irrawaddy and southern Vietnam dry forests (291, 294, 300) | ecoregion |
| A3 | Baobab Dry Forest | Madagascar dry deciduous forests (32) | ecoregion |
| A4 | Baikiaea Woodland | Zambezian Baikiaea woodlands (64): Zambezi teak on Kalahari sand | ecoregion |
| A5 | Alpine Steppe | Central Tibetan Plateau and Karakoram-West Tibetan alpine steppe, Yarlung Zanbo arid steppe (750, 754, 770) | ecoregion |
| A6 | Alpine Meadow | Central Asia's high meadows: Ghorat-Hazarajat, Hindu Kush, Khangai, Qilian, Sulaiman, Tian Shan (752, 753, 755, 763, 766, 767) | ecoregion |
| A7 | Oasis | Trees in hot deserts (desert ecoregions, BWh climate): date palm groves | place |
| A8 | Reed Marsh | Wetland in temperate and continental climates that no other rule claims (was vanilla's swamp) | place |
| A9 | (cloud forest lower) | Tropical moist forest ecoregions named "montane": cloud forest from 1,000 m, not 1,500 | place |
| A10 | Floodplain Forest | Woods along rivers and lakes (riparian) in temperate broadleaf ecoregions below 300 m: willow, poplar, ash, elm; not the hemiboreal's lakeshores, Australia's eucalypts or the rainforests | place |

## Half B (done 2026-11-06: 148 biomes, with round 2)

| # | Biome | Where | How |
|---|---|---|---|
| B1 | Pacific Island Forest | Oceania realm moist and dry forests (618-634, 635, 637, 638) and Norfolk, New Caledonia (147, 146, 164); not Hawaii, nor Lord Howe (142: no grid cells) | ecoregion |
| B2 | Hawaiian Forest, Hawaiian Shrubland | Hawai'i moist and dry forests (623, 636); Hawai'i high and low shrublands, Northwest Hawai'i scrub (639, 640, 641) | ecoregion |
| B3 | Madagascar Rainforest, Ericoid Thicket | Madagascar humid and subhumid forests (17, 18); Madagascar ericoid thickets (83) | ecoregion |
| B4 | Dipterocarp Rainforest | Indomalayan lowland rain forests: Borneo 219, Sumatra 278, Peninsular Malaysia 265, Mentawai 245, western Java 289, Tenasserim 284, Sri Lanka 274, northern Vietnam 260, the Philippines (241, 247, 231, 262, 248, 276), Myanmar's coast 250, the Cardamoms 223 | ecoregion |
| B5 | Maritime Tundra | Aleutian Islands tundra, Russian Bering tundra (406, 779): grass and heath, wet and windy | ecoregion |
| B6 | Feather-Grass Steppe, Mongolian Steppe | Pontic, Kazakh and Kazakh upland steppe (735, 732, 733); Mongolian-Manchurian grassland, Sayan intermontane steppe (734, 736) | ecoregion |
| B7 | Fog Woodland | South Arabian fog woodlands (56, Dhofar's monsoon woodland); and Arabia's juniper highlands (59, 108, 109) into Xeric Woodland | ecoregion |
| B8 | (additions) | Trans Fly savanna (188) as eucalypt; Amsterdam-St Paul and Tristan da Cunha (67, 68) into Tussock; Itigi-Sumbu thicket (46) into Thorn Scrub; Drakensberg escarpment (40) into Bushveld | ecoregion |
| B9 | Dry Evergreen Forest | East Deccan, Southeast Indochina and Sri Lanka dry evergreen forests (293, 299, 301) | ecoregion |
| B10 | Atlantic Oak Forest, Cork Oak Forest | Northwest Iberian montane forests (800); the Maghreb's woodlands and forests (798) where wooded | ecoregion |

## Round 2 (researched 2026-11-06; C1-C11 done the same day, 135 biomes)

By land area, the deserts are the biggest gap: 11.2M km2 of desert ecoregions have no landscape of their own, and
their bare ground still takes vanilla's biome. Tropical moist forest (15.9M km2) and boreal forest (9.5M km2) are
larger, but their trees already differ by species range; splitting them further would need forest-type data.

| # | Biome | Where | How |
|---|---|---|---|
| C1 | Sand Sea, Stony Desert | Hot deserts' bare ground: Sand Sea where the map has sand (OpenFreeMap draws ergs as landcover sand: the Grand Erg Oriental, Rub' al Khali, Erg Chebbi and the Taklimakan were checked; the Hamada du Draa and the Tanezrouft have none), Stony Desert elsewhere. Today `BiomePicker.bare` makes every hot desert desert or, by noise, badlands (red mesas across the Sahara), and `EarthChunkGenerator.column` lays sand on all of it: Stony Desert should be gravel, stone and coarse ground. | place (map sand, cover BARE, arid climate); changes the ground too |
| C2 | (Badlands where real) | Keep vanilla's badlands only in real badlands country: the Colorado Plateau and Wyoming Basin shrublands (429, 438), the Painted Desert; nowhere by noise | ecoregion |
| C3 | Lichen Woodland | Open spruce over lichen: Northern and Eastern Canadian Shield taiga, Northwest Territories taiga, Watson Highlands taiga (379, 374, 381, 383) | ecoregion |
| C4 | Shrub Tundra | Tundra the land cover shows as shrubs: the Low Arctic's dwarf birch and willow | place (tundra ecoregion, cover SHRUBS) |
| C5 | (Polar desert lower) | The Canadian High Arctic tundra (412) is polar semi-desert below 74 degrees too | ecoregion, into Polar Desert |
| C6 | Pinyon-Juniper Woodland | Trees in the sagebrush steppe ecoregions and the Great Basin and Colorado Plateau (429, 430, 434, 438; Mesa Verde) | place (sub sagebrush, cover TREES) |
| C7 | Polylepis Woodland | Trees in the puna and paramo | place (sub puna or paramo, cover TREES) |
| C8 | (fix) | The Tigris-Euphrates marshes (747) are freshwater reed marsh, not salt marsh | ecoregion, from salt to Reed Marsh |
| C9 | Kauri Forest, Araucaria Forest, Campinarana | Northland temperate kauri forests (173); Araucaria moist forests (440); Rio Negro campinarana (498), white-sand forest | ecoregion |
| C10 | Gibber Plain, Buttongrass Moorland | Tirari-Sturt stony desert (215); open land in Tasmania's ecoregions (177, 178, 179) | ecoregion; place (Tasmania, cover GRASS or SHRUBS) |
| C11 | Alpine Meadow everywhere, Fjell | Grass above the treeline in every temperate mountain (the Alps, Rockies, Caucasus still take vanilla's meadow) as Alpine Meadow; open land above Scandinavia's mountain birch belt (780 above the treeline) as Fjell | place (base MEADOW) |
| C12 | Lake | Inland lakes, which take vanilla's river biome today (rivers stay River): the map's water areas say river or lake (`OsmWater.Area.river`) | place; needs the lake/river kind per column |
| C13 | Coral Reef, real sea ice | Shallow warm reefs from the Allen Coral Atlas (its licence is not stated on its pages: confirm before use); frozen ocean where summer sea ice lasts (NSIDC) rather than by latitude | new datasets |

## Not planned

Farmland types (rice paddies, orchards, vineyards) and town parks: fields and towns keep vanilla's biomes by design.
Karst, lava fields and glacier forefields: no open data says where they are at this scale. Splitting the tropical
rainforests by continent (beyond B4's dipterocarps) and the taiga into pine and spruce: trees already differ by
species range, and forest type would need new data.
