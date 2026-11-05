# Ecoregion grid

Builds `src/main/resources/realearth/ecoregions.bin` and `ecoregions.tsv`: which of the world's 846 ecoregions, in
14 biomes, a place lies in. Earth's biomes follow it (`world/EarthBiomes.java`), and with them which trees grow. Not
part of the Gradle build; run it again only to change the method or take newer data. The method is described at the
top of `build_ecoregions.py`.

Needs only Python 3.9+, and RESOLVE Ecoregions 2017 (Dinerstein et al. 2017, CC BY 4.0, https://ecoregions.appspot.com/),
a 150 MB shapefile:

```bash
mkdir -p data
curl -L -o data/Ecoregions2017.zip https://storage.googleapis.com/teow2016/Ecoregions2017.zip
unzip data/Ecoregions2017.zip -d data
python3 build_ecoregions.py data/Ecoregions2017.shp ../../src/main/resources/realearth/
```

It takes about half a minute and prints the ecoregion at places to check. The 2026-11 build is 483 KB: the Olympic
Peninsula in the Central Pacific Northwest coastal forests (temperate conifer, a rainforest), the Blue Mountains in the
Eastern Australian temperate forests (eucalypts), Stockholm in the Sarmatic mixed forests, Kakadu in the Arnhem Land
tropical savanna.

Which ecoregions are known for a landscape of their own -- temperate rainforest, redwoods, hemiboreal and larch forest,
moorland, forest-steppe, prairie, fynbos, miombo, cerrado, thorn and cactus scrub, the Karoo, paramo and puna, salt
marsh, polar desert, swamp forest, laurel forest, Australia's eucalypts, spinifex and mulga, and some sixty more (each
explained in `SUBS`) -- is decided in the script, by ecoregion, name and place (`SUBS`, `LAUREL`, `AUSTRALIA`).
