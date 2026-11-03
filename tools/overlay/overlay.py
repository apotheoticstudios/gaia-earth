"""The parts every overlay converter shares: Web Mercator, z14 vector tiles (MVT) and PMTiles v3 archives, as much as
the overlays need. Standard library only."""
import json
import math
import struct

ZOOM = 14
EXTENT = 4096


def mercator(lat, lon):
    """Web Mercator position as a share of the world, x east and y south from the north-west corner."""
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360, 0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)


def by_tile(features):
    """Polygons [(rings, props)] in Mercator shares, each in every z14 tile its outer ring's box touches; and the
    bounds of them all as (min lon, min lat, max lon, max lat)."""
    per_tile, lons, lats = {}, [], []
    for rings, props in features:
        xs = [p[0] for p in rings[0]]
        ys = [p[1] for p in rings[0]]
        for tx in range(int(min(xs) * (1 << ZOOM)), int(max(xs) * (1 << ZOOM)) + 1):
            for ty in range(int(min(ys) * (1 << ZOOM)), int(max(ys) * (1 << ZOOM)) + 1):
                per_tile.setdefault((tx, ty), []).append((rings, props))
        lons += [min(xs) * 360 - 180, max(xs) * 360 - 180]
        lats += [math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y)))) for y in (min(ys), max(ys))]
    return per_tile, (min(lons), min(lats), max(lons), max(lats))


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


def encode_layer(name, features, tx, ty):
    """One z14 tile's polygons, [(rings in Mercator shares, properties)], as an MVT layer."""
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
    layer = field(15, 0, varint(2)) + field(1, 2, name.encode())
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


def write_pmtiles(path, tiles, bounds, layers, attribution):
    """tiles: {(x, y): bytes} at ZOOM, uncompressed; bounds: (min lon, min lat, max lon, max lat); layers: {name: {field:
    type}} for the metadata."""
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
    meta = json.dumps({"vector_layers": [{"id": k, "fields": v} for k, v in layers.items()], "attribution": attribution}).encode()
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


def self_test():
    assert tile_id(0, 0, 0) == 0 and tile_id(1, 0, 0) == 1 and tile_id(1, 1, 0) == 4
    x, y = mercator(0, 0)
    assert abs(x - 0.5) < 1e-12 and abs(y - 0.5) < 1e-12
    tile = encode_layer("test", [([[(0.5, 0.5), (0.5001, 0.5), (0.5001, 0.5001)]], {"a": 1.0, "b": "c"})], 8192, 8192)
    assert b"test" in tile and len(tile) > 20
    print("self-test passed")


if __name__ == "__main__":
    self_test()
