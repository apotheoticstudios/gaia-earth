package se.itssimple.realearth.climate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.world.GeoNoise;

/**
 * The world's ecoregions: RESOLVE Ecoregions 2017, Dinerstein et al. (2017), BioScience 67(6): 534-545, CC BY 4.0. 846
 * regions of like nature, each in one of 14 biomes (the Sierra Nevada's forests are temperate conifer forest, the
 * Serengeti's bushland tropical savanna), bundled as a 0.05 degree grid by tools/ecoregions, with the forests some
 * ecoregions are known for (temperate rainforest, hemiboreal forest, laurel forest, eucalypts) as their sub-biome.
 */
public final class Ecoregions {
	public static final int ROCK_AND_ICE = 0;
	public static final int TROPICAL_MOIST_FOREST = 1;
	public static final int TROPICAL_DRY_FOREST = 2;
	public static final int TROPICAL_CONIFER_FOREST = 3;
	public static final int TEMPERATE_BROADLEAF_FOREST = 4;
	public static final int TEMPERATE_CONIFER_FOREST = 5;
	public static final int BOREAL_FOREST = 6;
	public static final int TROPICAL_GRASSLAND = 7;
	public static final int TEMPERATE_GRASSLAND = 8;
	public static final int FLOODED_GRASSLAND = 9;
	public static final int MONTANE_GRASSLAND = 10;
	public static final int TUNDRA = 11;
	public static final int MEDITERRANEAN = 12;
	public static final int DESERT = 13;
	public static final int MANGROVES = 14;

	/** An ecoregion: its biome (above), and its sub-biome ("rainforest", "hemiboreal", "laurel", "eucalypt", or "-"). */
	public record Region(int ecoId, int biome, String sub, String realm, String name) {
	}

	/** Edges wind by up to a cell (5.5 km) over about 10 km, as the Köppen classes' do, instead of following the grid. */
	private static final double WARP_CELLS = 1.0;
	private static final double WARP_KM = 10.0;

	private static volatile Ecoregions instance;
	private static volatile boolean failed;

	private final int width;
	private final int height;
	private final double north;
	private final double west;
	private final double step;
	/** Each row as runs of one ecoregion: row y's runs are rowStart[y] to rowStart[y + 1], each from column runX to the next's. */
	private final int[] rowStart;
	private final short[] runX;
	private final short[] runRow;
	private final List<Region> regions = new ArrayList<>();

	private Ecoregions(byte[] grid, InputStream table) throws IOException, DataFormatException {
		ByteBuffer b = ByteBuffer.wrap(grid);
		if (b.getInt() != 0x52454543) { // "REEC"
			throw new IOException("not an ecoregion grid");
		}
		width = b.getInt();
		height = b.getInt();
		north = b.getDouble();
		west = b.getDouble();
		step = b.getDouble();
		// Read a row at a time into runs (about 280,000 of them, 1 MB): the whole grid would be 52 MB.
		rowStart = new int[height + 1];
		short[] xs = new short[1 << 16];
		short[] rows = new short[1 << 16];
		int runs = 0;
		byte[] raw = new byte[width * 2];
		ByteBuffer cells = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(grid, b.position(), grid.length - b.position());
			for (int y = 0; y < height; y++) {
				for (int n = 0; n < raw.length;) {
					int got = inflater.inflate(raw, n, raw.length - n);
					if (got == 0 && (inflater.finished() || inflater.needsInput())) {
						throw new IOException("ecoregion grid cut short");
					}
					n += got;
				}
				rowStart[y] = runs;
				for (int x = 0; x < width; x++) {
					short v = cells.getShort(2 * x);
					if (x == 0 || v != rows[runs - 1]) {
						if (runs == xs.length) {
							xs = java.util.Arrays.copyOf(xs, runs * 2);
							rows = java.util.Arrays.copyOf(rows, runs * 2);
						}
						xs[runs] = (short) x;
						rows[runs++] = v;
					}
				}
			}
			rowStart[height] = runs;
		} finally {
			inflater.end();
		}
		runX = java.util.Arrays.copyOf(xs, runs);
		runRow = java.util.Arrays.copyOf(rows, runs);
		regions.add(null); // row 0: no ecoregion
		try (BufferedReader r = new BufferedReader(new InputStreamReader(table, StandardCharsets.UTF_8))) {
			r.readLine();
			for (String line; (line = r.readLine()) != null;) {
				String[] f = line.split("\t", -1);
				regions.add(new Region(Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3], f[4], f[5]));
			}
		}
	}

	/** The bundled grid, or null if it can't be read. */
	public static Ecoregions get() {
		Ecoregions e = instance;
		if (e == null && !failed) {
			synchronized (Ecoregions.class) {
				if (instance == null && !failed) {
					try (InputStream grid = Ecoregions.class.getResourceAsStream("/realearth/ecoregions.bin");
						InputStream table = Ecoregions.class.getResourceAsStream("/realearth/ecoregions.tsv")) {
						instance = new Ecoregions(grid.readAllBytes(), table);
					} catch (IOException | DataFormatException | RuntimeException e2) {
						failed = true;
						RealEarth.LOGGER.error("Could not load the bundled ecoregion map; Earth's biomes are vanilla's", e2);
					}
				}
				e = instance;
			}
		}
		return e;
	}

	/** The ecoregion at a place, or null where there is none (the open sea). */
	public Region at(double latitude, double longitude) {
		double warpX = WARP_CELLS * GeoNoise.sample(latitude, longitude, WARP_KM, 47);
		double warpY = WARP_CELLS * GeoNoise.sample(latitude, longitude, WARP_KM, 53);
		int x = Math.floorMod((int) Math.floor((longitude - west) / step + warpX), width);
		int y = Math.max(0, Math.min(height - 1, (int) Math.floor((north - latitude) / step + warpY)));
		// The last run of the row starting at or before x.
		int lo = rowStart[y];
		int hi = rowStart[y + 1] - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (runX[mid] <= x) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		int row = runRow[lo] & 0xFFFF;
		return row < regions.size() ? regions.get(row) : null;
	}
}
