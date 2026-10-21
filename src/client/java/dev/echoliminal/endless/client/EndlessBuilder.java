package dev.echoliminal.endless.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.echoliminal.liminal.LiminalMesh;
import dev.echoliminal.liminal.client.EndlessBakeBridge;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * Worker thread side: turns a {@link Tile} into GPU-ready bytes. Level of detail 0 is lit by the ray tracer of the hand
 * made levels (through {@link EndlessBakeBridge}), indexed and with the block cell of every triangle so broken cells can
 * be hidden. Coarser levels get the cheap zone lighting (face shade times a zone tint, emissive full bright) and are
 * plain triangle lists, sorted by material, with the flat average colour of the material in place of its texture.
 */
final class EndlessBuilder {
	static final VertexFormat FORMAT = DefaultVertexFormat.BLOCK;
	static final int STRIDE = FORMAT.getVertexSize();
	static final int OFF_COLOR = FORMAT.getOffset(VertexFormatElement.COLOR);
	static final int OFF_UV0 = FORMAT.getOffset(VertexFormatElement.UV0);
	static final int OFF_UV2 = FORMAT.getOffset(VertexFormatElement.UV2);
	static final int OFF_NORMAL = FORMAT.getOffset(VertexFormatElement.NORMAL);
	private static final short FULL = 240;

	private EndlessBuilder() {
	}

	/** One material's slice: indices (near) or vertices (far) {@code first..first+count}. */
	record Range(int material, int first, int count, int pass) {
	}

	/** Bytes and metadata of one tile; owns native memory until {@link #free()}. */
	static final class Built {
		final Tile tile;
		ByteBuffer vertices;
		int vertexCount;
		/** Null for non-indexed (far) tiles. */
		int[] indices;
		/** Per triangle of {@link #indices}: the block cell (near only). */
		long[] triangleCell;
		List<Range> ranges = List.of();
		double anchorX, anchorY, anchorZ;
		double minX, minY, minZ, maxX, maxY, maxZ;
		int triangles;
		long millis;

		Built(Tile tile) {
			this.tile = tile;
		}

		void free() {
			if (this.vertices != null) {
				MemoryUtil.memFree(this.vertices);
				this.vertices = null;
			}
		}
	}

	static Built build(EndlessSource src, EndlessMaterials mats, Tile tile) {
		long started = System.nanoTime();
		Built built = tile.lod() == 0 ? near(src, mats, tile) : far(src, mats, tile);
		built.millis = (System.nanoTime() - started) / 1_000_000;
		return built;
	}

	private static int material(int[] mat, int t, int count) {
		int m = t < mat.length ? mat[t] : 0;
		return m >= 0 && m < count ? m : 0;
	}

	private static void emptyBounds(Built out, Tile tile) {
		out.minX = out.maxX = tile.minX();
		out.minY = out.maxY = 0;
		out.minZ = out.maxZ = tile.minZ();
	}

	private static Built far(EndlessSource src, EndlessMaterials mats, Tile tile) {
		Built out = new Built(tile);
		int s = tile.size();
		List<EndlessMesh> meshes = new ArrayList<>(s * s);
		for (int rz = tile.tz() * s; rz < (tile.tz() + 1) * s; rz++) {
			for (int rx = tile.tx() * s; rx < (tile.tx() + 1) * s; rx++) {
				EndlessMesh m = src.region(rx, rz, tile.lod());
				if (m != null && m.triangles() > 0) {
					meshes.add(m);
				}
			}
		}
		int n = mats.count();
		int[] per = new int[n];
		int total = 0;
		for (EndlessMesh m : meshes) {
			for (int t = 0; t < m.triangles(); t++) {
				per[material(m.mat(), t, n)]++;
				total++;
			}
		}
		out.triangles = total;
		out.anchorX = tile.minX();
		out.anchorY = 0;
		out.anchorZ = tile.minZ();
		if (total == 0) {
			emptyBounds(out, tile);
			return out;
		}
		out.minX = out.minY = out.minZ = Double.MAX_VALUE;
		out.maxX = out.maxY = out.maxZ = -Double.MAX_VALUE;
		int[] fill = new int[n];
		List<Range> ranges = new ArrayList<>();
		int at = 0;
		for (int m = 0; m < n; m++) {
			if (per[m] > 0) {
				ranges.add(new Range(m, at, per[m] * 3, mats.pass[m]));
			}
			fill[m] = at;
			at += per[m] * 3;
		}
		ByteBuffer buf = MemoryUtil.memAlloc(total * 3 * STRIDE).order(ByteOrder.nativeOrder());
		try {
			for (EndlessMesh m : meshes) {
				float[] p = m.pos(), nr = m.nor(), uv = m.uv();
				int[] idx = m.idx();
				for (int t = 0; t < m.triangles(); t++) {
					int mat = material(m.mat(), t, n);
					int i0 = idx[t * 3], i1 = idx[t * 3 + 1], i2 = idx[t * 3 + 2];
					boolean hasNormals = nr.length > Math.max(i0, Math.max(i1, i2)) * 3 + 2;
					float nx = hasNormals ? nr[i0 * 3] + nr[i1 * 3] + nr[i2 * 3] : 0;
					float ny = hasNormals ? nr[i0 * 3 + 1] + nr[i1 * 3 + 1] + nr[i2 * 3 + 1] : 1;
					float nz = hasNormals ? nr[i0 * 3 + 2] + nr[i1 * 3 + 2] + nr[i2 * 3 + 2] : 0;
					float cx = (p[i0 * 3] + p[i1 * 3] + p[i2 * 3]) / 3, cz = (p[i0 * 3 + 2] + p[i1 * 3 + 2] + p[i2 * 3 + 2]) / 3;
					float[] look = EndlessZones.look(src.zone(Mth.floor(cx), Mth.floor(cz)));
					float[] flat = mats.flat[mat];
					boolean emissive = mats.emissive[mat];
					float r, g, b, shade = shade(nx, ny, nz);
					short block, sky;
					if (emissive) {
						float peak = Math.max(0.001F, Math.max(flat[0], Math.max(flat[1], flat[2])));
						r = flat[0] / peak;
						g = flat[1] / peak;
						b = flat[2] / peak;
						block = FULL;
						sky = FULL;
					} else {
						r = flat[0] * shade * look[0];
						g = flat[1] * shade * look[1];
						b = flat[2] * shade * look[2];
						block = (short) look[3];
						sky = (short) look[4];
					}
					int first = fill[mat];
					fill[mat] += 3;
					for (int k = 0; k < 3; k++) {
						int v = idx[t * 3 + k];
						float x = p[v * 3], y = p[v * 3 + 1], z = p[v * 3 + 2];
						out.minX = Math.min(out.minX, x);
						out.minY = Math.min(out.minY, y);
						out.minZ = Math.min(out.minZ, z);
						out.maxX = Math.max(out.maxX, x);
						out.maxY = Math.max(out.maxY, y);
						out.maxZ = Math.max(out.maxZ, z);
						put(buf, (first + k) * STRIDE, (float) (x - out.anchorX), (float) (y - out.anchorY), (float) (z - out.anchorZ), r, g, b, flat[3],
							uv.length > v * 2 + 1 ? uv[v * 2] : 0, uv.length > v * 2 + 1 ? uv[v * 2 + 1] : 0, block, sky, nx, ny, nz);
					}
				}
			}
		} catch (RuntimeException e) {
			MemoryUtil.memFree(buf);
			throw e;
		}
		buf.limit(total * 3 * STRIDE);
		out.vertices = buf;
		out.vertexCount = total * 3;
		out.ranges = ranges;
		return out;
	}

	private static Built near(EndlessSource src, EndlessMaterials mats, Tile tile) {
		Built out = new Built(tile);
		EndlessMesh m = src.region(tile.tx(), tile.tz(), 0);
		if (m == null || m.triangles() == 0) {
			emptyBounds(out, tile);
			return out;
		}
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		float[] p = m.pos();
		for (int i = 0; i + 2 < p.length; i += 3) {
			minX = Math.min(minX, p[i]);
			minY = Math.min(minY, p[i + 1]);
			minZ = Math.min(minZ, p[i + 2]);
			maxX = Math.max(maxX, p[i]);
			maxY = Math.max(maxY, p[i + 1]);
			maxZ = Math.max(maxZ, p[i + 2]);
		}
		LiminalMesh mesh = new LiminalMesh(null, m.pos(), m.nor(), m.uv(), m.idx(), m.mat(), mats.list, Vec3.ZERO, 0.0F,
			new AABB(minX, minY, minZ, maxX, maxY, maxZ));
		EndlessBakeBridge.Result r = EndlessBakeBridge.bake(mesh);
		out.vertices = r.vertexData();
		out.vertexCount = r.vertexCount();
		out.indices = r.indices();
		out.triangleCell = r.triangleCell();
		out.triangles = r.indices().length / 3;
		out.anchorX = r.anchorX();
		out.anchorY = r.anchorY();
		out.anchorZ = r.anchorZ();
		out.minX = minX;
		out.minY = minY;
		out.minZ = minZ;
		out.maxX = maxX;
		out.maxY = maxY;
		out.maxZ = maxZ;
		List<Range> ranges = new ArrayList<>(r.ranges().length);
		for (int[] rg : r.ranges()) {
			ranges.add(new Range(rg[0], rg[1], rg[2], mats.pass[Math.min(rg[0], mats.count() - 1)]));
		}
		out.ranges = ranges;
		// Open field: the bake has no sun, so outdoor columns get full sky light and lose the indoor tint.
		ByteBuffer buf = out.vertices;
		for (int v = 0; v < out.vertexCount; v++) {
			int at = v * STRIDE;
			double x = buf.getFloat(at) + out.anchorX, z = buf.getFloat(at + 8) + out.anchorZ;
			if (src.zone(Mth.floor(x), Mth.floor(z)) == EndlessSource.ZONE_FIELD) {
				int g = Math.min(255, Math.round((buf.get(at + OFF_COLOR + 1) & 0xFF) / 0.95F));
				int b = Math.min(255, Math.round((buf.get(at + OFF_COLOR + 2) & 0xFF) / 0.78F));
				buf.put(at + OFF_COLOR + 1, (byte) g);
				buf.put(at + OFF_COLOR + 2, (byte) b);
				buf.putShort(at + OFF_UV2 + 2, (short) Math.max(buf.getShort(at + OFF_UV2 + 2), 235));
			} else {
				// Keep the indoor levels bright: a floor of block light, so deep storeys and pits never go black.
				buf.putShort(at + OFF_UV2, (short) Math.max(buf.getShort(at + OFF_UV2), 165));
			}
		}
		return out;
	}

	/** Vanilla-like face shading: top 1.0, bottom 0.5, north/south 0.8, east/west 0.6. */
	static float shade(float nx, float ny, float nz) {
		float len = nx * nx + ny * ny + nz * nz;
		if (len < 1.0E-6F) {
			return 1.0F;
		}
		return (nx * nx * 0.6F + nz * nz * 0.8F + ny * ny * (ny > 0.0F ? 1.0F : 0.5F)) / len;
	}

	private static byte unit(float f) {
		return (byte) Mth.clamp(Math.round(f * 255.0F), 0, 255);
	}

	private static byte normalByte(float f) {
		return (byte) ((int) (Mth.clamp(f, -1.0F, 1.0F) * 127.0F) & 0xFF);
	}

	private static void put(ByteBuffer buf, int at, float x, float y, float z, float r, float g, float b, float a, float u, float v, short block, short sky,
			float nx, float ny, float nz) {
		buf.putFloat(at, x);
		buf.putFloat(at + 4, y);
		buf.putFloat(at + 8, z);
		buf.put(at + OFF_COLOR, unit(r));
		buf.put(at + OFF_COLOR + 1, unit(g));
		buf.put(at + OFF_COLOR + 2, unit(b));
		buf.put(at + OFF_COLOR + 3, unit(a));
		buf.putFloat(at + OFF_UV0, u);
		buf.putFloat(at + OFF_UV0 + 4, v);
		buf.putShort(at + OFF_UV2, block);
		buf.putShort(at + OFF_UV2 + 2, sky);
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1.0E-6F) {
			nx = 0;
			ny = 1;
			nz = 0;
			len = 1;
		}
		buf.put(at + OFF_NORMAL, normalByte(nx / len));
		buf.put(at + OFF_NORMAL + 1, normalByte(ny / len));
		buf.put(at + OFF_NORMAL + 2, normalByte(nz / len));
		buf.put(at + OFF_NORMAL + 3, (byte) 0);
	}
}
