package dev.echoliminal.liminal.light;

import dev.echoliminal.liminal.LevelSky;
import dev.echoliminal.liminal.LiminalDay;
import dev.echoliminal.liminal.LiminalMesh;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Baked ray-traced lighting of one level, shared by the mesh vertices and the per-cell light field so both agree:
 * <ul>
 * <li>direct fluorescent light from emissive surfaces: every panel cluster is sampled at several fixed points spread
 * over its triangles, each with its own shadow ray, cosine terms and falloff with a smooth range cutoff; plus block
 * light from placed sources with shadow rays;</li>
 * <li>one bounce of indirect light: hemisphere rays gather the direct light of the surfaces they hit times their
 * average colour, which gives the soft fill of real fluorescent rooms;</li>
 * <li>sun and sky: sun rays (soft edges) plus sky-dome rays give a sky light level;</li>
 * <li>ambient occlusion: hemisphere rays for creases and corners (surfaces only).</li>
 * </ul>
 * All sampling patterns are fixed (no per-vertex randomness), so neighbouring vertices of one surface get
 * the same estimator and differ only by what they really see; {@link Smoothing} then removes the remaining
 * stair-steps. Pure CPU, thread-safe after construction; callers hold a {@link Probe} per thread. Positions are the
 * mesh's own world coordinates, so any level origin works.
 */
public final class LightModel {
	/** Fluorescent light: floor level where no panel reaches, and the level used when the mesh has no panels. */
	public static final float AMBIENT_LEVEL = 3.0F;
	public static final float NO_PANELS_LEVEL = 10.0F;
	private static final float GAIN = 1.3F;
	private static final float FALLOFF = 0.09F;
	public static final float RADIUS = 18.0F;
	private static final float LIGHT_CELL = 2.0F;
	private static final int AREA_SAMPLES = 6;
	/** Sky light level in full shadow under a roof, and the extra range up to open sunlit sky. */
	private static final float SKY_MIN = LiminalDay.SKY_MIN, SKY_RANGE = LiminalDay.SKY_RANGE;
	private static final double SUN_SPREAD = 0.05;
	private static final double SKY_RAY = 64.0;
	private static final double AO_RANGE = 1.4;
	private static final float AO_STRENGTH = 0.5F;
	private static final int SUN_RAYS = 8;
	private static final int AO_RAYS = 16;
	private static final int SKY_RAYS = 9;
	private static final int BOUNCE_RAYS = 24;
	/** How far indirect light is gathered, and how much of it counts. */
	public static final double BOUNCE_RANGE = 10.0;
	private static final float BOUNCE_GAIN = 0.85F;
	/** Test aid: imitate the first version's sparse random sampling (to compare noise). */
	public static volatile boolean legacySampling;

	/** A merged emissive panel cluster: area-weighted centre, total area, summed normal, fixed sample points over its triangles. */
	private static final class Area {
		double x, y, z, area;
		float nx, ny, nz;
		float[] samples;
	}

	/** Per-thread scratch and the last result. */
	public static final class Probe {
		private final RayScene.Tracer tracer = new RayScene.Tracer();
		private final IntArrayList near = new IntArrayList();
		/** Direct block light 0..15 (fluorescent and placed lights combined), or the full estimate after {@link #evaluate}. */
		public float block;
		/** Sky light 0..15 (0 for levels without skylight). */
		public float sky;
		/** Ambient occlusion factor 1 (open) .. ~0.5 (tight crease). */
		public float ao = 1.0F;
		/** Day cycle only: sun visibility 0..1 of every baked slice and the sky dome openness 0..1 (after {@link LightModel#direct}). */
		public final float[] slices = new float[LiminalDay.SLICES];
		public float open;
	}

	public final LevelSky skyInfo;
	public final RayScene scene;
	private final boolean skylight;
	private final boolean sun;
	private final double sx, sy, sz;
	private final double ux, uy, uz, vx, vy, vz;
	private final boolean dayCycle;
	/** Per baked sun slice: unit vector towards the sun (3 doubles) and disc basis (6 doubles). */
	private final double[] sliceDir, sliceBasis;
	private final Long2ObjectOpenHashMap<List<Area>> areaGrid = new Long2ObjectOpenHashMap<>();
	private final int areaGridSize = (int) Math.ceil(RADIUS);
	private final boolean noPanels;
	private final LongOpenHashSet proxies;
	private final float[] albedo;
	/** Direct block light per mesh vertex, set by the baker once the direct pass is done (bounce light reads it). */
	private volatile float[] direct;

	public LightModel(LiminalMesh mesh, LevelSky sky) {
		this(mesh, sky, null);
	}

	/** {@code cellOfTri}: block cell of every mesh triangle, so mined cells can stop occluding ({@link RayScene#setCellEnabled}). */
	public LightModel(LiminalMesh mesh, LevelSky sky, long[] cellOfTri) {
		this.skyInfo = sky == null ? LevelSky.NONE : sky;
		this.scene = RayScene.build(mesh, cellOfTri);
		this.skylight = this.skyInfo.skylight();
		this.sun = this.skylight && this.skyInfo.hasSun();
		if (this.sun) {
			double ax = -this.skyInfo.sunDirection().x, ay = -this.skyInfo.sunDirection().y, az = -this.skyInfo.sunDirection().z;
			double len = Math.sqrt(ax * ax + ay * ay + az * az);
			this.sx = ax / len;
			this.sy = ay / len;
			this.sz = az / len;
		} else {
			this.sx = 0;
			this.sy = 1;
			this.sz = 0;
		}
		// Basis of the sun disc.
		double hx = Math.abs(this.sy) < 0.9 ? 0 : 1, hy = Math.abs(this.sy) < 0.9 ? 1 : 0;
		double cx = hy * this.sz, cy = -hx * this.sz, cz = hx * this.sy - hy * this.sx;
		double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
		this.ux = cx / cl;
		this.uy = cy / cl;
		this.uz = cz / cl;
		this.vx = this.sy * this.uz - this.sz * this.uy;
		this.vy = this.sz * this.ux - this.sx * this.uz;
		this.vz = this.sx * this.uy - this.sy * this.ux;
		this.dayCycle = this.sun && this.skyInfo.cycles();
		this.sliceDir = this.dayCycle ? new double[LiminalDay.SLICES * 3] : new double[0];
		this.sliceBasis = this.dayCycle ? new double[LiminalDay.SLICES * 6] : new double[0];
		if (this.dayCycle) {
			LiminalDay.Path path = LiminalDay.path(this.skyInfo);
			for (int k = 0; k < LiminalDay.SLICES; k++) {
				Vec3 d = LiminalDay.sliceToSun(path, k);
				this.sliceDir[k * 3] = d.x;
				this.sliceDir[k * 3 + 1] = d.y;
				this.sliceDir[k * 3 + 2] = d.z;
				System.arraycopy(basis(d.x, d.y, d.z), 0, this.sliceBasis, k * 6, 6);
			}
		}
		for (Area a : collectAreas(mesh)) {
			this.areaGrid.computeIfAbsent(BlockPos.asLong(Mth.floor(a.x / this.areaGridSize), Mth.floor(a.y / this.areaGridSize), Mth.floor(a.z / this.areaGridSize)),
				k -> new ArrayList<>()).add(a);
		}
		this.noPanels = this.areaGrid.isEmpty();
		this.proxies = proxyCells(mesh);
		this.albedo = Albedo.of(mesh);
	}

	public Probe probe() {
		return new Probe();
	}

	public boolean hasSun() {
		return this.sun;
	}

	public boolean skylight() {
		return this.skylight;
	}

	/** Whether the level has any panels to bounce light off (otherwise bounce is skipped). */
	public boolean hasBounce() {
		return !this.noPanels;
	}

	/** Unit vector from the surface towards the sun (only meaningful when {@link #hasSun()}). */
	public double sunX() {
		return this.sx;
	}

	public double sunY() {
		return this.sy;
	}

	public double sunZ() {
		return this.sz;
	}

	/** Sets the per mesh vertex direct block light that bounce light gathers from. */
	public void setDirect(float[] perVertex) {
		this.direct = perVertex;
	}

	/** Cells holding the invisible light blocks {@link dev.echoliminal.liminal.LiminalLights} puts in front of emissive faces; they are not extra sources. */
	public LongOpenHashSet proxyCells() {
		return this.proxies;
	}

	/** Same cells {@code LiminalLights.apply} would fill for this mesh. */
	public static LongOpenHashSet proxyCells(LiminalMesh mesh) {
		LongOpenHashSet cells = new LongOpenHashSet();
		float[] p = mesh.positions();
		float[] n = mesh.normals();
		int[] idx = mesh.indices();
		int[] triMat = mesh.triangleMaterial();
		for (int t = 0; t < idx.length / 3; t++) {
			int m = t < triMat.length ? triMat[t] : 0;
			if (m < 0 || m >= mesh.materials().size() || !mesh.materials().get(m).emissive()) {
				continue;
			}
			int a = idx[3 * t] * 3, b = idx[3 * t + 1] * 3, c = idx[3 * t + 2] * 3;
			double cx = (p[a] + p[b] + p[c]) / 3.0, cy = (p[a + 1] + p[b + 1] + p[c + 1]) / 3.0, cz = (p[a + 2] + p[b + 2] + p[c + 2]) / 3.0;
			double ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
			double vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			if (n.length > a + 2 && nx * n[a] + ny * n[a + 1] + nz * n[a + 2] < 0) {
				nx = -nx;
				ny = -ny;
				nz = -nz;
			}
			double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (length < 1.0E-9) {
				continue;
			}
			cells.add(BlockPos.containing(cx + nx / length * 0.5, cy + ny / length * 0.5, cz + nz / length * 0.5).asLong());
		}
		return cells;
	}

	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * Full estimate for a point (direct light plus one bounce), result in {@code p.block}/{@code sky}/{@code ao}: the
	 * estimator the light field uses for cells. Mesh vertices run {@link #direct} and {@link #bounce} as separate passes.
	 */
	public void evaluate(Probe p, LightSources dyn, double x, double y, double z, float nx, float ny, float nz, boolean omni, int seed, boolean wantAo) {
		this.direct(p, dyn, x, y, z, nx, ny, nz, omni, seed, wantAo);
		p.block = combine(p.block, this.bounce(p, x, y, z, nx, ny, nz, omni));
	}

	/** Direct light of a point: block light, sky light and ambient occlusion in {@code p}. */
	public void direct(Probe p, LightSources dyn, double x, double y, double z, float nx, float ny, float nz, boolean omni, int seed, boolean wantAo) {
		double ox = omni ? x : x + nx * 0.04, oy = omni ? y : y + ny * 0.04, oz = omni ? z : z + nz * 0.04;
		float fluor = this.fluorescent(p, dyn, ox, oy, oz, nx, ny, nz, omni, seed);
		float point = dyn.lightCount() > 0 ? this.pointLights(p, dyn, ox, oy, oz, nx, ny, nz, omni, seed) : 0.0F;
		p.block = 15.0F * (1.0F - (1.0F - fluor / 15.0F) * (1.0F - point / 15.0F));
		if (this.dayCycle) {
			this.daySky(p, dyn, ox, oy, oz, nx, ny, nz, omni, omni);
			p.sky = LiminalDay.skyLevel(p.slices[LiminalDay.SLICES / 2], p.open, 1.0F);
		} else {
			p.sky = this.skylight ? this.skyLight(p, dyn, ox, oy, oz, nx, ny, nz, omni, seed) : 0.0F;
		}
		p.ao = wantAo && !omni ? this.occlusion(p, dyn, ox, oy, oz, nx, ny, nz, seed) : 1.0F;
	}

	/** Block light level from a direct level and the gathered indirect fraction (0..1). */
	public static float combine(float direct, float bounceFraction) {
		return 15.0F * (1.0F - (1.0F - direct / 15.0F) * (1.0F - Math.min(0.95F, bounceFraction * BOUNCE_GAIN)));
	}

	/**
	 * Indirect light: cosine-weighted rays (all directions for air points) find the nearest surface within
	 * {@link #BOUNCE_RANGE}; its direct light (interpolated from its vertices) times its colour is averaged. 0..1.
	 */
	public float bounce(Probe p, double x, double y, double z, float nx, float ny, float nz, boolean omni) {
		float[] direct = this.direct;
		if (this.noPanels || direct == null || this.scene.triangleCount() == 0) {
			return 0.0F;
		}
		double ox = omni ? x : x + nx * 0.04, oy = omni ? y : y + ny * 0.04, oz = omni ? z : z + nz * 0.04;
		double tx = 0, ty = 0, tz = 0, bx = 0, by = 0, bz = 0;
		if (!omni) {
			if (Math.abs(ny) < 0.9) {
				tx = nz;
				tz = -nx;
			} else {
				ty = -nz;
				tz = ny;
			}
			double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
			tx /= tl;
			ty /= tl;
			tz /= tl;
			bx = ny * tz - nz * ty;
			by = nz * tx - nx * tz;
			bz = nx * ty - ny * tx;
		}
		double sum = 0.0;
		int[] slotVertex = this.scene.slotVertex;
		for (int k = 0; k < BOUNCE_RAYS; k++) {
			double a = k * 2.399963;
			double dx, dy, dz;
			if (omni) {
				double yy = 1.0 - 2.0 * (k + 0.5) / BOUNCE_RAYS, r = Math.sqrt(Math.max(0.0, 1.0 - yy * yy));
				dx = Math.cos(a) * r;
				dy = yy;
				dz = Math.sin(a) * r;
			} else {
				double u = (k + 0.5) / BOUNCE_RAYS * 0.9, r = Math.sqrt(u), z2 = Math.sqrt(1.0 - u);
				double cx = Math.cos(a) * r, cy = Math.sin(a) * r;
				dx = tx * cx + bx * cy + nx * z2;
				dy = ty * cx + by * cy + ny * z2;
				dz = tz * cx + bz * cy + nz * z2;
			}
			if (this.scene.closest(p.tracer, ox, oy, oz, dx * BOUNCE_RANGE, dy * BOUNCE_RANGE, dz * BOUNCE_RANGE, 1.0)) {
				RayScene.Tracer tr = p.tracer;
				int s = tr.slot * 3;
				double w0 = 1.0 - tr.u - tr.v;
				double lit = w0 * fraction(direct[slotVertex[s]]) + tr.u * fraction(direct[slotVertex[s + 1]]) + tr.v * fraction(direct[slotVertex[s + 2]]);
				int m = this.scene.slotMaterial[tr.slot];
				sum += lit * (m >= 0 && m < this.albedo.length ? this.albedo[m] : 0.5F);
			}
		}
		return (float) (sum / BOUNCE_RAYS);
	}

	private static double fraction(float level) {
		return Math.max(0.0F, level - AMBIENT_LEVEL) / (15.0F - AMBIENT_LEVEL);
	}

	private float fluorescent(Probe p, LightSources dyn, double ox, double oy, double oz, float nx, float ny, float nz, boolean omni, int seed) {
		if (this.noPanels) {
			return NO_PANELS_LEVEL;
		}
		int gx = Mth.floor(ox / this.areaGridSize), gy = Mth.floor(oy / this.areaGridSize), gz = Mth.floor(oz / this.areaGridSize);
		double sum = 0.0;
		int count = 0;
		for (int ix = gx - 1; ix <= gx + 1; ix++) {
			for (int iy = gy - 1; iy <= gy + 1; iy++) {
				for (int iz = gz - 1; iz <= gz + 1; iz++) {
					List<Area> list = this.areaGrid.get(BlockPos.asLong(ix, iy, iz));
					if (list == null) {
						continue;
					}
					for (Area l : list) {
						double cdx = l.x - ox, cdy = l.y - oy, cdz = l.z - oz;
						double cd2 = cdx * cdx + cdy * cdy + cdz * cdz;
						if (cd2 > (RADIUS + 3.0) * (RADIUS + 3.0)) {
							continue;
						}
						int samples = l.samples.length / 3;
						if (legacySampling) {
							if (cd2 > RADIUS * RADIUS) {
								continue;
							}
							double d = Math.sqrt(cd2) + 1.0E-4;
							double facing = omni ? 1.0 : 0.35 + 0.65 * Math.abs((cdx * nx + cdy * ny + cdz * nz) / d);
							double emit = 0.3 + 0.7 * Math.abs((cdx * l.nx + cdy * l.ny + cdz * l.nz) / d);
							double c = l.area * facing * emit / (1.0 + cd2 * FALLOFF);
							if (c < 0.003) {
								continue;
							}
							int rays = c > 0.05 ? 2 : 1;
							int seen = 0;
							for (int k = 0; k < rays; k++) {
								int s = Math.floorMod(seed + count++ * 7 + k, samples) * 3;
								if (this.shadowRay(p, dyn, ox, oy, oz, l, s)) {
									seen++;
								}
							}
							sum += c * seen / rays;
							continue;
						}
						double part = l.area / samples;
						for (int k = 0; k < samples; k++) {
							int s = k * 3;
							double dx = l.samples[s] - ox, dy = l.samples[s + 1] - oy, dz = l.samples[s + 2] - oz;
							double d2 = dx * dx + dy * dy + dz * dz;
							if (d2 > RADIUS * RADIUS) {
								continue;
							}
							double d = Math.sqrt(d2) + 1.0E-4;
							double facing = omni ? 1.0 : 0.35 + 0.65 * Math.abs((dx * nx + dy * ny + dz * nz) / d);
							double emit = 0.3 + 0.7 * Math.abs((dx * l.nx + dy * l.ny + dz * l.nz) / d);
							// Smooth range cutoff over the outer 40 percent.
							double w = Math.min(1.0, (RADIUS - d) / (0.4 * RADIUS));
							double term = part * facing * emit / (1.0 + d2 * FALLOFF) * (w * w * (3.0 - 2.0 * w));
							if (term < 0.0004) {
								continue;
							}
							if (this.shadowRay(p, dyn, ox, oy, oz, l, s)) {
								sum += term;
							}
						}
					}
				}
			}
		}
		return AMBIENT_LEVEL + (15.0F - AMBIENT_LEVEL) * (float) (1.0 - Math.exp(-sum * GAIN));
	}

	/** True if the sample point (offset {@code s} in the cluster's samples) is visible from the origin. */
	private boolean shadowRay(Probe p, LightSources dyn, double ox, double oy, double oz, Area l, int s) {
		double sx = l.samples[s], sy = l.samples[s + 1], sz = l.samples[s + 2];
		// Lift the sample off the panel towards the lit side so it does not sit inside the ceiling.
		double side = (ox - sx) * l.nx + (oy - sy) * l.ny + (oz - sz) * l.nz >= 0 ? 0.06 : -0.06;
		sx += l.nx * side;
		sy += l.ny * side;
		sz += l.nz * side;
		return !this.scene.occluded(p.tracer, dyn, ox, oy, oz, sx - ox, sy - oy, sz - oz, 0.0, 0.995, false);
	}

	private static final double[][] LIGHT_JITTER = {{1, 1, 1}, {1, -1, -1}, {-1, 1, -1}, {-1, -1, 1}};

	/** Block light from placed sources: level minus distance (15 levels, 15 blocks), 4 fixed shadow rays around the source. */
	private float pointLights(Probe p, LightSources dyn, double ox, double oy, double oz, float nx, float ny, float nz, boolean omni, int seed) {
		dyn.nearby(ox, oy, oz, p.near);
		double dark = 1.0;
		for (int k = 0; k < p.near.size(); k++) {
			int i = p.near.getInt(k);
			double dx = dyn.lightX(i) - ox, dy = dyn.lightY(i) - oy, dz = dyn.lightZ(i) - oz;
			double d2 = dx * dx + dy * dy + dz * dz;
			int level = dyn.lightLevel(i);
			if (d2 >= (double) level * level) {
				continue;
			}
			double d = Math.sqrt(d2) + 1.0E-4;
			double facing = omni ? 1.0 : 0.6 + 0.4 * Math.max(0.0, (dx * nx + dy * ny + dz * nz) / d);
			double c = (level - d) * facing;
			if (c < 0.5) {
				continue;
			}
			double vis = 1.0;
			if (d > 0.6) {
				int seen = 0;
				for (double[] j : LIGHT_JITTER) {
					double tx = dx + j[0] * 0.25, ty = dy + j[1] * 0.25, tz = dz + j[2] * 0.25;
					double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
					if (!this.scene.occluded(p.tracer, dyn, ox, oy, oz, tx, ty, tz, omni ? 0.05 / len : 0.0, 1.0 - Math.min(0.3, len * 0.4) / len, true)) {
						seen++;
					}
				}
				vis = seen / 4.0;
			}
			dark *= 1.0 - c * vis / 15.0;
		}
		return (float) (15.0 * (1.0 - dark));
	}

	/** Sky light level: sun visibility (soft, fixed-pattern rays) and openness of the sky dome. */
	private float skyLight(Probe p, LightSources dyn, double ox, double oy, double oz, float nx, float ny, float nz, boolean omni, int seed) {
		double sunPart = 1.0;
		if (this.sun) {
			sunPart = this.sunPart(p, dyn, ox, oy, oz, nx, ny, nz, omni, this.sx, this.sy, this.sz, this.ux, this.uy, this.uz, this.vx, this.vy, this.vz, false);
		}
		double openPart = this.skyOpen(p, dyn, ox, oy, oz);
		double f = this.sun ? sunPart * 0.6 + openPart * 0.4 : 0.6 + openPart * 0.4;
		return (float) (SKY_MIN + SKY_RANGE * f);
	}

	/**
	 * Share of the sun's disc (direction (sx, sy, sz) towards the sun, disc basis u, v) that reaches the point, 0..1;
	 * 0.6 for surfaces facing away. {@code coarse}: one ray, 0 or 1.
	 */
	private double sunPart(Probe p, LightSources dyn, double ox, double oy, double oz, float nx, float ny, float nz, boolean omni,
			double sx, double sy, double sz, double ux, double uy, double uz, double vx, double vy, double vz, boolean coarse) {
		double lambert = omni ? 1.0 : nx * sx + ny * sy + nz * sz;
		if (lambert <= 0.05) {
			return 0.6;
		}
		boolean first = this.sunRay(p, dyn, ox, oy, oz, sx, sy, sz, ux, uy, uz, vx, vy, vz, 0);
		if (coarse) {
			return first ? 1.0 : 0.0;
		}
		// The innermost and outermost rays agreeing means the whole disc does (no penumbra here).
		boolean last = this.sunRay(p, dyn, ox, oy, oz, sx, sy, sz, ux, uy, uz, vx, vy, vz, SUN_RAYS - 1);
		if (first == last) {
			return first ? 1.0 : 0.0;
		}
		int seen = 0;
		for (int k = 0; k < SUN_RAYS; k++) {
			if (this.sunRay(p, dyn, ox, oy, oz, sx, sy, sz, ux, uy, uz, vx, vy, vz, k)) {
				seen++;
			}
		}
		return (double) seen / SUN_RAYS;
	}

	/** Openness of the sky dome (0..1): straight up and a ring at 45 degrees. */
	private double skyOpen(Probe p, LightSources dyn, double ox, double oy, double oz) {
		int open = 0;
		// Up plus two opposite ring rays all clear: treat the dome as open.
		boolean quick = !this.scene.occluded(p.tracer, dyn, ox, oy, oz, 0.0, SKY_RAY, 0.0, 0.0, 1.0, false)
			&& !this.scene.occluded(p.tracer, dyn, ox, oy, oz, SKY_RAY * 0.7071, SKY_RAY * 0.7071, 0.0, 0.0, 1.0, false)
			&& !this.scene.occluded(p.tracer, dyn, ox, oy, oz, -SKY_RAY * 0.7071, SKY_RAY * 0.7071, 0.0, 0.0, 1.0, false);
		if (quick) {
			open = SKY_RAYS;
		}
		for (int k = 0; k < SKY_RAYS && !quick; k++) {
			double dx = 0.0, dy = SKY_RAY, dz = 0.0;
			if (k > 0) {
				double a = (k - 1) * Math.PI * 2.0 / (SKY_RAYS - 1);
				dx = Math.cos(a) * SKY_RAY * 0.7071;
				dy = SKY_RAY * 0.7071;
				dz = Math.sin(a) * SKY_RAY * 0.7071;
			}
			if (!this.scene.occluded(p.tracer, dyn, ox, oy, oz, dx, dy, dz, 0.0, 1.0, false)) {
				open++;
			}
		}
		return (double) open / SKY_RAYS;
	}

	/** Day cycle: sun visibility of every baked slice (p.slices) and dome openness (p.open) of a point. */
	private void daySky(Probe p, LightSources dyn, double ox, double oy, double oz, float nx, float ny, float nz, boolean omni, boolean coarse) {
		for (int k = 0; k < LiminalDay.SLICES; k++) {
			p.slices[k] = (float) this.sunPart(p, dyn, ox, oy, oz, nx, ny, nz, omni, this.sliceDir[k * 3], this.sliceDir[k * 3 + 1], this.sliceDir[k * 3 + 2],
				this.sliceBasis[k * 6], this.sliceBasis[k * 6 + 1], this.sliceBasis[k * 6 + 2], this.sliceBasis[k * 6 + 3], this.sliceBasis[k * 6 + 4],
				this.sliceBasis[k * 6 + 5], coarse);
		}
		p.open = (float) this.skyOpen(p, dyn, ox, oy, oz);
	}

	/** True when the level's sun moves ({@code day_cycle}): {@link Probe#slices} are filled by {@link #direct}. */
	public boolean dayCycle() {
		return this.dayCycle;
	}

	/**
	 * Light-field helper for a cell centre: bit k of the result is set when slice k's sun is visible; {@code p.open} is
	 * the dome openness.
	 */
	public int sunMask(Probe p, LightSources dyn, double x, double y, double z) {
		int mask = 0;
		for (int k = 0; k < LiminalDay.SLICES; k++) {
			if (this.sunRay(p, dyn, x, y, z, this.sliceDir[k * 3], this.sliceDir[k * 3 + 1], this.sliceDir[k * 3 + 2], this.sliceBasis[k * 6], this.sliceBasis[k * 6 + 1],
				this.sliceBasis[k * 6 + 2], this.sliceBasis[k * 6 + 3], this.sliceBasis[k * 6 + 4], this.sliceBasis[k * 6 + 5], 0)) {
				mask |= 1 << k;
			}
		}
		p.open = (float) this.skyOpen(p, dyn, x, y, z);
		return mask;
	}

	/** True if sun ray {@code k} of the disc is clear. */
	private boolean sunRay(Probe p, LightSources dyn, double ox, double oy, double oz, double sx, double sy, double sz, double ux, double uy, double uz,
			double vx, double vy, double vz, int k) {
		double r = SUN_SPREAD * Math.sqrt((k + 0.5) / SUN_RAYS);
		double a = k * 2.399963;
		double c = Math.cos(a) * r, s = Math.sin(a) * r;
		double dx = (sx + ux * c + vx * s) * 300.0, dy = (sy + uy * c + vy * s) * 300.0, dz = (sz + uz * c + vz * s) * 300.0;
		return !this.scene.occluded(p.tracer, dyn, ox, oy, oz, dx, dy, dz, 0.0, 1.0, false);
	}

	/** Disc basis (u, v) perpendicular to the sun direction (sx, sy, sz); same construction as the static sun's. */
	private static double[] basis(double sx, double sy, double sz) {
		double hx = Math.abs(sy) < 0.9 ? 0 : 1, hy = Math.abs(sy) < 0.9 ? 1 : 0;
		double cx = hy * sz, cy = -hx * sz, cz = hx * sy - hy * sx;
		double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
		double ux = cx / cl, uy = cy / cl, uz = cz / cl;
		return new double[] {ux, uy, uz, sy * uz - sz * uy, sz * ux - sx * uz, sx * uy - sy * ux};
	}

	/** Cosine-weighted hemisphere rays: the share that hits something darkens the vertex. */
	private float occlusion(Probe p, LightSources dyn, double ox, double oy, double oz, float nx, float ny, float nz, int seed) {
		double tx, ty, tz;
		if (Math.abs(ny) < 0.9) {
			tx = nz;
			ty = 0;
			tz = -nx;
		} else {
			tx = 0;
			ty = -nz;
			tz = ny;
		}
		double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
		tx /= tl;
		ty /= tl;
		tz /= tl;
		double bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
		int rays = legacySampling ? 6 : AO_RAYS;
		double rot = legacySampling ? rnd(seed, 303) * Math.PI * 2.0 : 0.0;
		int hit = 0;
		for (int k = 0; k < rays; k++) {
			if (k == 4 && hit == 0 && !legacySampling) {
				// Nothing within reach in the first four directions: an open surface.
				break;
			}
			double a = rot + k * 2.399963;
			double u = (k + 0.5) / rays * 0.85;
			double r = Math.sqrt(u), z = Math.sqrt(1.0 - u);
			double cx = Math.cos(a) * r, cy = Math.sin(a) * r;
			double dx = (tx * cx + bx * cy + nx * z) * AO_RANGE, dy = (ty * cx + by * cy + ny * z) * AO_RANGE, dz = (tz * cx + bz * cy + nz * z) * AO_RANGE;
			if (this.scene.occluded(p.tracer, dyn, ox, oy, oz, dx, dy, dz, 0.0, 1.0, false)) {
				hit++;
			}
		}
		return 1.0F - AO_STRENGTH * hit / rays;
	}

	static float rnd(int a, int b) {
		int h = a * 0x9E3779B1 ^ b * 0x85EBCA6B;
		h ^= h >>> 15;
		h *= 0x2C1B3C6D;
		h ^= h >>> 12;
		h *= 0x297A2D39;
		h ^= h >>> 15;
		return (h >>> 8) * (1.0F / 16777216.0F);
	}

	private static double halton(int index, int base) {
		double f = 1.0, r = 0.0;
		int i = index;
		while (i > 0) {
			f /= base;
			r += f * (i % base);
			i /= base;
		}
		return r;
	}

	private static List<Area> collectAreas(LiminalMesh mesh) {
		float[] pos = mesh.positions();
		int[] idx = mesh.indices();
		int[] triMat = mesh.triangleMaterial();
		Long2ObjectOpenHashMap<Area> merged = new Long2ObjectOpenHashMap<>();
		Long2ObjectOpenHashMap<FloatArrayList> members = new Long2ObjectOpenHashMap<>();
		for (int t = 0; t < idx.length / 3; t++) {
			int m = t < triMat.length ? triMat[t] : 0;
			if (m < 0 || m >= mesh.materials().size() || !mesh.materials().get(m).emissive()) {
				continue;
			}
			int a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
			float e1x = pos[b] - pos[a], e1y = pos[b + 1] - pos[a + 1], e1z = pos[b + 2] - pos[a + 2];
			float e2x = pos[c] - pos[a], e2y = pos[c + 1] - pos[a + 1], e2z = pos[c + 2] - pos[a + 2];
			float cx = e1y * e2z - e1z * e2y, cy = e1z * e2x - e1x * e2z, cz = e1x * e2y - e1y * e2x;
			double area = 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
			if (area < 1.0E-5) {
				continue;
			}
			double mx = (pos[a] + pos[b] + pos[c]) / 3.0, my = (pos[a + 1] + pos[b + 1] + pos[c + 1]) / 3.0, mz = (pos[a + 2] + pos[b + 2] + pos[c + 2]) / 3.0;
			long key = BlockPos.asLong(Mth.floor(mx / LIGHT_CELL), Mth.floor(my / LIGHT_CELL), Mth.floor(mz / LIGHT_CELL));
			Area l = merged.computeIfAbsent(key, k -> new Area());
			l.x += mx * area;
			l.y += my * area;
			l.z += mz * area;
			l.area += area;
			l.nx += cx;
			l.ny += cy;
			l.nz += cz;
			FloatArrayList list = members.computeIfAbsent(key, k -> new FloatArrayList());
			for (int k = 0; k < 3; k++) {
				int v = idx[t * 3 + k] * 3;
				list.add(pos[v]);
				list.add(pos[v + 1]);
				list.add(pos[v + 2]);
			}
			list.add((float) area);
		}
		List<Area> lights = new ArrayList<>(merged.size());
		for (Long2ObjectOpenHashMap.Entry<Area> e : merged.long2ObjectEntrySet()) {
			Area l = e.getValue();
			l.x /= l.area;
			l.y /= l.area;
			l.z /= l.area;
			float n = Mth.sqrt(l.nx * l.nx + l.ny * l.ny + l.nz * l.nz);
			if (n > 0.0F) {
				l.nx /= n;
				l.ny /= n;
				l.nz /= n;
			}
			// Fixed sample points: triangle picked by area quantile, point on it by a Halton sequence.
			FloatArrayList tris = members.get(e.getLongKey());
			int count = tris.size() / 10;
			l.samples = new float[AREA_SAMPLES * 3];
			double total = l.area, run = 0.0;
			int tri = 0;
			double[] cumulative = new double[count];
			for (int i = 0; i < count; i++) {
				run += tris.getFloat(i * 10 + 9);
				cumulative[i] = run;
			}
			for (int k = 0; k < AREA_SAMPLES; k++) {
				double q = (k + 0.5) / AREA_SAMPLES * total;
				while (tri < count - 1 && cumulative[tri] < q) {
					tri++;
				}
				double u = halton(k + 1, 2), v = halton(k + 1, 3);
				if (u + v > 1.0) {
					u = 1.0 - u;
					v = 1.0 - v;
				}
				int at = tri * 10;
				for (int a = 0; a < 3; a++) {
					float p0 = tris.getFloat(at + a), p1 = tris.getFloat(at + 3 + a), p2 = tris.getFloat(at + 6 + a);
					l.samples[k * 3 + a] = (float) (p0 + u * (p1 - p0) + v * (p2 - p0));
				}
			}
			lights.add(l);
		}
		return lights;
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** Work item of {@link #parallel}. */
	public interface Task {
		void run(int index, Probe probe);
	}

	private static final ForkJoinPool POOL = new ForkJoinPool(Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2)), pool -> {
		var thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
		thread.setName("echoliminal-lightbake-" + thread.getPoolIndex());
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		thread.setDaemon(true);
		return thread;
	}, null, false);

	/** Runs {@code task} for 0..count-1 on the low-priority bake pool, one probe per chunk of work; blocks until done. */
	public void parallel(int count, Task task) {
		int chunk = 128;
		int chunks = (count + chunk - 1) / chunk;
		try {
			POOL.submit(() -> IntStream.range(0, chunks).parallel().forEach(c -> {
				Probe probe = this.probe();
				for (int i = c * chunk; i < Math.min(count, (c + 1) * chunk); i++) {
					task.run(i, probe);
				}
			})).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("light bake interrupted", e);
		} catch (java.util.concurrent.ExecutionException e) {
			throw new IllegalStateException("light bake failed", e.getCause());
		}
	}
}
