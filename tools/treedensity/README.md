# Tree density grid

Builds `src/main/resources/realearth/treedensity.bin`, how many trees stand on a hectare of woodland, which sets how
far apart Earth's trees grow. Not part of the Gradle build; run it again only to change the method or take newer
data. The method is described at the top of `build_treedensity.py`.

Needs only Python 3.9+, and the 1.27 GB WGS84 GeoTIFF revision of Crowther et al. (2015), CC BY 4.0, from Figshare
(the link ignores range requests, so fetch it in one go):

```bash
mkdir -p data
curl -L -o data/crowther-wgs84.zip https://ndownloader.figshare.com/files/5332990
unzip -j data/crowther-wgs84.zip '*.tif.ovr' '*.tfw' -d data
python3 build_treedensity.py data/Crowther_Nature_Biome_Revision_01_WGS84_GeoTiff.tif.ovr data/Crowther_Nature_Biome_Revision_01_WGS84_GeoTiff.tfw ../../src/main/resources/realearth/treedensity.bin
```

It reads only the overview pyramid beside the GeoTIFF (deflate tiles, which plain Python decodes quickly), takes a
few seconds, and prints its value at places to check. The 2026-11 build: Tyresta 835 trees a hectare (3.5 m apart),
the Black Forest 723, the Amazon 470, Monchique 407, Kruger's savanna 129 (8.8 m apart), the Sahara and the Beauce's
fields none.
