package dev.echoliminal.liminal;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Per-level sky and light settings from the .glb's scene extras ({@code sky_color}, {@code fog_color},
 * {@code fog_start}, {@code fog_end}, {@code time_of_day}, {@code sun_visible}, {@code skylight}) and the Empty named
 * "sun", plus {@code stone_fill} (default true: stone fills the level's volume; false leaves it hollow) and {@code unlit} (default false: textures render as photographed, full-bright, no baked light). Colours are 0xRRGGBB or -1 when unset; distances and time are NaN when unset. {@code sunDirection} is the
 * unit vector sunlight travels along (sun → ground), or null for no sun.
 *
 * <p>{@code dayCycle} ({@code day_cycle}, default false) lets the sun and moon travel: {@code time_of_day} is then the start
 * time and {@code dayLength} ({@code day_length}, seconds for a whole day, default 1200) the speed; see {@link LiminalDay}.
 */
public record LevelSky(int skyColor, int fogColor, float fogStart, float fogEnd, float timeOfDay, boolean sunVisible, boolean skylight,
	@Nullable Vec3 sunDirection, boolean stoneFill, boolean unlit, boolean dayCycle, float dayLength) {
	public static final float DEFAULT_DAY_LENGTH = 1200.0F;
	public static final LevelSky NONE = new LevelSky(-1, -1, Float.NaN, Float.NaN, Float.NaN, false, false, null, true, false, false, DEFAULT_DAY_LENGTH);

	public boolean hasSun() {
		return this.sunDirection != null;
	}

	/** True when the level's light follows a moving sun (needs a skylight level that is not unlit). */
	public boolean cycles() {
		return this.dayCycle && this.skylight && !this.unlit;
	}

	/**
	 * Lambert factor for a surface with unit normal (nx, ny, nz): 0.6 + 0.4·max(0, n·toSun); 1 without a sun. With a
	 * day cycle it follows the shade state the client keeps current ({@link LiminalDay#shadeState}).
	 */
	public float sunShade(float nx, float ny, float nz) {
		if (this.dayCycle) {
			LiminalDay.Day day = LiminalDay.shadeState;
			if (day != null) {
				return day.shade(nx, ny, nz);
			}
		}
		if (this.sunDirection == null) {
			return 1.0F;
		}
		double d = -(nx * this.sunDirection.x + ny * this.sunDirection.y + nz * this.sunDirection.z);
		return (float) (0.6 + 0.4 * Math.max(0.0, d));
	}

	/** The direction sunlight travels at {@code time} ticks on vanilla's sun path (rises east at 0, overhead at 6000). */
	public static Vec3 sunFromTime(float time) {
		double angle = time / 24000.0 * Math.PI * 2.0;
		return new Vec3(-Math.cos(angle), -Math.sin(angle), 0.0);
	}
}
