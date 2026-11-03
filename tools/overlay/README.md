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
