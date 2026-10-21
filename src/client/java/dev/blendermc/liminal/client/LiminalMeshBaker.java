package dev.blendermc.liminal.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.blendermc.BlenderMc;
import dev.blendermc.liminal.LevelSky;
import dev.blendermc.liminal.LiminalCells;
import dev.blendermc.liminal.LiminalDay;
import dev.blendermc.liminal.LiminalLevels;
import dev.blendermc.liminal.LiminalMesh;
import dev.blendermc.liminal.light.LightChange;
import dev.blendermc.liminal.light.LightModel;
import dev.blendermc.liminal.light.LightSources;
import dev.blendermc.liminal.light.Smoothing;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

/**
 * Turns a {@link LiminalMesh} into GPU-ready bytes off the render thread: one vertex buffer in the vanilla
 * {@link DefaultVertexFormat#BLOCK} layout and int indices with one range per material and the cell of every triangle
 * (so cells without their block can be left out).
 *
 * <p>The look is baked here, not taken from Minecraft's lighting: a CPU ray tracer ({@link LightModel}) lights every
 * vertex with shadowed fluorescent light from emissive triangles, shadowed block light from the world's torches and
 * lamps, sun shadows (stored as sky light), and ambient occlusion (in the colour). Block and sky light go in UV2 and
 * through the vanilla lightmap; colours carry face shading, a slight yellow tint and the level's static sun factor.
 * Changes in the placed world re-bake only the vertices near them ({@link Baked#recompute}).
 */
final class LiminalMeshBaker {
	static final VertexFormat FORMAT = DefaultVertexFormat.BLOCK;
	static final int STRIDE = FORMAT.getVertexSize();
	static final int OFFSET_COLOR = FORMAT.getOffset(VertexFormatElement.COLOR);
	static final int OFFSET_UV0 = FORMAT.getOffset(VertexFormatElement.UV0);
	static final int OFFSET_UV2 = FORMAT.getOffset(VertexFormatElement.UV2);
	static final int OFFSET_NORMAL = FORMAT.getOffset(VertexFormatElement.NORMAL);

	/** Lightmap coordinate (light level * 16) used for emissive faces, both channels. */
	static final short FULL = 240;
	/** Slightly yellow, slightly dirty: multiplied into every non-emissive vertex colour. */
	static final float TINT_R = 1.0F, TINT_G = 0.95F, TINT_B = 0.78F;
	private static final float[] WHITE = {1.0F, 1.0F, 1.0F};

	private LiminalMeshBaker() {
	}

	/** One material's slice of the index buffer. */
	record Range(int material, int firstIndex, int indexCount, boolean decal) {
	}

	record Indices(ByteBuffer data, List<Range> ranges) {
	}

	/** New light/colour of some vertices: {@code values[i]} = r | g << 8 | b << 16 | block << 24 | sky << 32 for {@code vertices[i]}. */
	record Patch(int[] vertices, long[] values) {
	}

	/** CPU result; owns native memory until {@link #free()}d. */
	static final class Baked {
		final LiminalMesh mesh;
		final LevelSky sky;
		final LightModel model;
		final double anchorX, anchorY, anchorZ;
		ByteBuffer vertexData;
		/** All indices, grouped by material in {@link #ranges} order; never filtered. */
		final int[] indices;
		/** Per triangle of {@link #indices}: the block cell it belongs to. */
		final long[] triangleCell;
		final int vertexCount;
		final List<Range> ranges;
		/** Per vertex: the source vertex in the mesh and its material. */
		final int[] vertexSrc;
		final int[] vertexMat;
		/**
		 * A live (Blender) level: lit like Blender's viewport. No yellow tint, and the light is computed here and
		 * written into the vertex colour (lightmap coordinates full bright), so the vanilla lightmap does not warm it;
		 * Blender's lamps tint what they reach with their colour.
		 */
		boolean neutral;
		List<dev.blendermc.liminal.GlbLoader.LevelLight> lamps = List.of();
		/** The brightness option (0..1) at bake time, applied like the vanilla lightmap applies it. */
		float gamma = 0.5F;
		/** The dimension's ambient light (dimension_type ambient_light), mixed in like the lightmap does. */
		float ambient = 0.1F;

		Baked(LiminalMesh mesh, LevelSky sky, LightModel model, double ax, double ay, double az, ByteBuffer vertexData, int[] indices,
				long[] triangleCell, int vertexCount, List<Range> ranges, int[] vertexSrc, int[] vertexMat) {
			this.mesh = mesh;
			this.sky = sky;
			this.model = model;
			this.anchorX = ax;
			this.anchorY = ay;
			this.anchorZ = az;
			this.vertexData = vertexData;
			this.indices = indices;
			this.triangleCell = triangleCell;
			this.vertexCount = vertexCount;
			this.ranges = ranges;
			this.vertexSrc = vertexSrc;
			this.vertexMat = vertexMat;
		}

		void free() {
			if (this.vertexData != null) {
				MemoryUtil.memFree(this.vertexData);
				this.vertexData = null;
			}
		}

		private boolean emissive(int o) {
			int m = this.vertexMat[o];
			return m < this.mesh.materials().size() && this.mesh.materials().get(m).emissive();
		}

		/** Index bytes and ranges with only the triangles whose cell passes {@code visible}. The caller frees the buffer. */
		Indices indices(LongPredicate visible) {
			ByteBuffer out = MemoryUtil.memAlloc(Math.max(4, this.indices.length * 4)).order(ByteOrder.nativeOrder());
			List<Range> kept = new ArrayList<>(this.ranges.size());
			int written = 0;
			for (Range r : this.ranges) {
				int first = written;
				for (int i = r.firstIndex(); i < r.firstIndex() + r.indexCount(); i += 3) {
					if (!visible.test(this.triangleCell[i / 3])) {
						continue;
					}
					out.putInt(written * 4, this.indices[i]);
					out.putInt(written * 4 + 4, this.indices[i + 1]);
					out.putInt(written * 4 + 8, this.indices[i + 2]);
					written += 3;
				}
				if (written > first) {
					kept.add(new Range(r.material(), first, written - first, r.decal()));
				}
			}
			out.limit(Math.max(4, written * 4));
			return new Indices(out, kept);
		}

		/** Per vertex: direct light (block, sky, occlusion), block light after one bounce, and unit normals. */
		float[] dBlock, dSky, dAo, rBlock, normals3;
		/**
		 * Day cycle only (null otherwise): per vertex the raw and the smoothed sun visibility of every baked slice (0..255), the
		 * smoothed dome openness, the time-invariant colour (face shade, AO, tint) and block light units, whether the vertex
		 * follows the clock (not emissive), and the clock state the vertex bytes were last written for.
		 */
		byte[][] sunRaw, sunSm;
		byte[] openSm, statBlock;
		float[] statRgb;
		boolean[] variant;
		volatile LiminalDay.Day dayState;
		/** Direct block light per mesh vertex, read by bounce rays. */
		float[] directPerSource;
		Smoothing smoothing;

		/** Allocates the lighting arrays; call once before the passes. */
		void allocate() {
			int n = this.vertexCount;
			this.dBlock = new float[n];
			this.dSky = new float[n];
			this.dAo = new float[n];
			this.rBlock = new float[n];
			this.normals3 = new float[n * 3];
			float[] nor = this.mesh.normals();
			for (int o = 0; o < n; o++) {
				int v = this.vertexSrc[o];
				boolean has = nor.length > v * 3 + 2;
				float x = has ? nor[v * 3] : 0.0F, y = has ? nor[v * 3 + 1] : 1.0F, z = has ? nor[v * 3 + 2] : 0.0F;
				float len = (float) Math.sqrt(x * x + y * y + z * z);
				if (len < 1.0E-6F) {
					x = 0.0F;
					y = 1.0F;
					z = 0.0F;
					len = 1.0F;
				}
				this.normals3[o * 3] = x / len;
				this.normals3[o * 3 + 1] = y / len;
				this.normals3[o * 3 + 2] = z / len;
			}
			if (this.model.dayCycle()) {
				this.sunRaw = new byte[LiminalDay.SLICES][n];
				this.sunSm = new byte[LiminalDay.SLICES][n];
				this.openSm = new byte[n];
				this.statBlock = new byte[n];
				this.statRgb = new float[n * 3];
				this.variant = new boolean[n];
				LiminalDay.Path path = LiminalDay.path(this.sky);
				this.dayState = LiminalDay.atTime(path, (float) path.start());
			}
			this.directPerSource = new float[this.mesh.positions().length / 3];
			this.model.setDirect(this.directPerSource);
			this.smoothing = new Smoothing(n, this.indices, this.indices.length, this.normals3);
		}

		/** Pass 1: direct light of vertex {@code o}. */
		void computeDirect(LightModel.Probe probe, LightSources sources, int o) {
			if (this.emissive(o)) {
				return;
			}
			int v = this.vertexSrc[o];
			float[] pos = this.mesh.positions();
			this.model.direct(probe, sources, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2], this.normals3[o * 3], this.normals3[o * 3 + 1], this.normals3[o * 3 + 2], false, v, true);
			this.dBlock[o] = probe.block;
			this.dSky[o] = probe.sky;
			this.dAo[o] = probe.ao;
			if (this.sunRaw != null) {
				// Day cycle: dSky holds the dome openness (0..1), the sun term lives in the slices.
				this.dSky[o] = probe.open;
				for (int k = 0; k < LiminalDay.SLICES; k++) {
					this.sunRaw[k][o] = (byte) Math.round(probe.slices[k] * 255.0F);
				}
			}
			this.directPerSource[v] = probe.block;
		}

		/** Pass 2: adds one bounce of indirect light to the direct block light of vertex {@code o}. */
		void computeRaw(LightModel.Probe probe, int o) {
			if (this.emissive(o)) {
				return;
			}
			int v = this.vertexSrc[o];
			float[] pos = this.mesh.positions();
			float bounce = this.model.bounce(probe, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2], this.normals3[o * 3], this.normals3[o * 3 + 1], this.normals3[o * 3 + 2], false);
			this.rBlock[o] = LightModel.combine(this.dBlock[o], bounce);
		}

		/**
		 * Pass 3: the final light of vertex {@code o}, smoothed over its neighbours of the same surface, packed like
		 * {@link Patch#values()}. Emissive vertices are always full bright.
		 */
		long finish(int o) {
			LiminalMesh.Material mat = this.vertexMat[o] < this.mesh.materials().size() ? this.mesh.materials().get(this.vertexMat[o]) : null;
			float[] base = baseColor(mat);
			if (mat != null && mat.emissive()) {
				return pack(unitInt(base[0]), unitInt(base[1]), unitInt(base[2]), FULL, FULL);
			}
			if (this.sky.unlit()) {
				return pack(unitInt(base[0]), unitInt(base[1]), unitInt(base[2]), FULL, FULL);
			}
			float nx = this.normals3[o * 3], ny = this.normals3[o * 3 + 1], nz = this.normals3[o * 3 + 2];
			float block = this.smoothing.smooth(this.rBlock, o), skyLevel = this.smoothing.smooth(this.dSky, o), ao = this.smoothing.smooth(this.dAo, o);
			int blockUnits = Mth.clamp(Math.round(block * 16.0F), 0, 240);
			if (this.sunRaw != null) {
				// Day cycle: keep the time-invariant parts; the sun and sky terms are combined for the clock in timePack.
				for (int k = 0; k < LiminalDay.SLICES; k++) {
					this.sunSm[k][o] = (byte) Math.round(this.smoothing.smoothUnsigned(this.sunRaw[k], o));
				}
				this.openSm[o] = (byte) Math.round(skyLevel * 255.0F);
				float fixed = shade(nx, ny, nz) * ao;
				float tr = this.neutral ? 1.0F : TINT_R, tg = this.neutral ? 1.0F : TINT_G, tb = this.neutral ? 1.0F : TINT_B;
				this.statRgb[o * 3] = base[0] * fixed * tr;
				this.statRgb[o * 3 + 1] = base[1] * fixed * tg;
				this.statRgb[o * 3 + 2] = base[2] * fixed * tb;
				this.statBlock[o] = (byte) blockUnits;
				this.variant[o] = true;
				return this.timePack(o, this.dayState);
			}
			float shade = shade(nx, ny, nz) * this.sky.sunShade(nx, ny, nz) * ao;
			int skyUnits = this.sky.skylight() ? Mth.clamp(Math.round(skyLevel * 16.0F), 0, 240) : 0;
			if (this.neutral) {
				float light = this.brightness(block, this.sky.skylight() ? skyLevel : 0.0F);
				float[] tint = this.lampTint(this.vertexSrc[o]);
				float k = shade * light;
				return pack(unitInt(base[0] * k * tint[0]), unitInt(base[1] * k * tint[1]), unitInt(base[2] * k * tint[2]), FULL, FULL);
			}
			return pack(unitInt(base[0] * shade * TINT_R), unitInt(base[1] * shade * TINT_G), unitInt(base[2] * shade * TINT_B), blockUnits, skyUnits);
		}

		/**
		 * Block and sky light levels (0..15) to brightness exactly like the vanilla lightmap shader (lightmap.fsh: the
		 * 1.5 block factor, the dimension's ambient light, the brightness option, the 4% grey), minus its "cubic
		 * nonsense" that turns block light yellow: every channel gets the same value.
		 */
		float brightness(float blockLevel, float skyLevel) {
			float block = curve(blockLevel) * 1.5F;
			float c = Mth.lerp(this.ambient, block, 1.0F) + curve(skyLevel);
			c = Mth.clamp(c, 0.0F, 1.0F);
			float inverted = 1.0F - c;
			c = Mth.lerp(this.gamma, c, 1.0F - inverted * inverted * inverted * inverted);
			return Mth.lerp(0.04F, c, 0.75F);
		}

		private static float curve(float level) {
			float f = Mth.clamp(level / 15.0F, 0.0F, 1.0F);
			return f / (4.0F - 3.0F * f);
		}

		/**
		 * The colour Blender's lamps give mesh vertex {@code v}: each lamp weighs by how far its light reaches (a level-L
		 * lamp reaches L blocks), mixed with white for the light that comes from elsewhere (panels, sky).
		 */
		float[] lampTint(int v) {
			if (this.lamps.isEmpty()) {
				return WHITE;
			}
			float[] pos = this.mesh.positions();
			double x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2];
			float r = 0.25F, g = 0.25F, b = 0.25F, w = 0.25F;
			for (dev.blendermc.liminal.GlbLoader.LevelLight lamp : this.lamps) {
				double d = lamp.position().distanceTo(new net.minecraft.world.phys.Vec3(x, y, z));
				float weight = (float) Math.max(0.0, 1.0 - d / (lamp.level() + 0.5));
				r += weight * lamp.red();
				g += weight * lamp.green();
				b += weight * lamp.blue();
				w += weight;
			}
			// Normalised so the brightest channel stays 1: the lamp colours the light, the bake decides how much there is.
			float max = Math.max(r, Math.max(g, b));
			return max <= 0.0F ? WHITE : new float[] {r / max, g / max, b / max};
		}

		/** Final bytes of a day-cycle vertex for the clock state {@code st}: interpolated slice sun, dome, day factor, sun or moon shade. */
		long timePack(int o, LiminalDay.Day st) {
			int n = LiminalDay.SLICES;
			float u = st.sliceU;
			int i0 = Math.min(n - 1, (int) u), i1 = Math.min(n - 1, i0 + 1);
			float f = u - i0;
			float sunPart = ((this.sunSm[i0][o] & 0xFF) * (1.0F - f) + (this.sunSm[i1][o] & 0xFF) * f) / 255.0F;
			float level = LiminalDay.skyLevel(sunPart, (this.openSm[o] & 0xFF) / 255.0F, st.day);
			float shade = st.shade(this.normals3[o * 3], this.normals3[o * 3 + 1], this.normals3[o * 3 + 2]);
			return pack(unitInt(this.statRgb[o * 3] * shade), unitInt(this.statRgb[o * 3 + 1] * shade), unitInt(this.statRgb[o * 3 + 2] * shade),
				this.statBlock[o] & 0xFF, Mth.clamp(Math.round(level * 16.0F), 0, 240));
		}

		/**
		 * Day cycle: rewrites the vertices {@code from..to-1} of the CPU copy for the clock state {@code st}. The caller uploads that range.
		 */
		void cycle(int from, int to, LiminalDay.Day st) {
			for (int o = from; o < to; o++) {
				if (this.variant[o]) {
					this.write(o, this.timePack(o, st));
				}
			}
		}

		/**
		 * Re-lights the vertices that any of {@code changes} can reach, against {@code sources}: direct light there,
		 * bounce light within bounce range of them, smoothing one ring further. Reads only the CPU vertex copy; the
		 * result is applied on the render thread ({@link #apply}). Only vertices whose bytes differ are returned.
		 */
		Patch recompute(LightSources sources, List<LightChange> changes) {
			if (this.sky.unlit()) {
				return new Patch(new int[0], new long[0]);
			}
			float[] pos = this.mesh.positions();
			int n = this.vertexCount;
			boolean[] direct = new boolean[n], gather = new boolean[n], finalize = new boolean[n];
			List<LightChange> wide = new ArrayList<>(changes.size());
			for (LightChange c : changes) {
				wide.add(new LightChange(c.x(), c.y(), c.z(), c.radius() + (float) LightModel.BOUNCE_RANGE, false));
			}
			IntArrayList directList = new IntArrayList(), gatherList = new IntArrayList();
			for (int o = 0; o < n; o++) {
				if (this.emissive(o)) {
					continue;
				}
				int v = this.vertexSrc[o] * 3;
				for (LightChange c : changes) {
					if (c.affects(this.model, pos[v], pos[v + 1], pos[v + 2])) {
						direct[o] = true;
						break;
					}
				}
				if (direct[o]) {
					directList.add(o);
				}
				boolean g = direct[o];
				if (!g && this.model.hasBounce()) {
					for (LightChange c : wide) {
						if (c.affects(this.model, pos[v], pos[v + 1], pos[v + 2])) {
							g = true;
							break;
						}
					}
				}
				if (g) {
					gather[o] = true;
					gatherList.add(o);
				}
			}
			this.model.parallel(directList.size(), (i, probe) -> this.computeDirect(probe, sources, directList.getInt(i)));
			this.model.parallel(gatherList.size(), (i, probe) -> this.computeRaw(probe, gatherList.getInt(i)));
			IntArrayList finalList = new IntArrayList();
			for (int i = 0; i < gatherList.size(); i++) {
				int o = gatherList.getInt(i);
				if (!finalize[o]) {
					finalize[o] = true;
					finalList.add(o);
				}
				this.smoothing.forEachNeighbour(o, j -> {
					if (!finalize[j]) {
						finalize[j] = true;
						finalList.add(j);
					}
				});
			}
			long[] values = new long[finalList.size()];
			this.model.parallel(finalList.size(), (i, probe) -> values[i] = this.finish(finalList.getInt(i)));
			IntArrayList keepV = new IntArrayList();
			LongArrayList keepL = new LongArrayList();
			for (int i = 0; i < values.length; i++) {
				if (values[i] != this.current(finalList.getInt(i))) {
					keepV.add(finalList.getInt(i));
					keepL.add(values[i]);
				}
			}
			// Runs are uploaded in ascending vertex order.
			int[] vs = keepV.toIntArray();
			long[] ls = keepL.toLongArray();
			Integer[] order = new Integer[vs.length];
			for (int i = 0; i < order.length; i++) {
				order[i] = i;
			}
			java.util.Arrays.sort(order, java.util.Comparator.comparingInt(i -> vs[i]));
			int[] sortedV = new int[vs.length];
			long[] sortedL = new long[vs.length];
			for (int i = 0; i < order.length; i++) {
				sortedV[i] = vs[order[i]];
				sortedL[i] = ls[order[i]];
			}
			return new Patch(sortedV, sortedL);
		}


		private long current(int o) {
			int at = o * STRIDE;
			ByteBuffer d = this.vertexData;
			return pack(d.get(at + OFFSET_COLOR) & 0xFF, d.get(at + OFFSET_COLOR + 1) & 0xFF, d.get(at + OFFSET_COLOR + 2) & 0xFF,
				d.getShort(at + OFFSET_UV2) & 0xFFFF, d.getShort(at + OFFSET_UV2 + 2) & 0xFFFF);
		}

		/** Writes a patch into the CPU copy of the vertices. */
		void apply(Patch patch, int from, int to) {
			for (int i = from; i < to; i++) {
				this.write(patch.vertices()[i], patch.values()[i]);
			}
		}

		private void write(int o, long value) {
			int at = o * STRIDE;
			this.vertexData.put(at + OFFSET_COLOR, (byte) value);
			this.vertexData.put(at + OFFSET_COLOR + 1, (byte) (value >> 8));
			this.vertexData.put(at + OFFSET_COLOR + 2, (byte) (value >> 16));
			this.vertexData.putShort(at + OFFSET_UV2, (short) (value >> 24 & 0xFF));
			this.vertexData.putShort(at + OFFSET_UV2 + 2, (short) (value >> 32 & 0xFF));
		}
	}

	static long pack(int r, int g, int b, int block, int sky) {
		return (long) r | (long) g << 8 | (long) b << 16 | (long) block << 24 | (long) sky << 32;
	}

	private static float[] baseColor(LiminalMesh.Material mat) {
		return mat != null && mat.baseColor() != null && mat.baseColor().length >= 4 ? mat.baseColor() : new float[] {1, 1, 1, 1};
	}

	static Baked bake(LiminalMesh mesh, LightSources sources) {
		long started = System.nanoTime();
		LiminalCells levelCells = mesh.name() == null ? null : LiminalLevels.cells(mesh.name());
		LiminalCells owners = levelCells != null && levelCells.mesh() == mesh ? levelCells : new LiminalCells(mesh);
		LevelSky sky = mesh.name() == null ? LevelSky.NONE : LiminalLevels.sky(mesh.name());
		if (sky == null) {
			sky = LevelSky.NONE;
		}
		float[] pos = mesh.positions();
		float[] nor = mesh.normals();
		float[] uvs = mesh.uvs();
		int[] idx = mesh.indices();
		int[] triMat = mesh.triangleMaterial();
		List<LiminalMesh.Material> materials = mesh.materials();
		int triCount = idx.length / 3;
		int matCount = Math.max(1, materials.size());
		int srcVerts = pos.length / 3;
		double ax = Math.floor(mesh.bounds().minX), ay = Math.floor(mesh.bounds().minY), az = Math.floor(mesh.bounds().minZ);

		int[] perMat = new int[matCount];
		for (int t = 0; t < triCount; t++) {
			perMat[material(triMat, t, matCount)]++;
		}
		int[][] trisOf = new int[matCount][];
		for (int m = 0; m < matCount; m++) {
			trisOf[m] = new int[perMat[m]];
		}
		int[] fill = new int[matCount];
		for (int t = 0; t < triCount; t++) {
			int m = material(triMat, t, matCount);
			trisOf[m][fill[m]++] = t;
		}

		// Re-index per material (a vertex used by two materials is duplicated, since colour is per material).
		int[] stamp = new int[srcVerts];
		int[] remap = new int[srcVerts];
		int[] outSrc = new int[Math.max(16, srcVerts)];
		int[] outMat = new int[outSrc.length];
		int outCount = 0;
		int[] indexData = new int[idx.length];
		long[] triangleCell = new long[triCount];
		long[] cellOfTri = new long[triCount];
		for (int t = 0; t < triCount; t++) {
			cellOfTri[t] = owners.cellOf(t);
		}
List<Range> ranges = new ArrayList<>();
		int written = 0;
		// Surfaces first, then decals (non-colliding materials such as stains), which are drawn with a depth bias.
		Integer[] order = new Integer[matCount];
		for (int m = 0; m < matCount; m++) {
			order[m] = m;
		}
		java.util.Arrays.sort(order, java.util.Comparator.comparing(m -> isDecal(materials, m)));
		for (int m : order) {
			if (trisOf[m].length == 0) {
				continue;
			}
			int first = written;
			for (int t : trisOf[m]) {
				triangleCell[written / 3] = owners.cellOf(t);
				for (int k = 0; k < 3; k++) {
					int v = idx[t * 3 + k];
					if (stamp[v] != m + 1) {
						stamp[v] = m + 1;
						if (outCount == outSrc.length) {
							outSrc = java.util.Arrays.copyOf(outSrc, outCount * 2);
							outMat = java.util.Arrays.copyOf(outMat, outCount * 2);
						}
						outSrc[outCount] = v;
						outMat[outCount] = m;
						remap[v] = outCount++;
					}
					indexData[written++] = remap[v];
				}
			}
			ranges.add(new Range(m, first, written - first, isDecal(materials, m)));
		}

		long modelStart = System.nanoTime();
		LightModel model = new LightModel(mesh, sky, cellOfTri);
		long modelDone = System.nanoTime();

		ByteBuffer vertexData = MemoryUtil.memAlloc(Math.max(STRIDE, outCount * STRIDE)).order(ByteOrder.nativeOrder());
		int[] vertexSrc = java.util.Arrays.copyOf(outSrc, outCount);
		int[] vertexMat = java.util.Arrays.copyOf(outMat, outCount);
		Baked baked = new Baked(mesh, sky, model, ax, ay, az, vertexData, java.util.Arrays.copyOf(indexData, written), triangleCell, outCount, ranges,
			vertexSrc, vertexMat);
		if (mesh.name() != null && dev.blendermc.live.LiveScene.isLive(mesh.name())) {
			baked.neutral = true;
			baked.lamps = dev.blendermc.live.LiveScene.lights(mesh.name());
			try {
				baked.gamma = net.minecraft.client.Minecraft.getInstance().options.gamma().get().floatValue();
				if (net.minecraft.client.Minecraft.getInstance().level != null) {
					baked.ambient = net.minecraft.client.Minecraft.getInstance().level.dimensionType().ambientLight();
				}
			} catch (RuntimeException ignored) {
			}
		}
		int total = outCount;
		try {
			baked.allocate();
			boolean unlit = sky.unlit();
			if (unlit) {
				BlenderMc.LOGGER.info("Liminal level {} is unlit: skipping the lighting bake, textures drawn full bright", mesh.name());
			} else {
				model.parallel(total, (o, probe) -> baked.computeDirect(probe, sources, o));
			}
			long directDone = System.nanoTime();
			if (!unlit) {
				model.parallel(total, (o, probe) -> baked.computeRaw(probe, o));
			}
			long bounceDone = System.nanoTime();
			model.parallel(total, (o, probe) -> {
				int v = vertexSrc[o];
				LiminalMesh.Material mat = vertexMat[o] < materials.size() ? materials.get(vertexMat[o]) : null;
				float x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2];
				float u = uvs.length > v * 2 + 1 ? uvs[v * 2] : 0.0F;
				float w = uvs.length > v * 2 + 1 ? uvs[v * 2 + 1] : 0.0F;
				long lit = baked.finish(o);
				int at = o * STRIDE;
				vertexData.putFloat(at, (float) (x - ax));
				vertexData.putFloat(at + 4, (float) (y - ay));
				vertexData.putFloat(at + 8, (float) (z - az));
				vertexData.put(at + OFFSET_COLOR, (byte) lit);
				vertexData.put(at + OFFSET_COLOR + 1, (byte) (lit >> 8));
				vertexData.put(at + OFFSET_COLOR + 2, (byte) (lit >> 16));
				vertexData.put(at + OFFSET_COLOR + 3, unit(baseColor(mat)[3]));
				vertexData.putFloat(at + OFFSET_UV0, u);
				vertexData.putFloat(at + OFFSET_UV0 + 4, w);
				vertexData.putShort(at + OFFSET_UV2, (short) (lit >> 24 & 0xFF));
				vertexData.putShort(at + OFFSET_UV2 + 2, (short) (lit >> 32 & 0xFF));
				vertexData.put(at + OFFSET_NORMAL, normalByte(baked.normals3[o * 3]));
				vertexData.put(at + OFFSET_NORMAL + 1, normalByte(baked.normals3[o * 3 + 1]));
				vertexData.put(at + OFFSET_NORMAL + 2, normalByte(baked.normals3[o * 3 + 2]));
				vertexData.put(at + OFFSET_NORMAL + 3, (byte) 0);
			});
			BlenderMc.LOGGER.debug("Liminal lighting passes: direct {} ms, bounce {} ms, smoothing+write {} ms", (directDone - modelDone) / 1_000_000,
				(bounceDone - directDone) / 1_000_000, (System.nanoTime() - bounceDone) / 1_000_000);
		} catch (RuntimeException e) {
			baked.free();
			throw e;
		}
		vertexData.limit(Math.max(STRIDE, outCount * STRIDE));
		long done = System.nanoTime();
		BlenderMc.LOGGER.info("Liminal lighting of {} baked: {} vertices, {} occluding triangles (BVH {} nodes, built in {} ms), {} placed light sources, "
				+ "{} ms total ({} ms lighting)", mesh.name(), outCount, model.scene.triangleCount(), model.scene.nodeCount(), (modelDone - modelStart) / 1_000_000,
			sources.lightCount(), (done - started) / 1_000_000, (done - modelDone) / 1_000_000);
		return baked;
	}

	private static boolean isDecal(List<LiminalMesh.Material> materials, int m) {
		return m < materials.size() && !materials.get(m).collides() && !materials.get(m).emissive();
	}

	private static int material(int[] triMat, int t, int matCount) {
		int m = t < triMat.length ? triMat[t] : 0;
		return m >= 0 && m < matCount ? m : 0;
	}

	/** Vanilla-like face shading for any normal: top 1.0, bottom 0.5, north/south 0.8, east/west 0.6. */
	static float shade(float nx, float ny, float nz) {
		float len = nx * nx + ny * ny + nz * nz;
		if (len < 1.0E-6F) {
			return 1.0F;
		}
		float x2 = nx * nx / len, y2 = ny * ny / len, z2 = nz * nz / len;
		return x2 * 0.6F + z2 * 0.8F + y2 * (ny > 0.0F ? 1.0F : 0.5F);
	}

	static byte unit(float f) {
		return (byte) unitInt(f);
	}

	static int unitInt(float f) {
		return Mth.clamp(Math.round(f * 255.0F), 0, 255);
	}

	private static byte normalByte(float f) {
		return (byte) ((int) (Mth.clamp(f, -1.0F, 1.0F) * 127.0F) & 0xFF);
	}
}
