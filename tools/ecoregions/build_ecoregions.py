"""Builds Project Gaia: Earth's ecoregion grid (src/main/resources/realearth/ecoregions.bin and ecoregions.tsv).

Which of the world's 846 ecoregions, in 14 biomes, a place lies in: RESOLVE Ecoregions 2017, Dinerstein et al.
(2017), "An Ecoregion-Based Approach to Protecting Half the Terrestrial Realm", BioScience 67(6): 534-545,
https://doi.org/10.1093/biosci/bix014; data CC BY 4.0 (https://ecoregions.appspot.com/). Earth's own biomes follow
the ecoregion's biome, and some ecoregions are known for a landscape of their own (SUBS below).

Method: each 0.05 degree cell takes the ecoregion whose polygons cover its centre (even-odd, holes left out). Sea
cells within COAST_CELLS of land take the nearest ecoregion, so a coast the terrain draws a little further out still
has one.

    python3 build_ecoregions.py <Ecoregions2017.shp> <out dir>

Output: ecoregions.bin, "REEC", int32 width, int32 height, float64 north latitude, west longitude and step
(degrees, cell edges), then deflate-compressed little-endian uint16 rows, north to south: 0 for none, else a row
of ecoregions.tsv (1-based). ecoregions.tsv: row, ECO_ID, biome number (1-14, 0 for Rock and Ice), sub-biome
(SUBS, or -), realm, name.
"""
import array
import math
import os
import struct
import sys
import zlib

STEP = 0.05
NORTH = 90.0
WEST = -180.0
COAST_CELLS = 3

# Ecoregions known for a landscape of their own, by ECO_ID (names in ecoregions.tsv), each a sub-biome of Earth's:
SUBS = {
    # The redwoods: the coast of northern California, the tallest forest on Earth.
    'redwood': {359},
    # Temperate rainforests -- the Pacific coast from Alaska to Oregon, Norway's coast, southern Chile, New Zealand,
    # Tasmania's west -- wet all year, mossy, tall.
    'rainforest': {349, 351, 360, 365, 708, 561, 563, 167, 169, 170, 171, 172, 173, 174, 175, 179, 180},
    # Hemiboreal forests: where temperate mixed forest meets the taiga -- southern Sweden and Finland, the Baltic states,
    # western Siberia's southern edge, Canada's forest-boreal transition -- mostly pine and spruce, with birch and oak.
    'hemiboreal': {679, 687, 333},
    # Larch taiga: eastern Siberia and the Greater Khingan, where only larches stand the cold and the permafrost.
    'larch': {710, 714, 718, 693},
    # Moorland: the heather, bog and rough grass of Atlantic Europe's uplands -- Ireland, Scotland, Wales, the Faroes,
    # Iceland -- where the land is open.
    'moorland': {672, 691, 651, 729, 711},
    # Forest-steppe: groves of birch, aspen and pine in the grassland, from Ukraine to Mongolia, and Canada's aspen parkland.
    'forest_steppe': {661, 726, 731, 737, 738, 386},
    # Oak savanna: North America's open oak woodland between prairie and forest.
    'oak_savanna': {403, 390, 391, 387, 343},
    # Tallgrass prairie: the wetter, eastern prairies of North America.
    'prairie': {388, 392, 397, 401},
    # Pine savanna: open longleaf and Caribbean pine over grass -- the US South's coastal plain, the pine barrens,
    # Belize's and the Miskito Coast's pine savannas.
    'pine_savanna': {399, 564, 573, 347, 363},
    # Fynbos and renosterveld: the Cape's fine-leaved shrubland.
    'fynbos': {89, 90},
    # Miombo: the Brachystegia woodland of south-central Africa.
    'miombo': {36, 39, 42},
    # Cerrado: Brazil's savanna woodland of twisted, thick-barked trees.
    'cerrado': {567, 566},
    # Thorn scrub: acacia and commiphora bush -- East Africa, the Horn, India's thorn forests, the Chaco, the Caatinga,
    # Madagascar's spiny thickets.
    'thorn': {51, 55, 57, 45, 50, 314, 315, 605, 569, 525, 99},
    # Cactus scrub: the deserts and matorral of Mexico and its borderlands, and Cuba's cactus scrub.
    'cactus': {435, 428, 426, 431, 607, 427, 432, 436, 437, 610, 600},
    # Sagebrush steppe: the Great Basin, the Snake and Columbia plains, Wyoming, the Colorado Plateau.
    'sagebrush': {430, 434, 438, 429},
    # The Karoo: South Africa's and Namibia's dwarf and succulent shrubland.
    'karoo': {94, 101, 110, 102},
    # Mulga: Australia's acacia shrubland.
    'mulga': {191, 216},
    # Paramo: the wet tropical alpine grassland of the northern Andes and East Africa's mountains (moorlands).
    'paramo': {590, 591, 593, 594, 78, 80, 86},
    # Puna: the dry high grassland of the central Andes.
    'puna': {587, 588, 589},
    # Salt marshes, saline meadows and salt pans.
    'salt': {69, 70, 73, 745, 742, 748, 312, 747},
    # Polar desert: the bare high Arctic.
    'polar': {777, 778, 783},
}
# Laurel forests (laurisilva): warm-temperate evergreen broadleaf, in East Asia and on the Atlantic islands; the
# temperate broadleaf ecoregions named "evergreen", and the Azores'.
LAUREL = {645}
# Australia: eucalypts, whatever the biome's forests are elsewhere; its deserts are spinifex grassland.
AUSTRALIA = (-44.5, -9.5, 112.0, 154.5)  # south, north, west, east of the mainland and Tasmania
PLACES = [("Olympic Peninsula", 47.8, -123.9), ("Sierra Nevada", 38.0, -120.0), ("Amazon", -4.0, -63.0),
          ("Sahara", 26.0, 3.0), ("Serengeti", -2.3, 34.8), ("Central Spain", 40.0, -4.0), ("Siberian taiga", 62.0, 100.0),
          ("Tibet", 32.0, 88.0), ("Pantanal", -17.5, -57.0), ("North Slope", 69.5, -152.0), ("Blue Mountains", -33.7, 150.3),
          ("Black Forest", 48.0, 8.2), ("Yakushima", 30.35, 130.5), ("Valdivia", -40.0, -73.0), ("Stockholm", 59.33, 18.07),
          ("Kakadu", -12.8, 132.5), ("Greenland ice", 72.0, -40.0)]


def read_dbf(path):
    with open(path, 'rb') as f:
        head = f.read(32)
        count, head_len, rec_len = struct.unpack('<IHH', head[4:12])
        fields = []
        while True:
            d = f.read(32)
            if d[0] == 0x0D:
                break
            fields.append((d[:11].split(b'\0')[0].decode(), d[16]))
        f.seek(head_len)
        out = []
        for _ in range(count):
            r = f.read(rec_len)
            o = 1
            rec = {}
            for name, length in fields:
                rec[name] = r[o:o + length].decode('latin1').strip()
                o += length
            out.append(rec)
        return out


def records(path):
    """Each polygon record of a shapefile: (bbox, list of rings of (x, y) as two arrays)."""
    with open(path, 'rb') as f:
        f.seek(100)
        while True:
            head = f.read(8)
            if len(head) < 8:
                return
            length = struct.unpack('>i', head[4:8])[0] * 2
            body = f.read(length)
            kind = struct.unpack('<i', body[:4])[0]
            if kind == 0:
                yield None, []
                continue
            bbox = struct.unpack('<4d', body[4:36])
            parts, points = struct.unpack('<2i', body[36:44])
            starts = list(struct.unpack('<%di' % parts, body[44:44 + 4 * parts])) + [points]
            xy = array.array('d')
            xy.frombytes(body[44 + 4 * parts:44 + 4 * parts + 16 * points])
            rings = [(xy[2 * starts[i]:2 * starts[i + 1]:2], xy[2 * starts[i] + 1:2 * starts[i + 1]:2]) for i in range(parts)]
            yield bbox, rings


def burn(grid, width, height, rings, value):
    """Even-odd fill of the cells whose centres the rings enclose."""
    rows = {}
    for xs, ys in rings:
        n = len(xs)
        cx = [(x - WEST) / STEP for x in xs]
        cy = [(NORTH - y) / STEP for y in ys]
        for i in range(n - 1):
            y0 = cy[i]
            y1 = cy[i + 1]
            if y0 == y1:
                continue
            lo, hi = (y0, y1) if y0 < y1 else (y1, y0)
            r0 = math.ceil(lo - 0.5)
            r1 = math.ceil(hi - 0.5)
            if r0 >= r1:
                continue
            x0 = cx[i]
            slope = (cx[i + 1] - x0) / (y1 - y0)
            for r in range(max(0, r0), min(height, r1)):
                rows.setdefault(r, []).append(x0 + (r + 0.5 - y0) * slope)
    for r, xs in rows.items():
        xs.sort()
        base = r * width
        for i in range(0, len(xs) - 1, 2):
            c0 = max(0, math.ceil(xs[i] - 0.5))
            c1 = min(width, math.ceil(xs[i + 1] - 0.5))
            for c in range(c0, c1):
                grid[base + c] = value


def coast_fill(grid, width, height):
    for _ in range(COAST_CELLS):
        filled = array.array('H', grid)
        for y in range(height):
            base = y * width
            for x in range(width):
                if grid[base + x]:
                    continue
                for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
                    yy = y + dy
                    if 0 <= yy < height:
                        v = grid[yy * width + (x + dx) % width]
                        if v:
                            filled[base + x] = v
                            break
        grid[:] = filled


def sub_biome(rec, bbox):
    eco = int(rec['ECO_ID'])
    biome = int(float(rec['BIOME_NUM'] or 0))
    name = rec['ECO_NAME'].lower()
    for sub, ids in SUBS.items():
        if eco in ids:
            return sub
    # Tropical swamp forests: freshwater and peat swamp forest, and the Amazon's flooded forests (varzea, igapo).
    if biome == 1 and ('swamp' in name or 'v\u00e1rzea' in name or 'igap\u00f3' in name):
        return 'swamp'
    if eco in LAUREL or (biome == 4 and 'evergreen' in name):
        return 'laurel'
    s, n, w, e = AUSTRALIA
    mid_lon = (bbox[0] + bbox[2]) / 2
    mid_lat = (bbox[1] + bbox[3]) / 2
    if rec['REALM'] == 'Australasia' and s <= mid_lat <= n and w <= mid_lon <= e:
        if biome in (4, 5, 7, 8, 12):
            return 'eucalypt'
        if biome == 13:
            return 'spinifex'
    return '-'


def main():
    shp, out = sys.argv[1], sys.argv[2]
    recs = read_dbf(shp[:-4] + '.dbf')
    width = round(360 / STEP)
    height = round(180 / STEP)
    grid = array.array('H', bytes(2 * width * height))
    # Ecoregions split into several records (Rock and Ice) share one table row.
    rows = {}
    subs = {}
    for i, (bbox, rings) in enumerate(records(shp)):
        if bbox is None:
            continue
        rec = recs[i]
        key = int(rec['ECO_ID'])
        row = rows.setdefault(key, len(rows) + 1)
        sub = sub_biome(rec, bbox)
        if subs.get(key, '-') == '-':
            subs[key] = sub
        burn(grid, width, height, rings, row)
    coast_fill(grid, width, height)
    by_row = {}
    for rec in recs:
        key = int(rec['ECO_ID'])
        if key in rows:
            by_row[rows[key]] = rec
    with open(os.path.join(out, 'ecoregions.tsv'), 'w') as f:
        f.write('row\teco_id\tbiome\tsub\trealm\tname\n')
        for row in sorted(by_row):
            rec = by_row[row]
            biome = int(float(rec['BIOME_NUM'] or 0)) if rec['BIOME_NAME'] != 'N/A' else 0
            f.write('%d\t%s\t%d\t%s\t%s\t%s\n' % (row, rec['ECO_ID'], biome, subs[int(rec['ECO_ID'])],
                                                  rec['REALM'] or '-', rec['ECO_NAME']))
    if sys.byteorder != 'little':
        grid.byteswap()
    body = zlib.compress(grid.tobytes(), 9)
    with open(os.path.join(out, 'ecoregions.bin'), 'wb') as f:
        f.write(b'REEC' + struct.pack('>ii3d', width, height, NORTH, WEST, STEP) + body)
    if sys.byteorder != 'little':
        grid.byteswap()
    print('%d ecoregions, %d x %d cells, %d bytes' % (len(rows), width, height, 12 + 24 + len(body)))
    for name, lat, lon in PLACES:
        v = grid[int((NORTH - lat) / STEP) * width + int((lon - WEST) / STEP)]
        rec = by_row.get(v)
        print('  %-18s %s' % (name, '-' if rec is None else '%s (biome %s, %s)' % (
            rec['ECO_NAME'], int(float(rec['BIOME_NUM'] or 0)), subs[int(rec['ECO_ID'])])))


if __name__ == '__main__':
    main()
