# Real Earth: data sources and credits

Real Earth builds its world from open data. The mod downloads the data while you play (or with
`/earth download`); only the climate map is bundled. These are the sources, with the attribution their licences
ask for. In game, `/earth credits` shows the same list with links.

## Map data: OpenStreetMap

Water, waterways, roads, paths, railways and bridges.

**© OpenStreetMap contributors.** OpenStreetMap data is available under the Open Database License (ODbL) 1.0:
<https://www.openstreetmap.org/copyright>

Served as vector tiles by **OpenFreeMap** (<https://openfreemap.org>), in the **OpenMapTiles** schema
(© OpenMapTiles, <https://openmaptiles.org>).

Real Earth turns the map data into terrain while generating a world; worlds made with it contain a derived
work of OpenStreetMap data.

## Building detail: Overture Maps

Roof shapes, roof heights, roof and facade colours, and the parts buildings are mapped in (towers, spires,
domes), from the buildings theme of the **Overture Maps Foundation** (<https://overturemaps.org>), read from its
PMTiles release. The buildings theme is available under the Open Database License (ODbL) 1.0; its data is
mostly from OpenStreetMap, **© OpenStreetMap contributors**, with other sources as listed at
<https://docs.overturemaps.org/attribution/>.

## Elevation: Terrain Tiles

Ground and sea-floor heights, from the **Terrain Tiles** dataset on the Registry of Open Data on AWS
(<https://registry.opendata.aws/terrain-tiles/>), originally produced by Mapzen. It combines these sources,
each credited as its provider requires:

- ArcticDEM terrain data: DEM(s) were created from DigitalGlobe, Inc., imagery and funded under National Science
  Foundation awards 1043681, 1559691, and 1542736.
- Australia terrain data © Commonwealth of Australia (Geoscience Australia) 2017.
- Austria terrain data © offene Daten Österreichs – Digitales Geländemodell (DGM) Österreich.
- Canada terrain data contains information licensed under the Open Government Licence – Canada.
- Europe terrain data produced using Copernicus data and information funded by the European Union – EU-DEM layers.
- Global ETOPO1 terrain data: U.S. National Oceanic and Atmospheric Administration.
- Mexico terrain data source: INEGI, Continental relief, 2016.
- New Zealand terrain data Copyright 2011 Crown copyright (c) Land Information New Zealand and the New Zealand
  Government (All rights reserved).
- Norway terrain data © Kartverket.
- United Kingdom terrain data © Environment Agency copyright and/or database right 2015. All rights reserved.
- United States 3DEP (formerly NED) and global GMTED2010 and SRTM terrain data courtesy of the U.S. Geological
  Survey.

## Land cover: ESA WorldCover

Forests, grassland, farmland, towns, bare ground, snow and ice, wetlands and mangroves.

**© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover
consortium.** Licensed under the Creative Commons Attribution 4.0 International License (CC BY 4.0):
<https://creativecommons.org/licenses/by/4.0/>

Zanaga, D., Van De Kerchove, R., Daems, D., De Keersmaecker, W., Brockmann, C., Kirches, G., Wevers, J., Cartus,
O., Santoro, M., Fritz, S., Lesiv, M., Herold, M., Tsendbazar, N.E., Xu, P., Ramoino, F., Arino, O. (2022).
ESA WorldCover 10 m 2021 v200. <https://doi.org/10.5281/zenodo.7254221>

Real Earth reads it as published and turns its classes into blocks and biomes.

## Climate: Köppen-Geiger

Climate zones, which choose biomes, surfaces and village styles.

Beck, H. E., McVicar, T. R., Vergopolan, N., Berg, A., Lutsko, N. J., Dufour, A., Zeng, Z., Jiang, X., van Dijk,
A. I. J. M., Miralles, D. G. (2023). High-resolution (1 km) Köppen-Geiger maps for 1901–2099 based on constrained
CMIP6 projections. *Scientific Data* 10, 724. <https://doi.org/10.1038/s41597-023-02549-6>

Licensed under CC BY 4.0 (<https://creativecommons.org/licenses/by/4.0/>). **Changes:** the 1991–2020 map at
1 km is bundled with the mod re-tiled and compressed into its own format (`koppen.kgz`); the climate classes are
unchanged.

## Snowline: Randolph Glacier Inventory and CHELSA

Where snow lasts, which sets snowy biomes, snow cover and ice: glaciers' median heights where there are
glaciers, and summer temperature and precipitation carried to height everywhere else.

RGI 7.0 Consortium (2023). Randolph Glacier Inventory – A Dataset of Global Glacier Outlines, Version 7.0.
NSIDC: National Snow and Ice Data Center, Boulder, Colorado USA. <https://doi.org/10.5067/f6jmovy5navz>
(global attribute table as mirrored by OGGM).

Licensed under CC BY 4.0 (<https://creativecommons.org/licenses/by/4.0/>).

Karger, D. N., Conrad, O., Böhner, J., Kawohl, T., Kreft, H., Soria-Auza, R. W., Zimmermann, N. E., Linder,
H. P., Kessler, M. (2017). Climatologies at high resolution for the earth's land surface areas. *Scientific
Data* 4, 170122. <https://doi.org/10.1038/sdata.2017.122>. CHELSA V2.1, 1981–2010: mean temperature of the
warmest quarter (bio10), annual precipitation (bio12) and the elevation they were made on.
<https://doi.org/10.16904/envidat.228>

Released under CC0 1.0 (<https://creativecommons.org/publicdomain/zero/1.0/>).

**Changes:** neither dataset is bundled as published. `tools/snowline/build_snowline.py` combines them into one
grid of snowline heights every 0.1 degrees (`snowline.bin`): the summer temperature at which glaciers survive,
by precipitation, is read off the glaciers; the climate gives a snowline everywhere; glaciers correct it within
about 200 km of them.

## Software

- Distant Horizons (optional, by James Seibel and contributors) is used through its public API only; it is not
  bundled.
