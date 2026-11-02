"""Fetches every 12th row and column of CHELSA V2.1's elevation grid: a 0.1 degree lattice of points.

CHELSA's grid (GLOBAL/input/dem_geo.sdat) is raw little-endian float32, bottom row first, 43200 x 20880
cells of 30 arc seconds from 90S to 84N, 3.6 GB in all. Rows sit at fixed offsets, so HTTP range requests
for the rows on the lattice fetch about 300 MB instead. The values are geopotential (m2/s2), as its header
says; divided by g they are metres (Everest's cell 8,625 m, Mont Blanc's 4,505 m, Stockholm 40 m).

    python fetch_dem.py <out.npy>
"""
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import numpy as np

URL = "https://os.zhdk.cloud.switch.ch/chelsav2/GLOBAL/input/dem_geo.sdat"
WIDTH, HEIGHT, STEP = 43200, 20880, 12
ROW_BYTES = WIDTH * 4


def lattice_rows():
    """Grid rows (0 = southernmost) of the lattice: the middle of each 12-row band."""
    return range(STEP // 2, HEIGHT, STEP)


def fetch(row):
    req = urllib.request.Request(URL, headers={
        "Range": f"bytes={row * ROW_BYTES}-{(row + 1) * ROW_BYTES - 1}",
        "User-Agent": "realearth-snowline-build",
    })
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
            if len(data) != ROW_BYTES:
                raise IOError(f"row {row}: {len(data)} bytes")
            return np.frombuffer(data, "<f4")[STEP // 2::STEP]
        except Exception:
            if attempt == 4:
                raise


def main(out):
    rows = list(lattice_rows())
    with ThreadPoolExecutor(8) as pool:
        lattice = np.stack(list(pool.map(fetch, rows)))
    lattice[lattice < -90000] = np.nan  # -99999 is no data (sea)
    lattice /= 9.80665
    # Store north first, like the other grids.
    np.save(out, lattice[::-1].astype("f4"))
    print(f"{lattice.shape[0]} x {lattice.shape[1]} points, {np.nanmin(lattice):.0f} to {np.nanmax(lattice):.0f} m")


if __name__ == "__main__":
    main(sys.argv[1])
