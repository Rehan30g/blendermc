package dev.echoliminal.liminal.light;

import dev.echoliminal.liminal.LiminalMesh;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

/** Average brightness of each material (base colour times the texture's mean), used as the reflectivity of bounce light. */
final class Albedo {
	private Albedo() {
	}

	static float[] of(LiminalMesh mesh) {
		List<LiminalMesh.Material> materials = mesh.materials();
		float[] out = new float[Math.max(1, materials.size())];
		java.util.Arrays.fill(out, 0.5F);
		for (int i = 0; i < materials.size(); i++) {
			LiminalMesh.Material m = materials.get(i);
			float[] c = m.baseColor();
			float base = c != null && c.length >= 3 ? 0.2126F * c[0] + 0.7152F * c[1] + 0.0722F * c[2] : 1.0F;
			float tex = 1.0F;
			if (m.texturePng() != null) {
				try {
					BufferedImage image = javax.imageio.ImageIO.read(new ByteArrayInputStream(m.texturePng()));
					if (image != null) {
						tex = mean(image);
					}
				} catch (java.io.IOException | RuntimeException e) {
					tex = 0.6F;
				}
			}
			out[i] = Math.max(0.05F, Math.min(0.9F, base * tex));
		}
		return out;
	}

	private static float mean(BufferedImage image) {
		int w = image.getWidth(), h = image.getHeight();
		int stepX = Math.max(1, w / 32), stepY = Math.max(1, h / 32);
		double sum = 0.0;
		int n = 0;
		for (int y = 0; y < h; y += stepY) {
			for (int x = 0; x < w; x += stepX) {
				int argb = image.getRGB(x, y);
				if ((argb >>> 24) < 128) {
					continue;
				}
				sum += (0.2126 * (argb >> 16 & 255) + 0.7152 * (argb >> 8 & 255) + 0.0722 * (argb & 255)) / 255.0;
				n++;
			}
		}
		return n == 0 ? 0.6F : (float) (sum / n);
	}
}
