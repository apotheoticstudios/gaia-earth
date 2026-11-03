# Dynamic waterways for Gaia Earth

Mapped currents now activate automatically when Earth chunks load. They follow OpenStreetMap
waterway lines and extend across mapped river surfaces. Gaia uses the existing water surface
elevation to keep currents out of caves and unrelated water above the channel. Saved Earth worlds
gain currents as their chunks load, and the current data is saved and synced with each chunk.
The later phases cover changes to channel shape, variable current strength and waterfalls.

To try the prototype, stand at a still water surface on Earth and run `/earth water test` as an
operator. It sets an eastward current in a temporary 32-by-32 area, four blocks deep. Append a heading
in degrees to change direction, for example `/earth water test 90` for south. Drop an item on the water
or float in a boat to check drift. Use `/earth water off` to clear the zone. The command uses existing
water and the zone expires when the server stops.

## Intended result

Keep rivers, streams, canals, lakes and coastlines in their real mapped locations. Add visible
downstream currents that carry items, swimmers and boats, with gentler lowland reaches, faster
mountain reaches, coherent junctions and mouths, and improved beds, banks and waterfalls.

Use code directly from Dynamic Waters where it fits, adapted to Gaia's data and platform. Gaia is
Fabric / Minecraft 26.1 / Java 25; the reference targets NeoForge / Minecraft 1.21.1. This is a source
port, not a dependency that can simply be added to the build.

The inspected reference implements generated channels and a current field queried by fluids and
entities. The initial target is that behaviour. Water volume conservation, rainfall, erosion,
tidal reversal and player-built dams that dynamically flood an upstream valley are separate features.

## What Gaia already has

- `osm/OsmWater.java`: polygon water and waterway centrelines from zoom-14 vector tiles. Lines keep
  only points and a default width: river 12 m, canal 8 m, stream 3 m, drain 2 m, ditch 1.5 m.
  The waterway class is discarded after width selection. Polygon areas keep only geometry and sea status.
- `terrain/ProcessedTile.java`: raster water coverage, inferred levels from flat elevation samples
  and nearby shores, and depth from shore distance. It does not solve a directed river network.
- `terrain/TerrainField.java`: samples those levels into columns, carves below water and converts
  metres into block heights. Its current `surface <= 0.5` treatment also folds mapped inland water
  near or below sea level into sea-level water; classify these explicitly in the new system.
- `terrain/ChunkTerrain.java`: ground and water heights, with a general inland-water boolean,
  but no current direction, speed or river identity.
- `world/EarthChunkGenerator.java`: fills water columns with vanilla source blocks, including an
  efficient uniform-water-section path. Preserve this optimisation if using vanilla water.
- Distant Horizons already consumes the terrain column heights. `SelfTest` checks water presence,
  elevation, shared chunk columns, bridges and fish spawning, but not downstream behaviour.

## Direct reuse from Dynamic Waters

Reference revision: `563ec7b559c32719155d6e808159896316dbe962`.
Paths below are relative to its `neoforge/src/main/java/com/AdamNew/dynamicwaters/` directory.

| Reference code | Proposed use | Required adaptation |
| --- | --- | --- |
| `VanillaRiverFlow.java` | Port angle encoding/decoding and compact chunk current lookup | Replace noise-router angle computation, sea-level bands and biome gating with Gaia's mapped river field; scope caches by world/dimension |
| `network/SyncVanillaFlowPacket.java`, `client/VanillaFlowClientEvents.java` | Port packet and client update structure | Fabric networking, versioned payload, variable elevation, unload cleanup and stronger validation |
| `mixin/FlowingFluidMixin.java` | Port the current override in `getFlow` | Gaia-only water and generated channel bounds; preserve vanilla fallback; omit unrelated global fluid-spreading changes and ship integration |
| `mixin/EntityMixin.java`, `mixin/ItemEntityMixin.java` | Adapt boat/item current handling where ordinary fluid pushing is insufficient | Verify 26.1 behaviour first; prevent double pushing, preserve player input and vertical movement; use explicit entity types |
| `terrain/ChannelForm.java` | Port bed cross-section, bend scour and bank profile calculations | Use physical metres and Gaia's mapped widths, curvature, projection and terrain limits |
| `shore/ShoreSkin.java`, palette helpers | Selectively port bank/bed material placement | Gaia's climate, strata, existing quay/road/building protection and generation pipeline |
| `mixin/WaterParticleMixin.java` and particle implementation | Port current-aligned surface effects | Client-only registration, actual fluid surface height, local particle budgets and per-world sound state |
| `fluid/RiverWaterFluid.java`, `block/RiverWaterBlock.java` | Reserve as an alternative if the vanilla-water prototype fails | Fabric fluid registration, water tags, buckets, waterlogging, renderer and neighbour behaviour |

Do not wholesale port `HydrologyManager`, the noise-generation mixins or the procedural river network
builder: they would add substantial unrelated machinery and could replace real-world river routes.
The compact direction codec can be reused without importing the TerraFirmaCraft-derived river graph.

The repository declares MPL-2.0, with specific TerraFirmaCraft-derived files under EUPL-1.2 and
async helpers under MIT. Track the actual header and provenance of every imported file, retain
notices, package relevant licence texts, and provide the modified covered source when distributing.
Keep imported modules separate from Gaia's MIT code and update its licence metadata accurately.
If importing any EUPL-derived files, review that licence separately rather than treating them as MPL.
Do not copy the bundled Minecraft-derived sound asset; use an existing game sound event initially.

## Implementation sequence

### 1. Prove current rendering and movement on Fabric 26.1

Build a small deterministic current field over a test channel. Port the compact codec, current
lookup, fluid-flow override and packet structure first. Use vanilla water plus current metadata as
the preferred design: this preserves existing water sections and water compatibility.

Verify texture direction, dropped-item drift, swimmer prediction and boat drift on a dedicated
server and a separate client. Use one authoritative field for visuals and physics, with any
entity-specific strength applied exactly once. Verify texture rendering against 26.1's actual
fluid renderer instead of assuming the old mixin is sufficient.

Gate: proceed with vanilla water only if these behaviours work without broad fluid changes. If
necessary, prototype the custom river fluid before committing the world format to either approach.

### 2. Preserve waterway meaning and build connected reaches

Extend `OsmWater.Line` to retain kind and available tags; retain polygon kind too. Inspect actual
vector tile attributes and geometry orientation before relying on them: display tiles may clip,
simplify or omit source identifiers, flow direction, widths, tunnels and hydraulic structures.
Extend `VectorTile.Feature` to preserve feature IDs if useful IDs are present in the source.

Add a `hydrology/` package with immutable reaches, junctions and waterbody classifications.
Deduplicate overlapping tile fragments, join endpoints with a small, documented tolerance and
split true junctions. Do not join every geometric crossing: aqueducts, culverts and different
layers can cross without connecting. Relate polygon river surfaces to their centrelines so a wide
river gets a current across its mapped width, rather than just a 12 m stripe.

Resolve downstream direction from verified source orientation where available, robust elevation
samples along reaches, outlet connectivity and shared lake levels. A single neighbouring height
difference is insufficient on flat terrain. Preserve uncertain reaches as still water with an
explicit confidence value. Canals and tidal reaches need separate policies; do not force them downhill.

Use fixed geographic regions with canonical ownership of shared reaches and boundary constraints.
Region workers must not recursively wait on each other. A bounded halo alone cannot solve an entire
river basin: retain unresolved outlet constraints and use canonical shared results. Freeze published
generation results so later downloads do not silently change already-generated terrain.

Gate: identical reach connectivity/direction regardless of tile request order, including seams.

### 3. Produce and persist a current field

Rasterise reach tangents into compact direction, strength and wet-height bounds per channel column.
Blend compatible directions through confluences and bends. Taper speed at banks and into receiving
lakes/sea; avoid blending separate channels across dry banks. Use actual projected world-space
tangents, including the legacy sinusoidal projection, and real-metre slope/distance calculations.

Store generated chunk current metadata durably; the existing `TerrainField` LRU is not persistence.
Persist the generator version, masks and height bounds with it so loading a chunk offline does not
need to recalculate against newer external data. Prototype 16x16 column resolution for narrow streams;
the reference's 4x4 grid may be too coarse for Gaia's metre-scale waterways. Quantify disk, memory and
network costs before choosing the final encoding.

Runtime queries must be bounded cache lookups: no downloads, terrain generation or chunk loads.
Apply only to water inside the recorded channel envelope, at the recorded elevations, on Earth.
Leave caves and unrelated water alone. Block edits need a bounded invalidation policy so a dam wall
cannot retain a through-wall current and a player pool cannot inherit current accidentally. Define
this as local current obstruction initially, not a complete upstream flood simulation.

Synchronise metadata when clients track chunks and on revisions. Include dimension, chunk, format
version and bounded arrays; clear on untracking, disconnect and world changes. Rendering must also
work from client render-region views. Dirty sections around each river's actual Y, not global sea level.

Gate: reload, reconnect, offline play and two simultaneous worlds preserve correct currents.

### 4. Make channel elevations and shapes coherent

Replace river-specific local level inference with connected downstream profiles. Keep lakes flat,
respect known outlet/sea levels and require ordinary river surfaces to be non-increasing downstream.
Treat dams, locks, waterfalls, closed basins and below-sea-level lakes as explicit exceptions/types.
Bound excavation and bank changes: questionable elevation must not create a kilometre-long trench.

Port `ChannelForm` for cross-sections and bend scour. Prefer polygon widths where present, use line
defaults only as fallback, and scale depth sensibly. Protect buildings, quay walls, bridges, tunnels
and roads. Run bridge-clearance calculations against the final water profile. Pass identical final
heights to normal chunks, height queries and Distant Horizons.

Handle block-height drops deliberately: controlled lips, pools and falling-water transitions rather
than long staircases of unrelated source blocks. Test neighbouring fluid updates, freezing, bucket
use and broken banks so a settled generated river does not drain or schedule runaway fluid ticks.

Gate: no uphill river reaches, dry gaps or cross-chunk surface mismatches in validated fixtures.

### 5. Add river character and release controls

Port selected shore placement and particle code. Add gravel/stone to steep reaches, sediment and
bars to gentle bends, riparian vegetation where appropriate, and local effects at rapids and falls.
Use mapped branches at real deltas; keep any invented tributaries/delta channels as an optional,
later generation mode. Particle direction comes from the same field as water and entities.

Add debug arrows, reach IDs, confidence, levels and speed to an `/earth water` diagnostic command.
Expose current strength, boat/item effects, particles and sounds separately from terrain changes.
Pin hydrology generation version and terrain-affecting settings in `WorldSettings`, following the
existing projection/lidar compatibility pattern. Existing worlds stay on legacy terrain; any current
backfill is explicit and only touches verified water. Do not automatically regenerate player builds.

## Validation and completion criteria

- Pure saved-data fixtures: straight/curved channels, flat ambiguous reach, confluence, split delta,
  canal, lake outlet, waterfall, culvert crossing, below-sea-level lake and mismatched tile endpoints.
- Generate adjacent regions/chunks in shuffled order; compare shared water levels, masks, vectors and
  heights. Include negative coordinates, Mercator wrap, high latitudes and legacy projection.
- Integrate targeted phases into `SelfTest`: correct downstream drift, still lakes, current Y bounds,
  bridge clearance, fish, source/flowing water interactions, chunk reload and save-format migration.
- Dedicated-server/client checks: movement prediction, boat control, no double forces, visual/physics
  agreement, packet validation, unload cleanup and no currents leaking into another dimension.
- Player edits: wall across river, bucket placement/removal, underwater building and adjacent pool;
  confirm documented local obstruction behaviour without promising hydrodynamic flooding.
- Use saved regional data and in-game views for a steep stream, wide lowland river, city canal,
  confluence and mouth. Select exact coordinates after confirming source coverage and direction.
- Compare chunk generation time, server tick time, fluid update count, memory and network traffic
  against the current baseline with fixed render/simulation distances and entity counts. Set release
  budgets from those measurements; do not claim performance parity before profiling.
- Run the build and relevant self-test phases for each implementation stage, then the complete
  self-tests and screenshot checks before release. The prototype's server checks run in the
  `Water current` phase of `./gradlew runSelftest -Ponly='Water current'`.

First deliverable: the phase-1 current prototype and provenance manifest. Then connect it to mapped
reaches and persistence. Channel reshaping follows after currents are correct and stable.

## Sources

- [Dynamic Waters reference repository](https://github.com/AdamNewGames/dynamicwaters-public/tree/563ec7b559c32719155d6e808159896316dbe962)
- [Third-party notices](https://github.com/AdamNewGames/dynamicwaters-public/blob/563ec7b559c32719155d6e808159896316dbe962/THIRD_PARTY_NOTICES.md)
- [Mozilla MPL FAQ: distribution and modified source](https://www.mozilla.org/en-US/MPL/2.0/FAQ/)
