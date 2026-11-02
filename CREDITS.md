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

## Lidar elevation: Mapterhorn and national surveys

In worlds made since lidar elevation came in, wherever Mapterhorn has national lidar its terrain tiles
replace the Terrain Tiles above: bare ground surveyed at 0.25-10 m across most of Europe, the USA, Japan
and parts of Canada, Australia and New Zealand.

Mapterhorn, terrain tiles combining open elevation data (<https://mapterhorn.com>). Code BSD-3-Clause;
the data keeps each producer's licence, listed in full at <https://mapterhorn.com/attribution>.
**Changes:** none to the data; its zoom-14 tiles are read, at the same 2.4-4.8 m sample spacing as the
Terrain Tiles, and kept on disk (`realearth-cache/mapterhorn`).

Its producers and their licences:

<!-- 151 sources, 70 producers, from https://download.mapterhorn.com/attribution.json -->
- Administration du cadastre et de la topographie Luxembourg -- CC0 <https://data.public.lu/fr/datasets/lidar-2019-modele-numerique-de-terrain-mnt/#/information>
- agentschap Digitaal Vlaanderen -- Model license for free reuse <https://www.vlaanderen.be/datavindplaats/catalogus/digitaal-hoogtemodel-vlaanderen-ii-dtm-raster-1-m>
- AHN.nl -- CC BY 4.0 <https://www.ahn.nl/dataroom>
- Amt für Landesplanung und Kartografie, Autonome Provinz Bozen -- Creative Commons CCZero <https://natur-raum.provinz.bz.it/de/digitale-hohenmodelle>
- ASTER GDEM courtesy of METI and NASA processed by 4cast LTD using VTbuilder -- ASTER GDEM Public Domain <https://www.jspacesystems.or.jp/ersdac/GDEM/E/>
- Bayerische Vermessungsverwaltung -- CC BY 4.0 <https://geodaten.bayern.de/opengeodata/OpenDataDetail.html?pn=dgm1>
- BEV - Bundesamt für Eich- und Vermessungswesen -- CC-BY-4.0 <https://www.bev.gv.at/Services/Produkte/Digitales-Gelaendehoehenmodell/ALS-Hoehenraster.html>
- burgenland.at. Land Burgenland -- Creative Commons Namensnennung 4.0 International (CC BY 4.0) <https://www.burgenland.at/service/open-government-data/bestandsliste/detail/digitales-gelaendemodell-5-meter-burgenland/>
- CC-BY 4.0 scne.es 2022-2025 -- CC BY 4.0 <https://centrodedescargas.cnig.es/CentroDescargas/lidar-tercera-cobertura>
- CC-BY 4.0 scne.es 2025 -- CC BY 4.0 <https://centrodedescargas.cnig.es/CentroDescargas/modelo-digital-terreno-mdt05-primera-cobertura>
- cuzk.gov.cz. Land Survey Office -- CC-BY 4.0 <https://geoportal.cuzk.cz/(S(rtmc0jv2tukm5cadwnkwsgd2))/Default.aspx?lng=EN&mode=TextMeta&side=vyskopis&metadataID=CZ-CUZK-EL&mapid=8&menu=304>
- Dati estratti dal Modello Digitale del Terreno (DTM) della Regione Autonoma Valle d'Aosta -- CC BY 4.0 <https://geoportale.regione.vda.it/download/dtm/>
- Department of Land Administration, MOI -- Open Government Data License, version 1.0 <https://data.gov.tw/en/datasets/169807>
- Direção-Geral do Território -- CC BY 4.0 <https://cdd.dgterritorio.gov.pt/dgt-fe/catalogos/colecoes?filter=LiDAR+-+Modelos+Digitais+do+Relevo&language=en>
- DLR e.V. 2010-2014, Airbus Defence and Space GmbH 2014-2018, provided under COPERNICUS by the European Union and ESA. All rights reserved. Accessed via OpenTopography and AWS Open Data. -- COPERNICUS full, free and open license <https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM>
- Elevation data 2021-2024, Republic of Estonia Land and Spatial Development Board -- Republic of Estonia Land and Spatial Development Board opendata licence 2025 <https://geoportaal.maaamet.ee/eng/spatial-data/elevation-data-p308.html>
- Federal Office of Topography swisstopo -- Open Government Data <https://www.swisstopo.admin.ch/en/height-model-swissalti3d>
- Freie und Hansestadt Hamburg, Landesbetrieb Geoinformation und Vermessung (LGV) -- Datenlizenz Deutschland - Namensnennung - Version 2.0 <https://metaver.de/trefferanzeige?docuuid=A39B4E86-15E2-4BF7-BA82-66F9913D5640>
- GeoBasis DE/LVGL-SL 2025 -- Datenlizenz Deutschland – Namensnennung – Version 2.0 <https://www.shop.lvgl.saarland.de/cloud/index.php/s/wWELJP3D5aRsrqi?path=%2FOD_DGM1_tiff_LK>
- GeoBasis-DE / LGB -- Datenlizenz Deutschland - Namensnennung - Version 2.0 <https://data.geobasis-bb.de/geobasis/daten/dgm/tif/>
- geoland.at, Land Kärnten. -- CC-BY-4.0: Land Kärnten - data.gv.at <https://www.data.gv.at/datasets/b5de6975-417b-4320-afdb-eb2a9e2a1dbf?locale=de>
- Geoscience Australia -- CC BY 4.0 <https://pid.geoscience.gov.au/dataset/ga/89644>
- GeoSN, dl-de/by-2-0 -- Datenlizenz Deutschland - Namensnennung - Version 2.0 <https://www.geodaten.sachsen.de/downloadbereich-digitale-hoehenmodelle-4851.html>
- Head Office of Geodesy and Cartography (GUGiK), National Geodetic and Cartographic Resource (PZGiK), Poland -- Open Government Data (Art. 40a. geod. i kartogr.) <https://www.geoportal.gov.pl/>
- Hessisches Ministerium für Umwelt, Klimaschutz, Landwirtschaft und Verbraucherschutz -- Datenlizenz Deutschland - Zero - Version 2.0 <https://www.metaver.de/trefferanzeige?docuuid=dbf48a95-b44d-48b3-a5b4-981e4c1bd8e6>
- Institut national de l'information géographique et forestière IGN -- Licence Ouverte / Open Licence version 2.0 <https://www.data.gouv.fr/datasets/mnt-lidar-hd>
- Instituto Geográfico Portugês -- CC BY 4.0 <https://catalogo-irig.madeira.gov.pt/geonetwork/srv/eng/catalog.search;jsessionid=38D04EB2937EF1FCD8FDC9E697BF4B3A#/metadata/74238358-6eee-45e1-a8fa-3a518321d4ba>
- Istituto Nazionale di Geofisica e Vulcanologia (INGV) -- Creative Commons Namensnennung 4.0 International (CC BY 4.0) <https://tinitaly.pi.ingv.it/Download_Area1_1.html>
- Kanton Zürich, Amt für Raumentwicklung, Abteilung Geoinformation -- CC0 <https://data.stadt-zuerich.ch/dataset/ktzh_digitales_terrainmodell__dtm__2021_bis_2022__ogd_>
- Kartverket -- CC BY 4.0 <https://www.kartverket.no/api-og-data/terrengdata>
- Klimadatastyrelsen Danmark -- CC BY 4.0 <https://dataforsyningen.dk/data/930>
- ktn.gv.at. Land Kärnten -- CC-BY-4.0: Land Kärnten - data.gv.at <https://www.data.gv.at/katalog/dataset/c4352e0c-f811-4c59-bdac-4ba199684fc2>
- land-oberoesterreich.gv.at. Land Oberösterreich -- Creative Commons Namensnennung 4.0 International (CC BY 4.0) <https://www.data.gv.at/katalog/dataset/d8f39b43-f52b-4387-9b6d-b93b412a2fc2>
- Landesamt für Vermessung und Geoinformation Sachsen-Anhalt (LVermGeo) -- Datenlizenz Deutschland - Namensnennung - Version 2.0 <https://www.lvermgeo.sachsen-anhalt.de/de/gdp-dgm-dom-lsa.html>
- Landesamt GeoInformation Bremen -- CC-BY-4.0 <https://www.metaver.de/trefferanzeige?docuuid=2351ABA6-019D-4155-853F-76EEFC26CA52>
- Landesamtes für Geoinformation und Landesvermessung Niedersachsen (LGLN) -- CC BY 4.0 <https://www.lgln.niedersachsen.de/startseite/geodaten_karten/3d_geobasisdaten/dgm/digitale-gelandemodelle-dgm-143150.html>
- Lantmäteriet -- CC0 <https://www.lantmateriet.se/sv/geodata/vara-produkter/produktlista/markhojdmodell-nedladdning/>
- Latvijas Ģeotelpiskās informācijas aģentūra -- Creative Commons Attribution 4.0 <https://www.lgia.gov.lv/en/Digit%C4%81lais%20reljefa%20modelis>
- LGL, www.lgl-bw.de, dl-de/by-2-0 -- Datenlizenz Deutschland - Namensnennung - Version 2.0 <https://www.lgl-bw.de/Produkte/Open-Data/>
- MDT02-cob2 2015-2021 CC-BY 4.0 scne.es -- CC BY 4.0 <https://centrodedescargas.cnig.es/CentroDescargas/modelo-digital-terreno-mdt02-segunda-cobertura>
- Ministerium des Innern des Landes Nordrhein-Westfalen -- Datenlizenz Deutschland - Zero - Version 2.0 <https://www.opengeodata.nrw.de/produkte/geobasis/hm/dgm1_tiff/dgm1_tiff/>
- National Land Survey of Finland 2025. Downloaded via GeoCubes. -- Attribution 4.0 International (CC BY 4.0) <https://www.maanmittauslaitos.fi/en/maps-and-spatial-data/datasets-and-interfaces/product-descriptions/elevation-model-2-m>
- Natural Resources Canada -- Open Government Licence - Canada <https://open.canada.ca/data/en/dataset/0fe65119-e96e-4a57-8bfe-9d9245fba06b>
- Natural Science Institute of Iceland -- CC BY 4.0 <https://gatt.lmi.is/geonetwork/srv/eng/catalog.search#/metadata/e6712430-a63c-4ae5-9158-c89d16da6361>
- Norwegian Polar Institute -- CC BY 4.0 <https://data.npolar.no/dataset/dce53a47-c726-4845-85c3-a65b46fe2fea>
- Provincia Autonoma di Trento - Ufficio Sistemi Informativi - Servizio autorizzazioni e valutazioni ambientali -- CC BY 2.5 <http://www.territorio.provincia.tn.it/portal/server.pt?open=514&objID=23954&mode=2>
- Regione Autonoma della Sardegna -- CC-BY 4.0 <https://webgis2.regione.sardegna.it/geonetwork/srv/ita/catalog.search#/metadata/R_SARDEG:BFPNM>
- Regione Lombardia -- CC BY 4.0 <https://geodati.gov.it/geoportale/eng/metadata-search-results?keyword=dfc98d60-5f02-4a2b-8113-5ad24cc53a9c>
- Regione Piemonte - A1613B - Sistema informativo territoriale e ambientale -- CC BY 4.0 <https://www.geoportale.piemonte.it/geonetwork/srv/api/records/r_piemon:224de2ac-023e-441c-9ae0-ea493b217a8e>
- Regione Siciliana - Assessorato del Territorio e dell'Ambiente - Area 2 Interdipartimentale - Nodo Regionale S.I.T.R -- CC-BY 4.0 <https://www.sitr.regione.sicilia.it/geoportale/it/Metadata/Details/946>
- salzburg.gv.at. Land Salzburg -- Creative Commons Namensnennung 4.0 International (CC BY 4.0) <https://www.data.gv.at/katalog/dataset/093d4dec-8397-4faf-b690-b8cbe9a9dc31>
- Scottish Government -- Open Government Licence <https://www.spatialdata.gov.scot/geonetwork/srv/eng/catalog.search#/metadata/40017f89-2857-49cb-a913-e0784f250769>
- Senatsverwaltung für Stadtentwicklung, Bauen und Wohnen Berlin -- Datenlizenz Deutschland - Zero - Version 2.0 <https://www.berlin.de/sen/sbw/stadtdaten/geoinformation/landesvermessung/geotopographie-atkis/dgm-digitale-gelaendemodelle/>
- Service public de Wallonie (SPW) -- CC-BY 4.0 <https://geoportail.wallonie.be/catalogue/a004e570-99d6-4fe5-b83d-49b774409278.html>
- Slovenian Environment Agency (ARSO) -- Creative Commons Attribution 4.0 <http://www.evode.gov.si/index.php?id=87>
- The Faroese Environment Agency, Umhvørvisstovan -- Open Government Data <https://www.foroyakort.fo/tak-datur-nidur/dtm-og-dsm-haeddarmodell>
- The LIST ©State of Tasmania. Downloaded via Source Coop. -- CC BY 3.0 <https://source.coop/alexgleith/tasmania-dem-2m>
- the National Land Authority (NLA), Water and Sanitation Corporation (WASAC) Ltd., Rwanda through UNDP, EMD, and windPRO -- Creative Commons Namensnennung 4.0 International (CC BY 4.0) <https://github.com/WASAC/rwanda-dem>
- Toitū Te Whenua Land Information New Zealand -- CC BY 4.0 <https://www.linz.govt.nz/products-services/data/types-linz-data/elevation-data/access-elevation-data>
- U.S. Geological Survey -- Public Domain (U.S. Government Work) <https://www.sciencebase.gov/catalog/item/543e6b86e4b0fd76af69cf4c>
- Welsh Government and NRW -- Open Government License <https://datamap.gov.wales/maps/lidar-data-download/>
- www.geoportal.gov.ro -- Romanian Open Government License v1.0 (OGL-ROU-1.0) <https://geoportal.ancpi.ro/portal/apps/webappviewer/index.html?id=3f34ee5af71c400396dda574f0d53274>
- © Environment Agency -- Open Government Licence <https://environment.data.gov.uk/dataset/13787b9a-26a4-4775-8523-806d13af58fc>
- © GDI-Th -- Datenlizenz Deutschland - Namensnennung - Version 2.0 <https://tlbg.thueringen.de/geobasisdaten/3d-informationen/digitale-gelaendemodelle#c75998>
- © GeoBasis-DE/LVermGeo SH/CC BY 4.0 -- CC BY 4.0 <https://geodaten.schleswig-holstein.de/gaialight-sh/_apps/dladownload/dl-dgm1.html>
- © GeoBasis-DE/M-V 2025 -- CC BY 4.0 <https://www.laiv-mv.de/Geoinformation/Geobasisdaten/Gelaendemodelle/>
- ©GeoBasis-DE / LVermGeoRP 2025 -- DL-DE->BY-2.0 <https://lvermgeo.rlp.de/geodaten-geoshop/open-data>
- ÚGKK SR -- CC BY Creative Commons Attribution („uvedenie autora“) 4.0 <https://geoportal.gov.sk/gallery/datasets/detail/c310bf1e-68a0-43b9-9955-c7dad13a93aa?csrt=4917832570778447195>
- Τμήμα Κτηματολογίου και Χωρομετρίας -- CC BY 4.0 <https://www.data.gov.cy/el/dataset/ypsometria-inspire>
- 国土交通省国土地理院 -- 国土地理院コンテンツ利用規約／測量法に基づく国土地理院長承認（使用）R 8JHs 131 <https://service.gsi.go.jp/kiban/app/>

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
