package se.itssimple.realearth.climate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import se.itssimple.realearth.RealEarth;

/**
 * A bundled grid of metres over the whole globe, read between its points: the snowline's and the
 * relief's format ("RESL", tools/snowline and tools/relief).
 */
public final class MetreGrid {
	private final int width;
	private final int height;
	private final double north;
	private final double west;
	private final double step;
	private final short[] meters;

	private MetreGrid(byte[] file) throws IOException, DataFormatException {
		ByteBuffer b = ByteBuffer.wrap(file);
		if (b.getInt() != 0x5245534C) { // "RESL"
			throw new IOException("not a RESL grid");
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
				throw new IOException("grid file is short: " + n + " of " + raw.length + " bytes");
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

	/** The grid in a resource of the mod's jar, or null (logged) if the jar is damaged. */
	public static MetreGrid load(String resource, String fallback) {
		try (InputStream in = MetreGrid.class.getResourceAsStream(resource)) {
			return new MetreGrid(in.readAllBytes());
		} catch (IOException | DataFormatException | NullPointerException e) {
			RealEarth.LOGGER.error("Could not load the bundled {}; {}", resource, fallback, e);
			return null;
		}
	}

	/** Metres at a place, between the grid's points. */
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
