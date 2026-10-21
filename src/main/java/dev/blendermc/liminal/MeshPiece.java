package dev.blendermc.liminal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * One block cell of liminal geometry, carried by the {@code blendermc:mesh_piece} item and by placed
 * {@code mesh_cell} blocks. Vertices are local to the cell (0..1 on every axis); materials carry their texture PNG,
 * so a piece needs no loaded level and works in any dimension or world.
 *
 * <p>{@code facing} is the horizontal direction (2D data value: 0 south, 1 west, 2 north, 3 east) the player faced
 * when the piece was broken off; placing rotates the piece by the difference to the placer's facing, so it faces the
 * player the way it faced the breaker.
 */
public record MeshPiece(String sourceLevel, int facing, List<Tri> triangles, List<PieceMaterial> materials) {
	public static final int MAX_TRIANGLES = 1024;
	public static final int MAX_MATERIALS = 16;
	/** Bigger textures are dropped from pieces (the colour stays). */
	public static final int MAX_TEXTURE_BYTES = 256 * 1024;

	/** A triangle: 9 position floats (cell-local), 9 normal floats, 6 UV floats, counter-clockwise front, material index. */
	public record Tri(float[] positions, float[] normals, float[] uvs, int material) {
		public static final Codec<Tri> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			floats(9).fieldOf("p").forGetter(Tri::positions),
			floats(9).fieldOf("n").forGetter(Tri::normals),
			floats(6).fieldOf("uv").forGetter(Tri::uvs),
			Codec.intRange(0, MAX_MATERIALS - 1).fieldOf("m").forGetter(Tri::material)
		).apply(instance, Tri::new));

		@Override
		public boolean equals(Object o) {
			return o instanceof Tri t && t.material == this.material && Arrays.equals(t.positions, this.positions)
				&& Arrays.equals(t.normals, this.normals) && Arrays.equals(t.uvs, this.uvs);
		}

		@Override
		public int hashCode() {
			return 31 * (31 * Arrays.hashCode(this.positions) + Arrays.hashCode(this.uvs)) + this.material;
		}
	}

	/** Same meaning as {@link LiminalMesh.Material}; {@code texturePng} may be null. */
	public record PieceMaterial(String name, float[] baseColor, byte @Nullable [] texturePng, boolean emissive, boolean collides) {
		public static final Codec<PieceMaterial> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.string(0, 64).fieldOf("name").forGetter(PieceMaterial::name),
			floats(4).fieldOf("color").forGetter(PieceMaterial::baseColor),
			Codec.BYTE_BUFFER.optionalFieldOf("png").forGetter(m -> Optional.ofNullable(m.texturePng).map(ByteBuffer::wrap)),
			Codec.BOOL.optionalFieldOf("emissive", false).forGetter(PieceMaterial::emissive),
			Codec.BOOL.optionalFieldOf("collides", true).forGetter(PieceMaterial::collides)
		).apply(instance, (name, color, png, emissive, collides) -> new PieceMaterial(name, color, png.map(MeshPiece::bytes).orElse(null),
			emissive, collides)));

		/** See {@link LiminalMesh.Material#thin()}. */
		public boolean thin() {
			return LiminalMesh.Material.isThin(this.name, this.emissive, this.collides);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof PieceMaterial m && m.emissive == this.emissive && m.collides == this.collides && m.name.equals(this.name)
				&& Arrays.equals(m.baseColor, this.baseColor) && Arrays.equals(m.texturePng, this.texturePng);
		}

		@Override
		public int hashCode() {
			return 31 * this.name.hashCode() + Arrays.hashCode(this.texturePng);
		}
	}

	public static final Codec<MeshPiece> CODEC = RecordCodecBuilder.<MeshPiece>create(instance -> instance.group(
		Codec.string(0, 64).fieldOf("level").forGetter(MeshPiece::sourceLevel),
		Codec.intRange(0, 3).optionalFieldOf("facing", 0).forGetter(MeshPiece::facing),
		Tri.CODEC.sizeLimitedListOf(MAX_TRIANGLES).fieldOf("triangles").forGetter(MeshPiece::triangles),
		PieceMaterial.CODEC.sizeLimitedListOf(MAX_MATERIALS).fieldOf("materials").forGetter(MeshPiece::materials)
	).apply(instance, MeshPiece::new)).validate(MeshPiece::validate);

	public static final StreamCodec<ByteBuf, MeshPiece> STREAM_CODEC = StreamCodec.of(MeshPiece::write, MeshPiece::read);

	public MeshPiece {
		triangles = List.copyOf(triangles);
		materials = List.copyOf(materials);
	}

	private static DataResult<MeshPiece> validate(MeshPiece piece) {
		for (Tri tri : piece.triangles) {
			if (tri.material >= piece.materials.size()) {
				return DataResult.error(() -> "mesh piece triangle uses missing material " + tri.material);
			}
		}
		return DataResult.success(piece);
	}

	private static Codec<float[]> floats(int size) {
		return Codec.FLOAT.listOf(size, size).xmap(list -> {
			float[] out = new float[size];
			for (int i = 0; i < size; i++) {
				out[i] = list.get(i);
			}
			return out;
		}, array -> {
			List<Float> list = new ArrayList<>(size);
			for (float f : array) {
				list.add(f);
			}
			return list;
		});
	}

	private static byte[] bytes(ByteBuffer buffer) {
		byte[] out = new byte[buffer.remaining()];
		buffer.duplicate().get(out);
		return out;
	}

	private static void write(ByteBuf buf, MeshPiece piece) {
		ByteBufCodecs.STRING_UTF8.encode(buf, piece.sourceLevel);
		buf.writeByte(piece.facing);
		ByteBufCodecs.VAR_INT.encode(buf, piece.materials.size());
		for (PieceMaterial m : piece.materials) {
			ByteBufCodecs.STRING_UTF8.encode(buf, m.name);
			for (float f : m.baseColor) {
				buf.writeFloat(f);
			}
			buf.writeBoolean(m.emissive);
			buf.writeBoolean(m.collides);
			buf.writeBoolean(m.texturePng != null);
			if (m.texturePng != null) {
				ByteBufCodecs.byteArray(MAX_TEXTURE_BYTES).encode(buf, m.texturePng);
			}
		}
		ByteBufCodecs.VAR_INT.encode(buf, piece.triangles.size());
		for (Tri t : piece.triangles) {
			for (float f : t.positions) {
				buf.writeFloat(f);
			}
			for (float f : t.normals) {
				buf.writeFloat(f);
			}
			for (float f : t.uvs) {
				buf.writeFloat(f);
			}
			buf.writeByte(t.material);
		}
	}

	private static MeshPiece read(ByteBuf buf) {
		String level = ByteBufCodecs.STRING_UTF8.decode(buf);
		int facing = buf.readByte() & 3;
		int materialCount = ByteBufCodecs.VAR_INT.decode(buf);
		if (materialCount < 0 || materialCount > MAX_MATERIALS) {
			throw new IllegalArgumentException("mesh piece with " + materialCount + " materials");
		}
		List<PieceMaterial> materials = new ArrayList<>(materialCount);
		for (int i = 0; i < materialCount; i++) {
			String name = ByteBufCodecs.STRING_UTF8.decode(buf);
			float[] color = new float[4];
			for (int c = 0; c < 4; c++) {
				color[c] = buf.readFloat();
			}
			boolean emissive = buf.readBoolean();
			boolean collides = buf.readBoolean();
			byte[] png = buf.readBoolean() ? ByteBufCodecs.byteArray(MAX_TEXTURE_BYTES).decode(buf) : null;
			materials.add(new PieceMaterial(name, color, png, emissive, collides));
		}
		int triangleCount = ByteBufCodecs.VAR_INT.decode(buf);
		if (triangleCount < 0 || triangleCount > MAX_TRIANGLES) {
			throw new IllegalArgumentException("mesh piece with " + triangleCount + " triangles");
		}
		List<Tri> triangles = new ArrayList<>(triangleCount);
		for (int i = 0; i < triangleCount; i++) {
			float[] p = new float[9], n = new float[9], uv = new float[6];
			for (int k = 0; k < 9; k++) {
				p[k] = buf.readFloat();
			}
			for (int k = 0; k < 9; k++) {
				n[k] = buf.readFloat();
			}
			for (int k = 0; k < 6; k++) {
				uv[k] = buf.readFloat();
			}
			int material = buf.readByte() & 0xFF;
			if (material >= materialCount) {
				throw new IllegalArgumentException("mesh piece triangle uses missing material " + material);
			}
			triangles.add(new Tri(p, n, uv, material));
		}
		return new MeshPiece(level, facing, triangles, materials);
	}

	/**
	 * Builds the piece for one cell of a level: the given triangles moved into cell-local coordinates, with only the
	 * materials they use. {@code facing} as in the class comment.
	 */
	public static MeshPiece fromCell(LiminalMesh mesh, int cellX, int cellY, int cellZ, int[] triangles, int facing) {
		float[] p = mesh.positions(), n = mesh.normals(), uv = mesh.uvs();
		int[] idx = mesh.indices();
		List<PieceMaterial> materials = new ArrayList<>();
		int[] remap = new int[mesh.materials().size()];
		Arrays.fill(remap, -1);
		List<Tri> tris = new ArrayList<>();
		for (int t : triangles) {
			if (tris.size() >= MAX_TRIANGLES) {
				break;
			}
			int source = mesh.triangleMaterial()[t];
			if (remap[source] < 0) {
				if (materials.size() >= MAX_MATERIALS) {
					continue;
				}
				LiminalMesh.Material m = mesh.materials().get(source);
				byte[] png = m.texturePng() != null && m.texturePng().length <= MAX_TEXTURE_BYTES ? m.texturePng() : null;
				remap[source] = materials.size();
				materials.add(new PieceMaterial(m.name(), m.baseColor().clone(), png, m.emissive(), m.collides()));
			}
			float[] tp = new float[9], tn = new float[9], tuv = new float[6];
			for (int v = 0; v < 3; v++) {
				int vi = idx[t * 3 + v];
				tp[v * 3] = p[vi * 3] - cellX;
				tp[v * 3 + 1] = p[vi * 3 + 1] - cellY;
				tp[v * 3 + 2] = p[vi * 3 + 2] - cellZ;
				System.arraycopy(n, vi * 3, tn, v * 3, 3);
				tuv[v * 2] = uv[vi * 2];
				tuv[v * 2 + 1] = uv[vi * 2 + 1];
			}
			tris.add(new Tri(tp, tn, tuv, remap[source]));
		}
		return new MeshPiece(mesh.name(), facing & 3, tris, materials);
	}

	/** This piece turned clockwise (seen from above) by {@code quarterTurns} × 90° around the cell's vertical axis. */
	public MeshPiece rotated(int quarterTurns) {
		int turns = Math.floorMod(quarterTurns, 4);
		if (turns == 0) {
			return this;
		}
		List<Tri> out = new ArrayList<>(this.triangles.size());
		for (Tri t : this.triangles) {
			float[] p = t.positions.clone(), n = t.normals.clone();
			for (int v = 0; v < 3; v++) {
				for (int k = 0; k < turns; k++) {
					// Clockwise from above: south (+Z) -> west (-X) -> north -> east, i.e. (x, z) -> (-z, x) around the centre.
					float px = p[v * 3] - 0.5F, pz = p[v * 3 + 2] - 0.5F;
					p[v * 3] = 0.5F - pz;
					p[v * 3 + 2] = 0.5F + px;
					float nx = n[v * 3], nz = n[v * 3 + 2];
					n[v * 3] = -nz;
					n[v * 3 + 2] = nx;
				}
			}
			out.add(new Tri(p, n, t.uvs, t.material));
		}
		return new MeshPiece(this.sourceLevel, (this.facing + turns) & 3, out, this.materials);
	}

	/** Positions of the triangles whose material collides (or all), 9 floats each, cell-local. */
	public float[] positions(boolean collidingOnly) {
		List<Tri> kept = new ArrayList<>(this.triangles.size());
		for (Tri t : this.triangles) {
			if (!collidingOnly || this.materials.get(t.material).collides()) {
				kept.add(t);
			}
		}
		float[] out = new float[kept.size() * 9];
		for (int i = 0; i < kept.size(); i++) {
			System.arraycopy(kept.get(i).positions, 0, out, i * 9, 9);
		}
		return out;
	}

	/** Positions of the triangles that give volume (not thin), 9 floats each, cell-local. */
	public float[] volumePositions() {
		List<Tri> kept = new ArrayList<>(this.triangles.size());
		for (Tri t : this.triangles) {
			if (!this.materials.get(t.material).thin()) {
				kept.add(t);
			}
		}
		float[] out = new float[kept.size() * 9];
		for (int i = 0; i < kept.size(); i++) {
			System.arraycopy(kept.get(i).positions, 0, out, i * 9, 9);
		}
		return out;
	}

	/** The piece's exact shapes, support faces and light occlusion (not cached; the block entity keeps one). */
	public CellGeometry geometry() {
		return new CellGeometry(this.positions(false), this.positions(true), this.volumePositions(), null);
	}

	/** The piece's exact triangle shape (cell-local); {@code collidingOnly} leaves out decal ("_nocollide") triangles. */
	public VoxelShape shape(boolean collidingOnly) {
		float[] p = this.positions(collidingOnly);
		return p.length == 0 ? net.minecraft.world.phys.shapes.Shapes.empty() : new MeshTriangleShape(p);
	}
}
