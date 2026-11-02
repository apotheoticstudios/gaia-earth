package se.itssimple.realearth.climate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import se.itssimple.realearth.RealEarth;

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
	private static volatile Snowline instance;
	private static volatile boolean failed;

	private final int width;
	private final int height;
	private final double north;
	private final double west;
	private final double step;
	private final short[] meters;

	private Snowline(byte[] file) throws IOException, DataFormatException {
		ByteBuffer b = ByteBuffer.wrap(file);
		if (b.getInt() != 0x5245534C) { // "RESL"
			throw new IOException("not a RESL snowline file");
		}
		width = b.getInt();
		height = b.getInt();
		north = b.getDouble();
		west = b.getDouble();
		step = b.getDouble();
		byte[] raw = new byte[width * height * 2];
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(file, b.position(), file.length - b.position());
			int n = 0;
			while (n < raw.length && !inflater.finished()) {
				n += inflater.inflate(raw, n, raw.length - n);
			}
			if (n != raw.length) {
				throw new IOException("snowline file is short: " + n + " of " + raw.length + " bytes");
			}
		} finally {
			inflater.end();
		}
		// Each row is stored as differences from the point to its west.
		meters = new short[width * height];
		ByteBuffer r = ByteBuffer.wrap(raw);
		for (int y = 0; y < height; y++) {
			int sum = 0;
			for (int x = 0; x < width; x++) {
				sum += r.getShort();
				meters[y * width + x] = (short) sum;
			}
		}
	}

	/** The bundled grid, loaded on first use. Null only if the jar is damaged. */
	public static Snowline get() {
		Snowline s = instance;
		if (s == null && !failed) {
			synchronized (Snowline.class) {
				if (instance == null && !failed) {
					try (InputStream in = Snowline.class.getResourceAsStream("/realearth/snowline.bin")) {
						instance = new Snowline(in.readAllBytes());
					} catch (IOException | DataFormatException | NullPointerException e) {
						failed = true;
						RealEarth.LOGGER.error("Could not load the bundled snowline map; snowlines fall back to latitude", e);
					}
				}
				s = instance;
			}
		}
		return s;
	}

	/** Snowline in metres above the sea, between the grid's points. Below 0 where snow lasts at sea level. */
	public double metersAt(double latitude, double longitude) {
		double fy = Math.max(0, Math.min(height - 1, (north - latitude) / step));
		double fx = (longitude - west) / step;
		int y0 = (int) Math.floor(fy);
		int y1 = Math.min(height - 1, y0 + 1);
		int x0 = (int) Math.floor(fx);
		double ty = fy - y0;
		double tx = fx - x0;
		int xa = Math.floorMod(x0, width);
		int xb = Math.floorMod(x0 + 1, width);
		double top = meters[y0 * width + xa] * (1 - tx) + meters[y0 * width + xb] * tx;
		double bottom = meters[y1 * width + xa] * (1 - tx) + meters[y1 * width + xb] * tx;
		return top * (1 - ty) + bottom * ty;
	}
}
