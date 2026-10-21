package dev.echoliminal.liminal.client;

import dev.echoliminal.liminal.LevelSky;
import dev.echoliminal.liminal.LiminalDay;
import dev.echoliminal.liminal.LiminalLevels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** The client's view of the day cycle of the level being drawn: sun, moon and light factors for this very frame. */
public final class LiminalDayClock {
	private static double cachedAt = Double.NaN, cachedOffset;
	private static @Nullable LevelSky cachedSky;
	private static LiminalDay.@Nullable Day cached;

	private LiminalDayClock() {
	}

	/** The state now (game time plus the partial tick), or null when the level has no {@code day_cycle}. */
	public static LiminalDay.@Nullable Day now() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || !LiminalLevels.isLiminal(level)) {
			return null;
		}
		LevelSky sky = LiminalLevels.activeSky();
		if (sky == null || !sky.dayCycle()) {
			return null;
		}
		double time = level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		double offset = LiminalDay.offset(LiminalLevels.activeName());
		if (cached == null || time != cachedAt || sky != cachedSky || offset != cachedOffset) {
			cached = LiminalDay.at(sky, LiminalLevels.activeName(), time);
			cachedOffset = offset;
			cachedAt = time;
			cachedSky = sky;
		}
		return cached;
	}
}
