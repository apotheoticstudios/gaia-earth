# Better roads: a plan

Roads, paths and their furniture are generated from map data, and should look as they do in real life (decided
2026-11-03; buildings are held to exactness instead, see `BUILDINGS-REBUILD-PLAN.md`). Where a source measures a
road (BGT in the Netherlands, see `DATA-SOURCES-PLAN.md` phase 4), the measurement wins.

## Today

`WayRaster` draws each OpenFreeMap way as a band of fixed width by class (`Ways.widthMeters`: motorway 10 m, primary
8 m, residential 5 m, path 1.6 m), levelled across, with bridges, ramps and tunnels. `EarthChunkGenerator.waySurface`
paves every road in grey concrete, every path as a dirt path, tracks in coarse dirt and gravel. No pavements, no
markings, no crossings, one material, and the same width in Tokyo as in Texas.

## What the data has (surveyed 2026-11-03)

Overture's transportation theme, in the release the mod already reads buildings from, at z14:

| Tile | Segments | Mapped sidewalks | Crosswalks | Steps | With a surface |
|---|---|---|---|---|---|
| Paris | 3,939 | 952 | 128 | 542 | 81% (795 paving stones) |
| Manhattan | 958 | 50 | 20 | 0 | 30% |
| Stockholm | 516 | 6 | 2 | 10 | 50% |

- **Classes:** motorway to residential, living street, pedestrian, service (with `parking_aisle`, `driveway`,
  `alley`), track, path, footway (with `sidewalk`, `crosswalk`), cycleway (`cycle_crossing`), steps, bridleway,
  and rail.
- **`road_surface`, along the segment:** paved, asphalt, concrete, paving stones, sett, cobblestone, gravel,
  dirt, metal and more.
- **`road_flags`:** bridge, tunnel, link, covered, indoor.
- **`level_rules`.**

Its base theme (already downloaded for single trees) has crossing points, traffic signals, kerbs, street lamps and
stops. Neither OpenFreeMap nor Overture carries a road's width, lanes or turn lanes: those are generated, from the
road's class, whether it is one-way, and the country's norms.

## Built (2026-11-04)

- **Widths and markings:**
  - lanes at the region's width;
  - centre lines, white or yellow by country;
  - lane lines and edge lines;
  - painted in Saro's Road Blocks' asphalt (`Markings`, `WayRaster`), from road version 1.
- **Zebra crossings and two-line crossings** at the base theme's marked crossings (`OsmTrees` crossings,
  `Markings.crossing`): 109 bar columns round Chatelet.
- **Surfaces:**
  - paved footways as concrete pavement, steps in stone, unpaved roads in gravel, paved tracks in asphalt, all from
    OpenFreeMap's paved and unpaved (`Ways.surface`);
  - paving stones, gravel and dirt from Overture's transportation theme (`OsmRoads`, matched to the nearest segment
    of the way's kind): 526 paving-stone columns round Chatelet.
  - Road version 2.
- **Pavements and kerbs:**
  - a pavement either side of town streets in built-up land, in Saro's sidewalk block (`Ways.sides`, `WayRaster`);
  - 2 to 2.5 m in Europe;
  - in North America and Australia, 1.5 m past a grass verge on residential streets;
  - none on Japan's residential lanes;
  - mapped footways where they are;
  - the asphalt two sixteenths below the pavement, a kerb of about 12 cm;
  - 13,834 pavement columns in central Stockholm;
  - road version 3.

## Phases

1. **Look right everywhere.** All generated from map data:
   - **Widths:** carriageways by class and one-way, by the country's lane width (about 3.0 to 3.75 m) and the
     usual lanes for the class.
   - **Markings:**
     - centre lines (white, or yellow in the Americas, Norway and Finland), dashed or solid by class;
     - lane lines on multi-lane roads;
     - edge lines on major roads and motorways;
     - zebra crossings at mapped crossing points and crosswalks.
   - **Materials:** each road and path in its mapped surface (asphalt, concrete, paving stones, setts, gravel,
     dirt, metal); asphalt where a road is paved but nothing more is said.
2. **Pavements:**
   - Overture's mapped sidewalks drawn where they are.
   - In built-up land where none are mapped, a pavement either side of streets that have them there, at the
     country's usual width, set apart from the carriageway by its material.
3. **Street furniture where mapped:** traffic signals, street lamps (replacing today's random lamps where lamps
   are mapped), bus stops and kerbs. Parking aisles and driveways in their own surface.
4. **Measured roads:** BGT surfaces in the Netherlands (in progress), then other surveyed street datasets (NYC
   planimetrics, Hong Kong, Singapore kerbs) as overlays.

Each phase is checked in the self-test against the map. In Paris and Stockholm:
- most columns on a mapped crosswalk get zebra stripes;
- a residential street's width is within a block of the norm;
- surfaces follow `road_surface`;
- a road's centre line runs unbroken along it.
