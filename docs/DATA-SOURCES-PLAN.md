# More real data: a plan

How to use the sources in `GAIA-3D-DATA-HANDOFF.docx` (researched 3 November 2026) to make the world closer to real
life. It builds on `ACCURACY-PLAN.md`: same ground rules (real numbers tested in `SelfTest`, one source of truth for
scale, checked in the game, licence first) and the same `data/` pipeline. Where the two overlap, the item says so.

## What decides the plan

**The block is the limit.** Worlds run at about 0.5 to 1 block a metre: the self-test forests measure 0.52
(Kakadu) to 1.00 (Tyresta). So 1 m data is already at or past block resolution. Data finer than that (0.25 m
LiDAR, LoD3 façades, façade textures) adds almost nothing. LoD2 roofs (roof planes, no windows) are exactly the
right level. Resample everything to the world's blocks a metre with the existing projection, never finer.

**The mod streams tiles; most of these sources don't.** The baseline (OpenFreeMap, Overture, Mapterhorn,
Terrarium, WorldCover) comes as web tiles the mod fetches as players move. Most of the new sources are bulk
downloads: CityJSON, CityGML, LAZ point clouds, GeoPackages, national shapefiles, often gigabytes for one country,
in national coordinate systems. They can't be read live in a chunk generator. They have to be converted once,
offline, into tiles the mod can stream like the others.

**So the core of the plan is one overlay format and one converter, not forty integrations.** Every regional
source becomes the same thing: a few layers in a Gaia overlay tile. The mod learns to read that one format; each new
source is only a converter script and a manifest entry.

**Licence rules that shape what ships.**

- The mod is MIT and may be monetised downstream, so anything NonCommercial is out: Global Building Atlas LoD1
  and heights, Bogotá's citywide surface raster, most Open Heritage 3D scans, Google Photorealistic 3D Tiles, and
  hosted Cesium content.
- ODbL (OSM, Overture buildings, Global Building Atlas footprints, the World Bank grid): a derived database must
  stay ODbL. Keep ODbL-derived layers in separate overlay files from CC BY / CC0 ones, so the open layers stay
  free of share-alike and each tile carries only the licences of what is in it.
- CC BY-SA (Bogotá downtown models) is share-alike too: keep it in its own file or skip it.
- Fee-based or order-only data (Taiwan national 3D, Stockholm LoD2, Victoria's 3D collection) waits for terms.

## The overlay: format, converter, hosting

### Format

One PMTiles archive per source release and layer group, read with the existing `data/PmTiles.java`, fetched
through `Http`, `DiskCache` and `TileStore`, so it shows in `/earth perf` and `/earth cache` and works with
`/earth download`. Tiles are at zoom 14 or 15 (about 2.4 or 1.2 km), the same grid as the OSM tiles.

Rasters wherever the source is really a surface. One representation then covers LoD2 models, LiDAR and
estimates alike, and it is the cheapest thing for a chunk generator to read:

| Layer | Kind | What it holds | Filled from |
|---|---|---|---|
| `ground` | raster, 1 m, int16 cm | bare earth, metres above the world's datum | AHN, 3DEP, IGN LiDAR HD, PNOA, DGT, ELVIS, LINZ, CanElevation, England EA, INEGI |
| `roof` | raster, 1 m, int16 cm + uint32 building id | top surface of each building | 3DBAG, LoD2 CityGML, PLATEAU, LiDAR DSM minus ground |
| `buildings` | vector | stable id, footprint, base, height, roof shape, use, floors, source, measured or estimated | Overture/OSM, LoD2 ground surfaces, Open Buildings, CBF, EUBUCCO |
| `surface` | vector | pavement, carriageway, kerb, footpath, steps, plaza polygons, with a level | BGT, Boston, NYC, HK, Singapore, TUM Manhattan |
| `structures` | vector | bridge decks (top height, clearance), tunnels, crossings, dams, runway ends with elevations | Prague bridges, PLATEAU, swissTLM3D, bridge inventories, OurAirports, Global Dam Watch |
| `trees` | vector points | position, species, trunk diameter, height | NYC, Vancouver tree inventories; canopy height peaks |
| `bed` | raster | river and sea floor, metres | Potomac, NOAA topobathy, EMODnet, AusSeabed |

Every feature keeps its source id, source name and release, and "measured" or "estimated", so the generator can
prefer a measurement over an estimate and `/earth credits` can say where a tile came from.

### Converter

`tools/overlay/`, Python like `tools/trees`, `tools/snowline` and `tools/treedensity`. Not part of the Gradle
build. One script per source reads the bulk download, converts coordinates and height datum offline (PROJ via
`pyproj`; EPSG:28992 + NAP for the Netherlands, LUREF for Luxembourg, NAD83 + NAVD88 for 3DEP and so on). It then
matches to the baseline by id or footprint overlap and writes overlay tiles. A shared core does the tiling, raster
encoding, PMTiles writing and manifest.

`tools/overlay/sources.tsv` is the manifest, like `tools/trees/sources.tsv`: region, release, URL, licence quoted,
attribution, horizontal and vertical reference, checksum of the input, status (`ok`, `ask`, `no`). The converter
refuses `no` and `ask`, just as the tree converter does.

### Hosting (a decision for you)

The converted tiles need a static host the mod can fetch from: Cloudflare R2 (no egress fees), a GitHub release,
or any HTTP server with range requests. A Netherlands overlay is perhaps 2 to 5 GB; Luxembourg tens of MB. The
mod falls back to today's generation wherever no overlay tile exists, so hosting can grow one region at a time.

## Phases

Each phase is worth doing even if the later ones never happen. Within a phase, items are in order of value for
effort.

### Phase 0: free wins with data already fetched

1. **Overture building heights for whole buildings.** Built: OpenFreeMap fills a missing `render_height` with
   5 m, so the old "no height" fallback never ran and Overture's height was used only for raised parts. A
   building whose OpenFreeMap height is that 5 m stand-in now takes its Overture building's height where
   Overture has one (`BuildingPlan.heightMeters`). About one building in ten in central Stockholm tiles has
   the stand-in.
2. **Road widths from tags: not possible with the current tiles.** OpenFreeMap's transportation layer carries
   no `width`, `lanes` or `sidewalk`, only `surface`, and Overture's road segments carry no width either.
   Widths need raw OSM extracts (Geofabrik) or the national networks in phase 4.
3. **OSM tree points.** Built: single trees mapped in OSM (`natural=tree`), read from Overture's base theme
   (`base.pmtiles`, zoom 13, the building detail's release, `OsmTrees`), grow as real species from
   `species.tsv` where the land cover shows no forest: street trees, park trees. Needleleaved ones grow as
   conifers, broadleaved as broadleaf. Round Les Halles in Paris, 76 of 145 mapped trees grow; the rest stand
   in forest cover (which keeps its own trees), on roads or buildings, or on paving and gravel, which trees
   don't take yet. Tiles are 0.1 to 4 MB (dense cities like Paris are the large ones), fetched while the
   chunk is built, as building detail is.

### Phase 1: the overlay, proven on one block in Delft

Build the format, the converter core and the mod's reader with the smallest real case, as the handoff proposes:
one Delft canal block with a bridge.

- 3DBAG CityJSON (CC BY 4.0): LoD2.2 roofs into `roof` and `buildings`.
- BGT (CC0): road, pavement, footpath and steps polygons into `surface`.
- AHN (check the release's terms; AHN 4 and 5 are CC0): bare earth into `ground`.

The mod reads the overlay and:

- builds roofs from the `roof` raster where present, instead of the procedural roof;
- uses the overlay height for walls;
- draws pavement and carriageway edges from `surface`;
- raises the bridge deck to its measured height.

Checks:

- roof heights within a block of 3DBAG at a set of points;
- footprint alignment;
- pavement edges against BGT;
- bridge clearance;
- no seam where the overlay tile meets an uncovered one;
- chunk timing with and without a warm cache.

Done when a side-by-side of today's Delft and overlay Delft, in game, shows the difference.

### Phase 2: buildings, country by country

Same reader, more converters. In order of what most improves the most players for least work:

1. **Netherlands, all of it:** 3DBAG + BGT + AHN, once Delft works.
2. **Luxembourg:** nationwide LoD2.2 CityGML, CC0. The country-scale test: stable ids across tiles, packaging,
   size.
3. **LoD2 elsewhere in Europe**, each with its terms recorded separately:
   - Bavaria and NRW state LoD2
   - swissBUILDINGS3D
   - Helsinki (CC BY 4.0)
   - Riga (CC BY 4.0)
   - Prague
   - Poland's shaped roofs where available, national LoD1 elsewhere
   - Hamburg (roof overhangs and rooftop details; skip its textures)
   - Vienna's roof product
4. **Japan, PLATEAU:** buildings, roads and bridges for the cities whose release terms allow conversion.
5. **North America:** Boston (public domain), New York (2014 model; trust only where it still matches current
   footprints), Montréal, Calgary.
6. **Heights from LiDAR where no model exists:** roof height = surface model minus bare earth, inside each
   footprint, taking the median of the top returns. England EA 1 m DSM, AHN, 3DEP, IGN LiDAR HD, PNOA, DGT,
   LINZ, Vancouver, GeoSampa and Montevideo.
7. **The empty half of the world** (ACCURACY-PLAN 4.1): where OSM has no buildings, Google Open Buildings 2.5D
   (CC BY 4.0 or ODbL) for Africa, South and Southeast Asia and Latin America, China CBF footprints with CNBH
   heights (CC BY 4.0), Microsoft footprints (CDLA Permissive 2.0) elsewhere. Check Overture's provenance first:
   it already contains Microsoft data. Marked "estimated": a mapped OSM or Overture building always wins.
8. **EUBUCCO** for Europe's missing heights, floors, use and construction year, keeping measured and estimated
   apart.

Priority per building, first that exists:

1. a current local 3D model
2. a LiDAR reconstruction
3. a mapped OSM or Overture height
4. an ML estimate
5. today's class-based default

A newer verified footprint beats an older model of a building since replaced.

### Phase 3: the ground and the water

Overlaps ACCURACY-PLAN phase 2. The overlay's `ground` layer is how phase 2.2 (national LiDAR) gets built.

1. **Copernicus GLO-30** under Mapterhorn and Terrarium (ACCURACY-PLAN 2.1).
2. **National bare earth** into `ground`, blended into the coarser data with a feathered edge:
   - Lantmäteriet first (the start area's flat city patch and humps under bridges)
   - then AHN, 3DEP, IGN, PNOA, DGT, England EA, ELVIS, LINZ, CanElevation and INEGI
   - OpenTopography to find more
3. **Riverbeds and harbours** into `bed`: the Potomac pilot first (CC0, 1 m, above and below water), then NOAA
   coastal topobathy, EMODnet and AusSeabed. Keep Gaia's own water-surface fit; only the bed changes. Ties to
   `DYNAMIC-WATERWAYS-PLAN.md`.
4. **Dams and reservoirs** from Global Dam Watch (CC BY 4.0): a dam wall where a mapped waterway crosses a dam
   point, with the reservoir level behind it.

### Phase 4: streets, bridges and stations

1. **Pavement polygons** into `surface`:
   - Boston ground plan
   - NYC roadbeds and sidewalks
   - Hong Kong pavements
   - Singapore kerbs and lane markings
   - TUM Manhattan (plan only; heights from `ground`)
   - Paris kerbs
   - Brisbane paths and bikeways
2. **Road widths** from national networks where documented: Sweden's NVDB first (the start area), then BD TOPO,
   ZABAGED, Vicmap and LINZ. Use them for widths and checks only: OSM stays the alignment, so roads are never
   drawn twice.
3. **Bridges and tunnels:**
   - Measured decks from Prague bridges, PLATEAU and swissTLM3D axes.
   - Clearances from the US bridge and tunnel inventories, Queensland and Brisbane records, and Vicmap culverts,
     as constraints on deck height.
   - Classified bridge-deck LiDAR returns where surveyed.
   - These feed the existing `Bridges.java` and `Tunnels.java`.
4. **Rail crossings and stations:**
   - Canadian grade crossings matched to road and track, generated once.
   - Station entrances from NSW, Singapore MRT exits and the Hong Kong pedestrian network, for stacked routes.
5. **Airports, ports, power:**
   - OurAirports runway ends with elevations (public domain), so runways lie at their real grade.
   - World Port Index for port identity.
   - WRI power plants for facility type.
   - World Bank grid lines, filtered to existing assets, never planned ones.

### Phase 5: trees and street objects

Builds directly on the real-tree system.

1. **Canopy height** (Meta and WRI, CC BY 4.0, 1 m): tree height and where trees stand, in place of the species'
   height range, wherever the canopy is mapped. Peaks in the canopy become trunk positions; the species still
   comes from `species.tsv`. ACCURACY-PLAN 3.1.
2. **Street-tree inventories:**
   - NYC census: species, trunk diameter, kerb position.
   - Vancouver.
   - A small table maps their species names to `species.tsv` keys, and the generator or a pack supplies any
     species missing.
3. **Street furniture:** Paris benches, lights, bins and fountains placed as existing props; OSM points
   elsewhere.

### Phase 6: appearance (last)

Roof and pavement colours from imagery: US NAIP (public domain) and LINZ aerial imagery, classified into a block
palette. Worth it only once roofs are real shapes; until then colour on a wrong shape looks worse, not better.

## Not used, and why

- **Global Building Atlas LoD1 and height maps:** CC BY-NC 4.0. Its ODbL footprint subset is usable.
- **Bogotá's citywide surface raster:** CC BY-NC-SA. The downtown models are CC BY-SA, so keep them separate
  or skip them.
- **Google Photorealistic 3D Tiles and hosted Cesium content:** their terms forbid extraction and offline use.
  CesiumJS itself is fine for previewing our own converted data.
- **Open Heritage 3D:** only per-asset, where the asset's own terms allow commercial reuse.
- **Taiwan national 3D, Stockholm LoD2 and Victoria's 3D collection:** fee-based or order-only. Leads to follow
  up, not defaults.
- **LoD3 façades and façade textures** (TUM2TWIN façades, Hamburg textures): finer than a block, so little to
  gain for the work.
- **Unconfirmed leads** (Indonesia RBI, Andhra Pradesh, Chile GEOMOP, Kenya, Afghanistan, Kazakhstan, Georgia,
  Armenia, Vanuatu, NunaGIS bulk): not until a real export and its terms are checked.

## Suggested order

1. **Phase 0.** Items 1 and 3 built; item 2 needs data the tiles don't carry.
2. **Phase 1 (Delft).** It decides the overlay format, so nothing in phases 2 to 5 is built before it works.
3. **Lantmäteriet bare earth (phase 3.2)** through the new `ground` layer. It fixes the start area's two worst
   known issues, and is ACCURACY-PLAN's own next step.
4. **The Netherlands and Luxembourg (phase 2.1, 2.2),** then canopy height (phase 5.1). The canopy layer is
   worldwide and lifts every forest the tree system now grows.
5. **Open Buildings 2.5D and China CBF (phase 2.7)** for the half of the world with no buildings today.
6. Then by where players actually go: more LoD2 countries, pavements, bridges.

## Decisions needed before phase 1

- **Hosting** for overlay tiles: where, and who pays for it.
- **Shipping:** confirm overlay tiles may be served to players under each source's attribution, with ODbL
  layers kept in their own files.
- **One world snapshot:** pin each source's release per world, so a world doesn't change under players when
  a source updates.
