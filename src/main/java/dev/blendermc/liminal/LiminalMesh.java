package dev.blendermc.liminal;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One liminal level as a triangle mesh, in world coordinates of the liminal dimension (blocks; the .glb is placed
 * so that Blender's origin lands on {@link LiminalLevels#ORIGIN}). Shared by the server (collision, ray hits) and
 * the client (rendering). Immutable once loaded.
 *
 * <p>Layout: vertex i has position {@code positions[3i..3i+2]}, normal {@code normals[3i..3i+2]}, texture
 * coordinates {@code uvs[2i..2i+1]}. Triangle t uses vertices {@code indices[3t..3t+2]} (counter-clockwise front
 * face, glTF convention) and material {@code triangleMaterial[t]}.
 */
public record LiminalMesh(
	String name,
	float[] positions,
	float[] normals,
	float[] uvs,
	int[] indices,
	int[] triangleMaterial,
	List<Material> materials,
	Vec3 spawn,
	float spawnYaw,
	AABB bounds
) {
	/**
	 * A glTF material: base colour factor (RGBA 0..1), the base colour texture as PNG bytes (or null), whether it
	 * is unlit/emissive (lit ceiling panels), and whether its faces collide ({@code false} for materials whose name
	 * ends in "_nocollide", e.g. hanging decals).
	 */
	public record Material(String name, float[] baseColor, byte[] texturePng, boolean emissive, boolean collides) {
		/**
		 * Thin surface: emissive, "_nocollide" or "_thin" materials (floating beams, decals, panels). They give no solid
		 * region behind them (no interior, not inside the level volume, no cut faces); they still render and may collide.
		 */
		public boolean thin() {
			return isThin(this.name, this.emissive, this.collides);
		}

		public static boolean isThin(String name, boolean emissive, boolean collides) {
			return emissive || !collides || name.toLowerCase(java.util.Locale.ROOT).endsWith("_thin");
		}
	}

	public int triangleCount() {
		return this.indices.length / 3;
	}
}
