package dev.echoliminal.liminal.client;

import dev.echoliminal.EchoLiminal;
import dev.echoliminal.liminal.LiminalMesh;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/** Helpers for levels with {@code unlit} set: photographed textures drawn as they are, with a grey and a light level taken from the room's average brightness. */
final class LiminalUnlit {
	private static final Map<LiminalMesh, Float> CACHE = new ConcurrentHashMap<>();

	private LiminalUnlit() {
	}

	/** Average luminance (0..1, as displayed) of the mesh's non-emissive textures, weighted by triangle count; 0.5 when there are none. */
	static float luminance(LiminalMesh mesh) {
		return CACHE.computeIfAbsent(mesh, LiminalUnlit::compute);
	}

	/** Constant block light (4..13) matching the room's brightness. */
	static float blockLevel(LiminalMesh mesh) {
		return 4.0F + 9.0F * luminance(mesh);
	}

	private static float compute(LiminalMesh mesh) {
		int[] triMat = mesh.triangleMaterial();
		var materials = mesh.materials();
		long[] tris = new long[Math.max(1, materials.size())];
		for (int m : triMat) {
			if (m >= 0 && m < tris.length) {
				tris[m]++;
			}
		}
		double sum = 0.0, weight = 0.0;
		for (int m = 0; m < materials.size(); m++) {
			LiminalMesh.Material mat = materials.get(m);
			if (mat.emissive() || mat.texturePng() == null || tris[m] == 0) {
				continue;
			}
			try {
				BufferedImage img = ImageIO.read(new ByteArrayInputStream(mat.texturePng()));
				if (img == null) {
					continue;
				}
				int step = Math.max(1, Math.min(img.getWidth(), img.getHeight()) / 256);
				double total = 0.0;
				long n = 0;
				for (int y = 0; y < img.getHeight(); y += step) {
					for (int x = 0; x < img.getWidth(); x += step) {
						int argb = img.getRGB(x, y);
						if ((argb >>> 24) < 128) {
							continue;
						}
						total += (0.2126 * (argb >> 16 & 255) + 0.7152 * (argb >> 8 & 255) + 0.0722 * (argb & 255)) / 255.0;
						n++;
					}
				}
				if (n > 0) {
					sum += total / n * tris[m];
					weight += tris[m];
				}
			} catch (java.io.IOException | RuntimeException e) {
				EchoLiminal.LOGGER.warn("Could not read texture of material {} for the unlit average", mat.name(), e);
			}
		}
		float lum = weight > 0 ? (float) (sum / weight) : 0.5F;
		EchoLiminal.LOGGER.info("Unlit level {}: average texture luminance {}", mesh.name(), lum);
		return lum;
	}
}
