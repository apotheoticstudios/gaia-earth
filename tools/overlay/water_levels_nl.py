"""Converts the Dutch water boards' level areas into a Gaia overlay of water levels, for Project Gaia: Earth.

Reads the areas whose water level a water board fixes (peilgebieden, in force: "Vigerend") in a box from PDOK's OGC API
for the water boards' areas (Waterschappen Waterbeheergebieden IMWA, CC BY-SA 4.0). The dataset has the areas but not
their levels, which are in each area's level decision (peilbesluit): water_levels.tsv cites a level for each area code,
and only areas with one are written. Each is clipped to the box, so a big area (the Delflandse boezem is 183 km2) costs
only the tiles asked for. One layer, "water_levels": each area with

    level   its water level, metres above NAP
    code    the water board's code for it
    source  where the level is cited

The mod (water surface version 4) lets mapped water inside an area stand at its level.

    python3 water_levels_nl.py <min lon> <min lat> <max lon> <max lat> <out.pmtiles>
    python3 water_levels_nl.py --self-test
"""
import json
import os
import sys
import urllib.request

from overlay import by_tile, encode_layer, mercator, write_pmtiles

API = "https://api.pdok.nl/hwh/waterschappen-waterbeheergebieden-imwa/ogc/v1/collections/peilgebied/items"
LEVELS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "water_levels.tsv")


def levels(path=LEVELS):
    """water_levels.tsv: {code: (metres above NAP, source)}."""
    out = {}
    with open(path, encoding="utf-8") as f:
        next(f)
        for line in f:
            if line.strip():
                code, level, source = line.rstrip("\n").split("\t", 2)
                out[code] = (float(level), source)
    return out


def fetch(bbox):
    """Every level area in force in the box (min lon, min lat, max lon, max lat), page by page."""
    url = "%s?f=json&limit=1000&bbox=%s" % (API, ",".join(str(v) for v in bbox))
    while url:
        page = json.loads(urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "gaia-earth-overlay",
                                                                                         "Accept": "application/geo+json"}), timeout=120).read())
        yield from (f for f in page["features"] if f["properties"].get("statuspeilgebied") == "Vigerend")
        url = next((l["href"] for l in page.get("links", []) if l.get("rel") == "next"), None)


def clip(ring, bbox):
    """A ring of (lon, lat) clipped to the box, Sutherland-Hodgman; [] where nothing is left."""
    w, s, e, n = bbox
    for inside, cross in ((lambda p: p[0] >= w, lambda a, b: (w, a[1] + (b[1] - a[1]) * (w - a[0]) / (b[0] - a[0]))),
                          (lambda p: p[0] <= e, lambda a, b: (e, a[1] + (b[1] - a[1]) * (e - a[0]) / (b[0] - a[0]))),
                          (lambda p: p[1] >= s, lambda a, b: (a[0] + (b[0] - a[0]) * (s - a[1]) / (b[1] - a[1]), s)),
                          (lambda p: p[1] <= n, lambda a, b: (a[0] + (b[0] - a[0]) * (n - a[1]) / (b[1] - a[1]), n))):
        out = []
        for i, b in enumerate(ring):
            a = ring[i - 1]
            if inside(b):
                if not inside(a):
                    out.append(cross(a, b))
                out.append(b)
            elif inside(a):
                out.append(cross(a, b))
        ring = out
        if not ring:
            return []
    return ring + [ring[0]] if ring[0] != ring[-1] else ring


def main(min_lon, min_lat, max_lon, max_lat, out):
    bbox = (min_lon, min_lat, max_lon, max_lat)
    cited = levels()
    features, seen, skipped = [], 0, set()
    for f in fetch(bbox):
        seen += 1
        code = f["properties"].get("code", "")
        if code not in cited:
            skipped.add(code)
            continue
        g = f["geometry"]
        for poly in g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]:
            rings = [clip([(c[0], c[1]) for c in ring], bbox) for ring in poly]
            if rings[0]:
                features.append(([[mercator(lat, lon) for lon, lat in r] for r in rings if r],
                                 {"level": cited[code][0], "code": code, "source": cited[code][1]}))
    per_tile, _ = by_tile(features)
    tiles = {k: encode_layer("water_levels", v, *k) for k, v in per_tile.items()}
    write_pmtiles(out, tiles, bbox, {"water_levels": {"level": "Number", "code": "String", "source": "String"}},
                  "Waterschappen via PDOK (IMWA, CC BY-SA 4.0); levels as cited")
    print("%d level areas in force, %d with a cited level, in %d z14 tiles, %.2f MB; no level cited for: %s"
          % (seen, len(features), len(tiles), sum(map(len, tiles.values())) / 1e6, ", ".join(sorted(skipped)) or "none"))


def self_test():
    square = [(0, 0), (4, 0), (4, 4), (0, 4), (0, 0)]
    clipped = clip(square, (1, 1, 2, 2))
    assert sorted(set(clipped)) == [(1, 1), (1, 2), (2, 1), (2, 2)], clipped
    assert clip(square, (5, 5, 6, 6)) == []
    assert clip(square, (-1, -1, 5, 5)) == square
    assert levels()["GPG2019BZM 1"][0] == -0.43
    print("self-test passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 6:
        main(*[float(v) for v in sys.argv[1:5]], sys.argv[5])
    else:
        sys.exit(__doc__)
