"""Writes Project Gaia: Earth's own biomes into the mod's data pack, from vanilla's.

Earth's biomes are the real world's (RESOLVE Ecoregions 2017's 14 biomes, and the forests some ecoregions are known
for; see world/EarthBiomes.java, which says where each one is and which trees grow in it). Each starts as a copy of a
vanilla biome (its ground cover, mobs, music and weather), takes the colours of the real landscape, and joins every
biome tag its vanilla parent is in, so foxes, rabbits and frogs treat it the same -- but not the structure tags
(STRUCTURE_TAGS): Earth has no structures but its own.

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
    # Sub-biomes: landscapes some ecoregions are known for, and wetlands, scrub and polar desert by place.
    ('redwood_forest', 'taiga', '#4f9440', '#2c6b33', 'Redwood Forest'),
    ('larch_taiga', 'taiga', '#8aa863', '#84a64e', 'Larch Taiga'),
    ('peat_bog', 'swamp', '#8a9a52', '#6a8448', 'Peat Bog'),
    ('moorland', 'plains', '#8f8f58', '#667a46', 'Moorland'),
    ('forest_steppe', 'plains', '#a7b766', '#6a9a48', 'Forest-Steppe'),
    ('oak_savanna', 'plains', '#b2b45e', '#6f9440', 'Oak Savanna'),
    ('tallgrass_prairie', 'plains', '#8fb558', '#6aa040', 'Tallgrass Prairie'),
    ('pine_savanna', 'plains', '#9fb55a', '#4f8a3f', 'Pine Savanna'),
    ('mediterranean_scrub', 'plains', '#b5b062', '#7a8a4e', 'Mediterranean Scrub'),
    ('fynbos', 'plains', '#9ea56a', '#74895a', 'Fynbos'),
    ('miombo_woodland', 'savanna', '#a8b052', '#7e9a3a', 'Miombo Woodland'),
    ('cerrado', 'savanna', '#b5ae58', '#7d9440', 'Cerrado'),
    ('thorn_scrub', 'savanna', '#bfae66', '#8a9050', 'Thorn Scrub'),
    ('cactus_scrub', 'savanna', '#c0ad6c', '#8f9356', 'Cactus Scrub'),
    ('sagebrush_steppe', 'plains', '#a9ab82', '#7f8a6a', 'Sagebrush Steppe'),
    ('karoo', 'savanna', '#bba770', '#8c8f5e', 'Karoo'),
    ('spinifex_grassland', 'savanna', '#c9a656', '#8a9a6a', 'Spinifex Grassland'),
    ('mulga_woodland', 'savanna', '#c1a660', '#7f8f62', 'Mulga Woodland'),
    ('paramo', 'meadow', '#8e9f62', '#61804c', 'P\u00e1ramo'),
    ('puna', 'meadow', '#b3a76d', '#7a8656', 'Puna'),
    ('swamp_forest', 'swamp', '#3f9a30', '#2a7f24', 'Swamp Forest'),
    ('salt_marsh', 'swamp', '#8aa25a', '#6f8a4a', 'Salt Marsh'),
    ('polar_desert', 'snowy_plains', '#a09a80', '#8a8a6a', 'Polar Desert'),
    ('sand_sea', 'desert', '#c8b77a', '#9a9a6a', 'Sand Sea'),
    ('stony_desert', 'desert', '#b8a87a', '#8f8f66', 'Stony Desert'),
    ('gibber_plain', 'desert', '#b89a6a', '#8f8a5e', 'Gibber Plain'),
    ('lichen_woodland', 'taiga', '#9aa070', '#4f7448', 'Lichen Woodland'),
    ('shrub_tundra', 'snowy_plains', '#93986a', '#6f8455', 'Shrub Tundra'),
    ('fjell', 'snowy_plains', '#8f9a6a', '#6f8456', 'Fjell'),
    ('pinyon_juniper_woodland', 'plains', '#a5a47a', '#5f7a50', 'Pinyon-Juniper Woodland'),
    ('polylepis_woodland', 'meadow', '#8a9a5e', '#5f7a46', 'Polylepis Woodland'),
    ('kauri_forest', 'forest', '#4f9a3a', '#2f7a35', 'Kauri Forest'),
    ('araucaria_forest', 'forest', '#7aa850', '#3f7a3a', 'Araucaria Forest'),
    ('campinarana', 'sparse_jungle', '#7a9a48', '#4f8a35', 'Campinarana'),
    ('buttongrass_moorland', 'plains', '#9a9a5a', '#6a7a4a', 'Buttongrass Moorland'),
    ('beech_forest', 'forest', '#6fa84a', '#4a8f30', 'Beech Forest'),
    ('mountain_birch_forest', 'birch_forest', '#8fb060', '#7aa04f', 'Mountain Birch Forest'),
    ('monsoon_forest', 'sparse_jungle', '#8fad48', '#6f9a35', 'Monsoon Forest'),
    ('mediterranean_conifer_forest', 'taiga', '#9aa85a', '#4f7a42', 'Mediterranean Conifer Forest'),
    ('dehesa', 'plains', '#b8b05c', '#6a8a45', 'Dehesa'),
    ('mallee', 'savanna', '#b5a862', '#7f9a72', 'Mallee'),
    ('chaparral', 'plains', '#ae9f5c', '#6f7f4a', 'Chaparral'),
    ('shortgrass_prairie', 'plains', '#b8b070', '#8a9a50', 'Shortgrass Prairie'),
    ('mixed_grass_prairie', 'plains', '#a8b468', '#7f9e4c', 'Mixed-Grass Prairie'),
    ('pampas', 'plains', '#9fb85e', '#6f9a45', 'Pampas'),
    ('patagonian_steppe', 'plains', '#b0a874', '#7f8a5e', 'Patagonian Steppe'),
    ('tussock_grassland', 'plains', '#b5a46a', '#7a8a58', 'Tussock Grassland'),
    ('desert_steppe', 'desert', '#b9aa78', '#8f8f62', 'Desert Steppe'),
    ('succulent_thicket', 'savanna', '#a8a65e', '#6f8a4a', 'Succulent Thicket'),
    ('alpine_tundra', 'snowy_plains', '#96a06a', '#708a56', 'Alpine Tundra'),
    ('alpine_shrubland', 'meadow', '#8e9e5e', '#5a7a46', 'Alpine Shrubland'),
    ('xeric_woodland', 'savanna', '#b5a96a', '#7f8a55', 'Xeric Woodland'),
    ('llanos', 'savanna', '#a5b450', '#6f9a3a', 'Llanos'),
    ('sahel', 'savanna', '#c8b56a', '#9a9a55', 'Sahel'),
    ('forest_savanna_mosaic', 'savanna', '#8fb04a', '#5f9a32', 'Forest-Savanna Mosaic'),
    ('mopane_woodland', 'savanna', '#b5a85a', '#8a9a45', 'Mopane Woodland'),
    ('bushveld', 'savanna', '#b0aa5a', '#7f9640', 'Bushveld'),
    ('kalahari_savanna', 'savanna', '#c2b06a', '#8f9a55', 'Kalahari Savanna'),
    ('highveld_grassland', 'plains', '#a9b05e', '#7f9a4a', 'Highveld Grassland'),
    ('forest_tundra', 'taiga', '#8f9f64', '#607f52', 'Forest-Tundra'),
    ('subalpine_forest', 'taiga', '#7a9a5a', '#4a7046', 'Subalpine Forest'),
    ('heathland', 'plains', '#8a8256', '#6a7a48', 'Heathland'),
    ('cypress_swamp', 'swamp', '#5f8f40', '#4f7a32', 'Cypress Swamp'),
    ('oak_woodland', 'plains', '#b0ad60', '#6a8a45', 'Oak Woodland'),
    ('himalayan_broadleaf_forest', 'forest', '#6aa045', '#3f8a35', 'Himalayan Broadleaf Forest'),
    ('ponderosa_pine_forest', 'plains', '#a3a65e', '#4f7a42', 'Ponderosa Pine Forest'),
    ('mitchell_grass_downs', 'savanna', '#c2ad62', '#8f9a60', 'Mitchell Grass Downs'),
    ('terai_grassland', 'plains', '#8fb048', '#5f9a35', 'Terai Grassland'),
    ('monte_scrub', 'savanna', '#bea96c', '#8f9258', 'Monte Scrub'),
    ('alpine_desert', 'snowy_plains', '#aaa183', '#8a8a6a', 'Alpine Desert'),
    ('afromontane_grassland', 'meadow', '#8fae5a', '#5f8a45', 'Afromontane Grassland'),
    ('snow_gum_woodland', 'forest', '#9aa872', '#7f9a80', 'Snow Gum Woodland'),
    ('argan_woodland', 'savanna', '#b8a866', '#6f8a4a', 'Argan Woodland'),
    ('kwongan_heath', 'plains', '#a6a065', '#7a8a5c', 'Kwongan Heath'),
    ('mojave_scrub', 'desert', '#c2b07a', '#8f9a68', 'Mojave Scrub'),
    ('saltbush_shrubland', 'desert', '#a8a882', '#8a9a7a', 'Saltbush Shrubland'),
    ('atlantic_forest', 'jungle', '#3f9e35', '#2a8a2a', 'Atlantic Forest'),
    ('tepui', 'sparse_jungle', '#7a9a50', '#4f7a3a', 'Tepui'),
    ('heath_forest', 'sparse_jungle', '#6f9a40', '#4f8a30', 'Heath Forest'),
    ('wet_tundra', 'snowy_plains', '#8fa06a', '#6f8a56', 'Wet Tundra'),
    ('brigalow_scrub', 'savanna', '#a9a862', '#7f9070', 'Brigalow Scrub'),
    ('tropical_alpine_grassland', 'meadow', '#8a9e60', '#5f7f4a', 'Tropical Alpine Grassland'),
    ('sawgrass_marsh', 'swamp', '#8a9a50', '#5f8a3a', 'Sawgrass Marsh'),
    ('papyrus_swamp', 'swamp', '#6aa040', '#4f8a32', 'Papyrus Swamp'),
    ('wet_meadow', 'plains', '#7fb055', '#5f9a40', 'Wet Meadow'),
    ('palouse_prairie', 'plains', '#b0b468', '#7f9a4c', 'Palouse Prairie'),
    ('dark_taiga', 'taiga', '#6f8f50', '#3f6a40', 'Dark Taiga'),
    ('pine_oak_forest', 'forest', '#8fae4c', '#4f8a3c', 'Pine-Oak Forest'),
    ('mixed_mesophytic_forest', 'forest', '#6faa48', '#4a9a32', 'Mixed Mesophytic Forest'),
    ('northern_hardwood_forest', 'forest', '#79b04f', '#57a03a', 'Northern Hardwood Forest'),
    ('oak_hickory_forest', 'forest', '#84b452', '#5f9a35', 'Oak-Hickory Forest'),
    ('korean_pine_forest', 'taiga', '#6fa04a', '#4a8040', 'Korean Pine Forest'),
    ('loess_plateau', 'plains', '#b8ac6a', '#7f9450', 'Loess Plateau'),
    ('tugai_forest', 'savanna', '#a8a85e', '#8aa060', 'Tugai Forest'),
    ('mixed_evergreen_forest', 'forest', '#7fa050', '#3f7a35', 'Mixed Evergreen Forest'),
    ('himalayan_conifer_forest', 'taiga', '#6a8f4c', '#3f6a3f', 'Himalayan Conifer Forest'),
    ('babassu_palm_forest', 'jungle', '#7aa83f', '#4f9a2f', 'Babassu Palm Forest'),
    ('matorral', 'plains', '#b0a85e', '#6f854a', 'Matorral'),
    ('alfa_steppe', 'plains', '#c0b06e', '#8a8f58', 'Alfa Steppe'),
    ('dragon_tree_woodland', 'savanna', '#b5a86a', '#6f8a52', 'Dragon Tree Woodland'),
    ('fog_desert', 'desert', '#b8b08a', '#8a9a72', 'Fog Desert'),
    ('mangrove_forest', 'mangrove_swamp', '#5f8a3a', '#4f7a2e', 'Mangrove Forest'),
    ('restinga', 'plains', '#8fae5a', '#4f8a3a', 'Restinga'),
    ('sand_dunes', 'beach', '#b5c07a', '#8aa060', 'Sand Dunes'),
    ('gallery_forest', 'jungle', '#4fa035', '#2f8a2a', 'Gallery Forest'),
    ('riparian_woodland', 'forest', '#6fae4a', '#5a9a3a', 'Riparian Woodland'),
    ('sudanian_savanna', 'savanna', '#b9b55e', '#7f9a40', 'Sudanian Savanna'),
    ('dry_dipterocarp_forest', 'sparse_jungle', '#a8aa52', '#7f9a3a', 'Dry Dipterocarp Forest'),
    ('baobab_dry_forest', 'savanna', '#b0a85a', '#7f9045', 'Baobab Dry Forest'),
    ('baikiaea_woodland', 'savanna', '#b6aa5c', '#7a9440', 'Baikiaea Woodland'),
    ('alpine_steppe', 'snowy_plains', '#b0aa78', '#8a8c62', 'Alpine Steppe'),
    ('alpine_meadow', 'meadow', '#86b05a', '#5f9045', 'Alpine Meadow'),
    ('oasis', 'savanna', '#8fae4a', '#5f9a35', 'Oasis'),
    ('reed_marsh', 'swamp', '#7fa050', '#5f8a3a', 'Reed Marsh'),
    ('floodplain_forest', 'forest', '#6aa848', '#4f9a38', 'Floodplain Forest'),
]
# The flooded grasslands' water is clear (the Pantanal, the Okavango), not a swamp's murk; a bog's is brown with
# peat, a tropical swamp forest's and a cypress swamp's dark with tannin, a salt marsh's the grey-green of a tidal creek.
WATER = {'flooded_grassland': '#4a7f9a', 'peat_bog': '#5a5a3c', 'swamp_forest': '#4d4a2e', 'salt_marsh': '#4a7a8a',
         'cypress_swamp': '#4a5a38', 'sawgrass_marsh': '#5a7a6a', 'papyrus_swamp': '#4a6a4a',
         'mangrove_forest': '#3f6a5a', 'reed_marsh': '#4a6a5a'}
BIOME_DIR = 'data/minecraft/worldgen/biome/'
# Where vanilla's structures may stand, and where strongholds lean: Earth generates none of them.
STRUCTURE_TAGS = ('has_structure/', 'stronghold_biased_to')
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
            if tag.startswith(STRUCTURE_TAGS):
                continue
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
