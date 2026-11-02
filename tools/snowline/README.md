# Snowline grid

Builds `src/main/resources/realearth/snowline.bin`, the snowline the mod's biomes, snow and ice follow. Not part
of the Gradle build; run it again only to change the method or take newer data. The method is described at the
top of `build_snowline.py`.

Needs Python 3.10+ with `numpy`, `rasterio` and `scipy`, and about 1.2 GB of downloads:

```bash
mkdir -p data/rgi data/chelsa data/dem
curl -o data/rgi/RGI2000-v7.0-G-global-attributes.csv https://cluster.klima.uni-bremen.de/~oggm/rgi/RGI2000-v7.0-G-global-attributes.csv
curl -o data/chelsa/bio10.tif https://os.zhdk.cloud.switch.ch/chelsav2/GLOBAL/climatologies/1981-2010/bio/CHELSA_bio10_1981-2010_V.2.1.tif
curl -o data/chelsa/bio12.tif https://os.zhdk.cloud.switch.ch/chelsav2/GLOBAL/climatologies/1981-2010/bio/CHELSA_bio12_1981-2010_V.2.1.tif
python fetch_dem.py data/dem/dem_lattice.npy
python build_snowline.py data/rgi/RGI2000-v7.0-G-global-attributes.csv data/chelsa/bio10.tif data/chelsa/bio12.tif data/dem/dem_lattice.npy ../../src/main/resources/realearth/snowline.bin
```

`fetch_dem.py` reads only every 12th row of CHELSA's 3.6 GB elevation grid, about 300 MB. The build takes
under a minute and prints how well the grid matches the glaciers, and its value at places to check.

The 2026-11 build: glaciers survive summers from -4.6 C at 224 mm of precipitation a year to +4.4 C at
4,869 mm; against glaciers over 10 km2 the grid is off by a median of +13 m.
