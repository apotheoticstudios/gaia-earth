package net.apotheoticstudios.gaiaearth.climate;

/**
 * The climatic snowline of any place on Earth: the height where a glacier's winter snow just lasts the
 * summer (its equilibrium line), every 0.1 degrees.
 *
 * Built by tools/snowline/build_snowline.py from the Randolph Glacier Inventory 7.0 (RGI 7.0 Consortium,
 * 2023; CC BY 4.0), whose glaciers' median heights say where it is wherever there is ice, and CHELSA
 * V2.1's summer temperature and elevation (Karger et al. 2017; CC0), which carry it on everywhere else.
 * So wet coasts get lower snowlines than dry interiors at the same latitude: western Norway's glaciers
 * reach far lower than Jotunheimen's. Bundled with the mod, 12 MB in memory once loaded.
 */
public final class Snowline {
	private static volatile MetreGrid instance;
	private static volatile boolean failed;

	private Snowline() {
	}

	/** The bundled grid of snowlines in metres above the sea (below 0 where snow lasts at sea level), loaded on first use. Null only if the jar is damaged. */
	public static MetreGrid get() {
		MetreGrid s = instance;
		if (s == null && !failed) {
			synchronized (Snowline.class) {
				if (instance == null && !failed) {
					instance = MetreGrid.load("/realearth/snowline.bin", "snowlines fall back to latitude");
					failed = instance == null;
				}
				s = instance;
			}
		}
		return s;
	}
}
