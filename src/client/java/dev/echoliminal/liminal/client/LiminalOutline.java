package dev.echoliminal.liminal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.echoliminal.liminal.LiminalCells;
import dev.echoliminal.liminal.LiminalLevels;
import dev.echoliminal.liminal.LiminalMesh;
import dev.echoliminal.liminal.MeshCellBlockEntity;
import dev.echoliminal.liminal.MeshPiece;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The hit outline of a mesh cell follows its surface instead of the 1/16 voxel shape: the edges of the cell's
 * triangles that border only one triangle (the outline of the surface, including where the grid cut it), drawn in
 * vanilla's outline style.
 */
public final class LiminalOutline {
	private static BlockPos cachedPos;
	private static Object cachedSource;
	private static float[] cachedEdges = new float[0];

	private LiminalOutline() {
	}

	public static void register() {
		WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register(LiminalOutline::render);
	}

	private static boolean render(WorldRenderContext context, BlockOutlineRenderState outline) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return true;
		}
		BlockEntity entity = mc.level.getBlockEntity(outline.pos());
		if (!(entity instanceof MeshCellBlockEntity cell)) {
			return true;
		}
		float[] edges = edges(cell, outline.pos());
		if (edges.length == 0) {
			return true;
		}
		Vec3 cam = context.worldState().cameraRenderState.pos;
		PoseStack poseStack = context.matrices();
		PoseStack.Pose pose = poseStack.last();
		float ox = (float) (outline.pos().getX() - cam.x), oy = (float) (outline.pos().getY() - cam.y), oz = (float) (outline.pos().getZ() - cam.z);
		float width = mc.getWindow().getAppropriateLineWidth();
		if (outline.highContrast()) {
			draw(context.consumers().getBuffer(RenderTypes.secondaryBlockOutline()), pose, edges, ox, oy, oz, 0xFF000000, 7.0F);
		}
		draw(context.consumers().getBuffer(RenderTypes.lines()), pose, edges, ox, oy, oz, outline.highContrast() ? -11010079 : ARGB.black(102), width);
		return false;
	}

	private static void draw(VertexConsumer consumer, PoseStack.Pose pose, float[] edges, float ox, float oy, float oz, int color, float width) {
		for (int i = 0; i < edges.length; i += 6) {
			Vector3f dir = new Vector3f(edges[i + 3] - edges[i], edges[i + 4] - edges[i + 1], edges[i + 5] - edges[i + 2]).normalize();
			consumer.addVertex(pose, edges[i] + ox, edges[i + 1] + oy, edges[i + 2] + oz).setColor(color).setNormal(pose, dir).setLineWidth(width);
			consumer.addVertex(pose, edges[i + 3] + ox, edges[i + 4] + oy, edges[i + 5] + oz).setColor(color).setNormal(pose, dir).setLineWidth(width);
		}
	}

	/** Boundary edges of the cell's surface, cell-local, 6 floats each; cached for the last looked-at cell. */
	private static float[] edges(MeshCellBlockEntity cell, BlockPos pos) {
		MeshPiece piece = cell.isLevelCell() ? null : cell.piece();
		LiminalCells cells = cell.isLevelCell() && cell.levelName() != null ? LiminalLevels.cells(cell.levelName()) : null;
		Object source = piece != null ? piece : cells;
		if (source == null) {
			return new float[0];
		}
		if (source == cachedSource && pos.equals(cachedPos)) {
			return cachedEdges;
		}
		float[] tris;
		if (piece != null) {
			tris = piece.positions(false);
		} else {
			int[] ids = cells.triangles(pos);
			LiminalMesh mesh = cells.mesh();
			tris = new float[ids == null ? 0 : ids.length * 9];
			for (int i = 0; ids != null && i < ids.length; i++) {
				for (int k = 0; k < 3; k++) {
					int v = mesh.indices()[ids[i] * 3 + k];
					tris[i * 9 + k * 3] = mesh.positions()[v * 3] - pos.getX();
					tris[i * 9 + k * 3 + 1] = mesh.positions()[v * 3 + 1] - pos.getY();
					tris[i * 9 + k * 3 + 2] = mesh.positions()[v * 3 + 2] - pos.getZ();
				}
			}
		}
		cachedSource = source;
		cachedPos = pos.immutable();
		cachedEdges = boundary(tris);
		return cachedEdges;
	}

	/** Edges used by exactly one triangle (vertices matched at 1/1024 of a block). */
	private static float[] boundary(float[] tris) {
		Object2IntOpenHashMap<String> uses = new Object2IntOpenHashMap<>();
		List<float[]> all = new ArrayList<>();
		for (int t = 0; t + 8 < tris.length; t += 9) {
			for (int k = 0; k < 3; k++) {
				int a = t + k * 3, b = t + ((k + 1) % 3) * 3;
				float[] edge = {tris[a], tris[a + 1], tris[a + 2], tris[b], tris[b + 1], tris[b + 2]};
				String key = key(edge);
				if (uses.addTo(key, 1) == 0) {
					all.add(edge);
				}
			}
		}
		List<float[]> kept = new ArrayList<>();
		for (float[] edge : all) {
			if (uses.getInt(key(edge)) == 1) {
				kept.add(edge);
			}
		}
		if (kept.isEmpty()) {
			kept = all;
		}
		float[] out = new float[kept.size() * 6];
		for (int i = 0; i < kept.size(); i++) {
			System.arraycopy(kept.get(i), 0, out, i * 6, 6);
		}
		return out;
	}

	/** Direction-independent key of an edge. */
	private static String key(float[] e) {
		String a = Math.round(e[0] * 1024) + "," + Math.round(e[1] * 1024) + "," + Math.round(e[2] * 1024);
		String b = Math.round(e[3] * 1024) + "," + Math.round(e[4] * 1024) + "," + Math.round(e[5] * 1024);
		return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
	}
}
