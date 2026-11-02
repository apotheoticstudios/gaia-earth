# A more accurate, more detailed Earth: a plan

Phases run in order: each one is worth doing even if the later ones never happen, and earlier phases make later
ones easier to check. Items that are already in `ROADMAP.md` say so. Built items say where.

## Ground rules for every phase

- **Real numbers in, real numbers tested.** Each change carries checks in `SelfTest` (`./gradlew runSelftest`)
  against published reference values, with the source cited next to each number, not just "it runs". Pure
  checks use fixed values, never the network.
- **One source of truth for scale.** Anything that turns blocks into metres or back goes through the existing
  projection and `VerticalScale`, never a new constant.
- **Checked in the game.** Each item lists `/earth tp` spots and what should be seen there. Code that is right
  but looks wrong is not done.
- **Same pipeline for new data.** A new source is fetched tile by tile through `data/` (`Http`, `DiskCache`,
  `TileStore`), shows in `/earth perf` and `/earth cache`, works with `/earth download`, falls back to today's
  behaviour when it is missing, and goes in `/earth credits` and `CREDITS.md`.
- **Licence first.** Before any code, read the source's current terms: attribution, share-alike (ODbL), caching
  and redistribution. Sources whose terms forbid storing or converting the data are not used.

## Phase 1: quick wins with the data already here

1. **Height cooling in real metres** (roadmap #1). Built in apotheoticstudios/realearth#1: snow and ice follow
   a block's real elevation (map scale and the eased heights above 2,800 m undone) and start at the same
   snowline the snowy biomes use; clients get the map's shape on join. Fixes "snow on high forest". Converting
   to metres alone would not have: the old rule cooled from each biome's own vanilla temperature, so taiga
   snowed 320 m up anywhere.
2. **Majority land cover for LOD** (known issue). Distant Horizons columns take the most common WorldCover
   class over the column, not the class at its centre. Test on a saved patch of sparse forest.
3. **Real sun** (roadmap #4). Day length from latitude and today's date with the standard NOAA solar formula.
   Tests against published sunrise and sunset for Stockholm, Kiruna and the equator.
4. **`/earth go <place>`** (roadmap #3). Nominatim, cached, at most one request a second, with a User-Agent;
   the server address configurable so a self-hosted Nominatim or Photon can stand in.
5. **One cache for server and client** (known issue).

## Phase 2: better ground

0. **A snowline from real data.** Built in apotheoticstudios/realearth#3: a 0.1 degree grid from the
   Randolph Glacier Inventory's glacier median heights (CC BY 4.0) and CHELSA V2.1 summer temperature and
   precipitation (CC0), with the summer temperature at which glaciers survive read off the glaciers by
   precipitation. Within ~13 m of glaciers over 10 km2; wet coasts low, dry interiors high.
   (`tools/snowline/` rebuilds it.)
1. **Copernicus GLO-30** as a global 30 m elevation source, blended under the Terrarium tiles where it is better
   (cleaner terrain, mostly free of buildings).
2. **National 1 m LiDAR**, bare earth (roadmap #2): Lantmäteriet (Sweden) first, then USGS 3DEP (USA), then
   others (AHN Netherlands, Swisstopo, IGN France, Denmark, England EA) as wanted. Each is its own format and
   coordinate system behind one interface; blended into the coarser data with a feathered edge so there are
   no seams. Fixes Stockholm's flat city patch and the humps under city bridges.
3. **Seafloor.** Check what the sea is built from today. If it is flat, use GEBCO (global, ~450 m) and EMODnet
   (Europe, ~115 m), with gravel, sand and clay by depth.
4. **Coasts and rivers.** OSM coastline polygons for the exact shore; river beds carved to a depth by width,
   from HydroRIVERS or MERIT Hydro; JRC Global Surface Water to cross-check where water really is.
5. **Glaciers** from the Randolph Glacier Inventory / GLIMS: packed and blue ice where real glaciers are.

## Phase 3: what grows and what lies under it

1. **Real forests** from canopy height: Meta/WRI 1 m where present, ETH 10 m elsewhere. Trees stand where
   the canopy is, as tall as it is; OSM `natural=tree` and `tree_row` for single trees and street trees.
2. **Surface blocks from soil and land use:** SoilGrids (clay, sand, peat) and OSM `natural=*` (scree, bare
   rock, heath, wetland) choose mud, sand, podzol, gravel and stone.
3. **Rock from geology maps** (OneGeology, national surveys): granite, limestone or sandstone in cliffs,
   quarries and caves.
4. **Climate from CHELSA** (CC0, 1 km): real temperature and rainfall for biomes and grass colour on top of
   Köppen, from the same files as the snowline (phase 2.0).

## Phase 4: towns everywhere

1. **Buildings where OSM has none:** Google Open Buildings (Africa, South and Southeast Asia, Latin America,
   with heights in its 2.5D version) and Microsoft's footprints, where OSM has no buildings. Today these
   places get vanilla villages. Check each dataset's licence (CC BY 4.0 or ODbL) first.
2. **Overture places, addresses and roads:** more shops where OSM is thin, house numbers on doors, road widths
   and surfaces.
3. **Road detail:** pavements from `sidewalk=*`, kerbs, crossings, lane markings, traffic signals.
4. **National 3D city models** for real roof shapes and heights: 3D BAG (Netherlands), German LoD2,
   swissBUILDINGS3D, New York.
5. **Buildings that follow their own axis, overhangs and arcades** (roadmap 6.4, 6.5).
6. **Buildings in Distant Horizons' far terrain** (roadmap 6).

## Not used

- **Google Earth, Google Maps and Google's photorealistic 3D tiles.** Their terms, as far as known, forbid
  keeping the data and turning it into something else, and every player would need a paid API key. Re-read
  the terms before ever reconsidering.
- **WorldClim 2.1**: free for academic and non-commercial use only, with no redistribution, so nothing
  derived from it can ship in the mod. CHELSA covers the same ground under CC0.
- **Google Earth Engine** is fine for processing public datasets once, offline, but the same datasets can
  usually be fetched from their own sources, which is simpler.

## Suggested order

Phase 1.1 (cooling) and the snowline from data (2.0) are built. Next Lantmäteriet (2.2),
which fixes the start area's two worst known issues, then Copernicus (2.1) for everywhere else. After that,
`/earth go` (1.4) for players and Open Buildings (4.1) for the empty half of the world.
