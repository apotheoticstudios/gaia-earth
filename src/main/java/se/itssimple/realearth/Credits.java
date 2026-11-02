package se.itssimple.realearth;

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
		+ "Glaciers: RGI 7.0 (CC BY 4.0)";

	private record Source(String what, String credit, String url) {
	}

	private static final List<Source> SOURCES = List.of(
		new Source("Water, roads, railways, bridges; the map under Xaero's World Map",
			"© OpenStreetMap contributors, ODbL 1.0. Tiles by OpenFreeMap, © OpenMapTiles.", "https://www.openstreetmap.org/copyright"),
		new Source("Roof shapes, colours and building parts",
			"Overture Maps Foundation, buildings theme (from OpenStreetMap and others), ODbL 1.0. © OpenStreetMap contributors.",
			"https://docs.overturemaps.org/attribution/"),
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
			"https://doi.org/10.5067/f6jmovy5navz")
	);

	private Credits() {
	}

	/** The short line, with a hint to the full list. */
	public static Component greeting() {
		return Component.literal("[Real Earth] " + SHORT + ". /earth credits for details.").withStyle(ChatFormatting.GRAY);
	}

	/** The full list, one message per source, each with a link. */
	public static List<Component> full() {
		return SOURCES.stream().map(s -> {
			MutableComponent line = Component.literal(s.what + ": ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(s.credit + " ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal("[link]").withStyle(st -> st.withColor(ChatFormatting.AQUA).withUnderlined(true)
					.withClickEvent(new ClickEvent.OpenUrl(URI.create(s.url)))));
			return (Component) line;
		}).toList();
	}
}
