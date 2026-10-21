package dev.blendermc.liminal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Day and night of a level with {@code day_cycle}. Time is a pure function of the world's game time, so server and
 * client agree and it survives restarts: {@code t = (start + gameTime · 24000 / (day_length · 20) + devOffset) mod 24000}
 * ({@code start} = {@code time_of_day}).
 *
 * <p>The sun's path is a great circle through the level's baked sun direction: it rises on a horizontal axis r at
 * t = 0, climbs through a peak {@code m} (tilted like vanilla's east-west arc, up to the zenith) and sets opposite at
 * t = 12000: {@code toSun(θ) = cosθ·r + sinθ·m}, θ = t/24000·2π. r and the peak elevation are solved so that at the
 * level's own {@code time_of_day} the sun stands exactly at {@code sun_dir} (what the static look was made for). The moon
 * is the opposite direction. Lighting is baked for {@link #SLICES} sun positions over the daytime half (θ = 0..π).
 */
public final class LiminalDay {
	public static final int SLICES = 12;
	public static final float DAY_TICKS = 24000.0F;
	/** Sky light level in full shade and the extra range up to open sunlit sky (see LightModel). */
	public static final float SKY_MIN = 6.0F, SKY_RANGE = 9.0F;
	/** Sun share of the sky light at night (moonlight is flat), and Lambert weight of the moon. */
	private static final float MOON_FLAT = 0.5F, MOON_LAMBERT = 0.5F;
	private static final Map<String, Double> OFFSETS = new ConcurrentHashMap<>();
	/** Shade state used by {@link LevelSky#sunShade}; kept current by the client (null: static sun). */
	public static volatile @Nullable Day shadeState;
	/** Bumped by {@link #setOffset}, so clients can jump instead of stepping. */
	public static volatile int offsetVersion;

	private LiminalDay() {
	}

	/** Dev offset of a level's time in ticks (command {@code /blendermc time}). */
	public static void setOffset(String level, double ticks) {
		OFFSETS.put(level, ticks);
		offsetVersion++;
	}

	public static double offset(String level) {
		Double v = OFFSETS.get(level);
		return v == null ? 0.0 : v;
	}

	/** The sun's path of a level. */
	public record Path(double rx, double rz, double mx, double my, double mz, double start) {
		/** Unit vector from the ground to the sun at angle theta (radians; 0 = rising). */
		public Vec3 toSun(double theta) {
			double c = Math.cos(theta), s = Math.sin(theta);
			return new Vec3(c * this.rx + s * this.mx, s * this.my, c * this.rz + s * this.mz);
		}
	}

	public static Path path(LevelSky sky) {
		Vec3 travel = sky.sunDirection() != null ? sky.sunDirection() : LevelSky.sunFromTime(Float.isNaN(sky.timeOfDay()) ? 3500.0F : sky.timeOfDay());
		Vec3 s = travel.scale(-1.0).normalize();
		double h = Math.hypot(s.x, s.z);
		double phi = h < 1.0E-6 ? 0.0 : Math.atan2(s.z, s.x);
		double theta0 = Double.NaN, em = Math.toRadians(70.0);
		if (!Float.isNaN(sky.timeOfDay())) {
			double th = sky.timeOfDay() / 24000.0 * Math.PI * 2.0;
			double sn = Math.sin(th);
			if (Math.abs(sn) > 0.05 && sn * s.y > 0.0) {
				theta0 = th;
				em = Math.asin(Math.min(1.0, s.y / sn));
				em = Math.max(em, Math.toRadians(30.0));
			}
		}
		if (Double.isNaN(theta0)) {
			theta0 = Math.asin(Math.max(-1.0, Math.min(1.0, s.y / Math.sin(em))));
		}
		double cm = Math.cos(em), sm = Math.sin(em);
		double alpha = Math.atan2(Math.sin(theta0) * cm, Math.cos(theta0));
		double rho = h < 1.0E-6 ? 0.0 : phi - alpha;
		double start = theta0 / (Math.PI * 2.0) * 24000.0;
		start -= 24000.0 * Math.floor(start / 24000.0);
		return new Path(Math.cos(rho), Math.sin(rho), -cm * Math.sin(rho), sm, cm * Math.cos(rho), start);
	}

	/** Sun, moon and light factors at one instant. */
	public static final class Day {
		/** Ticks 0..24000. */
		public final float time;
		/** Unit vector towards the sun. */
		public final double sx, sy, sz;
		/** 0 at night .. 1 in daylight, smooth over twilight. */
		public final float day;
		/** 1 at sunrise/sunset, 0 by day and night. */
		public final float twilight;
		/** 0..1 how high the moon stands (it is opposite the sun). */
		public final float moonUp;
		/** Position between the baked sun slices: 0 .. SLICES-1 (clamped). */
		public final float sliceU;

		Day(float time, Vec3 toSun) {
			this.time = time;
			this.sx = toSun.x;
			this.sy = toSun.y;
			this.sz = toSun.z;
			this.day = smooth((float) (toSun.y + 0.08) / 0.40F);
			this.twilight = Math.max(0.0F, 1.0F - (float) Math.abs(toSun.y - 0.04) / 0.26F);
			this.moonUp = clamp01((float) -toSun.y * 3.0F);
			double theta = time / 24000.0 * Math.PI * 2.0;
			if (theta > Math.PI * 1.5) {
				theta -= Math.PI * 2.0;
			}
			this.sliceU = (float) (Math.max(0.0, Math.min(Math.PI, theta)) / Math.PI * (SLICES - 1));
		}

		/** Lambert-style face factor for a unit normal: sun by day, a weaker moon by night. */
		public float shade(float nx, float ny, float nz) {
			float dot = (float) (nx * this.sx + ny * this.sy + nz * this.sz);
			float ls = Math.max(0.0F, dot);
			float lm = Math.max(0.0F, -dot);
			return 0.6F + 0.4F * (this.day * ls + (1.0F - this.day) * MOON_LAMBERT * this.moonUp * lm);
		}

		public Vec3 sun() {
			return new Vec3(this.sx, this.sy, this.sz);
		}
	}

	/** Time of the level in ticks (0..24000) at {@code gameTime}. */
	public static float time(LevelSky sky, String level, double gameTime) {
		return (float) timeOn(path(sky), sky, level, gameTime);
	}

	private static double timeOn(Path path, LevelSky sky, String level, double gameTime) {
		double length = sky.dayLength() > 0.0F ? sky.dayLength() : LevelSky.DEFAULT_DAY_LENGTH;
		double t = path.start() + gameTime * 24000.0 / (length * 20.0) + offset(level);
		return t - 24000.0 * Math.floor(t / 24000.0);
	}

	/** State at the given game time (fractional ticks allowed). */
	public static Day at(LevelSky sky, String level, double gameTime) {
		Path path = path(sky);
		return atTime(path, (float) timeOn(path, sky, level, gameTime));
	}

	public static Day atTime(Path path, float time) {
		return new Day(time, path.toSun(time / 24000.0 * Math.PI * 2.0));
	}

	/** Direction towards the sun of slice {@code k} (θ = k·π/(SLICES-1)). */
	public static Vec3 sliceToSun(Path path, int k) {
		return path.toSun(k * Math.PI / (SLICES - 1));
	}

	/** Sky light level (0..15 scale before the lightmap) from the sun share, the dome openness (both 0..1) and the day factor. */
	public static float skyLevel(float sunPart, float open, float day) {
		float sun = sunPart * day + MOON_FLAT * (1.0F - day);
		return SKY_MIN + SKY_RANGE * (0.6F * sun + 0.4F * open);
	}

	private static float clamp01(float v) {
		return v < 0.0F ? 0.0F : Math.min(v, 1.0F);
	}

	private static float smooth(float x) {
		float t = clamp01(x);
		return t * t * (3.0F - 2.0F * t);
	}
}
