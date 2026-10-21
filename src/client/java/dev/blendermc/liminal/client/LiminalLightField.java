package dev.blendermc.liminal.client;

import dev.blendermc.liminal.LevelSky;
import dev.blendermc.liminal.LiminalDay;
import dev.blendermc.liminal.light.LightChange;
import dev.blendermc.liminal.light.LightModel;
import dev.blendermc.liminal.light.LightSources;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * One light for everything in the liminal dimension: vanilla blocks, entities, particles and block entities get the
 * same ray-traced light as the level mesh ({@link LightModel}, sampled at the cell centre with the same shadow
 * tests). Per block cell: block light = shadowed fluorescent light from the mesh's emissive faces plus shadowed
 * light from placed sources (the world's own light engine is ignored, it leaks through walls), sky light = sun and
 * sky visibility; face shading = the mesh's face shade times the level's sun factor; no ambient occlusion (see the
 * Liminal*Light mixins).
 *
 * <p>Values are computed lazily per cell and cached (benign races), so chunk-building threads may read it. When
 * placed sources change, {@link #update} swaps the sources and clears the cached cells they can reach. Only active
 * while the client is in the liminal dimension and a level is drawn; Sodium's own light pipeline is not covered.
 */
public final class LiminalLightField {
	private static final short COMPUTED = (short) 0x8000;
	private static final int DAY_COMPUTED = 0x80000000;
	/** Day time that must pass (ticks of day time) and real ticks between two field time steps. */
	private static final float DAY_STEP = 545.0F;
	private static final int DAY_STEP_TICKS = 300;
	private static volatile @Nullable LiminalLightField active;

	private final LightModel model;
	private final LevelSky sky;
	private volatile LightSources sources;
	/** Unlit level: constant block light from the room's average brightness, or -1. */
	private final float unlitLevel;
	private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
	/** Per cell: {@link #COMPUTED} | block light << 4 | sky light; 0 = not computed yet. */
	private final short[] cache;
	/**
	 * Day cycle: per cell {@link #DAY_COMPUTED} | sun visibility bit per baked slice << 8 | dome openness (0..15) << 4 | block light;
	 * the clock-dependent sky level is combined on read, so a time change clears nothing.
	 */
	private final int[] dcache;
	private final boolean dayMode;
	/** The clock state the field's sky light is evaluated for (steps in {@link #tickDay}). */
	private volatile LiminalDay.@Nullable Day fday;
	private volatile int dayVersion;
	private long dayStepTick = Long.MIN_VALUE;
	private int seenOffset = LiminalDay.offsetVersion;
	private final ThreadLocal<LightModel.Probe> probes;
	private volatile int version;

	private LiminalLightField(LightModel model, LightSources sources, AABB bounds, float unlitLevel) {
		this.unlitLevel = unlitLevel;
		this.model = model;
		this.sky = model.skyInfo;
		this.sources = sources;
		this.fday = model.dayCycle() ? LiminalDayClock.now() : null;
		if (this.fday != null) {
			LiminalDay.shadeState = this.fday;
		}
		this.probes = ThreadLocal.withInitial(model::probe);
		int margin = 18;
		this.minX = Mth.floor(bounds.minX) - margin;
		this.minY = Mth.floor(bounds.minY) - margin;
		this.minZ = Mth.floor(bounds.minZ) - margin;
		this.sizeX = Math.min(1024, Mth.ceil(bounds.maxX) + margin - this.minX + 1);
		this.sizeY = Math.min(512, Mth.ceil(bounds.maxY) + margin - this.minY + 1);
		this.sizeZ = Math.min(1024, Mth.ceil(bounds.maxZ) + margin - this.minZ + 1);
		long cells = (long) this.sizeX * this.sizeY * this.sizeZ;
		this.dayMode = model.dayCycle() && unlitLevel < 0.0F;
		this.cache = !this.dayMode && cells <= 48L * 1024 * 1024 ? new short[(int) cells] : new short[0];
		this.dcache = this.dayMode && cells <= 32L * 1024 * 1024 ? new int[(int) cells] : new int[0];
	}

	/** Makes the field of a drawn level active (null: none). Callers re-mesh chunks afterwards. */
	static void set(LiminalMeshBaker.@Nullable Baked baked, LightSources sources) {
		active = baked == null ? null : new LiminalLightField(baked.model, sources, baked.mesh.bounds(), baked.sky.unlit() ? LiminalUnlit.blockLevel(baked.mesh) : -1.0F);
	}

	/**
	 * New placed sources: swaps them in, clears the cells the changes can reach and returns the render sections
	 * ({@link SectionPos#asLong}) holding them, or null when everything changed.
	 */
	static @Nullable LongOpenHashSet update(LightSources sources, List<LightChange> changes) {
		LiminalLightField field = active;
		if (field == null) {
			return new LongOpenHashSet();
		}
		field.sources = sources;
		LongOpenHashSet sections = new LongOpenHashSet();
		boolean all = false;
		for (LightChange change : changes) {
			for (double[] box : change.boxes(field.model)) {
				int x0 = Math.max(field.minX, Mth.floor(box[0])), y0 = Math.max(field.minY, Mth.floor(box[1])), z0 = Math.max(field.minZ, Mth.floor(box[2]));
				int x1 = Math.min(field.minX + field.sizeX - 1, Mth.floor(box[3])), y1 = Math.min(field.minY + field.sizeY - 1, Mth.floor(box[4])),
					z1 = Math.min(field.minZ + field.sizeZ - 1, Mth.floor(box[5]));
				if (x1 < x0 || y1 < y0 || z1 < z0) {
					continue;
				}
				if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) >= (long) field.sizeX * field.sizeY * field.sizeZ / 2) {
					all = true;
				}
				if (field.cache.length > 0 || field.dcache.length > 0) {
					for (int x = x0; x <= x1; x++) {
						for (int y = y0; y <= y1; y++) {
							int base = ((x - field.minX) * field.sizeY + (y - field.minY)) * field.sizeZ - field.minZ;
							if (field.cache.length > 0) {
								java.util.Arrays.fill(field.cache, base + z0, base + z1 + 1, (short) 0);
							}
							if (field.dcache.length > 0) {
								java.util.Arrays.fill(field.dcache, base + z0, base + z1 + 1, 0);
							}
						}
					}
				}
				if (!all) {
					for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
						for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
							for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
								sections.add(SectionPos.asLong(sx, sy, sz));
							}
						}
					}
				}
			}
		}
		field.version++;
		return all ? null : sections;
	}

	/**
	 * Day cycle: moves the field's clock to {@code now} when enough day time and real time passed since the last step (or at
	 * once the first time), bumps {@link #version()} and the shade state. Returns true on a step: vanilla sections must be re-meshed
	 * ({@link #dayVersion()}). Render thread, once per tick.
	 */
	static boolean tickDay(LiminalDay.@Nullable Day now, long tick) {
		LiminalLightField field = active;
		if (field == null || !field.dayMode || now == null) {
			if (LiminalDay.shadeState != null) {
				LiminalDay.shadeState = null;
			}
			return false;
		}
		LiminalDay.Day cur = field.fday;
		if (field.seenOffset != LiminalDay.offsetVersion) {
			field.seenOffset = LiminalDay.offsetVersion;
			cur = null;
		}
		if (cur != null) {
			float delta = Math.abs(now.time - cur.time);
			delta = Math.min(delta, LiminalDay.DAY_TICKS - delta);
			if (delta < DAY_STEP || tick - field.dayStepTick < DAY_STEP_TICKS) {
				return false;
			}
		}
		field.dayStepTick = tick;
		field.fday = now;
		LiminalDay.shadeState = now;
		field.dayVersion++;
		field.version++;
		return true;
	}

	/** Bumped whenever the field's clock stepped. */
	static int dayVersion() {
		LiminalLightField field = active;
		return field == null ? 0 : field.dayVersion;
	}

	/** Bumped whenever the light of some cells changed. */
	static int version() {
		LiminalLightField field = active;
		return field == null ? 0 : field.version;
	}

	public static boolean isActive() {
		return active != null;
	}

	/** The field's packed light for a block position, given vanilla's value (emissive full bright is kept). */
	public static int packed(BlockPos pos, int vanilla) {
		LiminalLightField field = active;
		if (field == null || vanilla == LightTexture.FULL_BRIGHT) {
			return vanilla;
		}
		return field.cell(pos.getX(), pos.getY(), pos.getZ());
	}

	/** Face shade like the mesh's (vanilla face shade times the sun factor), or NaN when inactive. */
	public static float shade(Direction direction, boolean shaded) {
		LiminalLightField field = active;
		if (field == null) {
			return Float.NaN;
		}
		if (!shaded) {
			return 1.0F;
		}
		float nx = direction.getStepX(), ny = direction.getStepY(), nz = direction.getStepZ();
		return LiminalMeshBaker.shade(nx, ny, nz) * field.sky.sunShade(nx, ny, nz);
	}

	/** Day cycle: block light and sky light of a cell for the field's clock. */
	private int dayCell(int x, int y, int z) {
		int ix = x - this.minX, iy = y - this.minY, iz = z - this.minZ;
		boolean inside = this.dcache.length > 0 && ix >= 0 && iy >= 0 && iz >= 0 && ix < this.sizeX && iy < this.sizeY && iz < this.sizeZ;
		int index = inside ? (ix * this.sizeY + iy) * this.sizeZ + iz : -1;
		int data = index >= 0 ? this.dcache[index] : 0;
		LightSources s = this.sources;
		if (data == 0) {
			LightModel.Probe probe = this.probes.get();
			this.model.evaluate(probe, s, x + 0.5, y + 0.5, z + 0.5, 0.0F, 0.0F, 0.0F, true, x * 73856093 ^ y * 19349663 ^ z * 83492791, false);
			int mask = 0;
			for (int k = 0; k < LiminalDay.SLICES; k++) {
				if (probe.slices[k] > 0.5F) {
					mask |= 1 << k;
				}
			}
			int block = Mth.clamp(Math.round(probe.block), 0, 15);
			data = DAY_COMPUTED | mask << 8 | Mth.clamp(Math.round(probe.open * 15.0F), 0, 15) << 4 | block;
			if (index >= 0 && s == this.sources) {
				this.dcache[index] = data;
			}
		}
		LiminalDay.Day day = this.fday;
		if (day == null) {
			day = LiminalDay.atTime(LiminalDay.path(this.sky), 6000.0F);
		}
		int mask = data >> 8 & 0xFFF;
		float u = day.sliceU;
		int i0 = Math.min(LiminalDay.SLICES - 1, (int) u), i1 = Math.min(LiminalDay.SLICES - 1, i0 + 1);
		float f = u - i0;
		float sun = (mask >> i0 & 1) * (1.0F - f) + (mask >> i1 & 1) * f;
		int sky = Mth.clamp(Math.round(LiminalDay.skyLevel(sun, (data >> 4 & 15) / 15.0F, day.day)), 0, 15);
		return LightTexture.pack(data & 15, sky);
	}

	/** Packed light ({@link LightTexture#pack}) at the centre of a cell. */
	private int cell(int x, int y, int z) {
		if (this.dayMode) {
			return this.dayCell(x, y, z);
		}
		int ix = x - this.minX, iy = y - this.minY, iz = z - this.minZ;
		boolean inside = this.cache.length > 0 && ix >= 0 && iy >= 0 && iz >= 0 && ix < this.sizeX && iy < this.sizeY && iz < this.sizeZ;
		int index = inside ? (ix * this.sizeY + iy) * this.sizeZ + iz : -1;
		if (index >= 0) {
			short cached = this.cache[index];
			if (cached != 0) {
				return LightTexture.pack(cached >> 4 & 15, cached & 15);
			}
		}
		LightSources s = this.sources;
		int block, sky;
		if (this.unlitLevel >= 0.0F) {
			float placed = s.placedLevel(x + 0.5, y + 0.5, z + 0.5);
			block = Mth.clamp(Math.round(15.0F * (1.0F - (1.0F - this.unlitLevel / 15.0F) * (1.0F - placed / 15.0F))), 0, 15);
			sky = 0;
		} else {
			LightModel.Probe probe = this.probes.get();
			this.model.evaluate(probe, s, x + 0.5, y + 0.5, z + 0.5, 0.0F, 0.0F, 0.0F, true, x * 73856093 ^ y * 19349663 ^ z * 83492791, false);
			block = Mth.clamp(Math.round(probe.block), 0, 15);
			sky = this.sky.skylight() ? Mth.clamp(Math.round(probe.sky), 0, 15) : 0;
		}
		if (index >= 0 && s == this.sources) {
			this.cache[index] = (short) (COMPUTED | block << 4 | sky);
		}
		return LightTexture.pack(block, sky);
	}
}
