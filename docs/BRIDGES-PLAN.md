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
