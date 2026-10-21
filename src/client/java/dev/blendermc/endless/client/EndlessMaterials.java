package dev.blendermc.endless.client;

import dev.blendermc.liminal.LiminalMesh;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Per palette material: draw pass and flat colour (for the far levels of detail, which do not use the texture). Immutable, thread safe. */
final class EndlessMaterials {
	static final int PASS_OPAQUE = 0, PASS_DECAL = 1, PASS_TRANSLUCENT = 2;

	final List<LiminalMesh.Material> list;
	final int[] pass;
	final boolean[] emissive;
	/** RGBA 0..1: base colour times the average texture colour. */
	final float[][] flat;

	EndlessMaterials(List<LiminalMesh.Material> list) {
		this.list = list;
		int n = Math.max(1, list.size());
		this.pass = new int[n];
		this.emissive = new boolean[n];
		this.flat = new float[n][];
		for (int m = 0; m < n; m++) {
			LiminalMesh.Material mat = m < list.size() ? list.get(m) : null;
			float[] base = mat != null && mat.baseColor() != null && mat.baseColor().length >= 4 ? mat.baseColor() : new float[] {1, 1, 1, 1};
			float[] avg = mat == null ? new float[] {1, 1, 1, 1} : average(mat.texturePng());
			this.flat[m] = new float[] {base[0] * avg[0], base[1] * avg[1], base[2] * avg[2], base[3] * avg[3]};
			this.emissive[m] = mat != null && mat.emissive();
			String name = mat == null ? "" : mat.name().toLowerCase(Locale.ROOT);
			boolean translucent = this.flat[m][3] < 0.97F || name.contains("water") || name.contains("glass");
			this.pass[m] = translucent ? PASS_TRANSLUCENT : mat != null && !mat.collides() && !mat.emissive() ? PASS_DECAL : PASS_OPAQUE;
		}
	}

	int count() {
		return this.pass.length;
	}

	private static float[] average(byte[] png) {
		if (png == null || png.length == 0) {
			return new float[] {1, 1, 1, 1};
		}
		try {
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
			if (img == null) {
				return new float[] {1, 1, 1, 1};
			}
			double r = 0, g = 0, b = 0, a = 0;
			int w = img.getWidth(), h = img.getHeight();
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int p = img.getRGB(x, y);
					double pa = (p >>> 24) / 255.0;
					r += (p >> 16 & 0xFF) / 255.0 * pa;
					g += (p >> 8 & 0xFF) / 255.0 * pa;
					b += (p & 0xFF) / 255.0 * pa;
					a += pa;
				}
			}
			if (a <= 0) {
				return new float[] {1, 1, 1, 0};
			}
			return new float[] {(float) (r / a), (float) (g / a), (float) (b / a), (float) (a / (w * h))};
		} catch (Exception e) {
			return new float[] {1, 1, 1, 1};
		}
	}
}
