package net.apotheoticstudios.gaiaearth;

import java.net.URI;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Attribution for the data Earth is built from, as the sources' licences ask. The full text is in
 * CREDITS.md, which is also packaged in the mod's jar; this is the in-game version.
 */
public final class Credits {
	/** One line, for places with little room: the F3 screen and the greeting on joining. */
	public static final String SHORT = "Map data © OpenStreetMap contributors (ODbL) · Terrain Tiles (USGS and others) · "
		+ "Lidar: Mapterhorn and national surveys · © ESA WorldCover 2021 (CC BY 4.0) · Köppen-Geiger, Beck et al. 2023 (CC BY 4.0) · "
		+ "Glaciers: RGI 7.0 (CC BY 4.0) · Tree density, Crowther et al. 2015 (CC BY 4.0) · Ecoregions, RESOLVE 2017 (CC BY 4.0)";

	private record Source(String what, String credit, String url) {
	}

	private static final List<Source> SOURCES = List.of(
		new Source("Water, roads, railways, bridges; the map under Xaero's World Map",
			"© OpenStreetMap contributors, ODbL 1.0. Tiles by OpenFreeMap, © OpenMapTiles.", "https://www.openstreetmap.org/copyright"),
		new Source("Roof shapes, colours and building parts; single trees, crossings and road surfaces",
			"Overture Maps Foundation, buildings, base and transportation themes (from OpenStreetMap and others), ODbL 1.0. © OpenStreetMap contributors.",
			"https://docs.overturemaps.org/attribution/"),
		new Source("Measured building heights in the Netherlands, where that overlay is installed",
			"Kadaster, 3D Basisvoorziening (heights reconstructed from AHN and aerial imagery by 3DBAG), CC BY 4.0.",
			"https://www.kadaster.nl/zakelijk/producten/geo-informatie/3d-producten"),
		new Source("Surveyed roads, pavements and cycle paths in the Netherlands, where that overlay is installed",
			"Kadaster, Basisregistratie Grootschalige Topografie (BGT) via PDOK, CC0.", "https://www.pdok.nl/introductie/-/article/basisregistratie-grootschalige-topografie-bgt-"),
		new Source("Elevation",
			"Terrain Tiles on AWS (Mapzen): USGS 3DEP, SRTM and GMTED2010; NOAA ETOPO1; Copernicus EU-DEM; Geoscience Australia; "
				+ "Kartverket; Environment Agency (UK); LINZ; INEGI; Natural Resources Canada; DGM Österreich; ArcticDEM.",
			"https://registry.opendata.aws/terrain-tiles/"),
		new Source("Lidar elevation",
			"Mapterhorn terrain tiles from national surveys (Lantmäteriet, Kartverket, USGS, IGN, swisstopo, GSI and 60 more), "
				+ "each under its own open licence, listed at the link.",
			"https://mapterhorn.com/attribution"),
		new Source("Land cover",
			"© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium. CC BY 4.0.",
			"https://esa-worldcover.org"),
		new Source("Climate",
			"Beck, H. E., et al. (2023), High-resolution (1 km) Köppen-Geiger maps for 1901–2099, Scientific Data 10, 724. CC BY 4.0; "
				+ "bundled re-tiled, classes unchanged.",
			"https://doi.org/10.1038/s41597-023-02549-6"),
		new Source("Snowline",
			"RGI 7.0 Consortium (2023), Randolph Glacier Inventory 7.0, NSIDC, doi:10.5067/f6jmovy5navz. CC BY 4.0. "
				+ "With CHELSA V2.1 climatologies (Karger et al. 2017, Scientific Data 4, 170122), CC0. "
				+ "Combined into a bundled 0.1° snowline grid.",
			"https://doi.org/10.5067/f6jmovy5navz"),
		new Source("Mountain heights",
			"CHELSA V2.1 elevation (Karger et al. 2017, Scientific Data 4, 170122), CC0, its summits measured in the elevation "
				+ "tiles above. Combined into a bundled 0.2° grid of the highest ground near each place.",
			"https://doi.org/10.16904/envidat.228"),
		new Source("How close together trees stand",
			"Crowther, T. W., et al. (2015), Mapping tree density at a global scale, Nature 525, 201–205; data Glick, H. B., et al. "
				+ "(2016), Scientific Data 3, 160069. CC BY 4.0; bundled resampled to 0.1 degrees.",
			"https://doi.org/10.6084/m9.figshare.3179986"),
		new Source("Earth's biomes, and the trees they grow",
			"Dinerstein, E., et al. (2017), An Ecoregion-Based Approach to Protecting Half the Terrestrial Realm, BioScience 67(6), "
				+ "534–545; RESOLVE Ecoregions 2017. CC BY 4.0; bundled rasterised to 0.05 degrees.",
			"https://doi.org/10.1093/biosci/bix014")
	);

	private Credits() {
	}

	/** The short line, with a hint to the full list. */
	public static Component greeting() {
		return Component.literal("[Project Gaia: Earth] " + SHORT + ". /earth credits for details.").withStyle(ChatFormatting.GRAY);
	}

	/** The full list, one message per source, each with a link. */
	public static List<Component> full() {
		return SOURCES.stream().map(s -> {
			MutableComponent line = Component.literal(s.what + ": ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(s.credit + " ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal("[link]").withStyle(st -> st.withColor(ChatFormatting.AQUA).withUnderlined(true)
					.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, s.url))));
			return (Component) line;
		}).toList();
	}
}
