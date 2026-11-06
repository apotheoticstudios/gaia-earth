"""Builds relief.bin: how high the highest ground anywhere near each place reaches, every 0.2 degrees.

New worlds keep land one block per metre (at the map's scale) wherever that fits under the build limit.
Around a range that doesn't fit, everything is scaled down by one factor worked out from this height, so
the range keeps its shape, just lower. See terrain/VerticalScale.

1. Every row of CHELSA V2.1's elevation grid (GLOBAL/input/dem_geo.sdat: raw little-endian float32,
   bottom row first, 43200 x 20880 cells of 30 arc seconds from 90S to 84N, geopotential in m2/s2, -99999
   at sea; Karger et al. 2017, CC0), read in 12-row bands by HTTP range requests (3.6 GB), keeping each
   0.1 degree cell's highest point. Saved to <cache.npy>, so a second run skips the download.
2. Raised to the ground the mod builds: 30" cells are averages, so they fall short of sharp peaks (Mont
   Blanc's cell is 300 m low, Aoraki's 650 m). Every cell that is the highest within SUMMIT_KM and could
   reach the build limit takes the highest point of the mod's own elevation tiles over it, Terrarium's and
   Mapterhorn's (lidar where there is lidar), at about 40 and 20 m a pixel, plus 1%. A pixel counts only as
   high as the second highest of the 3 x 3 around it, so a lone bad pixel doesn't; and a summit never comes
   out below CHELSA (Antarctica's tiles are lower) nor more than 1.6 times above it. Other cells keep
   CHELSA's height: lower than the summit within 40 km of them, and what the grid still misses the game
   eases into the last blocks under the limit (terrain/VerticalScale).
3. The highest point within RADIUS_KM, then smoothed (a Gaussian whose reach stays inside that radius, so
   no point ends up below its own ground), and written at 0.2 degrees, rounded up.

    python build_relief.py <cache.npy> <out.bin>

Needs numpy, scipy and pillow (with WebP). About 3.6 GB of CHELSA and 1 GB of tiles; the summits below
are only printed, to check by.

Output: the snowline's format ("RESL", int32 width, int32 height, float64 north latitude, west longitude
and step of the first point, then deflate-compressed big-endian int16 metres, rows north to south, each
row stored as differences from the point to its west).
"""
import io
import struct
import sys
import urllib.error
import urllib.request
import zlib
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter1d, maximum_filter1d, rank_filter

URL = "https://os.zhdk.cloud.switch.ch/chelsav2/GLOBAL/input/dem_geo.sdat"
WIDTH, HEIGHT, BAND = 43200, 20880, 12
ROW_BYTES = WIDTH * 4
CELL = 0.1  # degrees, after pooling
KM_PER_DEGREE = 111.195
RADIUS_KM = 150.0
SIGMA_KM = 45.0
TRUNCATE = 3.3  # Gaussian reach in sigmas: 148.5 km, inside RADIUS_KM
SUMMIT_KM = 40.0
# Same encoding, same z/x/y: Terrarium's 256 px tiles, and Mapterhorn's 512 px ones (zoom 13 only where there is lidar).
TILES = ("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png", "https://tiles.mapterhorn.com/{z}/{x}/{y}.webp")
# Blocks from the sea to the build limit in new worlds (terrain/VerticalScale), at one block per metre; a map true
# to scale at the equator (scaleLatitude 0) draws mountains largest, this many metres times cos(latitude).
ROOM = 3827

# Summits (metres, latitude, longitude) the pooled grid is checked against.
SUMMITS = {
    "Everest": (8849, 27.9881, 86.9250),
    "K2": (8611, 35.8808, 76.5155),
    "Kangchenjunga": (8586, 27.7025, 88.1475),
    "Aconcagua": (6961, -32.6532, -70.0109),
    "Chimborazo": (6263, -1.4692, -78.8175),
    "Denali": (6190, 63.0692, -151.0070),
    "Logan": (5959, 60.5672, -140.4053),
    "Kilimanjaro": (5895, -3.0758, 37.3533),
    "Elbrus": (5642, 43.3550, 42.4392),
    "Vinson": (4892, -78.5254, -85.6171),
    "Puncak Jaya": (4884, -4.0789, 137.1583),
    "Mont Blanc": (4806, 45.8326, 6.8652),
    "Whitney": (4421, 36.5785, -118.2923),
    "Elbert": (4401, 39.1178, -106.4453),
    "Aoraki": (3724, -43.5950, 170.1418),
    "Gunnbjorn Fjeld": (3694, 68.9192, -29.8994),
    "Galdhopiggen": (2469, 61.6365, 8.3125),
    "Kebnekaise": (2097, 67.9000, 18.5167),
}


def fetch_band(band):
    first = band * BAND
    req = urllib.request.Request(URL, headers={
        "Range": f"bytes={first * ROW_BYTES}-{(first + BAND) * ROW_BYTES - 1}",
        "User-Agent": "gaia-earth-relief-build",
    })
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                data = r.read()
            if len(data) != BAND * ROW_BYTES:
                raise IOError(f"band {band}: {len(data)} bytes")
            break
        except Exception:
            if attempt == 5:
                raise
    cells = np.frombuffer(data, "<f4").reshape(BAND, WIDTH // BAND, BAND)
    return np.maximum(cells.max(axis=(0, 2)), 0) / 9.80665  # sea (-99999) and land below it count as 0


def pooled(cache):
    if cache.exists():
        return np.load(cache)
    bands = HEIGHT // BAND
    done = 0
    rows = [None] * bands
    with ThreadPoolExecutor(24) as pool:
        for band, row in zip(range(bands), pool.map(fetch_band, range(bands))):
            rows[band] = row
            done += 1
            if done % 100 == 0:
                print(f"{done}/{bands} bands", flush=True)
    grid = np.stack(rows)[::-1].astype("f4")  # north first
    # 84N to 90N, beyond CHELSA: the Arctic Ocean.
    grid = np.vstack([np.zeros((round(6 / CELL), grid.shape[1]), "f4"), grid])
    np.save(cache, grid)
    return grid


def cell_lats(n):
    return 90 - CELL / 2 - CELL * np.arange(n)


def disc_max(grid, km):
    """The highest cell within about km of each (a box a little larger than the disc)."""
    lats = cell_lats(grid.shape[0])
    radius_rows = int(np.ceil(km / (KM_PER_DEGREE * CELL)))
    # East-west reach in cells at the most poleward row the north-south pass will draw on, so a point
    # stays inside the reach of every cell the smoothing takes from.
    poleward = np.minimum(89.95, np.abs(lats) + radius_rows * CELL)
    km_per_cell = KM_PER_DEGREE * CELL * np.cos(np.radians(poleward))
    out = np.empty_like(grid)
    for y in range(grid.shape[0]):
        half = min(grid.shape[1] // 2, int(np.ceil(km / km_per_cell[y])))
        out[y] = maximum_filter1d(grid[y], 2 * half + 1, mode="wrap")
    return maximum_filter1d(out, 2 * radius_rows + 1, axis=0, mode="nearest")


def tile_top(url):
    """A tile's highest point, each pixel counted as the second highest of its 3 x 3; None where there is no tile."""
    req = urllib.request.Request(url, headers={"User-Agent": "gaia-earth-relief-build"})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                rgb = np.asarray(Image.open(io.BytesIO(r.read())).convert("RGB"), dtype=np.float64)
            break
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if attempt == 5:
                raise
        except Exception:
            if attempt == 5:
                raise
    metres = rgb[..., 0] * 256 + rgb[..., 1] + rgb[..., 2] / 256 - 32768
    return float(rank_filter(metres, -2, size=3).max())


def tile_max(key):
    z, x, y = key
    tops = [t for t in (tile_top(u.format(z=z, x=x, y=y)) for u in TILES) if t is not None]
    return max(tops) if tops else 0.0


def summit_tiles(lat, lon):
    """Terrarium tiles over a cell and 0.02 degrees round it, at the zoom whose tiles are about a cell high."""
    z = int(np.clip(round(np.log2(360 * np.cos(np.radians(lat)) / CELL)), 6, 13))
    n = 1 << z
    def px(la, lo):
        return (lo + 180) / 360 * n, (1 - np.arcsinh(np.tan(np.radians(np.clip(la, -85.05, 85.05)))) / np.pi) / 2 * n
    half = CELL / 2 + 0.02
    x0, y0 = px(lat + half, lon - half)
    x1, y1 = px(lat - half, lon + half)
    return [(z, x % n, y) for x in range(int(x0), int(x1) + 1) for y in range(max(0, int(y0)), min(n - 1, int(y1)) + 1)]


def raised(grid):
    """Each regional summit at its height in the mod's own tiles."""
    lats = cell_lats(grid.shape[0])
    reach = 0.75 * ROOM * np.cos(np.radians(np.minimum(np.abs(lats), 85.05)))[:, None]
    ys, xs = np.nonzero((grid == disc_max(grid, SUMMIT_KM)) & (grid > 300) & (grid > reach))
    wanted = [summit_tiles(lats[y], -180 + CELL / 2 + CELL * x) for y, x in zip(ys, xs)]
    keys = sorted({k for ks in wanted for k in ks})
    print(f"{len(ys)} summits, {len(keys)} tiles", flush=True)
    with ThreadPoolExecutor(32) as pool:
        tops = dict(zip(keys, pool.map(tile_max, keys)))
    game = np.array([max(tops[k] for k in ks) for ks in wanted]) * 1.01
    chelsa = grid[ys, xs]
    ratio = game / chelsa
    print("summits over CHELSA: " + ", ".join(f"{q}% x{np.percentile(ratio, q):.3f}" for q in (1, 10, 50, 90, 99)) +
          f"; {np.count_nonzero(ratio < 1)} below it, {np.count_nonzero(ratio > 1.6)} over 1.6 times it")
    out = grid.copy()
    out[ys, xs] = np.clip(game, chelsa, chelsa * 1.6)
    return out


def check_summits(regional_height):
    for name, (metres, lat, lon) in SUMMITS.items():
        y = int((90 - lat) / CELL)
        x = int((lon + 180) / CELL) % regional_height.shape[1]
        print(f"{name:16s} {metres:5d} m, regional height {regional_height[y, x]:6.0f} m")


def regional(grid):
    lats = cell_lats(grid.shape[0])
    sigma_rows = SIGMA_KM / (KM_PER_DEGREE * CELL)
    out = disc_max(grid, RADIUS_KM)
    smooth = np.empty_like(out)
    for y in range(out.shape[0]):
        sigma = min(grid.shape[1] / 8, SIGMA_KM / (KM_PER_DEGREE * CELL * np.cos(np.radians(lats[y]))))
        smooth[y] = gaussian_filter1d(out[y], sigma, mode="wrap", truncate=TRUNCATE)
    return gaussian_filter1d(smooth, sigma_rows, axis=0, mode="nearest", truncate=TRUNCATE)


def sample(coarse, north, west, step, lats, lons):
    """Bilinear, as climate/MetreGrid reads it."""
    fy = np.clip((north - lats) / step, 0, coarse.shape[0] - 1)[:, None]
    fx = ((lons - west) / step)[None, :]
    y0 = np.floor(fy).astype(int)
    y1 = np.minimum(coarse.shape[0] - 1, y0 + 1)
    x0 = np.floor(fx).astype(int)
    ty, tx = fy - y0, fx - x0
    xa, xb = x0 % coarse.shape[1], (x0 + 1) % coarse.shape[1]
    top = coarse[y0, xa] * (1 - tx) + coarse[y0, xb] * tx
    bottom = coarse[y1, xa] * (1 - tx) + coarse[y1, xb] * tx
    return top * (1 - ty) + bottom * ty


def main(cache, out):
    grid = pooled(Path(cache))
    print(f"{grid.shape[1]} x {grid.shape[0]} cells, highest {grid.max():.0f} m")
    grid = raised(grid)
    smooth = regional(grid)
    check_summits(smooth)
    # 0.2 degrees: each point the highest of its four 0.1 degree cells, rounded up.
    coarse = np.ceil(smooth.reshape(grid.shape[0] // 2, 2, grid.shape[1] // 2, 2).max(axis=(1, 3))).astype(">i2")
    north, west, step = 90 - 2 * CELL / 2, -180 + 2 * CELL / 2, 2 * CELL
    # Self-check: read back as the mod reads it, no ground stands above its regional height.
    back = sample(coarse.astype("f8"), north, west, step, cell_lats(grid.shape[0]), -180 + CELL / 2 + CELL * np.arange(grid.shape[1]))
    short = grid / np.maximum(back, 1)
    print(f"ground over its regional height: {np.count_nonzero(short > 1.0)} cells, worst x{short.max():.4f}")
    assert short.max() < 1.005, "regional heights fall short of the ground"
    deltas = np.diff(coarse.astype("i4"), axis=1, prepend=0).astype(">i2")
    body = zlib.compress(deltas.tobytes(), 9)
    head = b"RESL" + struct.pack(">ii", coarse.shape[1], coarse.shape[0]) + struct.pack(">ddd", north, west, step)
    Path(out).write_bytes(head + body)
    print(f"{coarse.shape[1]} x {coarse.shape[0]} points, {len(head) + len(body)} bytes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
