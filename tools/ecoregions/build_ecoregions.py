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
    # Kauri forest: Northland's, the kauri among podocarps.
    'kauri': {173},
    # Araucaria forest: southern Brazil's Parana pine highlands.
    'araucaria': {440},
    # Campinarana: the Rio Negro's stunted forest on white sand.
    'campinarana': {498},
    # Lichen woodland: open spruce over lichen, the northern Canadian Shield's taiga.
    'lichen': {379, 374, 381, 383},
    # Gibber plain: Australia's stony desert of polished pebbles.
    'gibber': {215},
    # Reed marsh: the Tigris and Euphrates' freshwater marshes.
    'reed': {747},
    # Temperate rainforests -- the Pacific coast from Alaska to Oregon, Norway's coast, southern Chile, New Zealand,
    # Tasmania's west -- wet all year, mossy, tall.
    'rainforest': {349, 351, 360, 365, 708, 561, 563, 167, 169, 170, 171, 172, 174, 175, 179, 180},
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
    'oak_savanna': {403, 390, 391, 387, 343, 400, 385},
    # Tallgrass prairie: the wetter, eastern prairies of North America, and the Gulf's coastal prairie.
    'prairie': {388, 392, 397, 401, 384},
    # Pine savanna: open longleaf and Caribbean pine over grass -- the US South's coastal plain, the pine barrens,
    # Belize's and the Miskito Coast's pine savannas.
    'pine_savanna': {399, 564, 573, 347, 363, 393},
    # Fynbos and renosterveld: the Cape's fine-leaved shrubland.
    'fynbos': {89, 90},
    # Miombo: the Brachystegia woodland of south-central Africa.
    'miombo': {36, 39, 42},
    # Cerrado: Brazil's savanna woodland of twisted, thick-barked trees.
    'cerrado': {567, 566},
    # Thorn scrub: acacia and commiphora bush -- East Africa, the Horn, India's thorn forests, the Chaco, the Caatinga,
    # Madagascar's spiny thickets, Argentina's espinal.
    'thorn': {51, 55, 57, 45, 50, 314, 315, 605, 569, 525, 99, 575},
    # Cactus scrub: the deserts and matorral of Mexico and its borderlands, the Caribbean's and Venezuela's cardonales,
    # the Galapagos.
    'cactus': {435, 428, 426, 431, 607, 427, 432, 436, 437, 610, 600, 599, 597, 602, 603, 606, 601},
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
    'salt': {69, 70, 73, 745, 742, 748, 312},
    # Polar desert: the bare high Arctic, the Canadian High Arctic's polar semi-desert.
    'polar': {777, 778, 783, 412},
    # Beech forest: Europe's and Japan's beechwoods -- the English lowlands, the Apennines, western Europe, the Dinarides,
    # the Black Sea's and the Caspian's shores, Japan's mountains.
    'beech': {663, 644, 686, 671, 683, 665, 649, 660},
    # Mountain birch forest: the birch belt of the Scandinavian mountains, Kamchatka's stone birch.
    'mountain_birch': {780, 713},
    # Mediterranean conifer forest: the Atlas and Rif cedars, Iberia's pines, the black pines, firs and cedars of the
    # Taurus, Lebanon, Cyprus, Corsica and the Pindus, the Canary pine.
    'med_conifer': {701, 792, 791, 804, 786, 788, 801, 787},
    # Dehesa and montado: southwest Iberia's grazed oak savanna.
    'dehesa': {805},
    # Mallee: southern Australia's many-stemmed eucalypt scrub.
    'mallee': {198, 199, 201, 203},
    # Chaparral: California's dense evergreen shrubland.
    'chaparral': {422, 423, 424, 425},
    # Shortgrass and mixed-grass prairie: the drier, western Great Plains.
    'shortgrass': {396, 402},
    'mixed_grass': {389, 395, 394},
    # The pampas: the grasslands of Argentina and Uruguay.
    'pampas': {576, 574},
    # Patagonian steppe: the cold, windswept grass and shrub steppe east of the southern Andes.
    'patagonian': {578, 595},
    # Tussock grassland: New Zealand's tussock country, and the tussock of the sub-Antarctic islands.
    'tussock': {190, 194, 196, 133, 129},
    # Desert steppe: the cold semi-deserts of the Gobi, Central Asia and the Caspian.
    'desert_steppe': {824, 825, 826, 828, 724, 808, 827, 835, 807, 813, 829, 812, 815, 817, 819, 739, 740, 761},
    # Succulent thicket: South Africa's Albany thicket, Madagascar's succulent woodland.
    'succulent': {88, 100},
    # Alpine tundra: the treeless heights of the Alaskan, Yukon, Siberian and Labrador ranges, the Altai and Sayan.
    'alpine_tundra': {405, 411, 416, 419, 421, 771, 782, 410, 415, 749, 764},
    # Alpine shrubland: the Himalaya's and Tibet's rhododendron, juniper and willow scrub above the forest.
    'alpine_shrub': {751, 760, 769, 765, 768},
    # Xeric woodland: the open juniper, pistachio and acacia woodland of dry mountains, from the Sahara's to Afghanistan's.
    'xeric_woodland': {814, 816, 834, 844, 823, 846, 833, 106, 722, 723, 758, 730, 756, 757},
    # The llanos: the Orinoco's and Bolivia's seasonally flooded savannas, the Guianas', the humid Chaco's palm savanna.
    'llanos': {572, 565, 570, 571},
    # The Sahel: the dry acacia savanna south of the Sahara.
    'sahel': {53},
    # Forest-savanna mosaic: Africa's belt of gallery forest and savanna between rainforest and savanna.
    'forest_savanna': {44, 52, 58, 61, 63},
    # Mopane woodland: the mopane trees of the Zambezi and Angola.
    'mopane': {34, 65},
    # Bushveld: South Africa's and Zimbabwe's thorny savanna.
    'bushveld': {38, 48, 66},
    # The Kalahari: acacia savanna on red sand.
    'kalahari': {47, 97, 104},
    # Highveld grassland: South Africa's high treeless grassland.
    'highveld': {81, 41},
    # Oak woodland: the dry oak forest of the Zagros and Anatolia.
    'oak_woodland': {688, 662, 652},
    # Himalayan broadleaf forest: the oak, laurel and rhododendron forest of the Himalaya's middle hills.
    'himalayan_broadleaf': {306, 308},
    # Ponderosa pine forest: the open, park-like pine forest of the dry interior West.
    'ponderosa': {346, 362, 354, 348, 356},
    # Mitchell grass downs: Queensland's treeless cracking-clay grassland.
    'mitchell': {187},
    # Terai: the tall elephant grass and sal of the Himalayan foothills.
    'terai': {311},
    # Monte: Argentina's creosote and mesquite scrub.
    'monte': {577, 592},
    # Alpine desert: the cold, near-barren high plateaus of the Kunlun, northern Tibet and the Pamirs.
    'alpine_desert': {759, 762},
    # Afromontane grassland: the grassy highlands and forest patches of Africa's mountains.
    'afromontane': {77, 82, 84, 85, 87, 79},
    # Snow gum woodland: the Australian Alps.
    'snow_gum': {193},
    # Argan woodland: Morocco's argan and acacia woodland.
    'argan': {796},
    # Kwongan: southwest Australia's sandplain heath.
    'kwongan': {205},
    # The Mojave: Joshua trees and creosote bush.
    'mojave': {433},
    # Saltbush shrubland: the Nullarbor's treeless saltbush and bluebush plain.
    'saltbush': {212},
    # The Atlantic Forest (Mata Atlantica): Brazil's coastal rainforest.
    'atlantic': {439, 442, 443, 491, 492, 500},
    # Tepui: the table mountains of the Guiana Highlands.
    'tepui': {490},
    # Heath forest (kerangas): Borneo's and Sumatra's stunted forest on white sand.
    'heath_forest': {281},
    # Wet tundra: the Arctic's flat coastal tundra of sedge, moss and thaw lakes.
    'wet_tundra': {407, 784, 775, 781, 414},
    # Brigalow: Queensland's acacia scrub.
    'brigalow': {182},
    # Tropical alpine grassland: New Guinea's and Kinabalu's grass and heath above the forest.
    'tropical_alpine': {195, 313},
    # Sawgrass marsh: the Everglades.
    'sawgrass': {581},
    # Papyrus swamp: the Sudd.
    'papyrus': {74},
    # Wet meadow: the Amur's, the Nenjiang's and Lake Khanka's meadows.
    'wet_meadow': {741, 743, 746},
    # Palouse prairie: the bunchgrass hills of the inland Northwest.
    'palouse': {398},
    # Dark taiga: the fir, spruce and Siberian pine of the Russian Far East, Sakhalin, the Urals, the Sayan and the Altai.
    'dark_taiga': {715, 716, 719, 707, 690},
    # Pine-oak forest: the pines and oaks of the mountains of Mexico and Central America.
    'pine_oak': {553, 326, 327, 557, 558, 556, 559},
    # Mixed mesophytic forest: the Appalachians' rich coves of tulip tree, buckeye, basswood, maple and hemlock.
    'mesophytic': {329, 331},
    # Northern hardwood forest: maple, beech and yellow birch with hemlock and white pine -- New England, the Great Lakes,
    # the St. Lawrence, the Alleghenies.
    'northern_hardwood': {338, 344, 334, 335, 328},
    # Oak-hickory forest: the Ozarks, the Interior Plateau, the Piedmont, the southern Great Lakes.
    'oak_hickory': {336, 340, 341, 330, 342},
    # Korean pine forest: Manchuria's, the Ussuri's and Changbai's Korean pine among broadleaves.
    'korean_pine': {669, 685, 656},
    # The Loess Plateau: China's eroded yellow loess.
    'loess': {653},
    # Tugai: the poplar and tamarisk forest along Central Asia's desert rivers.
    'tugai': {684, 818},
    # Mixed evergreen forest: Douglas fir with tanoak and madrone in the Klamath and Siskiyou mountains.
    'mixed_evergreen': {357},
    # Himalayan conifer forest: the fir, hemlock and rhododendron of the Himalaya's and Hengduan's high slopes.
    'himalayan_conifer': {309, 702, 310, 697, 704, 706},
    # Babassu palm forest: Maranhao's.
    'babassu': {540},
    # Matorral: central Chile's shrubland.
    'matorral': {596},
    # Alfa steppe: the esparto grass of North Africa's high plateaus.
    'alfa': {797},
    # Dragon tree woodland: Socotra.
    'dragon_tree': {105},
    # Fog desert: the coastal deserts watered by fog -- the Atacama, the Sechura, the Namib, the Kaokoveld, the Saharan
    # Atlantic coast, eastern Arabia's fog shrublands.
    'fog_desert': {598, 608, 103, 98, 839, 821},
    # Restinga: Brazil's coastal forest and scrub on sand.
    'restinga': {441, 485},
    # Sudanian savanna: the shea and nere parkland south of the Sahel.
    'sudanian': {62, 43},
    # Dry dipterocarp forest: Indochina's open deciduous dipterocarp forest.
    'dry_dipterocarp': {291, 294, 300},
    # Baobab dry forest: western Madagascar's dry deciduous forest.
    'baobab': {32},
    # Baikiaea woodland: Zambezi teak on the Kalahari's sand.
    'baikiaea': {64},
    # Alpine steppe: the Tibetan Plateau's dry high grassland.
    'alpine_steppe': {750, 754, 770},
    # Alpine meadow: Central Asia's high summer pastures -- the Hindu Kush, the Khangai, the Qilian, the Tian Shan.
    'alpine_meadow': {752, 753, 755, 763, 766, 767},
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
    # Tropical montane forest: cloud forest from lower down than elsewhere.
    if biome == 1 and 'montane' in name:
        return 'montane'
    # Monsoon forest: the deciduous sal and teak forests of India and Indochina, bare in the dry season.
    if biome in (1, 2) and rec['REALM'] == 'Indomalayan' and 'deciduous' in name:
        return 'monsoon'
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
