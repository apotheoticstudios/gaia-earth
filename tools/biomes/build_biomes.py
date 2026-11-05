"""Writes Project Gaia: Earth's own biomes into the mod's data pack, from vanilla's.

Earth's biomes are the real world's (RESOLVE Ecoregions 2017's 14 biomes, and the forests some ecoregions are known
for; see world/EarthBiomes.java, which says where each one is and which trees grow in it). Each starts as a copy of a
vanilla biome (its ground cover, mobs, music and weather), takes the colours of the real landscape, and joins every
biome tag its vanilla parent is in, so foxes, rabbits, frogs, villages and structures treat it the same.

    python3 build_biomes.py <minecraft jar> <src/main/resources>

Writes data/gaia-earth/worldgen/biome/<name>.json, adds the biomes to vanilla's biome tags under
data/minecraft/tags/worldgen/biome/ (replace: false), and their names to assets/gaia-earth/lang/en_us.json. Run it
again after changing BIOMES or updating Minecraft.
"""
import json
import os
import sys
import zipfile

NAMESPACE = 'gaia-earth'
# name, vanilla parent, grass colour, foliage colour (None: the parent's, from its temperature and rainfall), name.
BIOMES = [
    ('tropical_rainforest', 'jungle', '#3fa52e', '#2b8a24', 'Tropical Rainforest'),
    ('cloud_forest', 'jungle', '#4a9c3c', '#2f7a34', 'Cloud Forest'),
    ('tropical_dry_forest', 'sparse_jungle', '#a8b04c', '#889a35', 'Tropical Dry Forest'),
    ('tropical_pine_forest', 'taiga', '#93ad4c', '#4e8a3c', 'Tropical Pine Forest'),
    ('temperate_deciduous_forest', 'forest', None, None, 'Temperate Deciduous Forest'),
    ('temperate_mixed_forest', 'forest', '#73b357', '#55a034', 'Temperate Mixed Forest'),
    ('temperate_rainforest', 'taiga', '#4c9a3a', '#2e7337', 'Temperate Rainforest'),
    ('hemiboreal_forest', 'taiga', '#7aae5a', '#4f8a45', 'Hemiboreal Forest'),
    ('laurel_forest', 'forest', '#5aa63e', '#2f7d2a', 'Laurel Forest'),
    ('eucalypt_forest', 'forest', '#9caa5e', '#7a9a6f', 'Eucalypt Forest'),
    ('temperate_conifer_forest', 'taiga', '#6c9a4c', '#4b7a46', 'Temperate Conifer Forest'),
    ('boreal_forest', 'taiga', None, None, 'Boreal Forest'),
    ('mediterranean_woodland', 'forest', '#a9b25a', '#6e8c48', 'Mediterranean Woodland'),
    ('tropical_savanna', 'savanna', None, None, 'Tropical Savanna'),
    ('eucalypt_savanna', 'savanna', '#c2b15e', '#8a9c6a', 'Eucalypt Savanna'),
    ('temperate_steppe', 'plains', '#b3b86a', '#8ca24c', 'Temperate Steppe'),
    ('flooded_grassland', 'swamp', '#5fa648', '#4f8f3a', 'Flooded Grassland'),
    ('montane_grassland', 'meadow', '#a3a866', '#6c8a50', 'Montane Grassland'),
    ('tundra', 'snowy_plains', '#9a9d68', '#78895a', 'Tundra'),
    ('xeric_shrubland', 'savanna', '#b8a967', '#8e9a5c', 'Xeric Shrubland'),
]
# The flooded grasslands' water is clear (the Pantanal, the Okavango), not a swamp's murk.
WATER = {'flooded_grassland': '#4a7f9a'}
BIOME_DIR = 'data/minecraft/worldgen/biome/'
TAG_DIR = 'data/minecraft/tags/worldgen/biome/'


def main():
    jar, resources = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(jar) as z:
        vanilla = {n[len(BIOME_DIR):-5]: json.loads(z.read(n)) for n in z.namelist() if n.startswith(BIOME_DIR) and n.endswith('.json')}
        tags = {n[len(TAG_DIR):-5]: json.loads(z.read(n)) for n in z.namelist() if n.startswith(TAG_DIR) and n.endswith('.json')}
    out_dir = os.path.join(resources, 'data', NAMESPACE, 'worldgen', 'biome')
    os.makedirs(out_dir, exist_ok=True)
    joins = {}
    for name, parent, grass, foliage, _ in BIOMES:
        biome = json.loads(json.dumps(vanilla[parent]))
        effects = biome.setdefault('effects', {})
        if grass:
            effects['grass_color'] = grass
            effects.pop('grass_color_modifier', None)
        if foliage:
            effects['foliage_color'] = foliage
        if name in WATER:
            effects['water_color'] = WATER[name]
        with open(os.path.join(out_dir, name + '.json'), 'w') as f:
            json.dump(biome, f, indent=2, sort_keys=True)
            f.write('\n')
        for tag, body in tags.items():
            if 'minecraft:' + parent in body.get('values', []):
                joins.setdefault(tag, []).append('%s:%s' % (NAMESPACE, name))
    tag_root = os.path.join(resources, TAG_DIR)
    for tag, values in sorted(joins.items()):
        path = os.path.join(tag_root, tag + '.json')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w') as f:
            json.dump({'replace': False, 'values': sorted(values)}, f, indent=2)
            f.write('\n')
    lang_path = os.path.join(resources, 'assets', NAMESPACE, 'lang', 'en_us.json')
    with open(lang_path) as f:
        lang = json.load(f)
    for name, _, _, _, title in BIOMES:
        lang['biome.%s.%s' % (NAMESPACE, name)] = title
    with open(lang_path, 'w') as f:
        json.dump(lang, f, indent=2, ensure_ascii=False)
        f.write('\n')
    print('%d biomes, %d tags' % (len(BIOMES), len(joins)))


if __name__ == '__main__':
    main()
