# Rebuilding buildings from the data up: a plan

The current building system (`terrain/Buildings.java`, `terrain/BuildingPlan.java`, `terrain/Interior.java`,
`world/BuildingPainter.java`, `world/Facades.java`, about 3,800 lines) is replaced by one that builds every building
from what the data says about it: its real footprint, its real parts, its real heights, its real roof, its real
walls, and storeys where the real ones are. Where a building's data is missing, the gap is filled from what the
data says about buildings like it nearby, never from a fixed guess.

It follows `ACCURACY-PLAN.md`'s ground rules (real numbers tested in `SelfTest` against cited values, one source
of truth for scale, checked in the game, licence first) and plugs into `DATA-SOURCES-PLAN.md`'s overlays.

## Way of working

This is a long project, done to Build The Earth's standard: every building as it really is, at its real size.
Accuracy comes before coverage and before speed.

- **Targets before code.** For each element, what "accurate" means and how it is measured:
  - footprint: within half a block of the source survey;
  - height and eaves: within a block of the measured value;
  - roof: its shape, ridge direction and pitch as measured;
  - wall and roof material and colour as surveyed or photographed;
  - windows where the imagery shows them.
- **Ground truth for every step.** Each phase is checked in `SelfTest` against an independent measurement:
  - 3DBAG LoD2.2 or the 3D Basisvoorziening for Delft;
  - German state LoD2 for a German town;
  - lidar for heights.
  Each check cites its source. Nothing ships on looks alone.
- **One building at a time if that is what accuracy takes.** The first milestone is a single real house in Delft
  built exactly: its footprint, walls, eaves, ridge and roof faces from 3DBAG's LoD2.2 model, its windows and door
  from street imagery, its materials from photographs, each checked against the source. Only then a street, then
  the town. Buildings that can't be made exact yet are marked as such, never passed off as accurate.
- **One pilot area first, then widen.** Delft first: it has everything open, footprints (BAG), LoD2.2 roofs
  (3DBAG and the PDOK 3D Basisvoorziening), lidar (AHN), surveyed streets (BGT) and street imagery. Each step is
  made right there before it goes anywhere else.
- **Region by region after that,** as each region's measured data is converted, the way BTE teams build one
  country at a time.

### Scale: 1:1 everywhere (to decide)

BTE builds at exactly one block a metre everywhere. This mod's default Mercator projection is 1:1 only at the start
latitude (`WorldSettings.scaleLatitude`): Paris comes out at 0.77 of its size and the equator at 0.51. Heights
follow the same scale, so buildings keep true proportions but not true size. The older sinusoidal projection is
1:1 everywhere but shears shapes away from its centre meridian. True 1:1 everywhere with little distortion needs a
BTE-style projection: an icosahedral (Dymaxion-like) map with each face laid flat at one block a metre, as BTE's
own projection does. That is a change to new worlds' terrain, not just buildings, and wants deciding before
buildings are measured against real sizes.

## What is wrong with the current system

| | Today | Where |
|---|---|---|
| Height | Storeys counted at 3 m each, then each built at least 5 blocks tall (`storeyMeters` 4.5 m), and at most 10 of them (`maxFloors`). At 1 block a metre, a six-storey 20 m block of flats stands 30 blocks; a 100 m tower is cut to 10 floors, 50 blocks. | `BuildingPlan.of`, `EarthConfig` |
| Missing height | 5 m (OpenFreeMap's stand-in), or 3 m or 6 m by footprint area. | `BuildingPlan.of` |
| Footprints | OpenFreeMap's buildings, each piece of a building split by a z14 tile edge planned on its own. Overture is read only for detail. | `Buildings.candidates` |
| Parts | Only parts starting above half the building's height, built as "towers". Wings at other heights, podiums under towers, arcades and passages are ignored. | `Buildings.raised` |
| Roofs | Pitched or flat from the footprint's depth, storeys and climate unless mapped; the ridge along the longest wall; `roof:direction` and `roof:orientation` ignored. | `BuildingPlan.of`, `ridgeDistance` |
| Walls | One block by nearest colour; one window pattern for every building. | `Facades`, `BuildingPainter` |
| Use | A handful of heuristics from POIs and size. | `Buildings.classify` |

## What the data has

Overture's buildings theme (already downloaded for roof detail) holds every OpenStreetMap building, plus
Microsoft's machine-learned footprints where OSM has none, each with a stable id. In tiles cached by the self-test:

| Tile | Buildings | From ML | With height | With storeys | With roof shape | With class | With parts |
|---|---|---|---|---|---|---|---|
| Paris | 4,179 | 1% | 3% | 70% | 2% | 68% | 944 parts |
| Haarlem area | 5,422 | 1% | 8% | 2% | 1% | 63% | 13 |
| Gothenburg | 602 | 16% | 7% | 30% | 11% | 35% | 43 |
| Monchique (rural Portugal) | 317 | 63% | 0% | 0% | 0% | 3% | 0 |
| Uppsala area | 279 | 0% | 0% | 6% | 2% | 55% | 0 |

Its fields are those of OSM's Simple 3D Buildings: `height`, `min_height`, `num_floors`, `min_floor`,
`roof_shape`, `roof_height`, `roof_direction`, `roof_orientation`, `roof_color`, `roof_material`,
`facade_color`, `facade_material`, `class`, `subtype`, `has_parts`, and parts in their own layer. A building cut by
a tile edge keeps its id in both tiles (57 of 602 shared between two neighbouring Gothenburg tiles), so its pieces
can be joined. Where an overlay is installed (3D Basisvoorziening for the Netherlands today; LoD2 models and lidar
later), heights and roofs are measured.

So most buildings have a footprint and a class, many have storeys, few have heights or roof shapes: the tags alone
are not enough for true shapes. They come from measured sources (below), added region by region.

## The new model

- **Building:** a stable id (Overture's, else OSM's), its footprint (pieces from neighbouring tiles joined by id),
  its class and use, and its volumes.
- **Volumes:** the parts, where it has parts (OSM's rule: the outline is then not drawn where parts cover it),
  else the outline. Each: footprint, base height, height, storeys, roof (shape, height, direction or
  orientation), wall and roof material and colour.
- **Every attribute knows where it came from:** measured (overlay), mapped (OSM tag), estimated (ML or satellite),
  or unknown. `/earth here` and the API say which, so a wrong building can be traced
  to its data.
- **Resolution order, per attribute:** measured, then mapped, then estimated.

## Decisions (2026-11-03)

- **True heights.** Every volume exactly as tall as it is (metres × the map's blocks a metre), no storey stretching,
  no floor cap but the build limit.
- **Outsides from real data only, never generated.** A building looks as it does in the real world, from every
  source that shows it: measured shapes first, mapped tags next. Where no source shows something, see "Where no
  data shows it" below.
- **No interiors for now.** Buildings are shells. Interiors (rooms, furniture, shops, villagers) get a whole new
  system later. Until then villagers, shops, shop signs, loot and `RealEarthApi`'s rooms go with the old system.

## Where true shapes and windows come from

| What | Sources that may be used | Coverage |
|---|---|---|
| Roof and wall shape (LoD2) | 3DBAG (NL, CC BY 4.0), German state LoD2, swissBUILDINGS3D, Luxembourg, Helsinki, Riga, Prague, Vienna, Poland, PLATEAU (Japan), New York, Boston, Montréal, Calgary | the listed cities and countries |
| Roof shape reconstructed from lidar | the same method as 3DBAG's roofer, run offline on national lidar: AHN, USGS 3DEP, England EA, IGN LiDAR HD, PNOA, DGT, Denmark, Lantmäteriet, LINZ, ELVIS, CanElevation | much of Europe, the US, Australia, New Zealand, Japan's cities |
| Heights | overlays, OSM tags, Overture's ML heights, Google Open Buildings 2.5D (CC BY 4.0), GHSL built-up height (satellite, 100 m, CC BY 4.0) | everywhere, from best to coarsest |
| Wall and roof material and colour | OSM tags; roof colour from open aerial imagery (NAIP, LINZ); textured city models where open | patchy |
| Windows and doors | window detection run offline on open street-level imagery (Mapillary, Panoramax, CC BY-SA, kept in its own overlay file) and on textured city models (Hamburg, Montréal, PLATEAU LoD3) | where that imagery exists |

**Not usable:** Google's photorealistic 3D tiles and Google Earth, and Apple's 3D maps. Their terms forbid
extracting geometry or using them offline (see `DATA-SOURCES-PLAN.md`, "Not used"). Global Building Atlas heights
are NonCommercial.

## Where no data shows it (to decide)

Large areas have no open 3D model, lidar or street imagery (much of Africa and Asia, many rural areas). There a
building still has its footprint and a height from the coarsest source, but nothing shows its roof shape, wall
material or windows. Options: the commonest shape and material of tagged buildings of its kind in its region,
marked as a statistical fill; or a plain flat-roofed box in a neutral wall, marked unknown, until a source covers it.

## Phases

Each phase runs behind `buildingSystem = "new"` until its self-tests pass. Then the default switches and the old
code goes.

1. **Model and sources.**
   - A `buildings` package reads Overture buildings and parts as the primary source, OpenFreeMap only where
     Overture is unavailable, and overlays first.
   - It joins pieces across tiles by id, resolves every attribute with its origin, and caches plans by id.
   - GHSL built-up height and Google Open Buildings 2.5D as height sources.
   - Self-tests check, against cited values:
     - Stockholm: heights as mapped;
     - Delft: as measured;
     - Paris: parts, storeys from `num_floors`;
     - Manhattan: towers past 10 storeys.
2. **Shells:** volumes at true heights, every roof shape with its direction where known, walls by material and
   colour, foundations on slopes, building pieces meeting cleanly across chunks. A new painter. No interiors.
3. **Switch over:** the new system becomes the default. Old `Buildings`, `BuildingPlan`, `Interior`,
   `BuildingPainter`, `Facades`, `storeyMeters`/`maxFloors`, and the villagers, shops, signs and loot built on
   them, are deleted. Distant Horizons' far view (`LodSampler`) uses the new heights.
4. **Measured roofs and walls:** LoD2 overlays (3DBAG, German state LoD2, swissBUILDINGS3D, PLATEAU), then roofs
   reconstructed from national lidar, as roof and wall surfaces per building.
5. **Windows and doors:** a window and door detector run offline on open street-level imagery and textured
   models, written as an openings layer per wall.

## Not changed

Buildings are held to this standard of exactness. Roads, rails, bridges, tunnels, trees, water and other features
may be generated from map data, as long as they look as they do in real life (2026-11-03). They stay as they are,
improved through `DATA-SOURCES-PLAN.md`. The overlay format stays too. The pavement work (BGT surfaces) runs in its
own session and touches only roads.
