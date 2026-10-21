package dev.echoliminal.liminal.client;

import dev.echoliminal.liminal.LiminalMesh;
import dev.echoliminal.liminal.light.LightSources;
import java.nio.ByteBuffer;

/**
 * Public door into the package-private {@link LiminalMeshBaker} for the endless world: bakes an anonymous mesh (no level
 * name, so no level sky, cells or placed lights) with the same ray traced light as the hand made levels and hands the
 * bytes over. The caller owns {@link Result#vertexData()} and frees it with {@code MemoryUtil.memFree}.
 */
public final class EndlessBakeBridge {
	/** {@code ranges[i] = {material, firstIndex, indexCount, decal ? 1 : 0}}; indices are grouped by range. */
	public record Result(ByteBuffer vertexData, int vertexCount, int[] indices, long[] triangleCell, int[][] ranges, double anchorX, double anchorY,
			double anchorZ) {
	}

	private EndlessBakeBridge() {
	}

	public static Result bake(LiminalMesh mesh) {
		LiminalMeshBaker.Baked baked = LiminalMeshBaker.bake(mesh, LightSources.EMPTY);
		ByteBuffer data = baked.vertexData;
		baked.vertexData = null;
		int[][] ranges = new int[baked.ranges.size()][];
		for (int i = 0; i < ranges.length; i++) {
			LiminalMeshBaker.Range r = baked.ranges.get(i);
			ranges[i] = new int[] {r.material(), r.firstIndex(), r.indexCount(), r.decal() ? 1 : 0};
		}
		return new Result(data, baked.vertexCount, baked.indices, baked.triangleCell, ranges, baked.anchorX, baked.anchorY, baked.anchorZ);
	}
}
