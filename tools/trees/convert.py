"""Converts a pack of tree schematics into vanilla structure templates for Project Gaia: Earth's trees.

Reads Sponge schematics (.schem, versions 1-3) and vanilla structure files (.nbt) and writes each tree as a
vanilla structure template, made ready for world generation to grow:

1. Ground: where the trunk meets the ground, so roots go under it and the trunk stands on it. Taken from soil
   the builder left under the tree; else from the paste origin WorldEdit saved, when it lies at the foot of
   the trunk and the pack's builder saved it there (sources.tsv, "ground"); else from the logs: a root flare,
   the bottom layers clearly wider than the trunk, goes under the ground.
2. Soil under the ground is dropped, so the world's own ground stays.
3. Leaves: their decay distance is recomputed as the game does it (steps through leaves to the nearest log), so
   leaves in reach of a log decay once it is chopped, like a sapling's tree. Builders' leaves are saved as
   player-placed (persistent); leaves out of reach of every log stay persistent so they never decay, and are
   counted.
4. Only the tree: blocks reached from the trunk through anything but ground -- soil, sand, gravel, paths, snow,
   stone and bricks, ground plants -- so a builder's display plinth, paths and flowers stay behind.
5. Width: world generation writes at most one chunk beyond the chunk it decorates, so a tree's crown can reach
   23 blocks from its trunk only when the trunk stands mid-chunk (the mod grows such trees only there); a tree
   reaching further is skipped and listed.

Only packs whose terms allow it are converted (sources.tsv, status "ok"). --unapproved also converts packs
waiting for permission ("ask"), for local testing only: their output must not be committed. Packs marked "no"
are never converted.

    python3 convert.py <sources.tsv> <pack id> <pack folder> <out folder> [--unapproved]
    python3 convert.py --self-test

Output, in <out folder>/<pack id>/: each tree as <its path in the pack>.nbt, keeping the data version of the
Minecraft its schematic was saved in, so the game upgrades blocks renamed since when it loads the tree (short
grass was "grass" before 1.20.3); and trees.json listing per tree its size, the foot of its trunk in the template ("base"), how the
ground was found, height above and depth below the ground, reach from the trunk, main log and leaf blocks,
and leaf counts; and every file skipped, with why.
"""
import csv
import gzip
import json
import os
import re
import struct
import sys
import tempfile
from collections import Counter

DATA_VERSION = 4786  # Minecraft 26.1
MAX_REACH = 23  # a trunk mid-chunk has 23 blocks of room each way within world generation's reach
MAX_ROOTS = 3  # layers of root flare at most
ROOT_SCAN = 6  # bottom layers looked at for the trunk's width
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void"}
SOIL = {"minecraft:dirt", "minecraft:grass_block", "minecraft:podzol", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
        "minecraft:mud", "minecraft:moss_block", "minecraft:mycelium"}
SIDES = ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))


class Skip(Exception):
    pass


# NBT, typed enough to write back what was read.
class Byte(int):
    pass


class Short(int):
    pass


class Long(int):
    pass


class Float(float):
    pass


class IntArray(list):
    pass


class LongArray(list):
    pass


class List(list):
    def __init__(self, items=(), kind=0):
        super().__init__(items)
        self.kind = kind


SCALARS = {1: (">b", 1, Byte), 2: (">h", 2, Short), 3: (">i", 4, int), 4: (">q", 8, Long), 5: (">f", 4, Float),
           6: (">d", 8, float)}


def read_nbt(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    if not data or data[0] != 10:
        raise ValueError("not NBT")
    return read_tag(data, 3 + struct.unpack_from(">H", data, 1)[0], 10)[0]


def read_tag(data, pos, kind):
    if kind in SCALARS:
        fmt, size, cls = SCALARS[kind]
        return cls(struct.unpack_from(fmt, data, pos)[0]), pos + size
    if kind in (7, 11, 12):
        n = struct.unpack_from(">i", data, pos)[0]
        pos += 4
        if kind == 7:
            return bytes(data[pos:pos + n]), pos + n
        size = 4 if kind == 11 else 8
        values = struct.unpack_from(">%d%s" % (n, "i" if kind == 11 else "q"), data, pos)
        return (IntArray if kind == 11 else LongArray)(values), pos + n * size
    if kind == 8:
        n = struct.unpack_from(">H", data, pos)[0]
        return data[pos + 2:pos + 2 + n].decode("utf-8", "replace"), pos + 2 + n
    if kind == 9:
        elem, n = data[pos], struct.unpack_from(">i", data, pos + 1)[0]
        pos += 5
        items = []
        for _ in range(n):
            item, pos = read_tag(data, pos, elem)
            items.append(item)
        return List(items, elem), pos
    if kind == 10:
        out = {}
        while True:
            tag = data[pos]
            if tag == 0:
                return out, pos + 1
            n = struct.unpack_from(">H", data, pos + 1)[0]
            name = data[pos + 3:pos + 3 + n].decode("utf-8", "replace")
            out[name], pos = read_tag(data, pos + 3 + n, tag)
    raise ValueError("unknown NBT tag %d" % kind)


def tag_kind(value):
    for cls, kind in ((Byte, 1), (Short, 2), (Long, 4), (Float, 5), (IntArray, 11), (LongArray, 12), (List, 9),
                      (bool, 1), (int, 3), (float, 6), (bytes, 7), (str, 8), (list, 9), (dict, 10)):
        if isinstance(value, cls):
            return kind
    raise TypeError("no NBT tag for %r" % (value,))


def write_tag(out, value, kind):
    if kind in SCALARS:
        out += struct.pack(SCALARS[kind][0], value)
    elif kind == 7:
        out += struct.pack(">i", len(value)) + value
    elif kind == 8:
        raw = value.encode("utf-8")
        out += struct.pack(">H", len(raw)) + raw
    elif kind == 9:
        elem = value.kind if isinstance(value, List) and value.kind else (tag_kind(value[0]) if value else 0)
        out += struct.pack(">Bi", elem, len(value))
        for item in value:
            write_tag(out, item, elem)
    elif kind == 10:
        for name, item in value.items():
            raw, item_kind = name.encode("utf-8"), tag_kind(item)
            out += struct.pack(">BH", item_kind, len(raw)) + raw
            write_tag(out, item, item_kind)
        out.append(0)
    else:
        out += struct.pack(">i%d%s" % (len(value), "i" if kind == 11 else "q"), len(value), *value)


def write_nbt(path, value):
    out = bytearray(b"\x0a\x00\x00")
    write_tag(out, value, 10)
    with gzip.open(path, "wb") as f:
        f.write(out)


def parse_state(state):
    """'minecraft:oak_leaves[distance=1,persistent=false]' as ('minecraft:oak_leaves', {'distance': '1', ...})."""
    name, _, rest = state.partition("[")
    props = dict(p.split("=", 1) for p in rest.rstrip("]").split(",") if "=" in p)
    return (name if ":" in name else "minecraft:" + name), props


def load(path):
    """A schematic's blocks, {(x, y, z): [name, properties, block entity or None]}, its paste origin if saved, and
    the data version of the Minecraft it was saved in."""
    root = read_nbt(path)
    if isinstance(root.get("Regions"), dict):
        return load_litematic(root), None, min(root.get("MinecraftDataVersion", DATA_VERSION), DATA_VERSION)
    s = root["Schematic"] if isinstance(root.get("Schematic"), dict) else root
    # Saved in a newer Minecraft than ours: the game can't fix it down, so take it as ours (blocks it lacks are dropped).
    version = min(s.get("DataVersion", DATA_VERSION), DATA_VERSION)
    if isinstance(s.get("Blocks"), dict):  # Sponge v3: the paster stands at -Offset from the minimum corner
        palette, data, entities = s["Blocks"]["Palette"], s["Blocks"]["Data"], s["Blocks"].get("BlockEntities", [])
        origin = tuple(-v for v in s["Offset"]) if "Offset" in s else None
    elif "BlockData" in s:  # Sponge v1 and v2: WorldEdit keeps the paster's offset in its metadata
        palette, data = s["Palette"], s["BlockData"]
        entities = s.get("BlockEntities", s.get("TileEntities", []))
        meta = s.get("Metadata", {})
        origin = tuple(-meta["WEOffset" + a] for a in "XYZ") if "WEOffsetX" in meta else None
    elif "palette" in s or "palettes" in s:
        return load_structure(s), None, version
    elif "Materials" in s:
        return load_legacy(s), None, DATA_VERSION
    else:
        raise ValueError("not a schematic")
    width, length = s["Width"] & 0xFFFF, s["Length"] & 0xFFFF
    states = {i: parse_state(state) for state, i in palette.items()}
    blocks = {}
    i = index = 0
    while i < len(data):
        value = shift = 0
        while True:
            byte = data[i]
            i += 1
            value |= (byte & 0x7F) << shift
            shift += 7
            if byte < 0x80:
                break
        name, props = states[value]
        if name not in AIR:
            blocks[(index % width, index // (width * length), index // width % length)] = [name, dict(props), None]
        index += 1
    for e in entities:
        pos = tuple(e["Pos"])
        if pos in blocks:
            nbt = dict(e.get("Data", e))
            nbt.pop("Pos", None)
            nbt.pop("Id", None)
            nbt["id"] = e["Id"]
            blocks[pos][2] = nbt
    return blocks, origin, version


# Pre-1.13 numeric block ids and data values, for the blocks trees are built of; anything else is reported.
LEGACY_WOOD = {17: ("oak", "spruce", "birch", "jungle"), 162: ("acacia", "dark_oak")}
LEGACY_LEAVES = {18: ("oak", "spruce", "birch", "jungle"), 161: ("acacia", "dark_oak")}
LEGACY_FENCES = {85: "oak", 188: "spruce", 189: "birch", 190: "jungle", 191: "dark_oak", 192: "acacia"}
COLOURS = ("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue",
           "brown", "green", "red", "black")


def legacy_block(block, data):
    """A pre-1.13 block id and data value as today's name and properties."""
    if block in LEGACY_WOOD and data & 3 < len(LEGACY_WOOD[block]):
        wood, axis = LEGACY_WOOD[block][data & 3], data >> 2
        return ("minecraft:%s_wood" % wood, {"axis": "y"}) if axis == 3 else ("minecraft:%s_log" % wood, {"axis": "yxz"[axis]})
    if block in LEGACY_LEAVES and data & 3 < len(LEGACY_LEAVES[block]):
        return "minecraft:%s_leaves" % LEGACY_LEAVES[block][data & 3], {}
    if block in LEGACY_FENCES:
        return "minecraft:%s_fence" % LEGACY_FENCES[block], {}
    if block in (95, 160, 35):
        return "minecraft:%s_%s" % (COLOURS[data & 15], {95: "stained_glass", 160: "stained_glass_pane", 35: "wool"}[block]), {}
    if block in (2, 3):
        return ("minecraft:grass_block" if block == 2 else "minecraft:dirt"), {}
    if block == 106:
        return "minecraft:vine", {}
    raise ValueError("legacy MCEdit block id %d:%d isn't mapped yet" % (block, data))


def load_legacy(s):
    """An MCEdit schematic's blocks (pre-1.13, numeric ids), indexed like a Sponge schematic's."""
    width, length = s["Width"] & 0xFFFF, s["Length"] & 0xFFFF
    blocks = {}
    for index, (block, data) in enumerate(zip(s["Blocks"], s["Data"])):
        if block:
            name, props = legacy_block(block, data & 15)
            blocks[(index % width, index // (width * length), index // width % length)] = [name, props, None]
    return blocks


def load_litematic(root):
    """A Litematica schematic's blocks: each region's states packed into longs, regions placed where they lie."""
    regions = []
    for r in root["Regions"].values():
        size = [r["Size"][a] for a in "xyz"]
        corner = [r["Position"][a] + min(0, size[i] + 1) for i, a in enumerate("xyz")]
        regions.append((corner, [abs(v) for v in size], r))
    low = [min(c[i] for c, _, _ in regions) for i in range(3)]
    blocks = {}
    for corner, (sx, sy, sz), r in regions:
        palette = [(p["Name"], {k: str(v) for k, v in p.get("Properties", {}).items()}) for p in r["BlockStatePalette"]]
        bits = max(2, (len(palette) - 1).bit_length())
        longs = [v & 0xFFFFFFFFFFFFFFFF for v in r["BlockStates"]]
        mask = (1 << bits) - 1
        for i in range(sx * sy * sz):
            at, shift = divmod(i * bits, 64)
            value = longs[at] >> shift
            if shift + bits > 64:
                value |= longs[at + 1] << (64 - shift)
            name, props = palette[value & mask]
            if name not in AIR:
                x, z, y = i % sx, i // sx % sz, i // (sx * sz)
                blocks[(corner[0] - low[0] + x, corner[1] - low[1] + y, corner[2] - low[2] + z)] = [name, dict(props), None]
    return blocks


def load_structure(s):
    palette = s["palette"] if "palette" in s else s["palettes"][0]
    states = [(p["Name"], {k: str(v) for k, v in p.get("Properties", {}).items()}) for p in palette]
    blocks = {}
    for b in s["blocks"]:
        name, props = states[b["state"]]
        if name not in AIR:
            blocks[tuple(b["pos"])] = [name, dict(props), b.get("nbt")]
    return blocks


def is_log(name):
    """In the game's #logs, which leaves count their distance from."""
    return name.endswith(("_log", "_wood", "_hyphae", "crimson_stem", "warped_stem"))


def is_leaves(name):
    return name.endswith("_leaves")


def neighbours(p):
    return [(p[0] + dx, p[1] + dy, p[2] + dz) for dx, dy, dz in SIDES]


def find_base(blocks, origin, ground=""):
    """The foot of the trunk -- its lowest block above the ground -- and how the ground was found.

    ground is the pack's setting in sources.tsv: "" trusts a saved paste origin at the foot of the trunk, "roots"
    ignores it (the pack's builder always saved from the bottom), "bottom" puts the lowest layer on the ground.
    """
    ys = [p[1] for p in blocks]
    leaf_ys = [p[1] for p, b in blocks.items() if is_leaves(b[0])]
    top = min(leaf_ys) if leaf_ys else max(ys) + 1
    logs = [p for p, b in blocks.items() if is_log(b[0])]
    soil = [p[1] for p, b in blocks.items() if b[0] in SOIL and p[1] < top]
    if soil:
        y, how = max(soil) + 1, "soil"
    elif ground == "" and origin and min(ys) <= origin[1] <= top and any(
            abs(p[0] - origin[0]) <= 2 and abs(p[2] - origin[2]) <= 2 and abs(p[1] - origin[1]) <= 1 for p in logs):
        y, how = origin[1], "origin"
    elif ground == "bottom" or not logs:
        y, how = min(ys), "bottom"
    else:
        y, how = root_flare(logs, min(ys), top), "roots"
    trunk = [p for p in logs if p[1] >= y]
    if trunk:
        lowest = min(p[1] for p in trunk)
        foot = [p for p in trunk if p[1] == lowest]
        x, z = sum(p[0] for p in foot) // len(foot), sum(p[2] for p in foot) // len(foot)
    else:
        x = (min(p[0] for p in blocks) + max(p[0] for p in blocks)) // 2
        z = (min(p[2] for p in blocks) + max(p[2] for p in blocks)) // 2
    return (x, y, z), how


def root_flare(logs, bottom, top):
    """The first layer above the roots: bottom layers clearly wider than the trunk (at least half as wide again,
    and more than a log wider), at most three. A trunk swelling gradually towards the ground, a baobab's or a
    beech's, doesn't count."""
    counts = Counter(p[1] for p in logs)
    trunk = min((counts[y] for y in range(bottom, min(top, bottom + ROOT_SCAN)) if counts[y]), default=0)
    y = bottom
    while y < bottom + MAX_ROOTS and counts[y] > trunk + 1 and counts[y] >= 1.5 * trunk:
        y += 1
    return y


def relink_leaves(blocks):
    """Sets each leaf's decay distance as the game would; returns how many are out of reach of every log."""
    frontier = [p for p, b in blocks.items() if is_leaves(b[0]) and any(is_log(blocks.get(n, ("",))[0]) for n in neighbours(p))]
    dist = dict.fromkeys(frontier, 1)
    for d in range(2, 7):
        reached = []
        for p in frontier:
            for n in neighbours(p):
                if n not in dist and n in blocks and is_leaves(blocks[n][0]):
                    dist[n] = d
                    reached.append(n)
        frontier = reached
    stuck = 0
    for p, b in blocks.items():
        if is_leaves(b[0]):
            d = dist.get(p, 7)
            b[1]["distance"] = str(d)
            b[1]["persistent"] = "false" if d < 7 else "true"
            stuck += d == 7
    return stuck


GROUND_BLOCKS = SOIL | {"minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:dirt_path", "minecraft:farmland",
                        "minecraft:snow_block", "minecraft:snow", "minecraft:clay", "minecraft:end_stone", "minecraft:brown_mushroom",
                        "minecraft:red_mushroom"}
GROUND_PLANTS = ("short_grass", "tall_grass", "dry_grass", "fern", "bush", "leaf_litter", "petals", "wildflowers", "moss_carpet",
                 "dandelion", "poppy", "orchid", "allium", "bluet", "tulip", "daisy", "cornflower", "lily_of_the_valley",
                 "torchflower", "sunflower", "lilac", "peony")


def is_ground(name):
    """Ground, paving, stone or a ground plant: never part of a tree, however close to its trunk."""
    if name in GROUND_BLOCKS or any(name.endswith(plant) for plant in GROUND_PLANTS):
        return True
    path = name.split(":")[-1]
    stonework = path.startswith(("polished_", "chiseled_", "cut_", "smooth_")) or "cobblestone" in path
    return stonework or path.endswith(("stone", "_bricks", "tuff", "deepslate", "andesite", "diorite", "granite", "calcite",
                                       "basalt", "terracotta", "concrete", "_tiles", "_slab", "_stairs"))


def tree_only(blocks, base):
    """The blocks reached from the trunk's foot through anything but ground, side, edge or corner on."""
    near = [p for p, b in blocks.items() if not is_ground(b[0]) and abs(p[0] - base[0]) <= 2 and abs(p[2] - base[2]) <= 2
            and base[1] - 1 <= p[1] <= base[1] + 2]
    frontier = near or [p for p, b in blocks.items() if is_log(b[0])]
    kept = set(frontier)
    while frontier:
        reached = []
        for x, y, z in frontier:
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for dz in (-1, 0, 1):
                        n = (x + dx, y + dy, z + dz)
                        if n not in kept and n in blocks and not is_ground(blocks[n][0]):
                            kept.add(n)
                            reached.append(n)
        frontier = reached
    return {p: blocks[p] for p in kept}


def convert(blocks, origin, ground="", version=DATA_VERSION):
    """A tree as a vanilla structure template, and what trees.json says about it."""
    base, how = find_base(blocks, origin, ground)
    blocks = tree_only(blocks, base)
    if not blocks:
        raise Skip("no tree left once its ground is gone")
    # The ground found may have been a builder's plinth, now gone: the tree mustn't hang above where it stood.
    base = (base[0], max(base[1], min(p[1] for p in blocks)), base[2])
    reach = max(max(abs(p[0] - base[0]), abs(p[2] - base[2])) for p in blocks)
    if reach > MAX_REACH:
        raise Skip("reaches %d blocks from its trunk" % reach)
    stuck = relink_leaves(blocks)
    lo = [min(p[i] for p in blocks) for i in range(3)]
    hi = [max(p[i] for p in blocks) for i in range(3)]
    palette, index, entries = [], {}, []
    for p, (name, props, nbt) in sorted(blocks.items()):
        key = (name, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            palette.append({"Name": name, "Properties": dict(props)} if props else {"Name": name})
        entry = {"pos": List([p[i] - lo[i] for i in range(3)], 3), "state": index[key]}
        if nbt is not None:
            entry["nbt"] = nbt
        entries.append(entry)
    size = [hi[i] - lo[i] + 1 for i in range(3)]
    template = {"DataVersion": version, "size": List(size, 3), "palette": List(palette, 10),
                "blocks": List(entries, 10), "entities": List([], 10)}
    names = Counter(b[0] for b in blocks.values())
    info = {
        "size": size,
        "base": [base[i] - lo[i] for i in range(3)],
        "ground": how,
        "height": hi[1] - base[1] + 1,
        "depth": base[1] - lo[1],
        "reach": reach,
        "log": next((n for n, _ in names.most_common() if is_log(n)), None),
        "leaves": next((n for n, _ in names.most_common() if is_leaves(n)), None),
        "leaf_count": sum(c for n, c in names.items() if is_leaves(n)),
        "stuck_leaves": stuck,
    }
    return template, info


def main(sources, pack, folder, out, unapproved=False):
    with open(sources, newline="", encoding="utf-8") as f:
        rows = {r["id"]: r for r in csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE)}
    row = rows.get(pack)
    if row is None:
        sys.exit("%s is not in %s: add it with its terms first" % (pack, sources))
    if row["status"] == "no":
        sys.exit("%s: its terms don't allow it (%s)" % (pack, sources))
    if row["status"] != "ok" and not unapproved:
        sys.exit("%s: waiting for permission (%s); --unapproved converts it for local testing only" % (pack, sources))
    target = os.path.join(out, pack)
    trees, skipped, written = [], [], set()
    for dirpath, _, files in sorted(os.walk(folder)):
        for filename in sorted(files):
            if not filename.lower().endswith((".schem", ".schematic", ".nbt", ".litematic")):
                continue
            src = os.path.join(dirpath, filename)
            rel = os.path.relpath(src, folder)
            name = re.sub(r"[^a-z0-9/._-]", "_", os.path.splitext(rel)[0].replace(os.sep, "/").lower())
            try:
                if name in written:
                    raise Skip("same name as another tree once converted")
                blocks, origin, version = load(src)
                if not blocks:
                    raise Skip("empty")
                template, info = convert(blocks, origin, row.get("ground") or "", version)
            except (Skip, ValueError, KeyError, IndexError, struct.error) as e:
                skipped.append({"source": rel, "reason": "%s: %s" % (type(e).__name__, e)})
                continue
            path = os.path.join(target, name + ".nbt")
            os.makedirs(os.path.dirname(path), exist_ok=True)
            write_nbt(path, template)
            written.add(name)
            trees.append(dict(file=name + ".nbt", source=rel, **info))
    os.makedirs(target, exist_ok=True)
    with open(os.path.join(target, "trees.json"), "w", encoding="utf-8") as f:
        json.dump({"pack": pack, "trees": trees, "skipped": skipped}, f, indent=1)
    print("%s: %d trees, %d skipped" % (pack, len(trees), len(skipped)))
    print("  ground found by: %s" % dict(Counter(t["ground"] for t in trees)))
    if trees:
        print("  height %d-%d, reach up to %d, leaves out of reach of a log: %d of %d" % (
            min(t["height"] for t in trees), max(t["height"] for t in trees), max(t["reach"] for t in trees),
            sum(t["stuck_leaves"] for t in trees), sum(t["leaf_count"] for t in trees)))
    for reason, n in Counter(s["reason"] for s in skipped).most_common():
        print("  skipped %d: %s" % (n, reason))
    return trees, skipped


def self_test():
    """Converts a few made-up trees and checks the ground, the leaves and the templates."""
    log, leaf = "minecraft:oak_log[axis=y]", "minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]"
    tree = {}
    for x, z in [(4, 4), (3, 4), (5, 4), (4, 3), (4, 5), (2, 4), (6, 4)]:
        tree[(x, 0, z)] = log  # roots spreading under the ground
    for x in (3, 4, 5):
        tree[(x, 1, 4)] = log
    for y in range(2, 9):
        tree[(4, y, 4)] = log
    for y in range(6, 10):
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                if (4 + dx, y, 4 + dz) not in tree and abs(dx) + abs(dz) <= 3:
                    tree[(4 + dx, y, 4 + dz)] = leaf
    for x in range(7, 13):
        tree[(x, 9, 4)] = leaf  # an arm of leaves: its last three are out of reach of any log
    with_soil = dict(tree)
    for x in range(1, 8):
        for z in range(1, 8):
            for y in (0, 1):
                with_soil.setdefault((x, y, z), "minecraft:dirt")

    def varints(values):
        out = bytearray()
        for v in values:
            while v >= 0x80:
                out.append(v & 0x7F | 0x80)
                v >>= 7
            out.append(v)
        return bytes(out)

    def sponge(blocks, size, version, offset=None, data_version=DATA_VERSION):
        palette = {"minecraft:air": 0}
        for state in blocks.values():
            palette.setdefault(state, len(palette))
        w, h, length = size
        data = varints(palette[blocks.get((x, y, z), "minecraft:air")]
                       for y in range(h) for z in range(length) for x in range(w))
        s = {"Version": version, "DataVersion": data_version, "Width": Short(w), "Height": Short(h), "Length": Short(length)}
        if version == 3:
            s["Blocks"] = {"Palette": palette, "Data": data, "BlockEntities": List([], 10)}
            if offset:
                s["Offset"] = IntArray(offset)
            return {"Schematic": s}
        s.update({"PaletteMax": len(palette), "Palette": palette, "BlockData": data, "Offset": IntArray([100, 64, 100])})
        if offset:
            s["Metadata"] = {"WEOffsetX": offset[0], "WEOffsetY": offset[1], "WEOffsetZ": offset[2]}
        return s

    wide = {(0, 0, 0): log, (1, 0, 0): log}
    wide.update({(x, 1, 0): leaf for x in range(0, 26)})
    with tempfile.TemporaryDirectory() as tmp:
        packs, out = os.path.join(tmp, "pack"), os.path.join(tmp, "out")
        os.makedirs(os.path.join(packs, "Oaks"))
        write_nbt(os.path.join(packs, "Oaks", "Oak 1.schem"), sponge(tree, (13, 12, 9), 2, [-4, -2, -4], 3465))
        write_nbt(os.path.join(packs, "Oaks", "oak_soil.schem"), sponge(with_soil, (13, 12, 9), 3))
        write_nbt(os.path.join(packs, "oak_plain.schem"), sponge(tree, (13, 12, 9), 3))
        write_nbt(os.path.join(packs, "wide.schem"), sponge(wide, (26, 2, 1), 3))
        sources = os.path.join(tmp, "sources.tsv")
        with open(sources, "w", encoding="utf-8") as f:
            f.write('id\tstatus\tauthor\turl\tterms\tnote\tground\ntest\tok\t-\t-\tuse "freely"\t\t\n'
                    'held\task\t-\t-\t-\t\t\nbanned\tno\t-\t-\t-\t\t\nflat\tok\t-\t-\t-\t\tbottom\n'
                    'rooted\tok\t-\t-\t-\t\troots\n')
        for pack, flag in (("banned", True), ("held", False), ("missing", True)):
            try:
                main(sources, pack, packs, out, flag)
                raise AssertionError("converted %s" % pack)
            except SystemExit:
                pass
        trees, skipped = main(sources, "test", packs, out)
        by_file = {t["file"]: t for t in trees}
        assert sorted(by_file) == ["oak_plain.nbt", "oaks/oak_1.nbt", "oaks/oak_soil.nbt"], sorted(by_file)
        assert [s["source"] for s in skipped] == ["wide.schem"] and "reaches 25" in skipped[0]["reason"], skipped
        for file, how in (("oaks/oak_1.nbt", "origin"), ("oaks/oak_soil.nbt", "soil"), ("oak_plain.nbt", "roots")):
            t = by_file[file]
            assert (t["ground"], t["base"], t["height"], t["depth"]) == (how, [2, 2, 2], 8, 2), (file, t)
            assert (t["stuck_leaves"], t["log"], t["leaves"]) == (3, "minecraft:oak_log", "minecraft:oak_leaves"), t
            template = read_nbt(os.path.join(out, "test", file))
            assert template["DataVersion"] == (3465 if file == "oaks/oak_1.nbt" else DATA_VERSION), template["DataVersion"]
            assert template["size"] == [11, 10, 5], template["size"]
            states = {tuple(b["pos"]): template["palette"][b["state"]] for b in template["blocks"]}
            assert len(states) == len(tree), (file, len(states))
            assert states[(2, 9, 2)]["Properties"] == {"distance": "1", "persistent": "false", "waterlogged": "false"}
            assert states[(4, 7, 2)]["Properties"]["distance"] == "2"
            assert states[(10, 9, 2)]["Properties"] == {"distance": "7", "persistent": "true", "waterlogged": "false"}
            assert states[(2, 0, 2)] == {"Name": "minecraft:oak_log", "Properties": {"axis": "y"}}
        flat = {t["file"]: t for t in main(sources, "flat", packs, out)[0]}
        assert (flat["oak_plain.nbt"]["ground"], flat["oak_plain.nbt"]["depth"]) == ("bottom", 0), flat["oak_plain.nbt"]
        rooted = {t["file"]: t for t in main(sources, "rooted", packs, out)[0]}
        assert (rooted["oaks/oak_1.nbt"]["ground"], rooted["oaks/oak_1.nbt"]["depth"]) == ("roots", 2), rooted["oaks/oak_1.nbt"]

    plinth = {(x, 0, z): ["minecraft:polished_tuff", {}, None] for x in range(-20, 21) for z in range(-20, 21)}
    plinth.update({(x, 1, 0): ["minecraft:dirt_path", {}, None] for x in range(-20, 21)})
    plinth.update({(15, 1, 15): ["minecraft:poppy", {}, None], (0, 1, 1): ["minecraft:short_grass", {}, None]})
    plinth.update({(0, y, 0): ["minecraft:oak_log", {"axis": "y"}, None] for y in range(1, 6)})
    plinth.update({(dx, 5, dz): ["minecraft:oak_leaves", {}, None] for dx in (-1, 0, 1) for dz in (-1, 0, 1) if dx or dz})
    kept = tree_only(plinth, (0, 1, 0))
    assert sorted({b[0] for b in kept.values()}) == ["minecraft:oak_leaves", "minecraft:oak_log"] and len(kept) == 13, kept
    on_plinth = convert(plinth, None, "roots")[1]
    assert (on_plinth["base"], on_plinth["depth"], on_plinth["height"]) == ([1, 0, 1], 0, 5), on_plinth

    # MCEdit's numeric ids: a jungle log lying along x, acacia bark, persistent birch leaves, a spruce fence.
    legacy = load_legacy({"Width": Short(4), "Height": Short(1), "Length": Short(1), "Blocks": bytes([17, 162, 18, 188]),
                          "Data": bytes([3 | 4, 0 | 12, 2 | 4, 0])})
    assert [legacy[(x, 0, 0)][:2] for x in range(4)] == [["minecraft:jungle_log", {"axis": "x"}], ["minecraft:acacia_wood", {"axis": "y"}],
                                                       ["minecraft:birch_leaves", {}], ["minecraft:spruce_fence", {}]], legacy

    # Litematica: states packed across longs, a region whose size runs negative from its position.
    def packed(values, bits):
        n = sum(v << (i * bits) for i, v in enumerate(values))
        longs = [(n >> (64 * k)) & 0xFFFFFFFFFFFFFFFF for k in range(-(-len(values) * bits // 64))]
        return LongArray(v - (1 << 64) if v >= 1 << 63 else v for v in longs)

    palette = [{"Name": "minecraft:air"}, {"Name": "minecraft:oak_log", "Properties": {"axis": "y"}}, {"Name": "minecraft:oak_leaves"},
               {"Name": "minecraft:birch_log", "Properties": {"axis": "x"}}, {"Name": "minecraft:vine"}]
    values = [(i * 7) % 5 for i in range(25)]  # 3 bits each: the 22nd straddles two longs
    region = {"Position": {"x": 3, "y": 4, "z": 0}, "Size": {"x": 5, "y": -5, "z": 1}, "BlockStatePalette": List(palette, 10),
              "BlockStates": packed(values, 3)}
    lite = load_litematic({"Regions": {"tree": region}})
    for i, v in enumerate(values):
        got = lite.get((i % 5, i // 5, 0))
        assert (got[0] if got else "minecraft:air") == palette[v]["Name"], (i, v, got)
    print("self-test passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    else:
        args = [a for a in sys.argv[1:] if a != "--unapproved"]
        if len(args) != 4:
            sys.exit(__doc__)
        main(*args, unapproved="--unapproved" in sys.argv)
