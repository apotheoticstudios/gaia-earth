"""Builds Project Gaia: Earth's tree density grid (src/main/resources/realearth/treedensity.bin) from public data.

How many trees stand on a hectare of woodland, from Crowther et al. (2015), "Mapping tree density at a global
scale", Nature 525, 201-205; data Glick et al. (2016), Scientific Data 3, 160069, CC BY 4.0
(https://doi.org/10.6084/m9.figshare.3179986). It counts trees at least 10 cm across at breast height, modelled at
1 km from over 420,000 ground counts; the WGS84 GeoTIFF revision of the biome-level model is used, through the
overview pyramid shipped beside it (deflate tiles, nearest-neighbour samples every 2 arc minutes), which plain Python
reads quickly where the full grid's LZW would take hours.

Method: the map's values are trees per km2 of each 1 km pixel, lower where only part of the pixel is wooded. The mod
plants trees only where the land cover shows trees, so each 0.1 degree cell takes the second highest of its samples
(about nine): the density of its wooded pixels, without a single outlier. Cells without data are 0.

    python3 build_treedensity.py <...WGS84_GeoTiff.tif.ovr> <...WGS84_GeoTiff.tfw> <out.bin>

Output: "RETD", int32 width, int32 height, float64 north latitude, west longitude and step (degrees, cell edges),
then deflate-compressed bytes, rows north to south: 0 for no data or under one tree a hectare, else
round(64 * log10(trees a hectare)), 1 to 255 -- 1 to about 9,600 a hectare in steps of 3.7%.
"""
import array
import math
import struct
import sys
import zlib

LEVEL = 1  # overview level: 4 full pixels across, 2 arc minutes
FACTOR = 4
STEP = 0.1
NORTH = 84.0
SOUTH = -60.0
WEST = -180.0
STEPS_PER_DECADE = 64.0
PLACES = [("Tyresta, Sweden", 59.18, 18.30), ("Amazon", -4.0, -63.0), ("Black Forest", 48.0, 8.2),
          ("Monchique, Portugal", 37.32, -8.55), ("Sahara", 26.0, 3.0), ("Beauce farmland", 48.25, 1.75),
          ("Kruger savanna", -24.0, 31.5), ("Siberian taiga", 62.0, 100.0), ("Borneo", 1.0, 114.0)]


def read_ifds(f):
    """Each image in a classic little-endian TIFF: {tag: tuple of values}."""
    f.seek(0)
    head = f.read(8)
    if head[:4] != b"II*\x00":
        raise ValueError("expected a little-endian classic TIFF")
    off = struct.unpack("<I", head[4:])[0]
    sizes = {1: ("B", 1), 3: ("H", 2), 4: ("I", 4), 12: ("d", 8), 16: ("Q", 8)}
    ifds = []
    while off:
        f.seek(off)
        n = struct.unpack("<H", f.read(2))[0]
        entries = [struct.unpack("<HHI4s", f.read(12)) for _ in range(n)]
        off = struct.unpack("<I", f.read(4))[0]
        tags = {}
        for tag, typ, count, raw in entries:
            if typ not in sizes:
                continue
            fmt, size = sizes[typ]
            if count * size <= 4:
                tags[tag] = struct.unpack_from("<%d%s" % (count, fmt), raw)
            else:
                here = f.tell()
                f.seek(struct.unpack("<I", raw)[0])
                tags[tag] = struct.unpack("<%d%s" % (count, fmt), f.read(count * size))
                f.seek(here)
        ifds.append(tags)
    return ifds


def main(ovr_path, tfw_path, out_path):
    with open(tfw_path) as t:
        tfw = [float(v) for v in t.read().split()]
    full_pixel, left, top = tfw[0], tfw[4] - tfw[0] / 2, tfw[5] + tfw[0] / 2  # cell edges of the full grid
    pixel = full_pixel * FACTOR
    cols, rows = round(360 / STEP), round((NORTH - SOUTH) / STEP)
    best = array.array("d", bytes(8 * cols * rows))
    second = array.array("d", bytes(8 * cols * rows))
    with open(ovr_path, "rb") as f:
        ifd = read_ifds(f)[LEVEL]
        width, height, tile = ifd[256][0], ifd[257][0], ifd[322][0]
        if ifd[259][0] != 8 or ifd.get(317, (1,))[0] != 1 or ifd[339][0] != 3:
            raise ValueError("expected deflate tiles of floats without a predictor")
        kind = "d" if ifd[258][0] == 64 else "f"
        across = -(-width // tile)
        for t, (offset, length) in enumerate(zip(ifd[324], ifd[325])):
            f.seek(offset)
            values = array.array(kind, zlib.decompress(f.read(length)))
            if max(values) <= 0:  # sea and nodata, most tiles
                continue
            x0, y0 = (t % across) * tile, (t // across) * tile
            for r in range(tile):
                y = y0 + r
                lat = top - (y + 0.5) * pixel
                row = int((NORTH - lat) / STEP)
                if y >= height or not 0 <= row < rows:
                    continue
                base = row * cols
                for c in range(tile):
                    v = values[r * tile + c]
                    if v <= 0 or v > 1e7 or x0 + c >= width:
                        continue
                    k = base + int((left + (x0 + c + 0.5) * pixel - WEST) / STEP) % cols
                    if v > best[k]:
                        second[k], best[k] = best[k], v
                    elif v > second[k]:
                        second[k] = v
    out = bytearray(cols * rows)
    for k in range(cols * rows):
        per_hectare = (second[k] or best[k]) / 100
        if per_hectare >= 1:
            out[k] = max(1, min(255, round(STEPS_PER_DECADE * math.log10(per_hectare))))
    with open(out_path, "wb") as o:
        o.write(b"RETD" + struct.pack(">iiddd", cols, rows, NORTH, WEST, STEP) + zlib.compress(bytes(out), 9))
    for name, lat, lon in PLACES:
        v = out[int((NORTH - lat) / STEP) * cols + int((lon - WEST) / STEP)]
        per_hectare = 10 ** (v / STEPS_PER_DECADE) if v else 0
        spacing = math.sqrt(10000 / per_hectare) if per_hectare else float("inf")
        print("%-20s %6.0f trees a hectare, %4.1f m apart" % (name, per_hectare, spacing))


if __name__ == "__main__":
    main(*sys.argv[1:4])
