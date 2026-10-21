package dev.blendermc.liminal.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.blendermc.BlenderMc;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Material textures shared by content hash: every distinct PNG becomes one {@link DynamicTexture} registered as
 * {@code blendermc:liminal/tex/<sha1>} (nearest filtering, no mipmaps), no matter how many pieces or items use it.
 * Textures are small (16x16 in the shipped levels) and kept for the session. Render thread only.
 */
public final class LiminalTextures {
	public static final Identifier WHITE = BlenderMc.id("liminal/white");
	private static final Map<String, Identifier> BY_HASH = new HashMap<>();
	private static boolean whiteRegistered;

	private LiminalTextures() {
	}

	/** The texture for these PNG bytes, or {@link #WHITE} for null/empty/unreadable data. */
	public static Identifier get(byte[] png) {
		if (png == null || png.length == 0) {
			return white();
		}
		String hash = sha1(png);
		Identifier id = BY_HASH.get(hash);
		if (id != null) {
			return id;
		}
		id = BlenderMc.id("liminal/tex/" + hash);
		try {
			NativeImage image = NativeImage.read(png);
			Identifier label = id;
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(label::toString, image));
		} catch (Exception e) {
			BlenderMc.LOGGER.warn("Unreadable liminal texture ({} bytes): {}", png.length, e.toString());
			id = white();
		}
		BY_HASH.put(hash, id);
		return id;
	}

	/** A 1x1 white texture, tinted by the material colour. */
	public static Identifier white() {
		if (!whiteRegistered) {
			whiteRegistered = true;
			NativeImage image = new NativeImage(1, 1, false);
			image.setPixel(0, 0, 0xFFFFFFFF);
			Minecraft.getInstance().getTextureManager().register(WHITE, new DynamicTexture(WHITE::toString, image));
		}
		return WHITE;
	}

	private static String sha1(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
		} catch (Exception e) {
			return Integer.toHexString(java.util.Arrays.hashCode(data)) + "_" + data.length;
		}
	}
}
