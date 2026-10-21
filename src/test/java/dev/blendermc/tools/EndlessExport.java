package dev.blendermc.tools;

import dev.blendermc.endless.gen.CellTris;
import dev.blendermc.endless.gen.EndlessGenerator;
import dev.blendermc.endless.gen.Mats;
import dev.blendermc.endless.gen.Region;
import dev.blendermc.endless.gen.Zone;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev tool: writes the generated endless world around a region as a .glb (open it in Blender) and prints timings.
 * Args: seed region(x,z) radius lod out.glb [bench]. The model is moved so the centre of the area sits at the origin.
 * Pure Java, no Minecraft classes.
 */
public final class EndlessExport {
	private EndlessExport() {
	}

	public static void main(String[] args) throws IOException {
		long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
		String[] rc = (args.length > 1 ? args[1] : "0,0").split(",");
		int rx = Integer.parseInt(rc[0].trim()), rz = Integer.parseInt(rc[1].trim());
		int radius = args.length > 2 ? Integer.parseInt(args[2]) : 0;
		int lod = args.length > 3 ? Integer.parseInt(args[3]) : 0;
		Path out = Path.of(args.length > 4 ? args[4] : ".tmp/endless.glb");
		boolean bench = args.length > 5 && args[5].equals("bench");
		EndlessGenerator g = new EndlessGenerator(seed);
		System.out.println("seed " + seed + " spawn " + java.util.Arrays.toString(g.spawn()));
		zoneCensus(g, rx * 64, rz * 64, (radius + 1) * 64);
		if (bench) {
			bench(g, rx, rz);
		}
		List<Region> regions = new ArrayList<>();
		long start = System.nanoTime();
		for (int z = rz - radius; z <= rz + radius; z++) {
			for (int x = rx - radius; x <= rx + radius; x++) {
				regions.add(g.generate(x, z, lod));
			}
		}
		long total = System.nanoTime() - start;
		long tris = 0;
		for (Region r : regions) {
			tris += r.triangleCount();
		}
		System.out.printf("lod %d: %d regions, %d triangles, %.1f ms total (%.1f ms/region)%n", lod, regions.size(), tris, total / 1e6, total / 1e6 / regions.size());
		double cx = (rx * 64 + 32), cz = (rz * 64 + 32);
		Files.createDirectories(out.toAbsolutePath().getParent());
		Files.write(out, glb(regions, cx, cz));
		System.out.println("wrote " + out.toAbsolutePath() + " (" + Files.size(out) / 1024 + " KiB), area centred at world (" + cx + ", " + cz + ")");
	}

	private static void zoneCensus(EndlessGenerator g, int x, int z, int half) {
		int[] count = new int[Zone.values().length];
		int total = 0;
		for (int dz = -half; dz < half; dz += 8) {
			for (int dx = -half; dx < half; dx += 8) {
				count[g.zoneAt(x + 32 + dx, z + 32 + dz).ordinal()]++;
				total++;
			}
		}
		StringBuilder sb = new StringBuilder("zones in the exported area:");
		for (Zone zone : Zone.values()) {
			sb.append(String.format(" %s %.0f%%", zone, 100.0 * count[zone.ordinal()] / total));
		}
		System.out.println(sb);
	}

	/** Generation time per LOD over a spread of regions (JIT warm), triangles, and the single-cell call. */
	private static void bench(EndlessGenerator g, int rx, int rz) {
		for (int lod = 0; lod < EndlessGenerator.LODS; lod++) {
			for (int round = 0; round < 4; round++) {
				long t0 = System.nanoTime();
				long tris = 0;
				int n = 0;
				for (int dz = -3; dz <= 3; dz += 2) {
					for (int dx = -3; dx <= 3; dx += 2) {
						tris += g.generate(rx + dx, rz + dz, lod).triangleCount();
						n++;
					}
				}
				if (round == 3) {
					System.out.printf("bench lod %d: %.2f ms/region, %d triangles/region (%d regions)%n", lod, (System.nanoTime() - t0) / 1e6 / n, tris / n, n);
				}
			}
		}
		long t0 = System.nanoTime();
		int tris = 0;
		for (int i = 0; i < 4096; i++) {
			CellTris c = g.cellTriangles(rx * 64 + i % 64, 64, rz * 64 + i / 64);
			tris += c.triangleCount();
		}
		System.out.printf("bench cellTriangles: %.1f us/cell (%d triangles in 4096 cells at y=64)%n", (System.nanoTime() - t0) / 1e3 / 4096, tris);
	}

	// ---- glb ---------------------------------------------------------------------------------------------------

	private static byte[] glb(List<Region> regions, double cx, double cz) throws IOException {
		int materials = Mats.ALL.size();
		List<float[]>[] pos = new List[materials], nrm = new List[materials], uv = new List[materials];
		for (int m = 0; m < materials; m++) {
			pos[m] = new ArrayList<>();
			nrm[m] = new ArrayList<>();
			uv[m] = new ArrayList<>();
		}
		for (Region r : regions) {
			for (int t = 0; t < r.triangleCount(); t++) {
				int m = r.triMaterial[t];
				float[] p = new float[9], n = new float[9], u = new float[6];
				for (int k = 0; k < 3; k++) {
					int v = r.indices[t * 3 + k];
					p[k * 3] = (float) (r.positions[v * 3] - cx);
					p[k * 3 + 1] = r.positions[v * 3 + 1];
					p[k * 3 + 2] = (float) (r.positions[v * 3 + 2] - cz);
					System.arraycopy(r.normals, v * 3, n, k * 3, 3);
					u[k * 2] = r.uvs[v * 2];
					u[k * 2 + 1] = r.uvs[v * 2 + 1];
				}
				pos[m].add(p);
				nrm[m].add(n);
				uv[m].add(u);
			}
		}
		ByteArrayOutputStream bin = new ByteArrayOutputStream();
		StringBuilder views = new StringBuilder(), accessors = new StringBuilder(), prims = new StringBuilder();
		StringBuilder mats = new StringBuilder(), textures = new StringBuilder(), images = new StringBuilder();
		int view = 0, accessor = 0, used = 0;
		for (int m = 0; m < materials; m++) {
			Mats.Mat mat = Mats.get(m);
			if (!mats.isEmpty()) {
				mats.append(',');
				textures.append(',');
				images.append(',');
			}
			// image + texture + material for every palette entry (indices stay equal to the palette ids)
			int imageView = view++;
			int imageOffset = bin.size();
			bin.writeBytes(mat.png());
			pad(bin);
			views.append(views.isEmpty() ? "" : ",").append("{\"buffer\":0,\"byteOffset\":").append(imageOffset).append(",\"byteLength\":").append(mat.png().length).append('}');
			images.append("{\"bufferView\":").append(imageView).append(",\"mimeType\":\"image/png\"}");
			textures.append("{\"sampler\":0,\"source\":").append(m).append('}');
			float[] c = mat.baseColor();
			mats.append("{\"name\":\"").append(mat.name()).append("\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,1,1,").append(c[3] < 1 ? c[3] : 1)
				.append("],\"baseColorTexture\":{\"index\":").append(m).append("},\"metallicFactor\":0,\"roughnessFactor\":1}");
			if (mat.emissive()) {
				mats.append(",\"emissiveFactor\":[1,1,1],\"emissiveTexture\":{\"index\":").append(m).append('}');
			}
			if (mat.translucent()) {
				mats.append(",\"alphaMode\":\"BLEND\",\"doubleSided\":true");
			}
			mats.append('}');
			int count = pos[m].size();
			if (count == 0) {
				continue;
			}
			int vertices = count * 3;
			float[] p = new float[vertices * 3], n = new float[vertices * 3], u = new float[vertices * 2];
			int[] ind = new int[vertices];
			float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (int t = 0; t < count; t++) {
				System.arraycopy(pos[m].get(t), 0, p, t * 9, 9);
				System.arraycopy(nrm[m].get(t), 0, n, t * 9, 9);
				System.arraycopy(uv[m].get(t), 0, u, t * 6, 6);
			}
			for (int i = 0; i < vertices; i++) {
				ind[i] = i;
				for (int k = 0; k < 3; k++) {
					min[k] = Math.min(min[k], p[i * 3 + k]);
					max[k] = Math.max(max[k], p[i * 3 + k]);
				}
			}
			int posView = addView(bin, views, floats(p), 34962, view++);
			int nrmView = addView(bin, views, floats(n), 34962, view++);
			int uvView = addView(bin, views, floats(u), 34962, view++);
			int idxView = addView(bin, views, ints(ind), 34963, view++);
			int a = accessor;
			accessors.append(accessors.isEmpty() ? "" : ",")
				.append("{\"bufferView\":").append(posView).append(",\"componentType\":5126,\"count\":").append(vertices).append(",\"type\":\"VEC3\",\"min\":[")
				.append(min[0]).append(',').append(min[1]).append(',').append(min[2]).append("],\"max\":[").append(max[0]).append(',').append(max[1]).append(',').append(max[2]).append("]},")
				.append("{\"bufferView\":").append(nrmView).append(",\"componentType\":5126,\"count\":").append(vertices).append(",\"type\":\"VEC3\"},")
				.append("{\"bufferView\":").append(uvView).append(",\"componentType\":5126,\"count\":").append(vertices).append(",\"type\":\"VEC2\"},")
				.append("{\"bufferView\":").append(idxView).append(",\"componentType\":5125,\"count\":").append(vertices).append(",\"type\":\"SCALAR\"}");
			accessor += 4;
			prims.append(prims.isEmpty() ? "" : ",").append("{\"attributes\":{\"POSITION\":").append(a).append(",\"NORMAL\":").append(a + 1).append(",\"TEXCOORD_0\":")
				.append(a + 2).append("},\"indices\":").append(a + 3).append(",\"material\":").append(m).append('}');
			used++;
		}
		String json = "{\"asset\":{\"version\":\"2.0\",\"generator\":\"blendermc EndlessExport\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0,\"name\":\"endless\"}],"
			+ "\"meshes\":[{\"name\":\"endless\",\"primitives\":[" + prims + "]}],\"materials\":[" + mats + "],\"textures\":[" + textures + "],\"images\":[" + images + "],"
			+ "\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":10497,\"wrapT\":10497}],"
			+ "\"accessors\":[" + accessors + "],\"bufferViews\":[" + views + "],\"buffers\":[{\"byteLength\":" + bin.size() + "}]}";
		byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
		int jsonPad = (4 - jsonBytes.length % 4) % 4;
		int binPad = (4 - bin.size() % 4) % 4;
		int total = 12 + 8 + jsonBytes.length + jsonPad + 8 + bin.size() + binPad;
		ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
		out.putInt(0x46546C67).putInt(2).putInt(total);
		out.putInt(jsonBytes.length + jsonPad).putInt(0x4E4F534A).put(jsonBytes);
		for (int i = 0; i < jsonPad; i++) {
			out.put((byte) 0x20);
		}
		out.putInt(bin.size() + binPad).putInt(0x004E4942).put(bin.toByteArray());
		return out.array();
	}

	private static void pad(ByteArrayOutputStream bin) {
		while (bin.size() % 4 != 0) {
			bin.write(0);
		}
	}

	private static int addView(ByteArrayOutputStream bin, StringBuilder views, byte[] data, int target, int index) {
		int offset = bin.size();
		bin.writeBytes(data);
		pad(bin);
		views.append(",{\"buffer\":0,\"byteOffset\":").append(offset).append(",\"byteLength\":").append(data.length).append(",\"target\":").append(target).append('}');
		return index;
	}

	private static byte[] floats(float[] f) {
		ByteBuffer b = ByteBuffer.allocate(f.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float v : f) {
			b.putFloat(v);
		}
		return b.array();
	}

	private static byte[] ints(int[] f) {
		ByteBuffer b = ByteBuffer.allocate(f.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int v : f) {
			b.putInt(v);
		}
		return b.array();
	}
}
