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
import sqlite3
import struct
import sys

from overlay import by_tile, encode_layer, mercator, write_pmtiles


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


def main(gpkg, year, out):
    features = list(buildings(gpkg, year))
    per_tile, bounds = by_tile(features)
    tiles = {k: encode_layer("buildings", v, *k) for k, v in per_tile.items()}
    write_pmtiles(out, tiles, bounds, {"buildings": {"height": "Number", "roof_height": "Number", "roof": "String", "id": "String",
                  "source": "String"}}, "Kadaster, 3D Basisvoorziening, CC BY 4.0")
    print("%d buildings in %d z14 tiles, %.1f MB" % (len(features), len(tiles), sum(map(len, tiles.values())) / 1e6))


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
    print("self-test passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 4:
        main(*sys.argv[1:])
    else:
        sys.exit(__doc__)
