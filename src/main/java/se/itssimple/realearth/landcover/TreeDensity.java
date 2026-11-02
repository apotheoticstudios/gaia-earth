package se.itssimple.realearth.landcover;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import se.itssimple.realearth.RealEarth;

/**
 * How many trees stand on a hectare of woodland, anywhere on land: Crowther et al. (2015), "Mapping tree density at
 * a global scale", Nature 525, 201-205; data Glick et al. (2016), CC BY 4.0. Built from over 420,000 ground counts of
 * trees at least 10 cm across; bundled as a 0.05 degree grid by tools/treedensity.
 *
 * Where trees stand comes from the land cover ({@link WorldCover}); this says how close together: about 1,000 a
 * hectare in a boreal stand, 3 m apart, a few dozen in dry woodland, 15 m and more.
 */
public final class TreeDensity {
	/** Grid values are 10^(value / 64) trees a hectare; 0 is no data. */
	private static final double STEPS_PER_DECADE = 64.0;

	private static volatile TreeDensity instance;
	private static volatile boolean failed;

	private final int width;
	private final int height;
	private final double north;
	private final double west;
	private final double step;
	private final byte[] cells;

	private TreeDensity(byte[] file) throws IOException, DataFormatException {
		ByteBuffer b = ByteBuffer.wrap(file);
		if (b.getInt() != 0x52455444) { // "RETD"
			throw new IOException("not a tree density grid");
		}
		width = b.getInt();
		height = b.getInt();
		north = b.getDouble();
		west = b.getDouble();
		step = b.getDouble();
		cells = new byte[width * height];
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(file, b.position(), file.length - b.position());
			int n = 0;
			while (n < cells.length && !inflater.finished()) {
				n += inflater.inflate(cells, n, cells.length - n);
			}
		} finally {
			inflater.end();
		}
	}

	/** The bundled grid, or null if it can't be read. */
	public static TreeDensity get() {
		TreeDensity d = instance;
		if (d == null && !failed) {
			synchronized (TreeDensity.class) {
				if (instance == null && !failed) {
					try (InputStream in = TreeDensity.class.getResourceAsStream("/realearth/treedensity.bin")) {
						instance = new TreeDensity(in.readAllBytes());
					} catch (IOException | DataFormatException | NullPointerException e) {
						failed = true;
						RealEarth.LOGGER.error("Could not load the bundled tree density map; trees grow at a default spacing", e);
					}
				}
				d = instance;
			}
		}
		return d;
	}

	/** Trees a hectare of woodland at a place, nearest cell; 0 where the map has no data (the sea, ice). */
	public double perHectare(double latitude, double longitude) {
		int x = Math.floorMod((int) Math.floor((longitude - west) / step), width);
		int y = (int) Math.floor((north - latitude) / step);
		if (y < 0 || y >= height) {
			return 0;
		}
		int v = cells[y * width + x] & 0xFF;
		return v == 0 ? 0 : Math.pow(10, v / STEPS_PER_DECADE);
	}
}
