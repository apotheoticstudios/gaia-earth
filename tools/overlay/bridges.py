"""Builds a Gaia overlay of bridges for a box, for Project Gaia: Earth: each bridge as it really is.

From OpenStreetMap (one Overpass query, ODbL): every bridge outline (man_made=bridge) and bridge way, with its
structure, opening, material and heights and its Wikidata item; from Wikidata (CC0): its type, material, length,
height, clearance, number of spans and longest span; from bridge_facts.tsv: cited numbers neither has; and in the
Netherlands from the BGT (Kadaster via PDOK, CC0) every deck, pier and abutment as surveyed, and from AHN (CC0) the
deck's real top, and on a truss bridge its trusses: where they stand and how high. Writes a PMTiles archive of z14
vector tiles with three layers:

    bridges         outlines: name, structure, movable (drawbridge, bascule, lift, swing, movable), material, length, height, clearance, spans, main_span,
                    span_count, width, layer, truss (its pattern: warren, pratt, howe, k, lattice; "deck" for a deck
                    truss), truss_height (its trusses' top over its deck, metres), wikidata, osm, sources ("" or 0 where
                    unknown; spans in metres, ";" between, from the north or west end)
    bridge_parts    decks, piers and abutments: kind (deck, pier, abutment, sill, and truss: a strip where a truss stands,
                    measured), movable, source
    bridge_heights  points every two metres on a measured deck: h, its top in metres above sea level (AHN, NAP)

    python3 bridges.py <min lon> <min lat> <max lon> <max lat> <out.pmtiles>
    python3 bridges.py --self-test
"""
import json
import math
import os
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib

from buildings_nl import rd_to_wgs84
from overlay import by_tile, encode_layer, mercator, write_pmtiles

OVERPASS = "https://overpass-api.de/api/interpreter"
WIKIDATA = "https://www.wikidata.org/w/api.php"
BGT = "https://api.pdok.nl/lv/bgt/ogc/v1/collections/overbruggingsdeel/items"
AHN = "https://service.pdok.nl/rws/ahn/wcs/v1_0"
AGENT = {"User-Agent": "gaia-earth-overlay (https://github.com/apotheoticstudios/gaia-earth)"}
FACTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bridge_facts.tsv")

STRUCTURES = {"beam": "beam", "girder": "beam", "arch": "arch", "tied-arch": "arch", "humpback": "humpback", "truss": "truss",
              "suspension": "suspension", "simple-suspension": "suspension", "cable-stayed": "cable_stayed",
              "cantilever": "cantilever", "floating": "floating", "trestle": "trestle"}
# A drawbridge lifts its leaf by chains or rods from balance beams overhead (the Dutch ophaalbrug); a bascule by a
# counterweight under the deck.
MOVABLE = {"bascule": "bascule", "drawbridge": "drawbridge", "lift": "lift", "swing": "swing", "transporter": "transporter",
           "submersible": "lift", "retractable": "swing", "tilt": "bascule"}
MATERIALS = {"steel": "steel", "metal": "steel", "iron": "iron", "concrete": "concrete", "reinforced_concrete": "concrete",
             "stone": "stone", "masonry": "stone", "brick": "brick", "wood": "wood", "timber": "wood"}
# Wikidata types and materials by their English label: the first keyword in a label decides.
TYPE_WORDS = [("suspension", ("suspension", "")), ("cable-stayed", ("cable_stayed", "")), ("truss", ("truss", "")),
              ("bascule", ("beam", "bascule")), ("drawbridge", ("beam", "drawbridge")), ("swing bridge", ("beam", "swing")),
              ("lift bridge", ("beam", "lift")), ("deck arch", ("arch", "")), ("through arch", ("arch", "")),
              ("arch", ("arch", "")), ("cantilever", ("cantilever", "")), ("girder", ("beam", "")), ("beam", ("beam", "")),
              ("viaduct", ("beam", ""))]
# Truss patterns and placements by Wikidata label (Pratt's variants: Parker, Baltimore, Pennsylvania, Whipple).
TRUSS_WORDS = [("warren", "warren"), ("pratt", "pratt"), ("parker", "pratt"), ("baltimore", "pratt"), ("pennsylvania", "pratt"),
               ("whipple", "pratt"), ("howe", "howe"), ("k truss", "k"), ("k-truss", "k"), ("lattice", "lattice"), ("deck truss", "deck")]
MATERIAL_WORDS = [("concrete", "concrete"), ("steel", "steel"), ("iron", "iron"), ("brick", "brick"), ("stone", "stone"),
                  ("granite", "stone"), ("limestone", "stone"), ("sandstone", "stone"), ("wood", "wood"), ("timber", "wood")]
# Default widths (metres) of a bridge way the map gives no width for, by highway class.
WIDTHS = {"motorway": 12, "trunk": 10, "primary": 9, "secondary": 8, "tertiary": 7, "residential": 6, "service": 4,
          "footway": 2.5, "cycleway": 3, "path": 2, "pedestrian": 4}


def fetch(url, data, accept):
    """One request's body. Some Pythons' TLS can't talk to some servers (overpass-api.de with macOS's LibreSSL): curl can."""
    try:
        req = urllib.request.Request(url, data=data, headers={**AGENT, "Accept": accept})
        return urllib.request.urlopen(req, timeout=180).read()
    except urllib.error.URLError as e:
        if "SSL" not in str(e.reason):
            raise
    command = ["curl", "-sfL", "-m", "180", "-A", AGENT["User-Agent"], "-H", "Accept: " + accept, url]
    if data is not None:
        command[1:1] = ["--data-binary", "@-"]
    run = subprocess.run(command, input=data, capture_output=True)
    if run.returncode != 0:
        raise OSError("curl %d for %s" % (run.returncode, url[:60]))
    return run.stdout


def get(url, data=None, accept="application/json", tries=6):
    """A URL's body, retried with backoff while the server is busy."""
    for attempt in range(tries):
        try:
            body = fetch(url, data, accept)
            if not body.lstrip().startswith(b"#") and b"Gateway Timeout" not in body[:200]:
                return body
        except OSError as e:
            if attempt == tries - 1:
                raise
            print("  retrying %s (%s)" % (url[:60], e), file=sys.stderr)
        time.sleep(15 * 2 ** attempt)
    raise OSError("no answer from " + url)


def overpass(bbox):
    """Every bridge outline, bridge way and bridge support in the box (min lon, min lat, max lon, max lat), with geometry."""
    w, s, e, n = bbox
    b = "%f,%f,%f,%f" % (s, w, n, e)
    q = ("[out:json][timeout:180];(way[man_made=bridge](%s);relation[man_made=bridge](%s);"
         "way[bridge][bridge!=no][highway](%s);way[bridge][bridge!=no][railway](%s);"
         "node['bridge:support'](%s);way['bridge:support'](%s););out geom;") % ((b,) * 6)
    return json.loads(get(OVERPASS, urllib.parse.urlencode({"data": q}).encode()))["elements"]


def number(v):
    """A tag's number in metres ("26", "26 m", "4,5"), or 0."""
    try:
        return float(str(v).split()[0].replace(",", ".").rstrip("m"))
    except (ValueError, IndexError):
        return 0.0


def describe(tags):
    """What a map element says about a bridge: structure, movable, material, height, width, layer, name, wikidata."""
    movable = MOVABLE.get(tags.get("bridge:movable", ""), "movable" if tags.get("bridge") == "movable" else "")
    return {"structure": STRUCTURES.get(tags.get("bridge:structure", ""), ""), "movable": movable,
            "material": MATERIALS.get(tags.get("material", "").replace(" ", "_"), ""), "height": number(tags.get("height", 0)),
            "width": number(tags.get("width", 0)), "layer": number(tags.get("layer", 0)), "name": tags.get("name", ""),
            "wikidata": tags.get("wikidata", "")}


def merge(into, more):
    """Fills what a description lacks from another."""
    for k, v in more.items():
        if v and not into.get(k):
            into[k] = v
    return into


def ring_of(geometry):
    return [(p["lon"], p["lat"]) for p in geometry]


def middle(line):
    """The middle of a line of (x, y) points: of its middle segment (a two-point way's ends lie on its bridge's outline)."""
    a, b = line[(len(line) - 1) // 2], line[len(line) // 2]
    return (a[0] + b[0]) / 2, (a[1] + b[1]) / 2


def inside(x, y, ring):
    """Whether (x, y) lies in a ring of (x, y) points, by ray casting."""
    hit = False
    for i in range(len(ring)):
        x0, y0 = ring[i - 1]
        x1, y1 = ring[i]
        if (y0 > y) != (y1 > y) and x < x0 + (y - y0) * (x1 - x0) / (y1 - y0):
            hit = not hit
    return hit


def strip(line, width):
    """A way's line (lon, lat points) widened to a polygon {width} metres across, for a bridge with no outline."""
    lat0 = sum(p[1] for p in line) / len(line)
    kx, ky = 111320 * math.cos(math.radians(lat0)), 110540
    pts = [(p[0] * kx, p[1] * ky) for p in line]
    left, right = [], []
    for i, (x, y) in enumerate(pts):
        a, b = pts[max(0, i - 1)], pts[min(len(pts) - 1, i + 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        d = math.hypot(dx, dy) or 1
        nx, ny = -dy / d * width / 2, dx / d * width / 2
        left.append(((x + nx) / kx, (y + ny) / ky))
        right.append(((x - nx) / kx, (y - ny) / ky))
    return left + right[::-1] + [left[0]]


def wikidata(ids):
    """For each item: what Wikidata says of its type, material and sizes (metres)."""
    out, labels = {}, {}
    ids = sorted(set(ids))
    claims = {}
    for i in range(0, len(ids), 50):
        page = json.loads(get(WIKIDATA + "?" + urllib.parse.urlencode({"action": "wbgetentities", "ids": "|".join(ids[i:i + 50]),
                                                                       "props": "claims", "format": "json"})))
        claims.update({q: e.get("claims", {}) for q, e in page.get("entities", {}).items()})
    targets = sorted({c["mainsnak"]["datavalue"]["value"]["id"] for cl in claims.values() for p in ("P31", "P186")
                      for c in cl.get(p, []) if c["mainsnak"].get("datavalue")})
    for i in range(0, len(targets), 50):
        page = json.loads(get(WIKIDATA + "?" + urllib.parse.urlencode({"action": "wbgetentities", "ids": "|".join(targets[i:i + 50]),
                                                                       "props": "labels", "languages": "en", "format": "json"})))
        labels.update({q: e.get("labels", {}).get("en", {}).get("value", "") for q, e in page.get("entities", {}).items()})
    for q, cl in claims.items():
        out[q] = from_claims(cl, labels)
    return out


def from_claims(cl, labels):
    """A description from an item's claims and the English labels of the items they name."""
    def metres(p):
        for c in cl.get(p, []):
            v = c["mainsnak"].get("datavalue", {}).get("value", {})
            if isinstance(v, dict) and v.get("unit", "").endswith("/Q11573"):
                return abs(float(v["amount"]))
        return 0.0

    def count(p):
        for c in cl.get(p, []):
            v = c["mainsnak"].get("datavalue", {}).get("value", {})
            if isinstance(v, dict) and "amount" in v:
                return float(v["amount"])
        return 0.0

    d = {"structure": "", "movable": "", "material": "", "length": metres("P2043"), "height": metres("P2048"),
         "clearance": metres("P2793"), "main_span": metres("P2787"), "width": metres("P2049"), "span_count": count("P1314")}
    for c in cl.get("P31", []):
        label = labels.get(c["mainsnak"].get("datavalue", {}).get("value", {}).get("id", ""), "").lower()
        hit = next((v for k, v in TYPE_WORDS if k in label), None)
        if hit:
            d["structure"] = d["structure"] or hit[0]
            d["movable"] = d["movable"] or hit[1]
        d["truss"] = " ".join(dict.fromkeys(d.get("truss", "").split() + [v for k, v in TRUSS_WORDS if k in label]))
    for c in cl.get("P186", []):
        label = labels.get(c["mainsnak"].get("datavalue", {}).get("value", {}).get("id", ""), "").lower()
        d["material"] = d["material"] or next((v for k, v in MATERIAL_WORDS if k in label), "")
    return d


def facts(path=FACTS):
    """bridge_facts.tsv: {match: {field: value}}, each row a cited number or word."""
    out = {}
    with open(path, encoding="utf-8") as f:
        next(f)
        for line in f:
            if line.strip():
                match, key, value, _ = line.rstrip("\n").split("\t", 3)
                out.setdefault(match, {})[key] = value if key in ("structure", "material", "movable", "spans", "truss") else float(value)
    return out


def wgs84_to_rd(lat, lon):
    """WGS84 to RD New, inverting rd_to_wgs84 by Newton's method (within its own metre)."""
    x, y = 155000.0, 463000.0
    for _ in range(8):
        la, lo = rd_to_wgs84(x, y)
        y += (lat - la) * 111200
        x += (lon - lo) * 111200 * math.cos(math.radians(lat))
    return x, y


def geotiff(blob):
    """A one-band float32 GeoTIFF (deflate or none, predictor 1 or 3) as (rows, x0, y0, cell), rows top first."""
    e = "<" if blob[:2] == b"II" else ">"
    off = struct.unpack(e + "I", blob[4:8])[0]
    tags = {}
    for i in range(struct.unpack(e + "H", blob[off:off + 2])[0]):
        tag, typ, cnt, val = struct.unpack(e + "HHII", blob[off + 2 + 12 * i:off + 14 + 12 * i])
        size = {3: 2, 4: 4, 12: 8, 2: 1}.get(typ, 4)
        raw = blob[off + 10 + 12 * i:off + 14 + 12 * i] if size * cnt <= 4 else blob[val:val + size * cnt]
        fmt = {3: "H", 4: "I", 12: "d"}.get(typ)
        tags[tag] = raw if fmt is None else struct.unpack(e + fmt * cnt, raw[:size * cnt])
    width, height = tags[256][0], tags[257][0]
    per_strip, predictor = tags.get(278, (height,))[0], tags.get(317, (1,))[0]
    data = b"".join(zlib.decompress(blob[o:o + n]) if tags[259][0] == 8 else blob[o:o + n] for o, n in zip(tags[273], tags[279]))
    rows = []
    for r in range(height):
        row = bytearray(data[r * width * 4:(r + 1) * width * 4])
        if predictor == 3:
            for i in range(1, len(row)):
                row[i] = (row[i] + row[i - 1]) & 0xFF
            row = bytes(b for i in range(width) for b in (row[i], row[width + i], row[2 * width + i], row[3 * width + i]))
            rows.append(struct.unpack(">%df" % width, row))
        else:
            rows.append(struct.unpack(e + "%df" % width, bytes(row)))
    scale, tie = tags[33550], tags[33922]
    return rows, tie[3], tie[4], scale[0]


def deck_heights(ring):
    """AHN's surface model on a deck (a ring of lon, lat points): a point every two metres, (lon, lat, metres NAP), each
    the lowest quarter of the half-metre cells in its square, so a car or a lamp on the deck doesn't lift it, and where
    those cells are (on a deck's edge a square's middle may be off the deck)."""
    (rows, gx, gy, cell), rd = dsm(ring)
    bins = {}
    for r, row in enumerate(rows):
        for c, h in enumerate(row):
            x, y = gx + (c + 0.5) * cell, gy - (r + 0.5) * cell
            if -100 < h < 400 and inside(x, y, rd):
                bins.setdefault((math.floor(x / 2), math.floor(y / 2)), []).append((h, x, y))
    out = []
    for _, cells in sorted(bins.items()):
        hs = sorted(c[0] for c in cells)
        lat, lon = rd_to_wgs84(sum(c[1] for c in cells) / len(cells), sum(c[2] for c in cells) / len(cells))
        out.append((lon, lat, round(hs[len(hs) // 4], 2)))
    return out


def dsm(ring):
    """AHN's half-metre surface model round a ring of lon, lat points, as geotiff() gives it, and the ring in RD New."""
    rd = [wgs84_to_rd(lat, lon) for lon, lat in ring]
    x0, y0 = math.floor(min(p[0] for p in rd)) - 1, math.floor(min(p[1] for p in rd)) - 1
    x1, y1 = math.ceil(max(p[0] for p in rd)) + 1, math.ceil(max(p[1] for p in rd)) + 1
    url = AHN + "?" + urllib.parse.urlencode({"SERVICE": "WCS", "VERSION": "1.0.0", "REQUEST": "GetCoverage", "COVERAGE": "dsm_05m",
                                              "CRS": "EPSG:28992", "BBOX": "%d,%d,%d,%d" % (x0, y0, x1, y1),
                                              "WIDTH": (x1 - x0) * 2, "HEIGHT": (y1 - y0) * 2, "FORMAT": "GEOTIFF"})
    return geotiff(get(url, accept="image/tiff")), rd


def along(line, x, y):
    """Where (x, y) lies by a line of (x, y) points: metres along it to its nearest point, and metres across (left positive)."""
    best, start = (float("inf"), 0.0, 0.0), 0.0
    for (ax, ay), (bx, by) in zip(line, line[1:]):
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy) or 1e-12
        u = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / length ** 2))
        d = math.hypot(x - ax - u * dx, y - ay - u * dy)
        if d < best[0]:
            best = (d, start + u * length, math.copysign(d, dx * (y - ay) - dy * (x - ax)))
        start += length
    return best[1], best[2]


def point_at(line, s, t):
    """The point s metres along a line of (x, y) points and t across it (left positive)."""
    for (ax, ay), (bx, by) in zip(line, line[1:]):
        length = math.hypot(bx - ax, by - ay) or 1e-12
        if s <= length or (bx, by) == line[-1]:
            return ax + (bx - ax) * s / length - (by - ay) / length * t, ay + (by - ay) * s / length + (bx - ax) / length * t
        s -= length


def trusses(cells, step=2.0):
    """Where a truss bridge's trusses stand and how high, from surface heights over it: cells (s, t, h), s metres along its
    way, t across it. Its deck at each step along it is the median there. Its trusses stand where the surface reads 4 m over
    that for 20 m or more (a catenary portal or a lamp is a step or two); their planes are where across, over those steps,
    the median reads 1.5 m or more over the deck; their top is the tenth highest of the steps' highest. Returns (from, to,
    [(across, width)], top over the deck), metres, or None."""
    steps = {}
    for s, t, h in cells:
        steps.setdefault(math.floor(s / step), []).append((t, h))
    deck = {k: sorted(h for _, h in v)[len(v) // 2] for k, v in steps.items()}
    high = sorted(k for k, v in steps.items() if sum(h > deck[k] + 4 for _, h in v) >= 4)
    best, run = [], []
    for k in high:
        run = run + [k] if run and k - run[-1] <= 2 else [k]
        if not best or run[-1] - run[0] > best[-1] - best[0]:
            best = run
    if not best or (best[-1] - best[0] + 1) * step < 20:
        return None
    over = range(best[0], best[-1] + 1)
    across = {}
    for k in over:
        for t, h in steps.get(k, []):
            across.setdefault(math.floor(t / 0.5), []).append(h - deck[k])
    planes, group = [], []
    for b in sorted(b for b, v in across.items() if sorted(v)[len(v) // 2] >= 1.5) + [None]:
        if group and (b is None or b - group[-1] > 1):
            planes.append(((group[0] + group[-1] + 1) * 0.25, (group[-1] - group[0] + 1) * 0.5))
            group = []
        group += [] if b is None else [b]
    tops = sorted(max(h for _, h in steps[k]) - deck[k] for k in over if k in steps)
    return best[0] * step, (best[-1] + 1) * step, planes, round(tops[int(len(tops) * 0.9)], 2)


def measure_trusses(ring, axis):
    """A truss bridge's trusses in AHN's surface model over its outline (a ring of lon, lat points), along its axis (a line of
    lon, lat points, its track or road): strips where they stand (rings of lon, lat points) and their top over its deck in
    metres; ([], 0) where it reads none."""
    (rows, gx, gy, cell), rd = dsm(ring)
    line = [wgs84_to_rd(lat, lon) for lon, lat in axis]
    cells = []
    for r, row in enumerate(rows):
        for c, h in enumerate(row):
            x, y = gx + (c + 0.5) * cell, gy - (r + 0.5) * cell
            if -100 < h < 400 and inside(x, y, rd):
                cells.append(along(line, x, y) + (h,))
    found = trusses(cells)
    if found is None:
        return [], 0.0
    s0, s1, planes, top = found
    strips = [strip([rd_to_wgs84(*point_at(line, s0 + (s1 - s0) * i / 20, t))[::-1] for i in range(21)], max(1.0, width))
              for t, width in planes]
    return strips, top


def in_netherlands(bbox):
    return bbox[0] > 3.2 and bbox[2] < 7.3 and bbox[1] > 50.7 and bbox[3] < 53.6


def bgt_parts(bbox):
    """The BGT's bridge parts in the box: [(rings of lon, lat, kind, movable, id)], current ones only."""
    kinds = {"dek": "deck", "pijler": "pier", "landhoofd": "abutment", "sloof": "sill"}
    url, out = "%s?f=json&limit=1000&bbox=%s" % (BGT, ",".join(str(v) for v in bbox)), []
    while url:
        page = json.loads(get(url, accept="application/geo+json"))
        for f in page["features"]:
            p, g = f["properties"], f.get("geometry")
            kind = kinds.get(p.get("type_overbruggingsdeel"))
            if kind is None or p.get("eind_registratie") or not g:
                continue
            polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]] if g["type"] == "Polygon" else []
            for poly in polys:
                out.append(([[(c[0], c[1]) for c in ring] for ring in poly], kind, str(p.get("overbrugging_is_beweegbaar")) == "true",
                            p.get("lokaal_id", "")))
        url = next((l["href"] for l in page.get("links", []) if l.get("rel") == "next"), None)
    return out


def main(min_lon, min_lat, max_lon, max_lat, out):
    bbox = (min_lon, min_lat, max_lon, max_lat)
    elements = overpass(bbox)
    outlines, ways, supports = [], [], []
    for el in elements:
        t = el.get("tags", {})
        if t.get("man_made") == "bridge" and el["type"] == "way" and len(el.get("geometry", [])) >= 4:
            outlines.append((ring_of(el["geometry"]), describe(t), "osm:way/%d" % el["id"]))
        elif t.get("man_made") == "bridge" and el["type"] == "relation":
            for m in el.get("members", []):
                if m.get("role") == "outer" and len(m.get("geometry", [])) >= 4:
                    outlines.append((ring_of(m["geometry"]), describe(t), "osm:relation/%d" % el["id"]))
        elif "bridge:support" in t and el["type"] == "node":
            supports.append(([[(el["lon"], el["lat"])]], "pier", False, "osm:node/%d" % el["id"]))
        elif "bridge:support" in t and len(el.get("geometry", [])) >= 4:
            supports.append(([ring_of(el["geometry"])], "pier", False, "osm:way/%d" % el["id"]))
        elif el["type"] == "way" and len(el.get("geometry", [])) >= 2:
            ways.append((ring_of(el["geometry"]), describe(t), t, "osm:way/%d" % el["id"]))
    # A way on an outline tells the outline what it lacks; a way with something to say and no outline gets one of its own.
    for line, d, tags, osm in ways:
        mid = middle(line)
        home = next((o for o in outlines if inside(mid[0], mid[1], o[0])), None)
        if home is not None:
            merge(home[1], d)
        elif d["structure"] or d["movable"] or d["wikidata"] or d["material"]:
            width = d["width"] or WIDTHS.get(tags.get("highway", ""), 5 if "railway" in tags else 6)
            outlines.append((strip(line, width), d, osm))
    known = wikidata([d["wikidata"] for _, d, _ in outlines if d["wikidata"]])
    cited = facts()
    bgt = bgt_parts(bbox) if in_netherlands(bbox) else []
    parts = list(supports)
    heights = []
    features = []
    for ring, d, osm in outlines:
        sources = [osm]
        wd = known.get(d["wikidata"])
        if wd:
            merge(d, wd)
            sources.append("wikidata:" + d["wikidata"])
        fact = cited.get("wikidata:" + d["wikidata"]) or cited.get(osm)
        if fact:
            d.update(fact)  # cited numbers win
            sources.append("bridge_facts.tsv")
        if d["structure"] == "truss" and in_netherlands(bbox):
            # Its tracks (or else its ways): the trusses measured along the longest, its deck along them where the BGT has none.
            on = [(l, tags) for l, _, tags, _ in ways if inside(*middle(l), ring)]
            tracks = [l for l, tags in on if "railway" in tags] or [l for l, _ in on]
            if tracks:
                strips, top = measure_trusses(ring, max(tracks, key=lambda l: sum(math.dist(a, b) for a, b in zip(l, l[1:]))))
                if top:
                    d["truss_height"] = top  # measured wins
                    sources.append("ahn")
                    parts += [([s], "truss", False, "ahn:" + osm) for s in strips]
                if not any(kind == "deck" and inside(rings[0][0][0], rings[0][0][1], ring) for rings, kind, _, _ in bgt):
                    for l in tracks:
                        heights += deck_heights(strip(l, 3))
        props = {k: d.get(k, "") or ("" if k in ("name", "structure", "movable", "material", "spans", "truss", "wikidata") else 0.0)
                 for k in ("name", "structure", "movable", "material", "length", "height", "clearance", "spans", "main_span",
                           "span_count", "width", "layer", "truss", "truss_height", "wikidata")}
        props = {k: (float(v) if isinstance(v, (int, float)) else str(v)) for k, v in props.items()}
        props.update({"osm": osm, "sources": "; ".join(sources)})
        features.append(([[mercator(lat, lon) for lon, lat in ring]], props))
    parts += bgt
    for rings, kind, _, _ in bgt:
        if kind == "deck":
            heights += deck_heights(rings[0])
    part_features = [([[mercator(lat, lon) for lon, lat in ring] for ring in rings], {"kind": kind, "movable": "yes" if movable else "",
                                                                                   "source": source}) for rings, kind, movable, source in parts]
    height_features = [([[mercator(lat, lon)]], {"h": h}) for lon, lat, h in heights]
    tiles = {}
    for name, feats in (("bridges", features), ("bridge_parts", part_features), ("bridge_heights", height_features)):
        if feats:
            per_tile, _ = by_tile(feats)
            for k, v in per_tile.items():
                tiles[k] = tiles.get(k, b"") + encode_layer(name, v, *k)
    text = {"name": "String", "structure": "String", "movable": "String", "material": "String", "spans": "String",
            "truss": "String", "wikidata": "String", "osm": "String", "sources": "String"}
    layers = {"bridges": {**text, **{k: "Number" for k in ("length", "height", "clearance", "main_span", "span_count", "width",
                                                                                            "layer", "truss_height")}},
              "bridge_parts": {"kind": "String", "movable": "String", "source": "String"}, "bridge_heights": {"h": "Number"}}
    write_pmtiles(out, tiles, bbox, layers, "OpenStreetMap contributors (ODbL); Wikidata (CC0); Kadaster BGT (CC0); AHN (CC0)")
    print("%d bridges (%d with Wikidata), %d parts, %d measured deck points in %d z14 tiles, %.2f MB"
          % (len(features), sum(1 for _, d, _ in outlines if d.get("wikidata") in known), len(parts), len(heights), len(tiles),
             sum(map(len, tiles.values())) / 1e6))


def self_test():
    assert describe({"bridge": "yes", "bridge:structure": "humpback", "layer": "1"})["structure"] == "humpback"
    assert describe({"bridge": "movable", "bridge:movable": "drawbridge"})["movable"] == "drawbridge"
    assert describe({"bridge": "movable"})["movable"] == "movable" and describe({"material": "reinforced concrete"})["material"] == "concrete"
    assert number("26 m") == 26 and number("4,5") == 4.5 and number("x") == 0
    labels = {"Q1": "arch bridge", "Q2": "steel", "Q3": "bascule bridge", "Q4": "reinforced concrete", "Q5": "double-beam drawbridge"}
    snak = lambda q: {"mainsnak": {"datavalue": {"value": {"id": q}}}}
    metre = lambda v: {"mainsnak": {"datavalue": {"value": {"amount": "+%s" % v, "unit": "http://www.wikidata.org/entity/Q11573"}}}}
    d = from_claims({"P31": [snak("Q1")], "P186": [snak("Q2")], "P2043": [metre(600)], "P2793": [metre(26)]}, labels)
    assert d["structure"] == "arch" and d["material"] == "steel" and d["length"] == 600 and d["clearance"] == 26
    d = from_claims({"P31": [snak("Q3")], "P186": [snak("Q4")]}, labels)
    assert d["structure"] == "beam" and d["movable"] == "bascule" and d["material"] == "concrete"
    assert from_claims({"P31": [snak("Q5")]}, labels)["movable"] == "drawbridge"
    assert merge({"structure": "arch", "material": ""}, {"structure": "beam", "material": "steel"}) == {"structure": "arch", "material": "steel"}
    square = [(0, 0), (2, 0), (2, 2), (0, 2)]
    assert inside(1, 1, square) and not inside(3, 1, square)
    x, y = wgs84_to_rd(*rd_to_wgs84(84500, 447000))
    assert abs(x - 84500) < 0.5 and abs(y - 447000) < 0.5, (x, y)
    poly = strip([(4.35, 52.01), (4.351, 52.01)], 6)
    assert inside(4.3505, 52.01, poly) and not inside(4.3505, 52.0101, poly)
    # A 2 x 1 float32 GeoTIFF, deflated with the floating-point predictor: values 1.5 and -2.25.
    raw = struct.pack(">2f", 1.5, -2.25)
    planes = bytearray(raw[i] for k in range(4) for i in (k, 4 + k))
    planes = bytes([planes[0]] + [(planes[i] - planes[i - 1]) & 0xFF for i in range(1, 8)])
    strip_data = zlib.compress(planes)
    entries = [(256, 3, 1, 2), (257, 3, 1, 1), (258, 3, 1, 32), (259, 3, 1, 8), (273, 4, 1, 0), (278, 3, 1, 1), (279, 4, 1, len(strip_data)),
               (317, 3, 1, 3), (339, 3, 1, 3), (33550, 12, 3, 0), (33922, 12, 6, 0)]
    head = 8 + 2 + 12 * len(entries) + 4
    extra = struct.pack("<3d", 0.5, 0.5, 0) + struct.pack("<6d", 0, 0, 0, 1000, 2000, 0)
    body = b""
    for tag, typ, cnt, val in entries:
        if tag == 273:
            val = head + len(extra)
        elif tag == 33550:
            val = head
        elif tag == 33922:
            val = head + 24
        body += struct.pack("<HHII", tag, typ, cnt, val)
    blob = b"II*\x00" + struct.pack("<I", 8) + struct.pack("<H", len(entries)) + body + b"\x00" * 4 + extra + strip_data
    rows, gx, gy, cell = geotiff(blob)
    assert rows == [(1.5, -2.25)] and (gx, gy, cell) == (1000, 2000, 0.5), (rows, gx, gy, cell)
    assert facts()["wikidata:Q580518"]["spans"] == "168;204"
    d = from_claims({"P31": [snak("Q6")]}, {"Q6": "Warren truss bridge"})
    assert d["structure"] == "truss" and d["truss"] == "warren", d
    # A 100 m deck at 15 m, a truss over 30 to 70 m with its planes 5 m either side, 10 m high, and a portal at 10 m.
    cells = [(s / 2, t / 2, 15.0) for s in range(200) for t in range(-16, 17)]
    cells += [(s / 2, t / 2, 25.0 if t in (-10, 10) else 20.0) for s in range(60, 140) for t in (-11, -10, -9, 9, 10, 11)]
    cells += [(10.0 + s / 2, t / 2, 21.0) for s in range(2) for t in range(-16, 17)]
    s0, s1, planes, top = trusses(cells)
    assert (s0, s1, top) == (30, 70, 10) and [round(t) for t, _ in planes] == [-5, 5], (s0, s1, planes, top)
    line = [(0, 0), (10, 0), (10, 10)]
    assert middle(line) == (10, 0) and middle([(0, 0), (4, 2)]) == (2, 1)
    assert along(line, 5, 1) == (5, 1) and along(line, 11, 5) == (15, -1) and point_at(line, 15, 1) == (9, 5)
    print("self-test passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 6:
        main(*[float(v) for v in sys.argv[1:5]], sys.argv[5])
    else:
        sys.exit(__doc__)
