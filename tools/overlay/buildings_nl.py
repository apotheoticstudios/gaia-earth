"""Converts Dutch measured building heights into a Gaia overlay of buildings, for Project Gaia: Earth.

Reads a 3D Basisvoorziening "hoogtestatistieken gebouwen" GeoPackage (Kadaster, CC BY 4.0): every BAG building's
footprint with heights reconstructed from AHN lidar and aerial imagery by 3DBAG's roofer -- ground, eaves, ridge,
height percentiles and roof type, in metres above NAP, in RD New (EPSG:28992) coordinates. Writes a PMTiles archive
of z14 vector tiles, the grid of the mod's map tiles, with one layer, "buildings": each building's footprint (whole,
in every tile it touches) with

    height       metres from the ground to the top: the ridge of a sloped roof, else the 70th percentile of the roof
    roof_height  metres from the eaves to the ridge of a sloped roof, 0 for a flat one
    roof         "slanted" or "flat"
    id           the BAG building id
    source       "3D Basisvoorziening <year>"

The mod takes these heights over OpenStreetMap's and Overture's wherever a footprint matches (see OsmDetail).
Buildings whose reconstruction failed are left out.

    python3 buildings_nl.py <hoogtestatistieken.gpkg> <year> <out.pmtiles>
    python3 buildings_nl.py --self-test
"""
import json
import math
import sqlite3
import struct
import sys

ZOOM = 14
EXTENT = 4096


def rd_to_wgs84(x, y):
    """RD New to WGS84 latitude and longitude, by the published polynomial approximation (within about a metre)."""
    dx, dy = (x - 155000) * 1e-5, (y - 463000) * 1e-5
    k = ((0, 1, 3235.65389), (2, 0, -32.58297), (0, 2, -0.24750), (2, 1, -0.84978), (0, 3, -0.06550), (2, 2, -0.01709),
         (1, 0, -0.00738), (4, 0, 0.00530), (2, 3, -0.00039), (4, 1, 0.00033), (1, 1, -0.00012))
    l = ((1, 0, 5260.52916), (1, 1, 105.94684), (1, 2, 2.45656), (3, 0, -0.81885), (1, 3, 0.05594), (3, 1, -0.05607),
         (0, 1, 0.01199), (3, 2, -0.00256), (1, 4, 0.00128), (0, 2, 0.00022), (2, 0, -0.00022), (5, 0, 0.00026))
    lat = 52.15517440 + sum(c * dx ** p * dy ** q for p, q, c in k) / 3600
    lon = 5.38720621 + sum(c * dx ** p * dy ** q for p, q, c in l) / 3600
    return lat, lon


def mercator(lat, lon):
    """Web Mercator position as a share of the world, x east and y south from the north-west corner."""
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360, 0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)


def polygons(blob):
    """The rings of each polygon in a GeoPackage geometry blob, as [(x, y), ...] lists."""
    if blob[:2] != b"GP":
        raise ValueError("not a GeoPackage geometry")
    flags = blob[3]
    envelope = (0, 32, 48, 48, 64)[(flags >> 1) & 7]
    return wkb(memoryview(blob)[8 + envelope:], [0])


def wkb(b, at):
    order = "<" if b[at[0]] == 1 else ">"
    kind = struct.unpack_from(order + "I", b, at[0] + 1)[0]
    at[0] += 5
    dims = 3 if kind // 1000 == 1 or kind & 0x80000000 else 2
    base = kind % 1000 & 0xFFFF
    if base == 3:
        rings = []
        n = struct.unpack_from(order + "I", b, at[0])[0]
        at[0] += 4
        for _ in range(n):
            m = struct.unpack_from(order + "I", b, at[0])[0]
            at[0] += 4
            pts = [struct.unpack_from(order + "dd", b, at[0] + i * 8 * dims) for i in range(m)]
            at[0] += m * 8 * dims
            rings.append(pts)
        return [rings]
    if base == 6:
        n = struct.unpack_from(order + "I", b, at[0])[0]
        at[0] += 4
        out = []
        for _ in range(n):
            out += wkb(b, at)
        return out
    raise ValueError("geometry type %d" % kind)


def buildings(path, year):
    """Each reconstructed building as (rings in Mercator shares, properties)."""
    db = sqlite3.connect(path)
    table = db.execute("select table_name from gpkg_contents where data_type = 'features'").fetchone()[0]
    rows = db.execute("select geom, identificatie, rf_success, rf_h_ground, rf_h_roof_min, rf_h_roof_ridge, rf_h_roof_70p, "
                      "rf_roof_type from \"%s\"" % table)
    for geom, ident, ok, ground, eaves, ridge, p70, roof in rows:
        if not ok or geom is None or ground is None or p70 is None:
            continue
        slanted = roof == "slanted" and ridge is not None and eaves is not None and ridge > eaves
        top = ridge if slanted else p70
        height = top - ground
        if not 2 <= height <= 400:
            continue
        props = {"height": round(height, 1), "roof_height": round(ridge - eaves, 1) if slanted else 0.0,
                 "roof": "slanted" if slanted else "flat", "id": ident, "source": "3D Basisvoorziening %s" % year}
        for poly in polygons(geom):
            yield [[mercator(*rd_to_wgs84(x, y)) for x, y in ring] for ring in poly], props


# Mapbox vector tiles and PMTiles v3, as much as this needs.
def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def field(number, wire, payload):
    key = varint(number << 3 | wire)
    return key + (varint(len(payload)) + payload if wire == 2 else payload)


def zigzag(n):
    return (n << 1) ^ (n >> 31)


def encode_tile(features, tx, ty):
    """One z14 tile's buildings as an MVT layer "buildings"."""
    keys, values, key_index, value_index, feats = [], [], {}, {}, []
    scale = (1 << ZOOM) * EXTENT
    for rings, props in features:
        geometry, cx, cy = [], 0, 0
        for i, ring in enumerate(rings):
            pts = [(round(x * scale - tx * EXTENT), round(y * scale - ty * EXTENT)) for x, y in ring]
            if pts[0] == pts[-1]:
                pts = pts[:-1]
            if len(pts) < 3:
                continue
            area = sum(pts[j][0] * pts[j - 1][1] - pts[j - 1][0] * pts[j][1] for j in range(len(pts)))
            if (area > 0) != (i == 0):  # outer rings clockwise in tile coordinates (y down), holes the other way
                pts.reverse()
            geometry += [9, zigzag(pts[0][0] - cx), zigzag(pts[0][1] - cy)]
            cx, cy = pts[0]
            geometry.append(2 | (len(pts) - 1) << 3)
            for x, y in pts[1:]:
                geometry += [zigzag(x - cx), zigzag(y - cy)]
                cx, cy = x, y
            geometry.append(15)
        if not geometry:
            continue
        tags = []
        for k, v in props.items():
            if k not in key_index:
                key_index[k] = len(keys)
                keys.append(k)
            vk = (type(v).__name__, v)
            if vk not in value_index:
                value_index[vk] = len(values)
                values.append(v)
            tags += [key_index[k], value_index[vk]]
        feats.append(field(2, 2, b"".join(varint(t) for t in tags)) + field(3, 0, varint(3))
                     + field(4, 2, b"".join(varint(g) for g in geometry)))
    layer = field(15, 0, varint(2)) + field(1, 2, b"buildings")
    for f in feats:
        layer += field(2, 2, f)
    for k in keys:
        layer += field(3, 2, k.encode())
    for v in values:
        layer += field(4, 2, field(1, 2, v.encode()) if isinstance(v, str) else field(3, 1, struct.pack("<d", v)))
    layer += field(5, 0, varint(EXTENT))
    return field(3, 2, layer)


def tile_id(z, x, y):
    acc = sum(4 ** i for i in range(z))
    n, d, s = 1 << z, 0, (1 << z) // 2
    while s > 0:
        rx, ry = 1 if x & s else 0, 1 if y & s else 0
        d += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                x, y = s - 1 - x, s - 1 - y
            x, y = y, x
        s //= 2
    return acc + d


def write_pmtiles(path, tiles, bounds):
    """tiles: {(x, y): bytes} at ZOOM, uncompressed; bounds: (min lon, min lat, max lon, max lat)."""
    entries, data = [], bytearray()
    for (x, y), blob in sorted(tiles.items(), key=lambda kv: tile_id(ZOOM, *kv[0])):
        entries.append((tile_id(ZOOM, x, y), len(data), len(blob)))
        data += blob
    d = varint(len(entries))
    last = 0
    for tid, _, _ in entries:
        d += varint(tid - last)
        last = tid
    d += b"".join(varint(1) for _ in entries) + b"".join(varint(n) for _, _, n in entries)
    for i, (_, off, n) in enumerate(entries):
        d += varint(0 if i and off == entries[i - 1][1] + entries[i - 1][2] else off + 1)
    meta = json.dumps({"vector_layers": [{"id": "buildings", "fields": {"height": "Number", "roof_height": "Number",
                       "roof": "String", "id": "String", "source": "String"}}], "attribution": "Kadaster, 3D Basisvoorziening, CC BY 4.0"}).encode()
    root_off = 127
    meta_off = root_off + len(d)
    data_off = meta_off + len(meta)
    e7 = [int(v * 1e7) for v in bounds]
    header = b"PMTiles" + bytes([3]) + struct.pack("<QQQQQQQQ", root_off, len(d), meta_off, len(meta), 0, 0, data_off, len(data))
    header += struct.pack("<QQQ", len(entries), len(entries), len(entries)) + bytes([1, 1, 1, 1, ZOOM, ZOOM])
    header += struct.pack("<iiii", *e7) + bytes([ZOOM]) + struct.pack("<ii", (e7[0] + e7[2]) // 2, (e7[1] + e7[3]) // 2)
    assert len(header) == 127
    with open(path, "wb") as f:
        f.write(header + d + meta + data)


def main(gpkg, year, out):
    per_tile, lons, lats, count = {}, [], [], 0
    for rings, props in buildings(gpkg, year):
        xs = [p[0] for p in rings[0]]
        ys = [p[1] for p in rings[0]]
        for tx in range(int(min(xs) * (1 << ZOOM)), int(max(xs) * (1 << ZOOM)) + 1):
            for ty in range(int(min(ys) * (1 << ZOOM)), int(max(ys) * (1 << ZOOM)) + 1):
                per_tile.setdefault((tx, ty), []).append((rings, props))
        lons += [min(xs) * 360 - 180, max(xs) * 360 - 180]
        lats += [math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y)))) for y in (min(ys), max(ys))]
        count += 1
    tiles = {k: encode_tile(v, *k) for k, v in per_tile.items()}
    write_pmtiles(out, tiles, (min(lons), min(lats), max(lons), max(lats)))
    print("%d buildings in %d z%d tiles, %.1f MB" % (count, len(tiles), ZOOM, sum(map(len, tiles.values())) / 1e6))


def self_test():
    # Amersfoort's Onze Lieve Vrouwetoren, the origin of RD: 52.15517, 5.38721 (published).
    lat, lon = rd_to_wgs84(155000, 463000)
    assert abs(lat - 52.15517440) < 1e-9 and abs(lon - 5.38720621) < 1e-9
    # A building corner in central Delft, as PDOK's BGT API gives it in RD and in WGS84: within half a metre.
    lat, lon = rd_to_wgs84(84355.604, 447474.114)
    assert abs(lat - 52.0111283) < 5e-6 and abs(lon - 4.3582017) < 8e-6, (lat, lon)
    blob = b"GP\x00\x01" + struct.pack("<i", 28992) + struct.pack("<B", 1) + struct.pack("<I", 3) + struct.pack("<II", 1, 4)
    blob += b"".join(struct.pack("<dd", *p) for p in ((0, 0), (10, 0), (10, 10), (0, 0)))
    assert polygons(blob) == [[[(0, 0), (10, 0), (10, 10), (0, 0)]]]
    assert tile_id(0, 0, 0) == 0 and tile_id(1, 0, 0) == 1 and tile_id(1, 1, 0) == 4
    print("self-test passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 4:
        main(*sys.argv[1:])
    else:
        sys.exit(__doc__)
