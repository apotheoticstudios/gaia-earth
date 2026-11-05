# Bridges as they really are: a plan

Today a bridge is one row of deck blocks on a guessed height profile (`terrain/Bridges.java`): the line between
its ends, lifted in rounded crests where water or a way below needs clearance, at the way's drawn width. Nothing
holds it up, nothing stands on it, and every bridge looks the same. This plan replaces that with each bridge built
as its real structure: its deck at its real outline and height, the piers and abutments it stands on, the arches,
trusses, towers and cables that carry it, its railings, in its real materials.

It follows `ACCURACY-PLAN.md`'s ground rules (real numbers tested in `SelfTest` against cited values, checked in the
game, licence first) and plugs into `DATA-SOURCES-PLAN.md`'s overlays, like the buildings rebuild.

## What the user chose (2026-11-04)

- **Two pilots:** a Delft canal bridge, measured end to end, and Västerbron in Stockholm, a big steel arch bridge.
- **The full structure in the first version:** deck, piers and abutments, arches, trusses, towers, cables, railings.
- **Where the data names a bridge's type but not its shape** (Västerbron: an arch, steel, 600 m long), the structure
  is built from that type and its sizes. Measured shapes win wherever they exist.

## The data, best first

1. **Measured.** The Netherlands first:
   - the BGT's bridge parts (`overbruggingsdeel`, CC0): every deck, pier (`pijler`), abutment (`landhoofd`) and
     sill as surveyed, and whether the bridge opens (`overbrugging_is_beweegbaar`);
   - AHN's surface model (CC0): the deck's real top, every half metre.
   Later, region by region: Japan's PLATEAU bridge models, swissTLM3D, the US National Bridge Inventory (spans,
   clearance, deck width, type and material for every US bridge, public domain), and Lantmäteriet's lidar for
   Sweden (free, behind a Geotorget login, so downloaded by hand).
2. **Mapped (OpenStreetMap, ODbL).** `man_made=bridge` outlines (the deck's real footprint), `bridge:structure`
   (beam, arch, truss, suspension, cable-stayed...), `bridge:movable`, `bridge:support` (piers), `layer`, `ele`,
   `height`, `maxheight` (clearance), `width`, `material`, `wikidata`. The mod's own map tiles keep only the outlines
   and layers, so the rest comes through the overlay.
3. **Wikidata (CC0).** Type (P31: arch, truss, suspension, cable-stayed, bascule bridge...), material (P186), length
   (P2043), height (P2048), clearance (P2793), number of spans (P1314), longest span (P2787).
4. **Cited facts** for a pilot where none of the above has a number (Västerbron's span lengths), one row each with
   its source, in `tools/overlay/bridge_facts.tsv`.

## How it is built

- **An overlay of bridges** (`tools/overlay/bridges.py`): for a box, the bridges from OpenStreetMap (one Overpass
  query) with their Wikidata facts and cited facts, and in the Netherlands the BGT's parts and AHN's deck heights.
  Layers in the PMTiles archive, on the mod's z14 grid:
  - `bridges`: each bridge's outline, with name, structure, material, movable, deck height, clearance, height,
    length, spans and main span, where known, and the source of each;
  - `bridge_parts`: decks, piers and abutments as surveyed or mapped, each with its kind and measured top;
  - `bridge_heights`: measured deck tops, a point every two metres.
- **The mod reads it** with the other overlays (`buildingOverlays`) and matches each bridge run (`Bridges.Run`) to
  the outline its centreline lies in.
- **The deck:** as wide as its outline, at its measured height where measured, else held to the mapped deck height
  and clearance, else the present crest profile.
- **Supports:** piers and abutments where surveyed or mapped; else placed from the spans the data gives; else for
  the bridge's type and length.
- **The structure, by type,** worked out once per bridge and built column by column, so every chunk gets the same
  bridge: deck arches under the deck and through arches over it, trusses beside it, suspension towers, main cables
  and hangers, cable-stayed pylons and stays, girders under beam bridges, bascule leaves where a bridge opens.
- **Railings** along both deck edges, and materials from the data: steel, concrete, stone, brick, wood.
- **A new road version**, so worlds made before keep their bridges.

## Checks

- **Delft:** deck within half a block of the BGT outline and within a block of AHN's height; piers and abutments
  where the BGT has them; an opening bridge built as one.
- **Västerbron:** its arches over the water with their cited spans, its deck at its cited clearance, its length,
  railings down both sides.
- **Everywhere:** the existing walkability, seam and deck checks keep passing.

## Order of work

1. The overlay tool: OpenStreetMap and Wikidata for any box; BGT parts and AHN heights in the Netherlands.
2. The mod reads the overlay and matches bridges to it.
3. The deck from the outline and the measured or mapped heights.
4. Piers and abutments.
5. Superstructures: Västerbron's arches first, Delft's bascules, then beams, trusses, suspension and cable-stayed.
6. Railings and materials.
7. Widen: the US inventory, PLATEAU, swissTLM3D; and what to build where no overlay covers a bridge.

## Where it stands (2026-11-05)

Built (road version 7):
- **The overlay** for central Delft and for Västerbron and Tranebergsbron (`tools/overlay/bridges.py`).
- **The deck** fills its outline and every surveyed deck (BGT). It lies at AHN's measured top where measured, as the
  lowest top within 3 m, since AHN's surface model reads railings and trees over a deck above it. Elsewhere it clears
  the main span by the cited clearance and climbs at a road's gradient (5 %, railways 2.5 %). A bridge's runs share
  its longest run's deck.
- **Under it:** piers where surveyed or mapped, else between its spans; girders a twentieth of their span deep, or a
  slab.
- **Arches:** a masonry barrel filled to the deck, kept open above the water; two steel or concrete ribs on posts
  where the deck stands a tenth of the span above the arch's feet; over the deck, ribs and hangers, where it doesn't.
- **Railings** along every road deck's sides, in the bridge's material.
- **Bridges that open** (road version 8). A bridge's spans lie between its surveyed supports where it has them. Its
  leaf is the cited opening span, else the longest: a slab on its piers. A drawbridge (the Dutch ophaalbrug: OSM
  `bridge:movable=drawbridge`, Wikidata "double-beam drawbridge") also has its lifting gear, built closed: a portal
  over the road at the hinge with the counterweight on it, balance beams along both sides, and rods to the leaf's tip.

Checked in `SelfTest`:
- Delft: 82 % of AHN's measured deck points within a block of the deck built there.
- The Jeronymusbrug: a brick humpback, open over the canal, railed.
- Västerbron: its arch 26 m clear of Riddarfjärden; 167 and 204 m between its piers (cited 168 and 204); every deck
  column over the water standing on its structure; railings.
- The Koepoortbrug: its leaf over 12.6 m of open water (cited 10.35 m; the BGT's abutments are 9.6 m apart); its
  underside 1.6 m over Delfland's boezem level, NAP -0.43 m (cited 2.5 m, within a block); its portal, rods and beams.

Trusses (road version 9, `terrain/BridgeTrusses.java`), piloted on Deventer's IJsselspoorbrug (1982, Wikidata Q21012706):
- **Measured** in the Netherlands (`bridges.py`): along a truss bridge's track (or road), AHN's surface model reads its
  trusses where it stands 4 m or more over the deck for 20 m or more (a catenary portal is a step or two): each truss a
  `truss` strip in `bridge_parts`, its top over the deck the bridge's `truss_height`. Where the BGT surveys no deck
  (it has the IJsselspoorbrug's piers and abutments, not its deck), the deck is measured along the tracks. The
  IJsselspoorbrug: two trusses 9.5 m apart (the tracks between them, the cycle path and footpath outside), 90 m long
  over its 95.7 m main span (Wikidata P2787), 9.96 m over the rails at 15.2 m NAP; its other spans are girders.
- **Pattern** from Wikidata's type (Warren, Pratt and its variants, Howe, K, lattice; "deck truss") or a cited row:
  the IJsselspoorbrug a Warren through truss (Structurae). A Warren truss's triangles are equilateral, so its panels
  are 1.15 times its height: 8 over the IJsselspoorbrug's 90 m. The others' panels are about square.
- **Built:** a through truss's bottom chord on the deck at its sides, its top chord its height over the deck, the deck a
  slab between (no girders under a span a truss carries); a deck truss under the deck. Steel as iron blocks. Unmeasured,
  a truss stands along the deck's edges over each cited span (else the longest), a tenth of the span high (as deep for
  its span as the IJsselspoorbrug's). Members keep off railway track.
- **Checked** (`SelfTest` "Bridges: the IJsselspoorbrug's trusses"): 8 blocks over the deck (9.96 m measured), 96 m
  between the piers either side of the main span (95.7 m cited), iron along both trusses with two diagonals a panel,
  and the deck clear of the IJssel as AHN measures it (558 of 567 columns within a block).
- Not used: nl.wikipedia's doorvaarthoogte of 13.63 m, whose water level is not given; with the rails at 15.2 m NAP it
  can't be over the IJssel's normal level (about 3 m NAP), so the measured deck stands.

Across map tiles (road version 10, `Bridges.runs`): the map cuts a way at each tile's edge, each tile's copy reaching
about 8 samples into the next, so the copies overlapped, never shared an end and stayed separate runs. A bridge's
longest run then stopped at the cut (the IJsselspoorbrug's at 316 of its 494 m), and its deck, spans and structure
with it. Now each copy is cut at its own tile's edge and the two cut ends are made one point (each tile rounds the
map's points its own way, so the nearest end within a sample), as `RailTracks` lays track one copy per tile. A chunk
reads the map tiles its bridges go on into, as it does for tunnels. Checked (`SelfTest` "Bridges: the
IJsselspoorbrug's trusses"): its two tracks are one run each, 494 m, the length of its outline.

Not yet:
- Which end a drawbridge is hinged at (in none of the data): its end nearer the run's start. Its portal stands on the
  leaf's first metre and a half, not on the abutment behind, and its beams don't reach back past it.
- A bascule's counterweight and pit, a lift bridge's towers, a swing bridge's pivot: their leaves only.
- The world's canal water in Delft stands about 0.8 m above Delfland's boezem level (0.38 m against NAP -0.43 m), so
  a canal bridge looks lower over the water in the game than it is.
- Trusses' top bracing and portals (AHN reads bracing between the IJsselspoorbrug's top chords), and verticals where a
  Warren truss has them: the data names none.
- A bridge reaching more than 24 map tiles past a chunk's own (30 km or so) is cut where the chunk stops reading
  tiles, so chunks far apart along it can lay different decks. A way that leaves its tile and comes back keeps only
  its first piece there.
- Suspension towers and cables, cable-stayed pylons and stays.
- Arches and piers for surveyed decks the map has no bridge for (they get the deck and railings only).
- Delft's bridges' materials: the data mostly has none, so a humpback is taken as brick.
- Measured decks in Sweden: Lantmäteriet's lidar, behind a login.
- Railings on railway bridges.
