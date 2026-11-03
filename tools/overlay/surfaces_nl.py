"""Converts the Dutch large-scale base map's roads and paths into a Gaia overlay of surfaces, for Project Gaia: Earth.

Reads the BGT (Basisregistratie Grootschalige Topografie, Kadaster via PDOK, CC0) "wegdeel" polygons in a box from
PDOK's OGC API: every carriageway, pavement, cycle path, square, step and parking space as surveyed, to the
centimetre, with what it is paved with. Keeps the current ones on the ground (not bridges or tunnels), and writes a
PMTiles archive of z14 vector tiles with one layer, "surfaces": each polygon with

    kind      road, footway, cycleway, parking or steps
    material  asphalt, concrete, bricks, pavers, tiles, gravel or unpaved
    id        the BGT object id

The mod draws them over the ways it draws from OpenStreetMap: a road as wide as surveyed, its pavements beside it,
cycle paths and squares where they are (see Surfaces).

    python3 surfaces_nl.py <min lon> <min lat> <max lon> <max lat> <out.pmtiles>
    python3 surfaces_nl.py --self-test
"""
import json
import sys
import urllib.request

from overlay import by_tile, encode_layer, mercator, write_pmtiles

API = "https://api.pdok.nl/lv/bgt/ogc/v1/collections/wegdeel/items"

KINDS = {"rijbaan lokale weg": "road", "rijbaan regionale weg": "road", "rijbaan autoweg": "road", "rijbaan autosnelweg": "road",
         "inrit": "road", "woonerf": "road", "baan voor vliegverkeer": "road", "voetpad": "footway", "voetgangersgebied": "footway",
         "fietspad": "cycleway", "ruiterpad": "footway", "parkeervlak": "parking", "voetpad op trap": "steps"}


def material(physical, detail):
    """What a surface is paved with, from BGT's fysiek voorkomen and its plus detail."""
    detail = (detail or "").lower()
    if "klinker" in detail:
        return "bricks"
    if "tegel" in detail:
        return "tiles"
    if "betonstraatsteen" in detail or "sierbestrating" in detail or "natuursteen" in detail:
        return "pavers"
    if "beton" in detail:
        return "concrete"
    return {"gesloten verharding": "asphalt", "open verharding": "pavers", "half verhard": "gravel"}.get(physical, "unpaved")


def surface(props):
    """A wegdeel's (kind, material), or None for one not drawn: ended, off the ground, or rails."""
    if props.get("eind_registratie") or str(props.get("relatieve_hoogteligging", 0)) not in ("0", "None"):
        return None
    kind = KINDS.get(props.get("functie"))
    return None if kind is None else (kind, material(props.get("fysiek_voorkomen"), props.get("plus_fysiek_voorkomen")))


def fetch(bbox):
    """Every wegdeel feature in the box (min lon, min lat, max lon, max lat), page by page."""
    url = "%s?f=json&limit=1000&bbox=%s" % (API, ",".join(str(v) for v in bbox))
    while url:
        page = json.loads(urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "gaia-earth-overlay",
                                                                                         "Accept": "application/geo+json"}), timeout=120).read())
        yield from page["features"]
        url = next((l["href"] for l in page.get("links", []) if l.get("rel") == "next"), None)


def polygons(geometry):
    coords = geometry["coordinates"]
    return coords if geometry["type"] == "MultiPolygon" else [coords] if geometry["type"] == "Polygon" else []


def main(min_lon, min_lat, max_lon, max_lat, out):
    features, seen = [], 0
    for f in fetch((min_lon, min_lat, max_lon, max_lat)):
        seen += 1
        s = surface(f["properties"])
        if s is None or not f.get("geometry"):
            continue
        for poly in polygons(f["geometry"]):
            rings = [[mercator(lat, lon) for lon, lat, *_ in ring] for ring in poly]
            features.append((rings, {"kind": s[0], "material": s[1], "id": f["properties"].get("lokaal_id", "")}))
    per_tile, bounds = by_tile(features)
    tiles = {k: encode_layer("surfaces", v, *k) for k, v in per_tile.items()}
    write_pmtiles(out, tiles, bounds, {"surfaces": {"kind": "String", "material": "String", "id": "String"}}, "Kadaster, BGT, CC0")
    print("%d of %d road parts in %d z14 tiles, %.1f MB" % (len(features), seen, len(tiles), sum(map(len, tiles.values())) / 1e6))


def self_test():
    assert surface({"functie": "voetpad", "fysiek_voorkomen": "open verharding", "plus_fysiek_voorkomen": "gebakken klinkers"}) == ("footway", "bricks")
    assert surface({"functie": "fietspad", "fysiek_voorkomen": "gesloten verharding"}) == ("cycleway", "asphalt")
    assert surface({"functie": "voetpad", "eind_registratie": "2020-04-21T10:09:24Z"}) is None
    assert surface({"functie": "rijbaan lokale weg", "fysiek_voorkomen": "open verharding", "relatieve_hoogteligging": 1}) is None
    assert surface({"functie": "spoorbaan"}) is None
    print("self-test passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 6:
        main(*[float(v) for v in sys.argv[1:5]], sys.argv[5])
    else:
        sys.exit(__doc__)
