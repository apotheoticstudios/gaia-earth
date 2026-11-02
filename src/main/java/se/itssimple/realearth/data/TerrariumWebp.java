package se.itssimple.realearth.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a lossless (VP8L) Terrarium WebP -- the format Mapterhorn serves its elevation tiles in --
 * straight into heights in metres:
 *
 *   height = (R * 256 + G + B / 256) - 32768
 *
 * A decoder of our own for the same reasons as {@link TerrariumPng}: a dedicated server never touches
 * AWT, and there is nothing extra to ship. Only the lossless bitstream is read (RFC 9649, section 3); a
 * lossy tile could not hold heights anyway.
 */
public final class TerrariumWebp {
	private static final int[] CODE_LENGTH_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

	/** Distance codes 1..120 as (x, y) offsets to a pixel nearby, RFC 9649 figure 20. */
	private static final int[] DISTANCES = {
		0, 1, 1, 0, 1, 1, -1, 1, 0, 2, 2, 0, 1, 2, -1, 2, 2, 1, -2, 1, 2, 2, -2, 2, 0, 3, 3, 0, 1, 3, -1, 3, 3, 1, -3, 1,
		2, 3, -2, 3, 3, 2, -3, 2, 0, 4, 4, 0, 1, 4, -1, 4, 4, 1, -4, 1, 3, 3, -3, 3, 2, 4, -2, 4, 4, 2, -4, 2, 0, 5, 3, 4,
		-3, 4, 4, 3, -4, 3, 5, 0, 1, 5, -1, 5, 5, 1, -5, 1, 2, 5, -2, 5, 5, 2, -5, 2, 4, 4, -4, 4, 3, 5, -3, 5, 5, 3, -5, 3,
		0, 6, 6, 0, 1, 6, -1, 6, 6, 1, -6, 1, 2, 6, -2, 6, 6, 2, -6, 2, 4, 5, -4, 5, 5, 4, -5, 4, 3, 6, -3, 6, 6, 3, -6, 3,
		0, 7, 7, 0, 1, 7, -1, 7, 5, 5, -5, 5, 7, 1, -7, 1, 4, 6, -4, 6, 6, 4, -6, 4, 2, 7, -2, 7, 7, 2, -7, 2, 3, 7, -3, 7,
		7, 3, -7, 3, 5, 6, -5, 6, 6, 5, -6, 5, 8, 0, 4, 7, -4, 7, 7, 4, -7, 4, 8, 1, 8, 2, 6, 6, -6, 6, 8, 3, 5, 7, -5, 7,
		7, 5, -7, 5, 8, 4, 6, 7, -6, 7, 7, 6, -7, 6, 8, 5, 7, 7, -7, 7, 8, 6, 8, 7
	};

	private TerrariumWebp() {
	}

	public static float[] decode(byte[] webp, int expectedSize) throws IOException {
		int[] argb = argb(webp, expectedSize);
		float[] heights = new float[argb.length];
		for (int i = 0; i < argb.length; i++) {
			int p = argb[i];
			heights[i] = (float) (((p >> 16) & 0xFF) * 256.0 + ((p >> 8) & 0xFF) + (p & 0xFF) / 256.0 - 32768.0);
		}
		return heights;
	}

	/** The image's pixels as ARGB, row-major. */
	static int[] argb(byte[] webp, int expectedSize) throws IOException {
		if (webp.length < 20 || !fourCc(webp, 0, "RIFF") || !fourCc(webp, 8, "WEBP")) {
			throw new IOException("not a WebP");
		}
		// The bitstream is the first chunk in a simple file, or a later one in an extended (VP8X) file.
		int at = 12;
		while (at + 8 <= webp.length) {
			int size = le32(webp, at + 4);
			if (size < 0 || at + 8 + size > webp.length) {
				throw new IOException("truncated WebP chunk");
			}
			if (fourCc(webp, at, "VP8L")) {
				return lossless(new Bits(webp, at + 8, at + 8 + size), expectedSize);
			}
			if (fourCc(webp, at, "VP8 ")) {
				throw new IOException("lossy WebP, not elevation");
			}
			at += 8 + size + (size & 1);
		}
		throw new IOException("WebP without image data");
	}

	private static int[] lossless(Bits in, int expectedSize) throws IOException {
		if (in.read(8) != 0x2F) {
			throw new IOException("not a lossless WebP");
		}
		int width = in.read(14) + 1;
		int height = in.read(14) + 1;
		in.read(1);
		if (in.read(3) != 0) {
			throw new IOException("unknown WebP lossless version");
		}
		if (width != expectedSize || height != expectedSize) {
			throw new IOException("WebP is " + width + "x" + height + ", expected " + expectedSize);
		}

		List<Transform> transforms = new ArrayList<>();
		int seen = 0;
		int xsize = width;
		while (in.read(1) == 1) {
			int type = in.read(2);
			if ((seen & (1 << type)) != 0) {
				throw new IOException("WebP transform repeated");
			}
			seen |= 1 << type;
			switch (type) {
				case 0, 1 -> {
					int bits = in.read(3) + 2;
					int[] data = image(in, divUp(xsize, 1 << bits), divUp(height, 1 << bits), false);
					transforms.add(new Transform(type, bits, data, xsize));
				}
				case 2 -> transforms.add(new Transform(2, 0, null, xsize));
				default -> {
					int size = in.read(8) + 1;
					int[] table = image(in, size, 1, false);
					for (int i = 1; i < size; i++) {
						table[i] = add(table[i], table[i - 1]);
					}
					int bits = size <= 2 ? 3 : size <= 4 ? 2 : size <= 16 ? 1 : 0;
					transforms.add(new Transform(3, bits, table, xsize));
					xsize = divUp(xsize, 1 << bits);
				}
			}
		}

		int[] pixels = image(in, xsize, height, true);
		for (int t = transforms.size() - 1; t >= 0; t--) {
			pixels = transforms.get(t).undo(pixels, height);
		}
		return pixels;
	}

	/** Entropy-coded image data; only the main image may use meta prefix codes. */
	private static int[] image(Bits in, int width, int height, boolean main) throws IOException {
		int cacheBits = 0;
		if (in.read(1) == 1) {
			cacheBits = in.read(4);
			if (cacheBits < 1 || cacheBits > 11) {
				throw new IOException("bad WebP colour cache size");
			}
		}
		int[] meta = null;
		int metaBits = 0;
		int metaWidth = 0;
		int groups = 1;
		if (main && in.read(1) == 1) {
			metaBits = in.read(3) + 2;
			metaWidth = divUp(width, 1 << metaBits);
			meta = image(in, metaWidth, divUp(height, 1 << metaBits), false);
			for (int i = 0; i < meta.length; i++) {
				meta[i] = (meta[i] >> 8) & 0xFFFF;
				groups = Math.max(groups, meta[i] + 1);
			}
		}
		int cacheSize = cacheBits > 0 ? 1 << cacheBits : 0;
		Code[][] codes = new Code[groups][];
		for (int g = 0; g < groups; g++) {
			codes[g] = new Code[] {code(in, 256 + 24 + cacheSize), code(in, 256), code(in, 256), code(in, 256), code(in, 40)};
		}

		int[] pixels = new int[width * height];
		int[] cache = cacheBits > 0 ? new int[cacheSize] : null;
		int shift = 32 - cacheBits;
		int pos = 0;
		while (pos < pixels.length) {
			Code[] g = codes[0];
			if (meta != null) {
				int y = pos / width;
				g = codes[meta[(y >> metaBits) * metaWidth + ((pos - y * width) >> metaBits)]];
			}
			int s = g[0].read(in);
			if (s < 256) {
				int red = g[1].read(in);
				int blue = g[2].read(in);
				int alpha = g[3].read(in);
				int p = (alpha << 24) | (red << 16) | (s << 8) | blue;
				pixels[pos++] = p;
				if (cache != null) {
					cache[(0x1E35A7BD * p) >>> shift] = p;
				}
			} else if (s < 256 + 24) {
				int length = prefixed(in, s - 256);
				int code = prefixed(in, g[4].read(in));
				int distance;
				if (code > 120) {
					distance = code - 120;
				} else {
					distance = Math.max(1, DISTANCES[2 * (code - 1)] + DISTANCES[2 * (code - 1) + 1] * width);
				}
				if (distance > pos || length > pixels.length - pos) {
					throw new IOException("WebP back reference out of the image");
				}
				for (int k = 0; k < length; k++) {
					int p = pixels[pos - distance];
					pixels[pos++] = p;
					if (cache != null) {
						cache[(0x1E35A7BD * p) >>> shift] = p;
					}
				}
			} else {
				if (cache == null || s - 280 >= cacheSize) {
					throw new IOException("bad WebP colour cache index");
				}
				pixels[pos++] = cache[s - 280];
			}
		}
		return pixels;
	}

	private static int prefixed(Bits in, int prefix) throws IOException {
		if (prefix < 4) {
			return prefix + 1;
		}
		int extra = (prefix - 2) >> 1;
		return ((2 + (prefix & 1)) << extra) + in.read(extra) + 1;
	}

	/** Reads one prefix code's lengths, simple or normal, and builds it. */
	private static Code code(Bits in, int alphabet) throws IOException {
		int[] lengths = new int[alphabet];
		if (in.read(1) == 1) {
			int symbols = in.read(1) + 1;
			int first = in.read(1 + 7 * in.read(1));
			if (first >= alphabet) {
				throw new IOException("bad WebP prefix code");
			}
			lengths[first] = 1;
			if (symbols == 2) {
				int second = in.read(8);
				if (second >= alphabet) {
					throw new IOException("bad WebP prefix code");
				}
				lengths[second] = 1;
			}
			return new Code(lengths);
		}

		int[] lengthLengths = new int[19];
		int count = 4 + in.read(4);
		for (int i = 0; i < count; i++) {
			lengthLengths[CODE_LENGTH_ORDER[i]] = in.read(3);
		}
		Code lengthCode = new Code(lengthLengths);
		int max = alphabet;
		if (in.read(1) == 1) {
			max = 2 + in.read(2 + 2 * in.read(3));
			if (max > alphabet) {
				throw new IOException("bad WebP prefix code");
			}
		}
		int previous = 8;
		int symbol = 0;
		while (symbol < alphabet && max-- > 0) {
			int len = lengthCode.read(in);
			if (len < 16) {
				lengths[symbol++] = len;
				if (len != 0) {
					previous = len;
				}
				continue;
			}
			int repeat = len == 16 ? 3 + in.read(2) : len == 17 ? 3 + in.read(3) : 11 + in.read(7);
			if (symbol + repeat > alphabet) {
				throw new IOException("bad WebP prefix code");
			}
			int value = len == 16 ? previous : 0;
			for (int r = 0; r < repeat; r++) {
				lengths[symbol++] = value;
			}
		}
		return new Code(lengths);
	}

	/**
	 * A canonical prefix code, read a bit at a time (codes are packed starting at their first bit, as in
	 * deflate). A code with one symbol takes no bits at all.
	 */
	private static final class Code {
		private final int[] counts = new int[16];
		private final int[] symbols;
		private final int single;

		Code(int[] lengths) {
			int used = 0;
			int last = 0;
			for (int s = 0; s < lengths.length; s++) {
				if (lengths[s] != 0) {
					counts[lengths[s]]++;
					used++;
					last = s;
				}
			}
			// An empty code stands for symbol 0 (RFC 9649 3.7.2.1.1).
			single = used <= 1 ? last : -1;
			symbols = new int[used];
			int[] offsets = new int[16];
			for (int len = 1; len < 15; len++) {
				offsets[len + 1] = offsets[len] + counts[len];
			}
			for (int s = 0; s < lengths.length; s++) {
				if (lengths[s] != 0) {
					symbols[offsets[lengths[s]]++] = s;
				}
			}
		}

		int read(Bits in) throws IOException {
			if (single >= 0) {
				return single;
			}
			int code = 0;
			int first = 0;
			int index = 0;
			for (int len = 1; len < 16; len++) {
				code |= in.read(1);
				int count = counts[len];
				if (code - first < count) {
					return symbols[index + code - first];
				}
				index += count;
				first = (first + count) << 1;
				code <<= 1;
			}
			throw new IOException("bad WebP prefix code");
		}
	}

	private record Transform(int type, int bits, int[] data, int width) {
		int[] undo(int[] pixels, int height) {
			switch (type) {
				case 0 -> {
					int blocks = divUp(width, 1 << bits);
					for (int y = 0; y < height; y++) {
						for (int x = 0; x < width; x++) {
							int i = y * width + x;
							int predicted;
							if (y == 0) {
								predicted = x == 0 ? 0xFF000000 : pixels[i - 1];
							} else if (x == 0) {
								predicted = pixels[i - width];
							} else {
								int mode = (data[(y >> bits) * blocks + (x >> bits)] >> 8) & 0xF;
								predicted = predict(mode, pixels, i, width);
							}
							pixels[i] = add(pixels[i], predicted);
						}
					}
					return pixels;
				}
				case 1 -> {
					int blocks = divUp(width, 1 << bits);
					for (int y = 0; y < height; y++) {
						for (int x = 0; x < width; x++) {
							int e = data[(y >> bits) * blocks + (x >> bits)];
							int i = y * width + x;
							int p = pixels[i];
							int green = (p >> 8) & 0xFF;
							int red = ((p >> 16) + delta(e, green)) & 0xFF;
							int blue = (p + delta(e >> 8, green) + delta(e >> 16, red)) & 0xFF;
							pixels[i] = (p & 0xFF00FF00) | (red << 16) | blue;
						}
					}
					return pixels;
				}
				case 2 -> {
					for (int i = 0; i < pixels.length; i++) {
						int p = pixels[i];
						int green = (p >> 8) & 0xFF;
						pixels[i] = (p & 0xFF00FF00) | ((((p >> 16) + green) & 0xFF) << 16) | ((p + green) & 0xFF);
					}
					return pixels;
				}
				default -> {
					int packedWidth = divUp(width, 1 << bits);
					int perPixel = 8 >> bits;
					int mask = (1 << perPixel) - 1;
					int[] out = new int[width * height];
					for (int y = 0; y < height; y++) {
						for (int x = 0; x < width; x++) {
							int packed = pixels[y * packedWidth + (x >> bits)] >> 8;
							int index = (packed >> ((x & ((1 << bits) - 1)) * perPixel)) & mask;
							out[y * width + x] = index < data.length ? data[index] : 0;
						}
					}
					return out;
				}
			}
		}
	}

	private static int predict(int mode, int[] p, int i, int width) {
		int left = p[i - 1];
		int top = p[i - width];
		int topLeft = p[i - width - 1];
		// The rightmost pixel's top-right neighbour is the first pixel of its own row, which this index is.
		int topRight = p[i - width + 1];
		return switch (mode) {
			case 1 -> left;
			case 2 -> top;
			case 3 -> topRight;
			case 4 -> topLeft;
			case 5 -> average(average(left, topRight), top);
			case 6 -> average(left, topLeft);
			case 7 -> average(left, top);
			case 8 -> average(topLeft, top);
			case 9 -> average(top, topRight);
			case 10 -> average(average(left, topLeft), average(top, topRight));
			case 11 -> select(left, top, topLeft);
			case 12 -> perChannel(left, top, topLeft, false);
			case 13 -> perChannel(average(left, top), topLeft, 0, true);
			default -> 0xFF000000;
		};
	}

	/** Per channel, each mod 256. */
	private static int add(int a, int b) {
		return (((a & 0xFF00FF00) + (b & 0xFF00FF00)) & 0xFF00FF00) | (((a & 0x00FF00FF) + (b & 0x00FF00FF)) & 0x00FF00FF);
	}

	/** Per channel (a + b) / 2, rounded down. */
	private static int average(int a, int b) {
		return (((a ^ b) & 0xFEFEFEFE) >>> 1) + (a & b);
	}

	private static int select(int left, int top, int topLeft) {
		int toLeft = 0;
		int toTop = 0;
		for (int s = 0; s < 32; s += 8) {
			int l = (left >> s) & 0xFF;
			int t = (top >> s) & 0xFF;
			int estimate = l + t - ((topLeft >> s) & 0xFF);
			toLeft += Math.abs(estimate - l);
			toTop += Math.abs(estimate - t);
		}
		return toLeft < toTop ? left : top;
	}

	/** ClampAddSubtractFull(a, b, c), or with half ClampAddSubtractHalf(a, b). */
	private static int perChannel(int a, int b, int c, boolean half) {
		int out = 0;
		for (int s = 0; s < 32; s += 8) {
			int x = (a >> s) & 0xFF;
			int y = (b >> s) & 0xFF;
			int v = half ? x + (x - y) / 2 : x + y - ((c >> s) & 0xFF);
			out |= Math.max(0, Math.min(255, v)) << s;
		}
		return out;
	}

	/** ColorTransformDelta: the element's low byte and the channel as signed 8-bit numbers. */
	private static int delta(int element, int channel) {
		return ((byte) element * (byte) channel) >> 5;
	}

	private static int divUp(int n, int d) {
		return (n + d - 1) / d;
	}

	private static boolean fourCc(byte[] b, int at, String s) {
		return b[at] == s.charAt(0) && b[at + 1] == s.charAt(1) && b[at + 2] == s.charAt(2) && b[at + 3] == s.charAt(3);
	}

	private static int le32(byte[] b, int at) {
		return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
	}

	/** Bits in the order VP8L packs them: least significant first. */
	private static final class Bits {
		private final byte[] data;
		private final int end;
		private int pos;
		private long buffer;
		private int count;

		Bits(byte[] data, int start, int end) {
			this.data = data;
			this.pos = start;
			this.end = end;
		}

		int read(int n) throws IOException {
			while (count < n) {
				if (pos >= end) {
					throw new IOException("truncated WebP");
				}
				buffer |= (long) (data[pos++] & 0xFF) << count;
				count += 8;
			}
			int value = (int) (buffer & ((1L << n) - 1));
			buffer >>>= n;
			count -= n;
			return value;
		}
	}
}
