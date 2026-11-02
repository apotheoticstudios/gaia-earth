package se.itssimple.realearth.osm;

import java.util.Arrays;
import java.util.List;

/**
 * Draws water shapes onto a grid: areas filled by scanline (even-odd across all rings of one area, so
 * islands stay dry), waterways as round-capped strokes of their real width.
 *
 * A grid is described by the sample coordinate of its first cell and how many cells there are per
 * sample (1 for the terrain grid, 4 for the shoreline raster). Cell centres sit at
 * origin + (i + 0.5) / perSample - 0.5, which for perSample = 1 is exactly the sample positions.
 */
public final class Rasterizer {
	private Rasterizer() {
	}

	/**
	 * @param out       cells, row-major, {@code size * size}
	 * @param value     written into every covered cell (a higher value is never overwritten by a lower one)
	 */
	public static void fill(List<double[]> rings, double originX, double originY, int size, double perSample, byte[] out, byte value) {
		// Each edge is tested only on the rows it may cross (a row more either side, for rounding), not on all
		// of them: in a big coastline or a part of a tile that is most of the cost. A ring wholly left or right
		// of the grid is left out: it crosses each row an even number of times there, which changes nothing.
		// The first and last cell centres, a cell further out.
		double left = originX - 0.5 / perSample - 0.5;
		double right = originX + (size + 0.5) / perSample - 0.5;
		int edges = 0;
		for (double[] ring : rings) {
			edges += ring.length / 2;
		}
		double[] e = new double[edges * 4];
		int[] lastRow = new int[edges];
		int[] next = new int[edges];
		int[] first = new int[size];
		Arrays.fill(first, -1);
		int count = 0;
		for (double[] ring : rings) {
			int points = ring.length / 2;
			double minX = Double.MAX_VALUE;
			double maxX = -Double.MAX_VALUE;
			for (int i = 0; i < points; i++) {
				minX = Math.min(minX, ring[2 * i]);
				maxX = Math.max(maxX, ring[2 * i]);
			}
			if (maxX < left || minX > right) {
				continue;
			}
			for (int i = 0; i < points; i++) {
				double y0 = ring[2 * i + 1];
				double y1 = ring[2 * ((i + 1) % points) + 1];
				int r0 = Math.max(0, (int) Math.floor((Math.min(y0, y1) - originY + 0.5) * perSample - 0.5));
				int r1 = Math.min(size - 1, (int) Math.ceil((Math.max(y0, y1) - originY + 0.5) * perSample - 0.5));
				if (y0 == y1 || r0 > r1) {
					continue;
				}
				e[4 * count] = ring[2 * i];
				e[4 * count + 1] = y0;
				e[4 * count + 2] = ring[2 * ((i + 1) % points)];
				e[4 * count + 3] = y1;
				lastRow[count] = r1;
				next[count] = first[r0];
				first[r0] = count++;
			}
		}
		int[] active = new int[count];
		int live = 0;
		double[] xs = new double[64];
		for (int row = 0; row < size; row++) {
			for (int j = first[row]; j >= 0; j = next[j]) {
				active[live++] = j;
			}
			double y = originY + (row + 0.5) / perSample - 0.5;
			int n = 0;
			int kept = 0;
			for (int k = 0; k < live; k++) {
				int j = active[k];
				if (lastRow[j] < row) {
					continue;
				}
				active[kept++] = j;
				double x0 = e[4 * j];
				double y0 = e[4 * j + 1];
				double x1 = e[4 * j + 2];
				double y1 = e[4 * j + 3];
				if ((y0 <= y) != (y1 <= y)) {
					if (n == xs.length) {
						xs = Arrays.copyOf(xs, n * 2);
					}
					xs[n++] = x0 + (y - y0) / (y1 - y0) * (x1 - x0);
				}
			}
			live = kept;
			if (n < 2) {
				continue;
			}
			Arrays.sort(xs, 0, n);
			for (int k = 0; k + 1 < n; k += 2) {
				int c0 = (int) Math.ceil((xs[k] - originX + 0.5) * perSample - 0.5);
				int c1 = (int) Math.floor((xs[k + 1] - originX + 0.5) * perSample - 0.5);
				c0 = Math.max(c0, 0);
				c1 = Math.min(c1, size - 1);
				for (int c = c0; c <= c1; c++) {
					int idx = row * size + c;
					if (out[idx] < value) {
						out[idx] = value;
					}
				}
			}
		}
	}

	/** Strokes a polyline with radius {@code radiusSamples}. */
	public static void stroke(double[] line, double radiusSamples, double originX, double originY, int size, double perSample, byte[] out, byte value) {
		double r = Math.max(radiusSamples, 0.5 / perSample);
		for (int i = 0; i + 3 < line.length; i += 2) {
			double ax = line[i];
			double ay = line[i + 1];
			double bx = line[i + 2];
			double by = line[i + 3];
			int c0 = Math.max(0, (int) Math.floor((Math.min(ax, bx) - r - originX + 0.5) * perSample - 0.5));
			int c1 = Math.min(size - 1, (int) Math.ceil((Math.max(ax, bx) + r - originX + 0.5) * perSample - 0.5));
			int r0 = Math.max(0, (int) Math.floor((Math.min(ay, by) - r - originY + 0.5) * perSample - 0.5));
			int r1 = Math.min(size - 1, (int) Math.ceil((Math.max(ay, by) + r - originY + 0.5) * perSample - 0.5));
			double dx = bx - ax;
			double dy = by - ay;
			double len2 = dx * dx + dy * dy;
			for (int row = r0; row <= r1; row++) {
				double py = originY + (row + 0.5) / perSample - 0.5;
				for (int c = c0; c <= c1; c++) {
					double px = originX + (c + 0.5) / perSample - 0.5;
					double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
					double ex = ax + t * dx - px;
					double ey = ay + t * dy - py;
					if (ex * ex + ey * ey <= r * r) {
						int idx = row * size + c;
						if (out[idx] < value) {
							out[idx] = value;
						}
					}
				}
			}
		}
	}
}
