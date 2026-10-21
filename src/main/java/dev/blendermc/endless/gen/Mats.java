package dev.blendermc.endless.gen;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The fixed material palette of the endless world. A material id is the index into {@link #ALL}. Textures are generated
 * from code: 128 px RGBA, seamlessly tileable, meant to be sampled LINEAR with mipmaps and repeated every
 * {@link Mat#scale()} blocks (real-world scale: one repeat per 2 blocks for walls, 4 for grass).
 */
public final class Mats {
	/**
	 * One palette entry. {@code color} is ARGB (a tint on top of the texture); {@code fill}: the volume behind its surface is
	 * solid; {@code scale}: blocks per texture repeat.
	 */
	public record Mat(int id, String name, int color, boolean emissive, boolean collides, boolean translucent, boolean fill, double scale, byte[] png) {
		/** Thin surface: no volume behind it (emissive, "_nocollide"). */
		public boolean thin() {
			return this.emissive || !this.collides;
		}

		/** Base colour factor 0..1 RGBA. */
		public float[] baseColor() {
			return new float[] {(this.color >> 16 & 255) / 255F, (this.color >> 8 & 255) / 255F, (this.color & 255) / 255F, (this.color >>> 24) / 255F};
		}
	}

	public static final int WALLPAPER = 0, CARPET = 1, CEILING_TILE = 2, LIGHT_PANEL = 3, DEAD_PANEL = 4, GRASS_A = 5, GRASS_B = 6, DIRT = 7,
		POLE_WOOD = 8, TILE = 9, TILE_BLUE = 10, WATER = 11, WIRE = 12, CONCRETE = 13, FRAME = 14, POOL_CEILING = 15, POOL_PANEL = 16,
		SKIRTING = 17, METAL = 18, STAIR = 19, PLANK = 20, GRASS_C = 21, STONE_DARK = 22, STRIP = 23;

	public static final List<Mat> ALL;

	/** Texture edge in pixels. */
	static final int S = 128;

	static {
		ALL = List.of(
			mat(WALLPAPER, "wallpaper", 0xFFFFFFFF, false, true, false, false, 2.5, wallpaper()),
			mat(CARPET, "carpet", 0xFFFFFFFF, false, true, false, false, 2.0, carpet()),
			mat(CEILING_TILE, "ceiling_tile", 0xFFFFFFFF, false, true, false, false, 2.0, ceilingTiles(0xE6E0C8, 0xA8A28A)),
			mat(LIGHT_PANEL, "light_panel", 0xFFFFFFFF, true, true, false, false, 2.0, panel(0xFFFBE4, 0xC9C4A8)),
			mat(DEAD_PANEL, "dead_panel", 0xFFFFFFFF, false, true, false, false, 2.0, panel(0x8C8A7E, 0x66645A)),
			mat(GRASS_A, "grass_a", 0xFFFFFFFF, false, true, false, true, 4.0, grass(0x6FA33C, 0x4F8228, 0x93C45A, 11)),
			mat(GRASS_B, "grass_b", 0xFFFFFFFF, false, true, false, true, 4.0, grass(0x62952F, 0x44741F, 0x86B84C, 12)),
			mat(DIRT, "dirt", 0xFFFFFFFF, false, true, false, true, 3.0, dirt()),
			mat(POLE_WOOD, "pole_wood", 0xFFFFFFFF, false, true, false, false, 2.0, wood(0x6E5439, 0x4A3823, false)),
			mat(TILE, "tile", 0xFFFFFFFF, false, true, false, true, 2.0, tile(0xEEF4F2, 0xA9B5B4, 70)),
			mat(TILE_BLUE, "tile_blue", 0xFFFFFFFF, false, true, false, true, 2.0, tile(0x8CCADC, 0x5F8F9E, 71)),
			mat(WATER, "water_nocollide", 0xFFFFFFFF, false, false, true, false, 3.0, water()),
			mat(WIRE, "wire_nocollide", 0xFF1E1E22, false, false, false, false, 2.0, flat(0xFFFFFF)),
			mat(CONCRETE, "concrete", 0xFFFFFFFF, false, true, false, true, 3.0, concrete(0xA9A7A0)),
			mat(FRAME, "frame", 0xFFFFFFFF, false, true, false, false, 2.0, painted(0xF1ECE0)),
			mat(POOL_CEILING, "pool_ceiling", 0xFFFFFFFF, false, true, false, false, 2.0, ceilingTiles(0xF1F6F6, 0xB7C6C8)),
			mat(POOL_PANEL, "pool_light_panel", 0xFFFFFFFF, true, true, false, false, 2.0, panel(0xEEF9FF, 0xBFD4DC)),
			mat(SKIRTING, "skirting", 0xFFFFFFFF, false, true, false, false, 2.0, painted(0xF4EFE2)),
			mat(METAL, "metal", 0xFFFFFFFF, false, true, false, false, 2.0, metal()),
			mat(STAIR, "stair", 0xFFFFFFFF, false, true, false, true, 2.0, concrete(0xC4BFB0)),
			mat(PLANK, "plank", 0xFFFFFFFF, false, true, false, false, 2.0, wood(0x9A7648, 0x6A4D2C, true)),
			mat(GRASS_C, "grass_c", 0xFFFFFFFF, false, true, false, true, 4.0, grass(0x8FA245, 0x6B7C2E, 0xB3BF6A, 13)),
			mat(STONE_DARK, "stone_dark", 0xFFFFFFFF, false, true, false, true, 3.0, darkStone()),
			mat(STRIP, "light_strip", 0xFFFFFFFF, true, true, false, false, 2.0, panel(0xFFF6D8, 0xD8C9A0)));
	}

	private Mats() {
	}

	public static Mat get(int id) {
		return ALL.get(id);
	}

	private static Mat mat(int id, String name, int color, boolean emissive, boolean collides, boolean translucent, boolean fill, double scale, byte[] png) {
		return new Mat(id, name, color, emissive, collides, translucent, fill, scale, png);
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Tileable noise toolkit

	private static int hash(int x, int y, int salt) {
		int h = x * 374761393 + y * 668265263 + salt * 1274126177;
		h = (h ^ (h >>> 13)) * 1274126177;
		return h ^ (h >>> 16);
	}

	/** 0..1 pseudo random per lattice point. */
	private static double rnd(int x, int y, int salt) {
		return (hash(x, y, salt) & 0xFFFFFF) / (double) 0x1000000;
	}

	/** Tileable value noise: {@code px, py} in pixels, {@code cx, cy} lattice cells across the texture. 0..1. */
	private static double vn(double px, double py, int cx, int cy, int salt) {
		double u = px / S * cx, v = py / S * cy;
		int ix = (int) Math.floor(u), iy = (int) Math.floor(v);
		double fx = u - ix, fy = v - iy;
		fx = fx * fx * (3 - 2 * fx);
		fy = fy * fy * (3 - 2 * fy);
		int x0 = Math.floorMod(ix, cx), x1 = Math.floorMod(ix + 1, cx), y0 = Math.floorMod(iy, cy), y1 = Math.floorMod(iy + 1, cy);
		double a = rnd(x0, y0, salt), b = rnd(x1, y0, salt), c = rnd(x0, y1, salt), d = rnd(x1, y1, salt);
		return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
	}

	/** Tileable fractal noise, 0..1, {@code base} cells across at the first octave. */
	private static double fbm(double px, double py, int base, int salt) {
		return vn(px, py, base, base, salt) * 0.5 + vn(px, py, base * 2, base * 2, salt + 1) * 0.28 + vn(px, py, base * 4, base * 4, salt + 2) * 0.15
			+ vn(px, py, base * 8, base * 8, salt + 3) * 0.07;
	}

	private static int rgb(double r, double g, double b) {
		return 0xFF000000 | clamp((int) Math.round(r)) << 16 | clamp((int) Math.round(g)) << 8 | clamp((int) Math.round(b));
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(255, v));
	}

	private static double r(int c) {
		return c >> 16 & 255;
	}

	private static double g(int c) {
		return c >> 8 & 255;
	}

	private static double b(int c) {
		return c & 255;
	}

	private static double mix(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static int lerpColor(int a, int b, double t) {
		return rgb(mix(r(a), r(b), t), mix(g(a), g(b), t), mix(b(a), b(b), t));
	}

	/** Scales a colour (brightness) and adds fine per pixel grain. */
	private static int shade(int c, double f) {
		return rgb(r(c) * f, g(c) * f, b(c) * f);
	}

	private static double grain(int x, int y, int salt) {
		return rnd(x, y, salt) - 0.5;
	}

	private static byte[] flat(int base) {
		int[] px = new int[S * S];
		for (int i = 0; i < px.length; i++) {
			px[i] = rgb(r(base), g(base), b(base));
		}
		return png(px);
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Textures

	/** Pale faded yellow wallpaper: soft wide stripes, a tone-on-tone damask motif, a few damp stains, fine paper grain. */
	private static byte[] wallpaper() {
		int base = 0xE9DFA6, motifColor = 0xD9CC86;
		int[] px = new int[S * S];
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				double stripe = 0.5 + 0.5 * Math.cos(x * 2 * Math.PI / 64.0);
				int cx = x % 64 - 32, cy = y % 64 - 32;
				// Diamond lattice with a small inner diamond: reads as a printed motif, not as grain.
				double d = Math.abs(cx) + Math.abs(cy);
				double motif = d < 14 ? (d < 6 ? 0.55 : d > 11 ? 0.35 : 0.0) : 0.0;
				double d2 = Math.abs(Math.abs(cx) - 32) + Math.abs(Math.abs(cy) - 32);
				motif = Math.max(motif, d2 < 9 ? (d2 < 4 ? 0.5 : 0.3) : 0.0);
				double faded = 0.4 + 0.6 * fbm(x, y, 2, 301);
				int col = lerpColor(base, motifColor, Math.min(1, motif * faded + stripe * 0.12));
				double stain = fbm(x, y, 3, 305);
				double blotch = stain > 0.66 ? (stain - 0.66) * 1.6 : 0;
				col = lerpColor(col, 0xB79F5C, Math.min(0.35, blotch));
				px[y * S + x] = shade(col, 1 + grain(x, y, 311) * 0.035);
			}
		}
		return png(px);
	}

	/** Worn office carpet: fibre noise, directional streaks, matted patches, a faint tile grid. */
	private static byte[] carpet() {
		int[] px = new int[S * S];
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				double fibre = rnd(x, y, 401) - 0.5;
				double streak = vn(x, y, 64, 12, 403) - 0.5;
				double wear = fbm(x, y, 4, 405) - 0.5;
				double f = 1 + fibre * 0.22 + streak * 0.14 + wear * 0.36;
				if (x % 64 == 0 || y % 64 == 0) {
					f *= 0.93;
				}
				double fleck = rnd(x / 2, y / 2, 409) > 0.93 ? 0.88 : 1.0;
				px[y * S + x] = shade(rgb(176, 150, 84), f * fleck);
			}
		}
		return png(px);
	}

	/** Acoustic tiles: 2 x 2 tiles per texture with grooves, pinholes, stains. */
	private static byte[] ceilingTiles(int base, int groove) {
		int[] px = new int[S * S];
		int t = S / 2;
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				int tx = x / t, ty = y / t;
				double tint = 0.97 + rnd(tx, ty, 501) * 0.06;
				int lx = x % t, ly = y % t;
				int edge = Math.min(Math.min(lx, t - 1 - lx), Math.min(ly, t - 1 - ly));
				int c;
				if (edge < 2) {
					c = shade(groove, 0.92 + grain(x, y, 502) * 0.1);
				} else {
					double pits = rnd(x / 2, y / 2, 503) > 0.9 ? 0.82 : 1.0;
					double fissure = vn(x, y, 20, 4, 507) > 0.8 ? 0.93 : 1.0;
					double stain = fbm(x, y, 3, 509);
					double sm = stain > 0.64 ? 1 - (stain - 0.64) * 1.1 : 1.0;
					c = shade(base, tint * pits * fissure * sm * (1 + grain(x, y, 504) * 0.05) * (edge < 4 ? 0.96 : 1.0));
				}
				px[y * S + x] = c;
			}
		}
		return png(px);
	}

	private static byte[] panel(int base, int frame) {
		int[] px = new int[S * S];
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				int edge = Math.min(Math.min(x, S - 1 - x), Math.min(y, S - 1 - y));
				double lines = (y % 16 == 0) ? 0.96 : 1.0;
				int c = edge < 5 ? shade(frame, 0.95 + grain(x, y, 601) * 0.06) : shade(base, lines * (1 - 0.06 * Math.min(1, (24 - Math.min(edge, 24)) / 24.0)) + grain(x, y, 602) * 0.02);
				px[y * S + x] = c;
			}
		}
		return png(px);
	}

	/** Draws a short blade with wrap-around; colour blended over what is there. */
	private static void blade(int[] px, double x, double y, double dx, double dy, int len, int color) {
		for (int i = 0; i < len; i++) {
			double t = i / (double) len;
			int ix = Math.floorMod((int) Math.round(x + dx * i), S), iy = Math.floorMod((int) Math.round(y + dy * i), S);
			px[iy * S + ix] = lerpColor(px[iy * S + ix], color, 0.85 - 0.5 * t);
		}
	}

	private static byte[] grass(int base, int shadow, int light, int salt) {
		int[] px = new int[S * S];
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				double patch = fbm(x, y, 4, 701 + salt);
				int c = lerpColor(shadow, base, 0.35 + 0.65 * patch);
				px[y * S + x] = shade(c, 1 + grain(x, y, 703) * 0.1);
			}
		}
		java.util.SplittableRandom rng = new java.util.SplittableRandom(salt * 7919L);
		for (int i = 0; i < 2600; i++) {
			double x = rng.nextDouble() * S, y = rng.nextDouble() * S;
			double ang = (rng.nextDouble() - 0.5) * 1.1;
			int len = 5 + rng.nextInt(9);
			int col = rng.nextInt(4) == 0 ? light : rng.nextInt(3) == 0 ? shadow : base;
			col = shade(col, 0.85 + rng.nextDouble() * 0.35);
			blade(px, x, y, Math.sin(ang) * 0.9, -Math.cos(ang), len, col);
		}
		for (int i = 0; i < 90; i++) {
			int x = rng.nextInt(S), y = rng.nextInt(S);
			px[y * S + x] = rgb(70, 54, 32);
		}
		return png(px);
	}

	private static byte[] dirt() {
		int[] px = new int[S * S];
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				double clump = fbm(x, y, 5, 801);
				double c = 0.7 + clump * 0.55;
				double pebble = vn(x, y, 40, 40, 803) > 0.86 ? 1.25 : 1.0;
				px[y * S + x] = shade(rgb(112, 86, 58), c * pebble * (1 + grain(x, y, 804) * 0.12));
			}
		}
		return png(px);
	}

	/** Wood grain along y (poles) or planks (floors, furniture). */
	private static byte[] wood(int base, int dark, boolean planks) {
		int[] px = new int[S * S];
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				double warp = fbm(x, y, 2, 901) * 5.0;
				double ring = 0.5 + 0.5 * Math.sin((x + warp * 3) * 2 * Math.PI / 8.0 + fbm(x, y, 3, 905) * 6.0);
				double fine = vn(x, y, 96, 3, 907);
				double c = mix(0.78, 1.08, ring * 0.45 + fine * 0.55);
				int col = lerpColor(dark, base, Math.min(1, c * 0.9));
				if (planks) {
					int pl = x / 32;
					double pt = 0.92 + rnd(pl, 0, 911) * 0.16;
					col = shade(col, pt);
					if (x % 32 == 0 || (y + pl * 37) % 128 == 0) {
						col = shade(col, 0.55);
					}
				}
				px[y * S + x] = shade(col, 1 + grain(x, y, 913) * 0.05);
			}
		}
		return png(px);
	}

	/** Glazed tile: 2 x 2 tiles, grout lines with grime, per tile tint, soft sheen. */
	private static byte[] tile(int base, int grout, int salt) {
		int[] px = new int[S * S];
		int t = S / 2;
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				int tx = x / t, ty = y / t, lx = x % t, ly = y % t;
				int edge = Math.min(Math.min(lx, t - 1 - lx), Math.min(ly, t - 1 - ly));
				double grime = fbm(x, y, 4, 1001 + salt);
				double dirt = 0.75 + 0.5 * grime;
				int c;
				if (edge < 2) {
					c = shade(grout, 0.78 + 0.25 * grime);
				} else {
					double tint = 0.965 + rnd(tx, ty, 1003 + salt) * 0.07;
					double sheen = 1.0 + 0.05 * Math.sin((lx + ly) * 2 * Math.PI / (t * 1.0));
					double edgeGrime = edge < 7 ? 1 - (7 - edge) * 0.012 * (0.4 + grime) : 1.0;
					double speck = rnd(x, y, 1005) > 0.985 ? 0.9 : 1.0;
					double blotch = grime > 0.66 ? 1 - (grime - 0.66) * 0.7 : 1.0;
					c = shade(base, tint * sheen * edgeGrime * speck * blotch * (1 + grain(x, y, 1007) * 0.03));
				}
				px[y * S + x] = edge < 2 ? shade(c, dirt) : c;
			}
		}
		return png(px);
	}

	/** Water: tileable ripples in brightness and alpha. */
	private static byte[] water() {
		int[] px = new int[S * S];
		int[][] waves = {{2, 1, 0}, {-3, 2, 1}, {1, -4, 2}, {5, 3, 3}, {-4, -5, 4}};
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				double h = 0;
				for (int[] w : waves) {
					h += Math.sin(2 * Math.PI * (w[0] * x + w[1] * y) / S + w[2] * 1.7) / (1 + Math.abs(w[0]) + Math.abs(w[1])) * 2.2;
				}
				double caustic = Math.pow(Math.max(0, h), 2) * 0.5;
				double f = 0.92 + h * 0.07 + caustic * 0.12 + grain(x, y, 1101) * 0.02;
				int c = shade(rgb(74, 160, 204), f);
				int a = clamp((int) (150 + h * 12 + caustic * 20));
				px[y * S + x] = a << 24 | c & 0xFFFFFF;
			}
		}
		return png(px);
	}

	private static byte[] concrete(int base) {
		int[] px = new int[S * S];
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				double cloud = fbm(x, y, 4, 1201);
				double pores = rnd(x, y, 1203) > 0.975 ? 0.78 : 1.0;
				double seam = (y % 64 == 0) ? 0.88 : 1.0;
				double stain = fbm(x * 0.5, y * 1.0, 2, 1205);
				double sm = stain > 0.6 ? 1 - (stain - 0.6) * 0.8 : 1;
				px[y * S + x] = shade(base, (0.86 + 0.26 * cloud) * pores * seam * sm * (1 + grain(x, y, 1207) * 0.08));
			}
		}
		return png(px);
	}

	private static byte[] painted(int base) {
		int[] px = new int[S * S];
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				double f = 0.96 + 0.06 * fbm(x, y, 3, 1301) + grain(x, y, 1303) * 0.03;
				px[y * S + x] = shade(base, f);
			}
		}
		return png(px);
	}

	private static byte[] metal() {
		int[] px = new int[S * S];
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				double brushed = vn(x, y, 6, 128, 1401);
				double f = 0.8 + 0.3 * brushed + 0.1 * fbm(x, y, 3, 1403);
				px[y * S + x] = shade(rgb(96, 100, 110), f);
			}
		}
		return png(px);
	}

	private static byte[] darkStone() {
		int[] px = new int[S * S];
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				double vein = Math.abs(fbm(x, y, 3, 1501) - 0.5);
				double v = vein < 0.02 ? 1.8 : 1.0;
				double f = 0.7 + 0.5 * fbm(x, y, 5, 1503);
				px[y * S + x] = shade(rgb(40, 42, 48), f * v * (1 + grain(x, y, 1505) * 0.1));
			}
		}
		return png(px);
	}

	// ----------------------------------------------------------------------------------------------------------------
	// PNG

	/** Encodes {@code S x S} ARGB pixels as an 8-bit RGBA PNG. */
	static byte[] png(int[] argb) {
		byte[] raw = new byte[S * (S * 4 + 1)];
		int o = 0;
		for (int y = 0; y < S; y++) {
			raw[o++] = 0;
			for (int x = 0; x < S; x++) {
				int p = argb[y * S + x];
				raw[o++] = (byte) (p >> 16);
				raw[o++] = (byte) (p >> 8);
				raw[o++] = (byte) p;
				raw[o++] = (byte) (p >>> 24);
			}
		}
		Deflater deflater = new Deflater(6);
		deflater.setInput(raw);
		deflater.finish();
		ByteArrayOutputStream z = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		while (!deflater.finished()) {
			z.write(buf, 0, deflater.deflate(buf));
		}
		deflater.end();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
		byte[] ihdr = new byte[13];
		put32(ihdr, 0, S);
		put32(ihdr, 4, S);
		ihdr[8] = 8;
		ihdr[9] = 6;
		chunk(out, "IHDR", ihdr);
		chunk(out, "IDAT", z.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void put32(byte[] b, int at, int v) {
		b[at] = (byte) (v >>> 24);
		b[at + 1] = (byte) (v >>> 16);
		b[at + 2] = (byte) (v >>> 8);
		b[at + 3] = (byte) v;
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
		byte[] len = new byte[4];
		put32(len, 0, data.length);
		out.writeBytes(len);
		byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		out.writeBytes(t);
		out.writeBytes(data);
		CRC32 crc = new CRC32();
		crc.update(t);
		crc.update(data);
		byte[] c = new byte[4];
		put32(c, 0, (int) crc.getValue());
		out.writeBytes(c);
	}
}
