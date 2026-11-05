# Gaia overlays

Regional data turned once, offline, into tiles the mod streams like its other sources (see
`docs/DATA-SOURCES-PLAN.md`). Not part of the Gradle build; Python 3.9+, standard library only.

An overlay is a PMTiles archive of z14 vector tiles, on the grid of the mod's map tiles. The mod reads the archives
listed in its config's `buildingOverlays` (http(s): or file: URLs, comma-separated) and takes their measured heights
and roof heights over OpenStreetMap's and Overture's wherever a footprint matches.

`sources.tsv` lists each source with its release, terms and coordinate systems. Only sources marked `ok` are
converted. Downloads go in `data/`, which git ignores.

## Netherlands: measured buildings

The Kadaster's 3D Basisvoorziening publishes 3DBAG's roof reconstruction for every BAG building, per map sheet.
Find a sheet's download with PDOK's API:

```bash
curl "https://api.pdok.nl/kadaster/3d-basisvoorziening/ogc/v1/collections/hoogtestatistieken_gebouwen/items?f=json&bbox=4.356,52.010,4.360,52.012"
```

Unzip it into `data/`, then:

```bash
python3 buildings_nl.py data/<sheet>_hoogtestatistieken_gebouwen.gpkg 2025 data/<name>.pmtiles
python3 buildings_nl.py --self-test
```

Sheet 37EN1 (Delft and round it): 72,438 buildings, 20 tiles, 3.8 MB, in 3 seconds.

## Netherlands: surveyed roads and pavements

The BGT's road parts (`wegdeel`, CC0) for a box, fetched from PDOK's API page by page: carriageways, pavements,
cycle paths, squares, steps and parking spaces, with what they are paved with. List the archive in
`buildingOverlays` beside the buildings one.

```bash
python3 surfaces_nl.py <min lon> <min lat> <max lon> <max lat> data/<name>.pmtiles
python3 surfaces_nl.py --self-test
```

`overlay.py` holds what the converters share: Web Mercator, vector tiles and PMTiles (`python3 overlay.py` tests it).

## Bridges, anywhere (and measured in the Netherlands)

Each bridge as it really is (see `docs/BRIDGES-PLAN.md`): its outline and structure from OpenStreetMap (one Overpass
query, ODbL), its type, material and sizes from Wikidata (CC0), cited numbers neither has from `bridge_facts.tsv`
(one row each, with its source), and in the Netherlands every deck, pier and abutment from the BGT and the deck's real
top from AHN's surface model (both CC0). List the archive in `buildingOverlays`; worlds from road version 7 build
their bridges from it.

```bash
python3 bridges.py <min lon> <min lat> <max lon> <max lat> data/<name>.pmtiles
python3 bridges.py --self-test
```

Central Delft (4.350, 52.008, 4.365, 52.016): 85 bridges, 58 with Wikidata, 79 surveyed parts, 2,062 measured deck
points. Västerbron and Tranebergsbron (17.99, 59.315, 18.04, 59.34): 42 bridges, 38 with Wikidata.

## Netherlands: water levels

The water boards' level areas in force (peilgebieden, PDOK's Waterschappen Waterbeheergebieden IMWA, CC BY-SA 4.0)
for a box, each clipped to it, with the level its decision fixes as cited in `water_levels.tsv` (the dataset has the
areas, not their levels). List the archive in `buildingOverlays`; worlds from water surface version 4 let mapped water
inside an area stand at its level. Being CC BY-SA, an archive made from it is shared alike, and credited.

```bash
python3 water_levels_nl.py <min lon> <min lat> <max lon> <max lat> data/<name>.pmtiles
python3 water_levels_nl.py --self-test
```

Central Delft (4.345, 52.004, 4.372, 52.020): the Delflandse boezem, NAP -0.43 m, over 4 tiles; 13 other areas there
have no cited level yet.
