import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GHSL's population grid (GHS-POP R2023A, 2025, 1 km on World Mollweide; CC BY 4.0, European Commission JRC) summed
 * onto a quarter-degree latitude-longitude grid, for weighting the projection by where people live (Tune.java).
 *
 * <pre>java Population.java GHS_POP_E2025_GLOBE_R2023A_54009_1000_V1_0.tif population.bin</pre>
 *
 * Writes 720 rows (north to south, from 90N) of 1440 doubles (from 180W), big-endian: the people in each cell.
 */
public class Population {
	static final int COLS = 1440, ROWS = 720;

	public static void main(String[] args) throws IOException {
		try (RandomAccessFile f = new RandomAccessFile(args[0], "r")) {
			Tiff t = new Tiff(f);
			double[] grid = new double[COLS * ROWS];
			double r = t.originX / -(2 * Math.sqrt(2)); // the sphere's radius: x runs to 2 sqrt 2 R either side
			double total = 0;
			for (int ty = 0; ty < t.tilesDown; ty++) {
				for (int tx = 0; tx < t.tilesAcross; tx++) {
					double[] tile = t.tile(tx, ty);
					for (int j = 0; j < t.tileH; j++) {
						int row = ty * t.tileH + j;
						if (row >= t.height) {
							break;
						}
						double y = t.originY - (row + 0.5) * t.pixel;
						double theta = Math.asin(y / (Math.sqrt(2) * r));
						double lat = Math.toDegrees(Math.asin((2 * theta + Math.sin(2 * theta)) / Math.PI));
						for (int i = 0; i < t.tileW; i++) {
							int col = tx * t.tileW + i;
							double v = tile[j * t.tileW + i];
							if (col >= t.width || !(v > 0)) {
								continue;
							}
							double x = t.originX + (col + 0.5) * t.pixel;
							double lon = Math.toDegrees(Math.PI * x / (2 * Math.sqrt(2) * r * Math.cos(theta)));
							if (Math.abs(lon) > 180) {
								continue; // the corners outside the ellipse
							}
							int gx = Math.min(COLS - 1, (int) ((lon + 180) * 4));
							int gy = Math.min(ROWS - 1, (int) ((90 - lat) * 4));
							grid[gy * COLS + gx] += v;
							total += v;
						}
					}
				}
			}
			try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(Path.of(args[1]))))) {
				for (double v : grid) {
					out.writeDouble(v);
				}
			}
			System.out.printf("%.0f people (%.3f billion) in %d x %d cells%n", total, total / 1e9, COLS, ROWS);
		}
	}

	/** Just enough of a BigTIFF reader for this file: tiled, LZW, no predictor, 64-bit floats. */
	static final class Tiff {
		final RandomAccessFile f;
		int width, height, tileW, tileH, tilesAcross, tilesDown;
		long[] offsets, counts;
		double pixel, originX, originY;

		Tiff(RandomAccessFile f) throws IOException {
			this.f = f;
			ByteBuffer head = read(0, 16);
			if (head.getShort(2) != 43) {
				throw new IOException("not a BigTIFF");
			}
			long ifd = head.getLong(8);
			long n = read(ifd, 8).getLong(0);
			int compression = 0, bits = 0, format = 0, predictor = 1;
			for (int i = 0; i < n; i++) {
				ByteBuffer e = read(ifd + 8 + i * 20L, 20);
				int tag = e.getShort(0) & 0xFFFF, type = e.getShort(2);
				long count = e.getLong(4);
				switch (tag) {
					case 256 -> width = (int) scalar(e, type);
					case 257 -> height = (int) scalar(e, type);
					case 258 -> bits = (int) scalar(e, type);
					case 259 -> compression = (int) scalar(e, type);
					case 317 -> predictor = (int) scalar(e, type);
					case 339 -> format = (int) scalar(e, type);
					case 322 -> tileW = (int) scalar(e, type);
					case 323 -> tileH = (int) scalar(e, type);
					case 324 -> offsets = array(e, type, count);
					case 325 -> counts = array(e, type, count);
					case 33550 -> pixel = read(e.getLong(12), 8).getDouble(0);
					case 33922 -> {
						ByteBuffer tp = read(e.getLong(12), 48);
						originX = tp.getDouble(24);
						originY = tp.getDouble(32);
					}
					default -> {
					}
				}
			}
			if (compression != 5 || bits != 64 || format != 3 || predictor != 1 || tileW == 0) {
				throw new IOException("unexpected layout: compression " + compression + ", " + bits + " bits, format " + format + ", predictor " + predictor);
			}
			tilesAcross = (width + tileW - 1) / tileW;
			tilesDown = (height + tileH - 1) / tileH;
		}

		double[] tile(int tx, int ty) throws IOException {
			int k = ty * tilesAcross + tx;
			byte[] raw = new byte[(int) counts[k]];
			f.seek(offsets[k]);
			f.readFully(raw);
			ByteBuffer b = ByteBuffer.wrap(lzw(raw, tileW * tileH * 8)).order(ByteOrder.LITTLE_ENDIAN);
			double[] v = new double[tileW * tileH];
			for (int i = 0; i < v.length; i++) {
				v[i] = b.getDouble(i * 8);
			}
			return v;
		}

		private ByteBuffer read(long at, int n) throws IOException {
			byte[] b = new byte[n];
			f.seek(at);
			f.readFully(b);
			return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
		}

		private static long scalar(ByteBuffer e, int type) {
			return switch (type) {
				case 3 -> e.getShort(12) & 0xFFFF;
				case 4 -> e.getInt(12) & 0xFFFFFFFFL;
				default -> e.getLong(12);
			};
		}

		private long[] array(ByteBuffer e, int type, long count) throws IOException {
			int size = type == 4 ? 4 : 8;
			ByteBuffer b = read(e.getLong(12), (int) (count * size));
			long[] v = new long[(int) count];
			for (int i = 0; i < count; i++) {
				v[i] = size == 4 ? b.getInt(i * 4) & 0xFFFFFFFFL : b.getLong(i * 8);
			}
			return v;
		}

		/** TIFF's LZW: codes MSB first, 9 to 12 bits, widening one code early. */
		static byte[] lzw(byte[] in, int size) {
			byte[] out = new byte[size];
			byte[][] table = new byte[4096][];
			for (int i = 0; i < 256; i++) {
				table[i] = new byte[] {(byte) i};
			}
			int next = 258, width = 9, pos = 0;
			long bitPos = 0;
			byte[] prev = null;
			while (pos < size) {
				int code = 0;
				for (int i = 0; i < width; i++, bitPos++) {
					int byteAt = (int) (bitPos >> 3);
					int bit = byteAt < in.length ? (in[byteAt] >> (7 - (bitPos & 7))) & 1 : 0;
					code = (code << 1) | bit;
				}
				if (code == 257) {
					break;
				}
				if (code == 256) {
					next = 258;
					width = 9;
					prev = null;
					continue;
				}
				byte[] entry;
				if (code < next && table[code] != null) {
					entry = table[code];
					if (prev != null) {
						table[next++] = concat(prev, entry[0]);
					}
				} else {
					entry = concat(prev, prev[0]);
					table[next++] = entry;
				}
				int n = Math.min(entry.length, size - pos);
				System.arraycopy(entry, 0, out, pos, n);
				pos += n;
				prev = entry;
				if (next + 1 >= (1 << width) && width < 12) {
					width++;
				}
			}
			return out;
		}

		private static byte[] concat(byte[] a, byte b) {
			byte[] c = java.util.Arrays.copyOf(a, a.length + 1);
			c[a.length] = b;
			return c;
		}
	}
}
