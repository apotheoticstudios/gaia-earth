import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Turns a conformal icosahedral projection (BTE's construction: the sphere mapped conformally onto an icosahedron's
 * faces, unfolded) and sets its scale so that as many people as possible stand at their true size, weighted by GHSL's
 * population (population.bin, from Population.java).
 *
 * <pre>java Tune.java population.bin</pre>
 *
 * The scale of such a map has a closed form: blocks per metre at p is K * prod over the 12 vertices v of |p - v|^(-1/6)
 * (the -1/6 is each vertex's angle deficit, pi/3, over 2 pi; Klein's icosahedral function gives it, and it matches BTE's
 * projection within 0.2% at 13 cities, the rest its interpolated table). So only the vertices and K matter here, not
 * the faces' layout. Positions go through the conformal latitude first, so the map is conformal on the WGS84
 * ellipsoid itself, not just on a sphere.
 */
public class Tune {
	static final double A = 6378137.0, F = 1 / 298.257223563, E2 = F * (2 - F), E = Math.sqrt(E2);
	/** Within 3%: blocks per metre from 0.97 to 1.03. */
	static final double LO = Math.log(0.97), HI = Math.log(1.03);
	static final double STOCKHOLM_LAT = 59.3293, STOCKHOLM_LON = 18.0686;
	/** BTE's vertices (Fuller's Dymaxion), longitude and latitude, from terraplusplus (MIT). */
	static final double[][] FULLER = {{10.536199, 64.7}, {-5.245390, 2.300882}, {58.157706, 10.447378}, {122.3, 39.1},
		{-143.478490, 50.103201}, {-67.132330, 23.717925}, {36.521510, -50.1032}, {112.867673, -23.71793}, {174.754610, -2.300882},
		{-121.842290, -10.44735}, {-57.7, -39.1}, {-169.4638, -64.7}};

	/** Populated cells: a point on the conformal sphere, the ellipsoid's own scale there (log), and the people. */
	record Cells(double[] x, double[] y, double[] z, double[] logM, double[] w, double total) {
		int n() {
			return w.length;
		}
	}

	record Stats(double logS, double within1, double within3, double within5, double within10, double p50, double p90, double p99, double stockholm) {
	}

	public static void main(String[] args) throws IOException {
		double[] grid = new double[1440 * 720];
		try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(Path.of(args[0]))))) {
			for (int i = 0; i < grid.length; i++) {
				grid[i] = in.readDouble();
			}
		}
		Cells fine = cells(grid, 1);
		Cells coarse = cells(grid, 4);
		System.out.printf("%d populated quarter-degree cells, %d one-degree; %.3f billion people%n", fine.n(), coarse.n(), fine.total() / 1e9);

		double[][] fuller = new double[12][];
		for (int i = 0; i < 12; i++) {
			fuller[i] = unit(conformalLat(FULLER[i][1]), Math.toRadians(FULLER[i][0]));
		}
		report("BTE's vertices, best scale", fuller, fine, Double.NaN);
		// BTE as it is: geodetic latitude taken as the sphere's, and its own scale, 1.2230 blocks per metre (east, on
		// its sphere) at Stockholm, from its code.
		double[][] fullerGeodetic = new double[12][];
		for (int i = 0; i < 12; i++) {
			fullerGeodetic[i] = unit(Math.toRadians(FULLER[i][1]), Math.toRadians(FULLER[i][0]));
		}
		double bteLogS = Math.log(1.2230) - logScale(fullerGeodetic, unit(Math.toRadians(STOCKHOLM_LAT), Math.toRadians(STOCKHOLM_LON)));
		report("BTE as it is", fullerGeodetic, fineGeodetic(grid), bteLogS);

		// A regular icosahedron, turned every way: first coarsely, then the best refined on the fine cells.
		double[][] base = icosahedron();
		Random random = new Random(20261104);
		int samples = 200_000;
		double[][] quats = new double[samples][];
		for (int i = 0; i < samples; i++) {
			quats[i] = randomQuat(random);
		}
		double[] score = new double[samples];
		IntStream.range(0, samples).parallel().forEach(i -> score[i] = objective(rotate(base, quats[i]), coarse, false));
		Integer[] order = new Integer[samples];
		for (int i = 0; i < samples; i++) {
			order[i] = i;
		}
		Arrays.sort(order, Comparator.comparingDouble(i -> -score[i]));
		System.out.printf("coarse search: %d orientations, best %.1f%% of people within 3%%%n", samples, 100 * score[order[0]]);

		List<double[]> refined = new ArrayList<>();
		for (int r = 0; r < 24; r++) {
			refined.add(quats[order[r]]);
		}
		List<double[]> best = refined.parallelStream().map(q -> refine(base, q, fine, new Random(Arrays.hashCode(q)), false)).toList();
		double[] winner = best.stream().max(Comparator.comparingDouble(q -> objective(rotate(base, q), fine, false))).orElseThrow();
		double[][] vertices = rotate(base, winner);
		Stats s = report("tuned", vertices, fine, Double.NaN);
		System.out.printf("tuned: quaternion %.12f %.12f %.12f %.12f; scale K = %.10f%n", winner[0], winner[1], winner[2], winner[3], Math.exp(s.logS()));
		System.out.println("tuned vertices (geodetic latitude, longitude):");
		for (double[] v : vertices) {
			System.out.printf("  %10.5f %11.5f%n", Math.toDegrees(geodeticLat(Math.asin(v[2]))), Math.toDegrees(Math.atan2(v[1], v[0])));
		}
		// The same, but with the start held at exactly 1:1 (K fixed by Stockholm), refined from the best orientations.
		List<double[]> held = best.parallelStream().map(q -> refine(base, q, fine, new Random(~Arrays.hashCode(q)), true)).toList();
		double[] heldWinner = held.stream().max(Comparator.comparingDouble(q -> objective(rotate(base, q), fine, true))).orElseThrow();
		double[][] heldVertices = rotate(base, heldWinner);
		Stats hs = report(String.format("tuned, Stockholm held %.2f", START), heldVertices, fine, startLogS(heldVertices));
		System.out.printf("tuned, Stockholm held: quaternion %.12f %.12f %.12f %.12f; scale K = %.10f%n", heldWinner[0], heldWinner[1], heldWinner[2], heldWinner[3],
			Math.exp(hs.logS()));
		seams("tuned, Stockholm held", heldVertices, fine);
		seams("BTE's vertices", fuller, fine);
		seams("tuned", vertices, fine);
		cities("tuned", vertices, s.logS());
		cities("tuned, Stockholm held", heldVertices, hs.logS());
		net("tuned", vertices, fine);
		net("tuned, Stockholm held", heldVertices, fine);
		net("BTE's vertices", fuller, fine);
		// The runners-up, for a choice with fewer people on a seam.
		for (int r = 0; r < 6; r++) {
			double[][] v = rotate(base, best.get(r));
			Stats st = evaluate(v, fine, Double.NaN);
			System.out.printf("candidate %d: %.1f%% within 3%%, 90%% within %.1f%%, Stockholm %.4f; ", r, 100 * st.within3(), 100 * st.p90(), st.stockholm());
			seams(null, v, fine);
		}
	}

	/** The fine cells summed into blocks of {@code step} x {@code step}, each at its people's mean position. */
	static Cells cells(double[] grid, int step) {
		List<double[]> list = new ArrayList<>();
		double total = 0;
		for (int gy = 0; gy < 720; gy += step) {
			for (int gx = 0; gx < 1440; gx += step) {
				double w = 0, lat = 0, lon = 0;
				for (int j = 0; j < step; j++) {
					for (int i = 0; i < step; i++) {
						double v = grid[(gy + j) * 1440 + gx + i];
						w += v;
						lat += v * (90 - (gy + j + 0.5) / 4);
						lon += v * (-180 + (gx + i + 0.5) / 4);
					}
				}
				if (w > 0) {
					list.add(new double[] {lat / w, lon / w, w});
					total += w;
				}
			}
		}
		int n = list.size();
		double[] x = new double[n], y = new double[n], z = new double[n], logM = new double[n], w = new double[n];
		for (int i = 0; i < n; i++) {
			double phi = Math.toRadians(list.get(i)[0]);
			double chi = conformalLat(list.get(i)[0]);
			double[] p = unit(chi, Math.toRadians(list.get(i)[1]));
			x[i] = p[0];
			y[i] = p[1];
			z[i] = p[2];
			// Metres on the sphere of radius A per metre on the ellipsoid, east (and so north, being conformal).
			logM[i] = Math.log(A * Math.cos(chi) / (primeVertical(phi) * Math.cos(phi)));
			w[i] = list.get(i)[2];
		}
		return new Cells(x, y, z, logM, w, total);
	}

	/** As BTE places points: geodetic latitude taken as the sphere's, scale the mean of north and east. */
	static Cells fineGeodetic(double[] grid) {
		Cells c = cells(grid, 1);
		for (int i = 0; i < c.n(); i++) {
			double phi = Math.asin(conformalToGeodeticZ(c.z()[i]));
			double lon = Math.atan2(c.y()[i], c.x()[i]);
			double[] p = unit(phi, lon);
			c.x()[i] = p[0];
			c.y()[i] = p[1];
			c.z()[i] = p[2];
			double s = Math.sin(phi), w = Math.sqrt(1 - E2 * s * s);
			double m = A * (1 - E2) / (w * w * w), nn = A / w;
			c.logM()[i] = Math.log(A / Math.sqrt(m * nn));
		}
		return c;
	}

	static double conformalToGeodeticZ(double z) {
		return Math.sin(geodeticLat(Math.asin(z)));
	}

	/** log(prod |p - v|^(-1/6)): the icosahedral map's scale at p, up to K. */
	static double logScale(double[][] v, double[] p) {
		double prod = 1;
		for (double[] q : v) {
			double dx = p[0] - q[0], dy = p[1] - q[1], dz = p[2] - q[2];
			prod *= dx * dx + dy * dy + dz * dz;
		}
		return -Math.log(prod) / 12;
	}

	/**
	 * How near true size people are: with K chosen to put the most people within 3% (or {@code logS} if given), the
	 * shares within 1, 3, 5 and 10%, the error that half, 90% and 99% of people are within, and Stockholm's scale.
	 */
	static Stats evaluate(double[][] v, Cells c, double logS) {
		int n = c.n();
		double[] lk = new double[n];
		for (int i = 0; i < n; i++) {
			lk[i] = logScale(v, new double[] {c.x()[i], c.y()[i], c.z()[i]}) + c.logM()[i];
		}
		Integer[] idx = new Integer[n];
		for (int i = 0; i < n; i++) {
			idx[i] = i;
		}
		Arrays.sort(idx, Comparator.comparingDouble(i -> lk[i]));
		if (Double.isNaN(logS)) {
			// The window [LO, HI] in log scale holding the most people, by two pointers over the sorted scales.
			double best = -1, sum = 0;
			int lo = 0;
			for (int hi = 0; hi < n; hi++) {
				sum += c.w()[idx[hi]];
				while (lk[idx[hi]] - lk[idx[lo]] > HI - LO) {
					sum -= c.w()[idx[lo++]];
				}
				if (sum > best) {
					best = sum;
					logS = LO - lk[idx[lo]];
				}
			}
		}
		double[] err = new double[n];
		for (int i = 0; i < n; i++) {
			err[i] = Math.abs(Math.exp(lk[i] + logS) - 1);
		}
		Integer[] byErr = new Integer[n];
		for (int i = 0; i < n; i++) {
			byErr[i] = i;
		}
		Arrays.sort(byErr, Comparator.comparingDouble(i -> err[i]));
		double acc = 0, w1 = 0, w3 = 0, w5 = 0, w10 = 0, p50 = -1, p90 = -1, p99 = -1;
		for (int i : byErr) {
			acc += c.w()[i];
			w1 += err[i] <= 0.01 ? c.w()[i] : 0;
			w3 += err[i] <= 0.03 + 1e-12 ? c.w()[i] : 0;
			w5 += err[i] <= 0.05 ? c.w()[i] : 0;
			w10 += err[i] <= 0.10 ? c.w()[i] : 0;
			p50 = p50 < 0 && acc >= 0.5 * c.total() ? err[i] : p50;
			p90 = p90 < 0 && acc >= 0.9 * c.total() ? err[i] : p90;
			p99 = p99 < 0 && acc >= 0.99 * c.total() ? err[i] : p99;
		}
		double phi = Math.toRadians(STOCKHOLM_LAT), chi = conformalLat(STOCKHOLM_LAT);
		double sthlm = Math.exp(logScale(v, unit(chi, Math.toRadians(STOCKHOLM_LON))) + logS
			+ Math.log(A * Math.cos(chi) / (primeVertical(phi) * Math.cos(phi))));
		double t = c.total();
		return new Stats(logS, w1 / t, w3 / t, w5 / t, w10 / t, p50, p90, p99, sthlm);
	}

	/**
	 * People within 3%, nudged by the 90th percentile's error so that ties go to the tighter fit; from histograms of
	 * the log scale in steps of 0.0001, fast enough to try hundreds of thousands of orientations.
	 */
	static double objective(double[][] v, Cells c, boolean start) {
		final int bins = 20000; // log scale from -1 to 1
		double[] h = new double[bins];
		double[] lk = new double[c.n()];
		double[] p = new double[3];
		for (int i = 0; i < c.n(); i++) {
			p[0] = c.x()[i];
			p[1] = c.y()[i];
			p[2] = c.z()[i];
			lk[i] = logScale(v, p) + c.logM()[i];
			int b = (int) ((lk[i] + 1) * bins / 2);
			if (b >= 0 && b < bins) {
				h[b] += c.w()[i];
			}
		}
		int width = (int) Math.round((HI - LO) * bins / 2);
		double sum = 0, best = -1;
		int bestStart = 0;
		for (int b = 0; b < bins; b++) {
			sum += h[b] - (b >= width ? h[b - width] : 0);
			if (sum > best) {
				best = sum;
				bestStart = b - width + 1;
			}
		}
		double logS = LO - (bestStart * 2.0 / bins - 1);
		if (start) {
			logS = startLogS(v);
			best = 0;
			for (int i = 0; i < c.n(); i++) {
				best += lk[i] + logS >= LO && lk[i] + logS <= HI ? c.w()[i] : 0;
			}
		}
		double[] e = new double[1000]; // |error| from 0 to 100% in steps of 0.1%
		for (int i = 0; i < c.n(); i++) {
			e[Math.min(999, (int) (Math.abs(Math.exp(lk[i] + logS) - 1) * 1000))] += c.w()[i];
		}
		double acc = 0;
		int p90 = 999;
		for (int b = 0; b < 1000; b++) {
			acc += e[b];
			if (acc >= 0.9 * c.total()) {
				p90 = b;
				break;
			}
		}
		return best / c.total() - 0.01 * p90 / 1000.0;
	}

	/** The scale the start is held at, when it is (-Dstart=0.99 to try another). */
	static final double START = Double.parseDouble(System.getProperty("start", "1"));

	/** The log of K that puts the start, Stockholm, at {@link #START} blocks a metre. */
	static double startLogS(double[][] v) {
		return Math.log(START) - logScale(v, unit(conformalLat(STOCKHOLM_LAT), Math.toRadians(STOCKHOLM_LON))) - logM(STOCKHOLM_LAT);
	}

	/** Metres on the sphere of radius A per metre on the ellipsoid at a latitude, through the conformal latitude. */
	static double logM(double latDegrees) {
		double phi = Math.toRadians(latDegrees), chi = conformalLat(latDegrees);
		return Math.log(A * Math.cos(chi) / (primeVertical(phi) * Math.cos(phi)));
	}

	static final Object[][] CITIES = {{"Stockholm", 59.33, 18.07}, {"London", 51.51, -0.13}, {"Paris", 48.86, 2.35}, {"Delft", 52.01, 4.36},
		{"Moscow", 55.76, 37.62}, {"New York", 40.71, -74.01}, {"Los Angeles", 34.05, -118.24}, {"Mexico City", 19.43, -99.13},
		{"Sao Paulo", -23.55, -46.63}, {"Buenos Aires", -34.60, -58.38}, {"Lagos", 6.52, 3.38}, {"Cairo", 30.04, 31.24},
		{"Johannesburg", -26.20, 28.05}, {"Delhi", 28.61, 77.21}, {"Singapore", 1.35, 103.82}, {"Beijing", 39.90, 116.40},
		{"Tokyo", 35.68, 139.69}, {"Sydney", -33.87, 151.21}, {"Auckland", -36.85, 174.76}};

	static void cities(String name, double[][] v, double logS) {
		StringBuilder b = new StringBuilder(name + " at cities:");
		for (Object[] c : CITIES) {
			double lat = (double) c[1], lon = (double) c[2];
			b.append(String.format(" %s %.3f,", c[0], Math.exp(logScale(v, unit(conformalLat(lat), Math.toRadians(lon))) + logM(lat) + logS)));
		}
		System.out.println(b);
	}

	/**
	 * The cut with the fewest people near it whose net lies flat without overlapping: every tree of 11 edges through
	 * the 12 vertices, cheapest first (branch and bound), unfolded face by face across the 19 edges left joined.
	 */
	static void net(String name, double[][] v, Cells c) {
		List<int[]> edges = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			for (int j = i + 1; j < 12; j++) {
				if (dot(v[i], v[j]) > 0.4) {
					edges.add(new int[] {i, j});
				}
			}
		}
		double[] people = edgePeople(v, edges, c);
		Integer[] order = new Integer[30];
		for (int i = 0; i < 30; i++) {
			order[i] = i;
		}
		Arrays.sort(order, Comparator.comparingDouble(e -> people[e]));
		List<int[]> faces = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			for (int j = i + 1; j < 12; j++) {
				for (int k = j + 1; k < 12; k++) {
					if (dot(v[i], v[j]) > 0.4 && dot(v[j], v[k]) > 0.4 && dot(v[i], v[k]) > 0.4) {
						faces.add(new int[] {i, j, k});
					}
				}
			}
		}
		double[] best = {Double.MAX_VALUE};
		boolean[] bestCut = new boolean[30];
		search(order, people, edges, faces, 0, new boolean[30], 0, 0, best, bestCut);
		StringBuilder b = new StringBuilder();
		for (int e = 0; e < 30; e++) {
			if (bestCut[e]) {
				b.append(String.format(" %d-%d (%.2fM)", edges.get(e)[0], edges.get(e)[1], people[e] / 1e6));
			}
		}
		System.out.printf("%s: the cheapest cut that unfolds flat passes %.2f million people (%.3f%%); cut edges:%s%n", name, best[0] / 1e6,
			100 * best[0] / c.total(), b);
	}

	private static void search(Integer[] order, double[] people, List<int[]> edges, List<int[]> faces, int at, boolean[] cut, int count, double sum,
		double[] best, boolean[] bestCut) {
		if (sum >= best[0]) {
			return;
		}
		if (count == 11) {
			if (unfoldsFlat(cut, edges, faces)) {
				best[0] = sum;
				System.arraycopy(cut, 0, bestCut, 0, 30);
			}
			return;
		}
		if (at == 30 || 30 - at < 11 - count) {
			return;
		}
		int e = order[at];
		int[] parent = new int[12];
		for (int i = 0; i < 12; i++) {
			parent[i] = i;
		}
		boolean cycle = false;
		for (int f = 0; f < 30; f++) {
			if (cut[f]) {
				parent[find(parent, edges.get(f)[0])] = find(parent, edges.get(f)[1]);
			}
		}
		cycle = find(parent, edges.get(e)[0]) == find(parent, edges.get(e)[1]);
		if (!cycle) {
			cut[e] = true;
			search(order, people, edges, faces, at + 1, cut, count + 1, sum + people[e], best, bestCut);
			cut[e] = false;
		}
		search(order, people, edges, faces, at + 1, cut, count, sum, best, bestCut);
	}

	/** Unfolds the faces across the edges not cut, on the plane's triangular grid; flat if no two land on one triangle. */
	static boolean unfoldsFlat(boolean[] cut, List<int[]> edges, List<int[]> faces) {
		double[][][] at = new double[20][][];
		int[][] verts = new int[20][];
		at[0] = new double[][] {{0, 0}, {1, 0}, {0.5, Math.sqrt(3) / 2}};
		verts[0] = faces.get(0);
		java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>(List.of(0));
		java.util.Set<Long> taken = new java.util.HashSet<>();
		taken.add(key(at[0]));
		int placed = 1;
		while (!queue.isEmpty()) {
			int f = queue.poll();
			for (int g = 0; g < 20; g++) {
				if (at[g] != null) {
					continue;
				}
				int[] shared = shared(verts[f], faces.get(g));
				if (shared == null || cut[edgeIndex(edges, verts[f][shared[0]], verts[f][shared[1]])]) {
					continue;
				}
				// The new face's third vertex: the old face's third reflected across the shared edge.
				double[] p = at[f][shared[0]], q = at[f][shared[1]], r = at[f][3 - shared[0] - shared[1]];
				double[] m = {(p[0] + q[0]) / 2, (p[1] + q[1]) / 2};
				double[] apex = {2 * m[0] - r[0], 2 * m[1] - r[1]};
				int a = verts[f][shared[0]], b = verts[f][shared[1]];
				int third = -1;
				for (int x : faces.get(g)) {
					if (x != a && x != b) {
						third = x;
					}
				}
				verts[g] = new int[] {a, b, third};
				at[g] = new double[][] {p, q, apex};
				if (!taken.add(key(at[g]))) {
					return false;
				}
				placed++;
				queue.add(g);
			}
		}
		return placed == 20;
	}

	private static long key(double[][] t) {
		long x = Math.round((t[0][0] + t[1][0] + t[2][0]) * 2), y = Math.round((t[0][1] + t[1][1] + t[2][1]) * 2);
		return x * 1_000_003L + y;
	}

	private static int[] shared(int[] f, int[] g) {
		int[] s = new int[2];
		int n = 0;
		for (int i = 0; i < 3; i++) {
			for (int x : g) {
				if (f[i] == x && n < 2) {
					s[n++] = i;
				}
			}
		}
		return n == 2 ? s : null;
	}

	private static int edgeIndex(List<int[]> edges, int a, int b) {
		for (int e = 0; e < edges.size(); e++) {
			int[] x = edges.get(e);
			if (x[0] == Math.min(a, b) && x[1] == Math.max(a, b)) {
				return e;
			}
		}
		throw new IllegalStateException();
	}

	/** A (1+1) evolution strategy on the orientation: small random turns, kept when better, the step shrinking. */
	static double[] refine(double[][] base, double[] q, Cells c, Random random, boolean start) {
		double best = objective(rotate(base, q), c, start);
		double sigma = Math.toRadians(2);
		for (int i = 0; i < 400 && sigma > Math.toRadians(0.005); i++) {
			double[] t = mul(smallTurn(random, sigma), q);
			double score = objective(rotate(base, t), c, start);
			if (score > best) {
				best = score;
				q = t;
				sigma *= 1.5;
			} else {
				sigma *= 0.9;
			}
		}
		return q;
	}

	static Stats report(String name, double[][] v, Cells c, double logS) {
		Stats s = evaluate(v, c, logS);
		System.out.printf("%-28s within 1%%: %5.1f%%  3%%: %5.1f%%  5%%: %5.1f%%  10%%: %5.1f%%;  half within %.2f%%, 90%% within %.2f%%, 99%% within %.1f%%;  Stockholm %.4f%n",
			name, 100 * s.within1(), 100 * s.within3(), 100 * s.within5(), 100 * s.within10(), 100 * s.p50(), 100 * s.p90(), 100 * s.p99(), s.stockholm());
		return s;
	}

	/**
	 * Where the map must be cut: unfolding an icosahedron cuts it along 11 of its 30 edges, a tree through all 12
	 * vertices. The tree with the fewest people within 25 km of it (Kruskal's), and the people within 500 km of a vertex.
	 */
	static void seams(String name, double[][] v, Cells c) {
		List<int[]> edges = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			for (int j = i + 1; j < 12; j++) {
				if (dot(v[i], v[j]) > 0.4) {
					edges.add(new int[] {i, j});
				}
			}
		}
		double[] people = edgePeople(v, edges, c);
		double nearVertex = 0;
		for (int k = 0; k < c.n(); k++) {
			double[] p = {c.x()[k], c.y()[k], c.z()[k]};
			for (double[] q : v) {
				if (dot(p, q) > Math.cos(500_000 / A)) {
					nearVertex += c.w()[k];
					break;
				}
			}
		}
		Integer[] order = new Integer[edges.size()];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		Arrays.sort(order, Comparator.comparingDouble(e -> people[e]));
		int[] parent = new int[12];
		for (int i = 0; i < 12; i++) {
			parent[i] = i;
		}
		double cut = 0;
		int used = 0;
		for (int e : order) {
			int a = find(parent, edges.get(e)[0]), b = find(parent, edges.get(e)[1]);
			if (a != b) {
				parent[a] = b;
				cut += people[e];
				used++;
			}
		}
		System.out.printf("%s%d edges; the least-peopled cut (%d edges) passes %.1f million people (%.2f%%); %.1f million (%.2f%%) live within 500 km of a vertex%n",
			name == null ? "" : name + ": ", edges.size(), used, cut / 1e6, 100 * cut / c.total(), nearVertex / 1e6, 100 * nearVertex / c.total());
	}

	/** The people within 25 km of each edge. */
	static double[] edgePeople(double[][] v, List<int[]> edges, Cells c) {
		double near = Math.sin(25_000 / A);
		double[] people = new double[edges.size()];
		for (int k = 0; k < c.n(); k++) {
			double[] p = {c.x()[k], c.y()[k], c.z()[k]};
			for (int e = 0; e < edges.size(); e++) {
				double[] a = v[edges.get(e)[0]], b = v[edges.get(e)[1]];
				double[] n = normalize(cross(a, b));
				if (Math.abs(dot(p, n)) < near && dot(p, cross(n, a)) >= -near && dot(p, cross(b, n)) >= -near && dot(p, a) > 0 && dot(p, b) > 0) {
					people[e] += c.w()[k];
				}
			}
		}
		return people;
	}

	static int find(int[] parent, int i) {
		while (parent[i] != i) {
			i = parent[i] = parent[parent[i]];
		}
		return i;
	}

	static double[][] icosahedron() {
		double g = (1 + Math.sqrt(5)) / 2;
		double[][] v = new double[12][];
		int k = 0;
		for (int s1 = -1; s1 <= 1; s1 += 2) {
			for (int s2 = -1; s2 <= 1; s2 += 2) {
				v[k++] = normalize(new double[] {0, s1, s2 * g});
				v[k++] = normalize(new double[] {s1, s2 * g, 0});
				v[k++] = normalize(new double[] {s2 * g, 0, s1});
			}
		}
		return v;
	}

	static double conformalLat(double latDegrees) {
		double phi = Math.toRadians(latDegrees), s = Math.sin(phi);
		return 2 * Math.atan(Math.tan(Math.PI / 4 + phi / 2) * Math.pow((1 - E * s) / (1 + E * s), E / 2)) - Math.PI / 2;
	}

	static double geodeticLat(double chi) {
		double phi = chi;
		for (int i = 0; i < 10; i++) {
			double s = Math.sin(phi);
			phi = 2 * Math.atan(Math.tan(Math.PI / 4 + chi / 2) * Math.pow((1 + E * s) / (1 - E * s), E / 2)) - Math.PI / 2;
		}
		return phi;
	}

	static double primeVertical(double phi) {
		double s = Math.sin(phi);
		return A / Math.sqrt(1 - E2 * s * s);
	}

	static double[] unit(double lat, double lon) {
		return new double[] {Math.cos(lat) * Math.cos(lon), Math.cos(lat) * Math.sin(lon), Math.sin(lat)};
	}

	static double[] randomQuat(Random r) {
		return normalize(new double[] {r.nextGaussian(), r.nextGaussian(), r.nextGaussian(), r.nextGaussian()});
	}

	static double[] smallTurn(Random r, double angle) {
		double[] axis = normalize(new double[] {r.nextGaussian(), r.nextGaussian(), r.nextGaussian()});
		double a = r.nextGaussian() * angle / 2;
		return new double[] {Math.cos(a), axis[0] * Math.sin(a), axis[1] * Math.sin(a), axis[2] * Math.sin(a)};
	}

	static double[] mul(double[] a, double[] b) {
		return normalize(new double[] {a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3], a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
			a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1], a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]});
	}

	static double[][] rotate(double[][] v, double[] q) {
		double w = q[0], x = q[1], y = q[2], z = q[3];
		double[][] m = {{1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)}, {2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)},
			{2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)}};
		double[][] out = new double[v.length][];
		for (int i = 0; i < v.length; i++) {
			out[i] = new double[] {dot(m[0], v[i]), dot(m[1], v[i]), dot(m[2], v[i])};
		}
		return out;
	}

	static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	static double[] normalize(double[] a) {
		double n = 0;
		for (double d : a) {
			n += d * d;
		}
		n = Math.sqrt(n);
		double[] out = new double[a.length];
		for (int i = 0; i < a.length; i++) {
			out[i] = a[i] / n;
		}
		return out;
	}
}
