package dev.blendermc.endless.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import dev.blendermc.BlenderMc;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Material textures of the endless world: 128 px procedural images uploaded with a full mip chain (the shared
 * {@code LiminalTextures} are single level and nearest), to be sampled with the linear mipmapped repeat sampler. Cached by
 * content hash for the session. Render thread only.
 */
final class EndlessTextures {
	private static final Map<String, GpuTextureView> BY_HASH = new HashMap<>();
	private static final java.util.List<GpuTexture> OWNED = new java.util.ArrayList<>();

	private EndlessTextures() {
	}

	/** The view for these PNG bytes, or null if they are unreadable. */
	static GpuTextureView get(byte[] png) {
		if (png == null || png.length == 0) {
			return null;
		}
		String hash = sha1(png);
		if (BY_HASH.containsKey(hash)) {
			return BY_HASH.get(hash);
		}
		GpuTextureView view = null;
		try {
			NativeImage base = NativeImage.read(png);
			int w = base.getWidth(), h = base.getHeight();
			int levels = 1;
			while ((w >> levels) >= 1 && (h >> levels) >= 1 && levels < 8) {
				levels++;
			}
			var device = RenderSystem.getDevice();
			GpuTexture texture = device.createTexture("blendermc endless " + hash.substring(0, 8), 5, TextureFormat.RGBA8, w, h, 1, levels);
			var encoder = device.createCommandEncoder();
			NativeImage level = base;
			for (int l = 0; l < levels; l++) {
				encoder.writeToTexture(texture, level, l, 0, 0, 0, level.getWidth(), level.getHeight(), 0, 0);
				NativeImage next = l + 1 < levels ? halve(level) : null;
				level.close();
				level = next;
			}
			view = device.createTextureView(texture);
			OWNED.add(texture);
		} catch (Exception e) {
			BlenderMc.LOGGER.warn("Unreadable endless texture ({} bytes): {}", png.length, e.toString());
		}
		BY_HASH.put(hash, view);
		return view;
	}

	/** Box filter down by two, channel by channel (alpha weighted by itself would be better for water; good enough). */
	private static NativeImage halve(NativeImage src) {
		int w = Math.max(1, src.getWidth() / 2), h = Math.max(1, src.getHeight() / 2);
		NativeImage out = new NativeImage(w, h, false);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int[] p = {src.getPixel(x * 2, y * 2), src.getPixel(Math.min(src.getWidth() - 1, x * 2 + 1), y * 2),
					src.getPixel(x * 2, Math.min(src.getHeight() - 1, y * 2 + 1)),
					src.getPixel(Math.min(src.getWidth() - 1, x * 2 + 1), Math.min(src.getHeight() - 1, y * 2 + 1))};
				int result = 0;
				for (int shift = 0; shift < 32; shift += 8) {
					int sum = 0;
					for (int v : p) {
						sum += v >>> shift & 0xFF;
					}
					result |= (sum / 4 & 0xFF) << shift;
				}
				out.setPixel(x, y, result);
			}
		}
		return out;
	}

	private static String sha1(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
		} catch (Exception e) {
			return Integer.toHexString(java.util.Arrays.hashCode(data)) + "_" + data.length;
		}
	}
}
