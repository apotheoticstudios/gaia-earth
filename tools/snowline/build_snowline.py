"""Builds Real Earth's snowline grid (src/main/resources/realearth/snowline.bin) from public data.

The snowline here is the climatic snowline: the height where a glacier's winter snow just lasts the summer,
which glaciologists measure as the equilibrium-line altitude (ELA). Two sources:

- Randolph Glacier Inventory 7.0 (RGI 7.0 Consortium, 2023; CC BY 4.0): every glacier on Earth outside the
  two ice sheets, with its median elevation, a standard stand-in for its ELA (Braithwaite & Raper 2009).
  The global attribute table is mirrored by OGGM:
  https://cluster.klima.uni-bremen.de/~oggm/rgi/RGI2000-v7.0-G-global-attributes.csv
- CHELSA V2.1 (Karger et al. 2017, 2021; CC0): mean temperature of the warmest quarter (bio10) and annual
  precipitation (bio12), 1981-2010, and the elevation they were made on (fetch_dem.py), at 30 arc seconds.

Method, on a lattice of points every 0.1 degrees:

1. Climate: the snowline is where summer air, cooling at 6.5 C/km above the ground, reaches the summer
   temperature at which glaciers survive. Where more snow falls, ice outlasts warmer summers (Ohmura et al.
   1992), so that temperature depends on precipitation; it is read off the glaciers themselves -- each
   glacier's nearest lattice point, carried to its median height, binned by precipitation -- not chosen.
2. Glaciers: where there are glaciers, what they say beats the climate. Their departures from step 1 are
   spread with a Gaussian falloff (60 km) and added, fading smoothly to nothing ~200 km from any glacier,
   so ice that is there keeps its real height and the climate's shape carries on elsewhere. Short enough
   that a wet coast's glaciers and a dry valley's 100 km inland keep their own.
3. Smoothed over ~15 km, any point without data filled from the nearest one, and written as metres.

    python build_snowline.py <rgi.csv> <bio10.tif> <bio12.tif> <dem_lattice.npy> <out.bin>

Output: "RESL", int32 width, int32 height, float64 north latitude, west longitude and step (degrees,
of the first point), then deflate-compressed big-endian int16 metres, rows north to south, each row
stored as differences from the point to its west.
"""
import csv
import struct
import sys
import zlib

import numpy as np
import rasterio
from rasterio.windows import Window
from scipy import ndimage
from scipy.spatial import cKDTree

LAPSE = 0.0065  # C per metre
STEP = 12  # lattice spacing in 30 arc second pixels: 0.1 degrees
PIXEL = 1 / 120
TOP = 84.0  # CHELSA's grid runs from 90S to 84N
FALLOFF_KM = 60.0
EARTH_KM = 6371.0


def lattice_coords(height, width):
    """Latitude of each lattice row and longitude of each column, at the pixel centres fetch_dem.py read."""
    rows = 5 + STEP * np.arange(height)  # north-first pixel rows (fetch_dem reads rows 6 + 12k from the south)
    cols = STEP // 2 + STEP * np.arange(width)
    return TOP - (rows + 0.5) * PIXEL, -180 + (cols + 0.5) * PIXEL, rows, cols


def read_chelsa(path, rows, cols):
    """A CHELSA grid at the lattice points, in its own units (scale and offset applied); NaN where empty."""
    out = np.full((len(rows), len(cols)), np.nan, "f4")
    with rasterio.open(path) as r:
        scale, offset, nodata = r.scales[0], r.offsets[0], r.nodata
        band = 1200
        for start in range(0, r.height, band):
            block = r.read(1, window=Window(0, start, r.width, min(band, r.height - start)))
            sel = (rows >= start) & (rows < start + block.shape[0])
            raw = block[rows[sel] - start][:, cols].astype("f4")
            raw[raw == nodata] = np.nan
            out[sel] = raw * scale + offset
    return out


def unit(lat, lon):
    la, lo = np.radians(lat), np.radians(lon)
    return np.stack([np.cos(la) * np.cos(lo), np.cos(la) * np.sin(lo), np.sin(la)], axis=-1)


def read_glaciers(path):
    lat, lon, zmed, area = [], [], [], []
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            lat.append(float(row["cenlat"]))
            lon.append(float(row["cenlon"]))
            zmed.append(float(row["zmed_m"]))
            area.append(float(row["area_km2"]))
    return np.array(lat), np.array(lon), np.array(zmed), np.array(area)


def nearest_point(lat, lon, lats, lons):
    i = np.clip(np.rint((lats[0] - lat) / (STEP * PIXEL)).astype(int), 0, len(lats) - 1)
    j = np.clip(np.rint((lon - lons[0]) / (STEP * PIXEL)).astype(int), 0, len(lons) - 1)
    return i, j


def weighted_median(values, weights):
    order = np.argsort(values)
    c = np.cumsum(weights[order])
    return values[order][np.searchsorted(c, c[-1] / 2)]


def main(rgi_path, bio10_path, bio12_path, dem_path, out_path):
    z = np.load(dem_path)
    lats, lons, rows, cols = lattice_coords(*z.shape)
    t = read_chelsa(bio10_path, rows, cols)
    rain = np.maximum(read_chelsa(bio12_path, rows, cols), 1.0)
    land = np.isfinite(z) & np.isfinite(t) & np.isfinite(rain)
    print(f"lattice {z.shape[1]} x {z.shape[0]}, {land.sum()} land points")

    # 1. Summer temperature at glaciers' median heights. Small glaciers lie low in shade and avalanche
    # gullies, below the climate's snowline, so they count for less.
    glat, glon, zmed, area = read_glaciers(rgi_path)
    gi, gj = nearest_point(glat, glon, lats, lons)
    ok = land[gi, gj] & (glat < TOP)
    weight = np.minimum(area, 50.0)
    t_at_ela = t[gi, gj] - LAPSE * (zmed - z[gi, gj])
    print(f"{ok.sum()} glaciers on the lattice; summer temperature at their median heights "
          f"{weighted_median(t_at_ela[ok], weight[ok]):.2f} C, 10-90% "
          f"{np.percentile(t_at_ela[ok], 10):.1f} to {np.percentile(t_at_ela[ok], 90):.1f} C")

    # By precipitation: twelve bins with equal glacier area, the weighted median of each, joined up in
    # log(precipitation) and held flat beyond the wettest and driest bins.
    log_rain = np.log(rain[gi, gj])
    order = np.argsort(log_rain[ok])
    lr, tt, ww = log_rain[ok][order], t_at_ela[ok][order], weight[ok][order]
    edges = np.searchsorted(np.cumsum(ww), np.linspace(0, ww.sum(), 13)[1:-1])
    knots_x, knots_t = [], []
    for part in np.split(np.arange(len(lr)), edges):
        knots_x.append(weighted_median(lr[part], ww[part]))
        knots_t.append(weighted_median(tt[part], ww[part]))
    print("  summer temperature at which glaciers survive, by annual precipitation:")
    for x, tk in zip(knots_x, knots_t):
        print(f"    {np.exp(x):6.0f} mm  {tk:+5.1f} C")
    t_snow = np.interp(np.log(rain), knots_x, knots_t)

    climate = np.where(land, z + (t - t_snow) / LAPSE, np.nan)

    # 2. Glaciers' departures from the climate, spread with a falloff. Binned into 0.1 degree cells first,
    # then solved on a 0.25 degree grid and interpolated: the field is smooth at this scale.
    resid = zmed - climate[gi, gj]
    good = ok & np.isfinite(resid)
    cell = 0.1
    bi = np.floor((90 - glat[good]) / cell).astype(int)
    bj = np.floor((glon[good] + 180) / cell).astype(int) % int(360 / cell)
    key = bi * 10000 + bj
    uniq, inv = np.unique(key, return_inverse=True)
    wsum = np.bincount(inv, weight[good])
    rsum = np.bincount(inv, weight[good] * resid[good])
    blat = 90 - (uniq // 10000 + 0.5) * cell
    blon = -180 + (uniq % 10000 + 0.5) * cell
    tree = cKDTree(unit(blat, blon))
    cell = 0.25
    clat = np.arange(90 - cell / 2, -90, -cell)
    clon = np.arange(-180 + cell / 2, 180, cell)
    glat2, glon2 = np.meshgrid(clat, clon, indexing="ij")
    pts = unit(glat2.ravel(), glon2.ravel())
    reach = 3.5 * FALLOFF_KM / EARTH_KM
    num = np.zeros(len(pts))
    den = np.zeros(len(pts))
    for k, near in enumerate(tree.query_ball_point(pts, reach)):
        if near:
            d = np.linalg.norm(tree.data[near] - pts[k], axis=1) * EARTH_KM
            kern = np.exp(-0.5 * (d / FALLOFF_KM) ** 2)
            num[k] = np.sum(kern * rsum[near])
            den[k] = np.sum(kern * wsum[near])
    # A few square km of glacier nearby are enough to trust them; less, and the correction fades out.
    coarse = (num / (den + 5.0)).reshape(glat2.shape)
    fi = (90 - lats) / cell - 0.5
    fj = (lons + 180) / cell - 0.5
    fi2, fj2 = np.meshgrid(fi, fj, indexing="ij")
    wrapped = np.concatenate([coarse, coarse[:, :1]], axis=1)
    correction = ndimage.map_coordinates(wrapped, [fi2, fj2 % coarse.shape[1]], order=1, mode="nearest")
    print(f"glacier correction: {np.percentile(resid[good], 10):.0f} to {np.percentile(resid[good], 90):.0f} m "
          f"at the glaciers (10-90%), spread {np.abs(correction).max():.0f} m at most")

    snow = climate + correction

    # 3. Smooth over land only, then fill the sea from the nearest land.
    filled = np.where(land, snow, 0.0)
    mass = ndimage.gaussian_filter(land.astype("f8"), 1.5, mode=("nearest", "wrap"))
    smooth = ndimage.gaussian_filter(filled, 1.5, mode=("nearest", "wrap")) / np.maximum(mass, 1e-6)
    smooth = np.where(land, smooth, np.nan)
    idx = ndimage.distance_transform_edt(~land, return_distances=False, return_indices=True)
    grid = smooth[idx[0], idx[1]]
    grid = np.clip(np.rint(grid), -500, 7500).astype(">i2")

    # How well the result matches the glaciers it was made from, and at places to look at.
    at = grid[gi[good], gj[good]].astype("f8")
    err = at - zmed[good]
    big = weight[good] >= 1.0
    print(f"against glaciers over 1 km2: median {np.median(err[big]):+.0f} m, "
          f"middle half {np.percentile(err[big], 25):+.0f} to {np.percentile(err[big], 75):+.0f} m; "
          f"by area: median {weighted_median(err, weight[good]):+.0f} m; "
          f"over 10 km2: median {np.median(err[weight[good] >= 10]):+.0f} m")
    for name, la, lo in PLACES:
        i, j = nearest_point(np.array([la]), np.array([lo]), lats, lons)
        print(f"  {name:32s} {grid[i[0], j[0]]:6d} m")

    deltas = np.diff(grid.astype("i4"), axis=1, prepend=0).astype(">i2")
    body = zlib.compress(deltas.tobytes(), 9)
    head = b"RESL" + struct.pack(">ii", grid.shape[1], grid.shape[0]) + struct.pack(">ddd", lats[0], lons[0], STEP * PIXEL)
    with open(out_path, "wb") as f:
        f.write(head + body)
    print(f"wrote {out_path}: {len(head) + len(body)} bytes")


PLACES = [
    ("Central Alps (Aletsch)", 46.50, 8.03),
    ("Norway, outer coast (Alfotbreen)", 61.75, 5.65),
    ("Norway, Jostedalsbreen (Nigardsbreen)", 61.70, 7.15),
    ("Norway, Jotunheimen (Storbreen)", 61.57, 8.13),
    ("Norway, east Jotunheimen (Hellstugubreen)", 61.56, 8.44),
    ("Kebnekaise (Storglaciaren)", 67.90, 18.57),
    ("Svalbard (Longyearbyen)", 78.20, 15.60),
    ("Glacier National Park", 48.70, -113.70),
    ("Grand Canyon South Rim", 36.06, -112.11),
    ("Kilimanjaro", -3.07, 37.35),
    ("Khumbu (Everest)", 27.95, 86.82),
    ("Southern Alps west (Franz Josef)", -43.47, 170.18),
    ("Patagonia (Southern Icefield)", -49.30, -73.30),
    ("Stockholm", 59.33, 18.07),
]


if __name__ == "__main__":
    main(*sys.argv[1:6])
