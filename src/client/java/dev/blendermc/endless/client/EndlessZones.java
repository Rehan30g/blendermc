package dev.blendermc.endless.client;

import dev.blendermc.BlenderMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Fog and sky of the endless dimension, blended smoothly from the zones around the player (backrooms: dense dark yellow,
 * field: long pale overcast, poolrooms: medium cyan-green). The sky is the fog colour everywhere, so far geometry fades
 * into it with no seam. Also the per-zone look of the cheap far lighting and how far out regions are worth streaming.
 */
public final class EndlessZones {
	/** Far plane (blocks) in the endless dimension: outer ring 2048 plus tile diagonals. */
	public static final float FAR_PLANE = 3800.0F;
	public static final Identifier DIMENSION_ID = BlenderMc.id("liminal_endless");

	/** Per zone: fog start, fog end, fog r, g, b. */
	private static final float[][] FOG = fogTable();
	/** Per zone, far lighting: colour multiplier r, g, b, then block light and sky light (0..240) written to the lightmap coordinate. */
	private static final float[][] LOOK = {
		{0.95F, 0.90F, 0.70F, 225.0F, 0.0F},
		{0.96F, 0.98F, 1.00F, 0.0F, 235.0F},
		{0.95F, 1.00F, 1.00F, 230.0F, 0.0F},
	};

	private static final float[] current = {60.0F, 400.0F, 0.5F, 0.5F, 0.5F};
	private static boolean snapped;
	private static volatile float streamLimit = 256.0F;

	/** Fog of each zone from the generator's Zone table. */
	private static float[][] fogTable() {
		dev.blendermc.endless.gen.Zone[] zones = dev.blendermc.endless.gen.Zone.values();
		float[][] out = new float[zones.length][];
		for (int i = 0; i < zones.length; i++) {
			int c = zones[i].fogColor;
			out[i] = new float[] {zones[i].fogStart, zones[i].fogEnd, (c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F};
		}
		return out;
	}

	private EndlessZones() {
	}

	public static boolean isEndless(@Nullable Level level) {
		return level != null && level.dimension().identifier().equals(DIMENSION_ID);
	}

	public static boolean active() {
		ClientLevel level = Minecraft.getInstance().level;
		return level != null && isEndless(level);
	}

	/** Far light look of a zone (see {@link #LOOK}). */
	static float[] look(int zone) {
		return LOOK[Math.floorMod(zone, LOOK.length)];
	}

	private static float[] fogOf(int zone) {
		return FOG[Math.floorMod(zone, FOG.length)];
	}

	/** {start, end, r, g, b} of the fog being drawn; null outside the endless dimension. */
	public static float @Nullable [] fog(@Nullable Level level) {
		if (!(level instanceof ClientLevel) || !isEndless(level)) {
			return null;
		}
		return current.clone();
	}

	public static int skyColorArgb() {
		return 0xFF000000 | Math.round(current[2] * 255) << 16 | Math.round(current[3] * 255) << 8 | Math.round(current[4] * 255);
	}

	/** How far (blocks) regions are worth generating right now: the densest fog hides everything beyond it. */
	static float streamLimit() {
		return streamLimit;
	}

	static void reset() {
		snapped = false;
	}

	/** Once per tick: moves the fog towards the zone mix around the player and updates the stream limit. */
	static void tick(EndlessSource source, double px, double pz) {
		int x = (int) Math.floor(px), z = (int) Math.floor(pz);
		float[] target = new float[5];
		float weight = 0.0F;
		int[][] taps = {{0, 0, 3}, {24, 0, 1}, {-24, 0, 1}, {0, 24, 1}, {0, -24, 1}};
		for (int[] t : taps) {
			float[] f = fogOf(source.zone(x + t[0], z + t[1]));
			for (int i = 0; i < 5; i++) {
				target[i] += f[i] * t[2];
			}
			weight += t[2];
		}
		for (int i = 0; i < 5; i++) {
			target[i] /= weight;
		}
		float k = snapped ? 0.04F : 1.0F;
		snapped = true;
		for (int i = 0; i < 5; i++) {
			current[i] += (target[i] - current[i]) * k;
		}
		// What may become visible soon: look around at the stream distance of a near zone change.
		float far = current[1];
		for (int a = 0; a < 8; a++) {
			double ang = a * Math.PI / 4.0;
			far = Math.max(far, fogOf(source.zone((int) (x + Math.cos(ang) * 96), (int) (z + Math.sin(ang) * 96)))[1]);
		}
		streamLimit = Math.max(192.0F, Math.min(2100.0F, far * 1.1F + 32.0F));
	}
}
