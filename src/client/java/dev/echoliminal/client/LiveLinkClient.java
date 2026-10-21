package dev.echoliminal.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.echoliminal.EchoLiminal;
import dev.echoliminal.client.mixin.LivingEntityRendererInvoker;
import dev.echoliminal.liminal.LiminalLevels;
import dev.echoliminal.live.LiveLink;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Client half of the Blender live link (same JVM as the integrated server):
 * <ul>
 * <li>block models: the baked quads of a block state (its JSON model as the game resolved it, resource packs included),
 * every face with its own texture and biome tint, so grass, leaves, stairs, torches look in Blender as in the game;</li>
 * <li>living entities (the player and mobs) in the linked level: their model, posed by the game's own animation each
 * tick and captured vertex by vertex, so Blender shows them moving in sync;</li>
 * <li>no pause when the window loses focus while Blender is linked (working in Blender would freeze the world it mirrors).</li>
 * </ul>
 */
public final class LiveLinkClient {
	private static final Direction[] FACES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};
	/** Living entities further than this from the player are not mirrored. */
	private static final double ENTITY_RANGE = 96.0;
	private static boolean pauseSuppressed;
	private static int connection = -1;
	/** Entity id -> what Blender has as its mesh (vertex count and texture). */
	private static final Int2ObjectOpenHashMap<String> SENT_ENTITIES = new Int2ObjectOpenHashMap<>();

	private LiveLinkClient() {
	}

	public static void register() {
		LiveLink.blockModels = (state, pos) -> Minecraft.getInstance().execute(() -> {
			try {
				sendBlockModel(state, pos);
			} catch (RuntimeException e) {
				EchoLiminal.LOGGER.warn("Blender live link: no model for {}", state, e);
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(LiveLinkClient::tick);
	}

	private static void tick(Minecraft mc) {
		if (LiveLink.linked() && mc.hasSingleplayerServer()) {
			if (mc.options.pauseOnLostFocus) {
				mc.options.pauseOnLostFocus = false;
				pauseSuppressed = true;
			}
		} else if (pauseSuppressed) {
			mc.options.pauseOnLostFocus = true;
			pauseSuppressed = false;
		}
		if (connection != LiveLink.connectionId()) {
			connection = LiveLink.connectionId();
			SENT_ENTITIES.clear();
		}
		try {
			sendEntities(mc);
		} catch (RuntimeException e) {
			EchoLiminal.LOGGER.warn("Blender live link: entities failed: {}", e.toString());
		}
	}

	// ------------------------------------------------------------------ textures

	/** Sends a texture once per connection under {@code key}; false if it could not be read. */
	private static boolean sendTexture(String key, Identifier file) {
		if (!LiveLink.firstTime("tex:" + key)) {
			return true;
		}
		byte[] png = readPng(file);
		if (png == null) {
			return false;
		}
		JsonObject texture = new JsonObject();
		texture.addProperty("t", "texture");
		texture.addProperty("key", key);
		LiveLink.sendToBlender(texture, png);
		return true;
	}

	/** A texture file from the resource packs, or the pixels of a texture made at runtime (downloaded skins). */
	private static byte @Nullable [] readPng(Identifier file) {
		Minecraft mc = Minecraft.getInstance();
		try {
			var resource = mc.getResourceManager().getResource(file);
			if (resource.isPresent()) {
				try (InputStream in = resource.get().open()) {
					return in.readAllBytes();
				}
			}
			AbstractTexture texture = mc.getTextureManager().getTexture(file);
			if (texture instanceof DynamicTexture dynamic && dynamic.getPixels() != null) {
				java.nio.file.Path temp = java.nio.file.Files.createTempFile("echoliminal-skin", ".png");
				try {
					dynamic.getPixels().writeToFile(temp);
					return java.nio.file.Files.readAllBytes(temp);
				} finally {
					java.nio.file.Files.deleteIfExists(temp);
				}
			}
		} catch (Exception e) {
			EchoLiminal.LOGGER.warn("Blender live link: no texture {}: {}", file, e.toString());
		}
		return null;
	}

	/** Height of a PNG from its header (to know how many animation frames a sprite strip holds). */
	private static int pngHeight(byte[] png) {
		return png.length > 24 ? ByteBuffer.wrap(png, 20, 4).order(ByteOrder.BIG_ENDIAN).getInt() : 0;
	}

	// ------------------------------------------------------------------ block models

	/** The baked model of {@code state} at {@code pos}: one quad per face, UVs local to each face's own texture. Render thread. */
	private static void sendBlockModel(BlockState state, BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		List<BlockModelPart> parts = mc.getBlockRenderer().getBlockModel(state).collectParts(RandomSource.create(42L));
		JsonArray quads = new JsonArray();
		FloatArrayList data = new FloatArrayList();
		for (BlockModelPart part : parts) {
			for (Direction face : FACES) {
				for (BakedQuad quad : part.getQuads(face)) {
					TextureAtlasSprite sprite = quad.sprite();
					Identifier name = sprite.contents().name();
					Identifier file = Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png");
					String key = name.toString();
					float vScale = 1.0F;
					sendTexture(key, file);
					byte[] png = FRAME_CACHE.computeIfAbsent(key, k -> readPng(file));
					if (png != null && pngHeight(png) > 0) {
						vScale = Math.min(1.0F, sprite.contents().height() / (float) pngHeight(png));
					}
					JsonObject q = new JsonObject();
					q.addProperty("tex", key);
					q.addProperty("tint", quad.isTinted() ? mc.getBlockColors().getColor(state, mc.level, pos, quad.tintIndex()) & 0xFFFFFF : -1);
					q.addProperty("emissive", quad.lightEmission() > 0 || state.getLightEmission() > 0);
					quads.add(q);
					// Tinted overlays (grass sides) lie on the face below them: lift them a hair so Blender does not z-fight.
					Direction side = quad.direction();
					float lift = quad.isTinted() && side != null ? 0.0005F : 0.0F;
					float lx = side == null ? 0 : lift * side.getStepX();
					float ly = side == null ? 0 : lift * side.getStepY();
					float lz = side == null ? 0 : lift * side.getStepZ();
					float du0 = sprite.getU1() - sprite.getU0();
					float dv0 = sprite.getV1() - sprite.getV0();
					for (int i = 0; i < 4; i++) {
						var p = quad.position(i);
						data.add(p.x() + lx);
						data.add(p.y() + ly);
						data.add(p.z() + lz);
						long uv = quad.packedUV(i);
						data.add(du0 == 0 ? 0 : (UVPair.unpackU(uv) - sprite.getU0()) / du0);
						data.add(dv0 == 0 ? 0 : (UVPair.unpackV(uv) - sprite.getV0()) / dv0 * vScale);
					}
				}
			}
		}
		if (state.getBlock() instanceof net.minecraft.world.level.block.SignBlock) {
			// Signs are drawn by their block entity, not a model: Blender builds the board from the sign texture.
			String wood = net.minecraft.world.level.block.SignBlock.getWoodType(state.getBlock()).name();
			String folder = state.getBlock() instanceof net.minecraft.world.level.block.CeilingHangingSignBlock
				|| state.getBlock() instanceof net.minecraft.world.level.block.WallHangingSignBlock ? "entity/signs/hanging/" : "entity/signs/";
			sendTexture("minecraft:" + folder + wood, Identifier.withDefaultNamespace("textures/" + folder + wood + ".png"));
		}
		JsonObject model = new JsonObject();
		model.addProperty("t", "blockmodel");
		model.addProperty("key", state.toString());
		model.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		model.add("quads", quads);
		LiveLink.sendToBlender(model, floats(data));
	}

	private static final java.util.Map<String, byte[]> FRAME_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	// ------------------------------------------------------------------ entities

	/** Captures where a model's vertices end up (positions and texture coordinates), quads of 4 vertices. */
	private static final class Capture implements VertexConsumer {
		final FloatArrayList positions = new FloatArrayList();
		final FloatArrayList uvs = new FloatArrayList();

		void reset() {
			this.positions.clear();
			this.uvs.clear();
		}

		int vertices() {
			return this.positions.size() / 3;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			this.positions.add(x);
			this.positions.add(y);
			this.positions.add(z);
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			this.uvs.add(u);
			this.uvs.add(v);
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}
	}

	private static final Capture CAPTURE = new Capture();

	/**
	 * Every living entity in the linked level, posed exactly as the game draws it this tick (its renderer's body
	 * transforms, the model's own animation): vertex positions relative to the level origin, one frame for all.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void sendEntities(Minecraft mc) {
		String level = LiveLink.linkedLevel();
		if (level == null || mc.level == null || mc.player == null || !LiminalLevels.isLiminal(mc.level)) {
			return;
		}
		BlockPos origin = LiminalLevels.originOf(level);
		if (origin == null) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		JsonArray list = new JsonArray();
		JsonArray things = new JsonArray();
		FloatArrayList positions = new FloatArrayList();
		IntOpenHashSet seen = new IntOpenHashSet();
		IntOpenHashSet tnts = new IntOpenHashSet();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity.distanceToSqr(mc.player) > ENTITY_RANGE * ENTITY_RANGE || !level.equals(LiminalLevels.levelAt(entity.getX(), entity.getZ()))) {
				continue;
			}
			EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
			EntityRenderState raw = renderer.createRenderState(entity, partial);
			EntityModel model;
			Identifier texture;
			PoseStack pose = new PoseStack();
			pose.translate(raw.x - origin.getX(), raw.y - origin.getY(), raw.z - origin.getZ());
			LivingEntityRenderState livingState = null;
			if (renderer instanceof LivingEntityRenderer living && raw instanceof LivingEntityRenderState state) {
				if (state.isInvisible) {
					continue;
				}
				livingState = state;
				model = (EntityModel) living.getModel();
				model.setupAnim(state);
				pose.scale(state.scale, state.scale, state.scale);
				LivingEntityRendererInvoker body = (LivingEntityRendererInvoker) living;
				body.echoliminal$setupRotations(state, pose, state.bodyRot, state.scale);
				pose.scale(-1.0F, -1.0F, 1.0F);
				body.echoliminal$scale(state, pose);
				pose.translate(0.0F, -1.501F, 0.0F);
				texture = living.getTextureLocation(state);
			} else if (renderer instanceof net.minecraft.client.renderer.entity.AbstractBoatRenderer
				&& raw instanceof net.minecraft.client.renderer.entity.state.BoatRenderState boat) {
				// Boats and rafts: the same transforms as AbstractBoatRenderer.submit.
				model = ((dev.echoliminal.client.mixin.AbstractBoatRendererInvoker) renderer).echoliminal$model();
				model.setupAnim(boat);
				pose.translate(0.0F, 0.375F, 0.0F);
				pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F - boat.yRot));
				pose.scale(-1.0F, -1.0F, 1.0F);
				pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90.0F));
				texture = renderer instanceof net.minecraft.client.renderer.entity.BoatRenderer
					? ((dev.echoliminal.client.mixin.BoatRendererAccessor) renderer).echoliminal$texture()
					: ((dev.echoliminal.client.mixin.RaftRendererAccessor) renderer).echoliminal$texture();
			} else {
				JsonObject thing = thing(entity, partial, origin);
				if (thing != null) {
					things.add(thing);
					if (entity instanceof net.minecraft.world.entity.item.PrimedTnt) {
						tnts.add(entity.getId());
					}
				}
				continue;
			}
			CAPTURE.reset();
			model.renderToBuffer(pose, CAPTURE, 0xF000F0, OverlayTexture.NO_OVERLAY);
			int count = CAPTURE.vertices();
			if (count == 0 || CAPTURE.uvs.size() != count * 2) {
				continue;
			}
			String signature = count + "|" + texture;
			if (!signature.equals(SENT_ENTITIES.get(entity.getId()))) {
				sendTexture(texture.toString(), texture);
				JsonObject mesh = new JsonObject();
				mesh.addProperty("t", "entity_mesh");
				mesh.addProperty("id", entity.getId());
				mesh.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
				mesh.addProperty("tex", texture.toString());
				mesh.addProperty("n", count);
				mesh.addProperty("player", entity == mc.player);
				LiveLink.sendToBlender(mesh, floats(CAPTURE.uvs));
				SENT_ENTITIES.put(entity.getId(), signature);
			}
			seen.add(entity.getId());
			JsonArray item = new JsonArray();
			item.add(entity.getId());
			item.add(count);
			item.add(livingState != null ? held(model, livingState, pose) : new JsonArray());
			list.add(item);
			positions.addAll(CAPTURE.positions);
		}
		// A primed TNT that vanished while its fuse was running out exploded: Blender shows the blast.
		for (int id : TNT_LAST.keySet().toIntArray()) {
			if (!tnts.contains(id)) {
				double[] last = TNT_LAST.remove(id);
				if (last[3] <= 3.0) {
					JsonObject blast = new JsonObject();
					blast.addProperty("t", "explosion");
					JsonArray at = new JsonArray();
					at.add(last[0]);
					at.add(last[1] + 0.5);
					at.add(last[2]);
					blast.add("p", at);
					blast.addProperty("r", 4.0);
					LiveLink.sendToBlender(blast, null);
				}
			}
		}
		SENT_ENTITIES.keySet().removeIf(id -> !seen.contains((int) id));
		JsonObject frame = new JsonObject();
		frame.addProperty("t", "entities");
		frame.add("list", list);
		frame.add("things", things);
		LiveLink.sendToBlender(frame, floats(positions));
	}

	/** Last place and fuse of every primed TNT seen, to notice the one that just exploded. */
	private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<double[]> TNT_LAST = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();

	/** Makes sure Blender has the model of {@code state} (once per connection, shared with the server's requests). */
	private static void ensureModel(BlockState state, BlockPos pos) {
		if (LiveLink.firstTime("model:" + state)) {
			sendBlockModel(state, pos);
		}
	}

	/** A dropped item, primed TNT or falling block: what to show, where (bottom centre, level coordinates), turned and sized. */
	private static @Nullable JsonObject thing(Entity entity, float partial, BlockPos origin) {
		double x = net.minecraft.util.Mth.lerp(partial, entity.xo, entity.getX()) - origin.getX();
		double y = net.minecraft.util.Mth.lerp(partial, entity.yo, entity.getY()) - origin.getY();
		double z = net.minecraft.util.Mth.lerp(partial, entity.zo, entity.getZ()) - origin.getZ();
		JsonObject thing = new JsonObject();
		thing.addProperty("id", entity.getId());
		float scale = 1.0F;
		float yaw = 0.0F;
		if (entity instanceof net.minecraft.world.entity.item.FallingBlockEntity falling) {
			BlockState state = falling.getBlockState();
			ensureModel(state, entity.blockPosition());
			thing.addProperty("kind", "block");
			thing.addProperty("model", state.toString());
		} else if (entity instanceof net.minecraft.world.entity.item.PrimedTnt tnt) {
			BlockState state = tnt.getBlockState();
			ensureModel(state, entity.blockPosition());
			float fuse = tnt.getFuse() - partial + 1.0F;
			if (fuse < 10.0F) {
				// The swell the game draws just before the blast.
				float f = net.minecraft.util.Mth.clamp(1.0F - fuse / 10.0F, 0.0F, 1.0F);
				f *= f;
				f *= f;
				scale = 1.0F + f * 0.3F;
			}
			thing.addProperty("kind", "block");
			thing.addProperty("model", state.toString());
			thing.addProperty("flash", (int) fuse / 5 % 2 == 0);
			y -= (scale - 1.0F) * 0.5F;
			TNT_LAST.put(entity.getId(), new double[] {x, y, z, fuse});
		} else if (entity instanceof net.minecraft.world.entity.item.ItemEntity drop) {
			net.minecraft.world.item.ItemStack stack = drop.getItem();
			if (stack.isEmpty()) {
				return null;
			}
			float age = drop.getAge() + partial;
			y += net.minecraft.util.Mth.sin(age / 10.0F + drop.bobOffs) * 0.1F + 0.1F;
			yaw = (float) Math.toDegrees(net.minecraft.world.entity.item.ItemEntity.getSpin(age, drop.bobOffs));
			if (!item(thing, stack, entity.blockPosition())) {
				return null;
			}
			scale = thing.get("kind").getAsString().equals("block") ? 0.25F : 0.5F;
		} else {
			return null;
		}
		JsonArray at = new JsonArray();
		at.add(Math.round(x * 1000.0) / 1000.0);
		at.add(Math.round(y * 1000.0) / 1000.0);
		at.add(Math.round(z * 1000.0) / 1000.0);
		thing.add("p", at);
		thing.addProperty("s", scale);
		thing.addProperty("yaw", yaw);
		return thing;
	}

	/** How Blender draws an item: a block item as its block, anything else as its flat texture. False if it has neither. */
	private static boolean item(JsonObject into, net.minecraft.world.item.ItemStack stack, BlockPos pos) {
		if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
			BlockState state = blockItem.getBlock().defaultBlockState();
			ensureModel(state, pos);
			into.addProperty("kind", "block");
			into.addProperty("model", state.toString());
			return true;
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		String key = id.getNamespace() + ":item/" + id.getPath();
		if (!sendTexture(key, Identifier.fromNamespaceAndPath(id.getNamespace(), "textures/item/" + id.getPath() + ".png"))) {
			return false;
		}
		into.addProperty("kind", "sprite");
		into.addProperty("tex", key);
		return true;
	}

	/**
	 * The items in a mob's or the player's hands: where the game draws them (hand of the posed model, the vanilla
	 * in-hand offsets and a typical third-person hold), as a column-major matrix in level coordinates.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static JsonArray held(EntityModel model, LivingEntityRenderState state, PoseStack pose) {
		JsonArray hands = new JsonArray();
		if (!(model instanceof net.minecraft.client.model.ArmedModel armed) || !(state instanceof net.minecraft.client.renderer.entity.state.ArmedEntityRenderState arms)) {
			return hands;
		}
		for (net.minecraft.world.entity.HumanoidArm arm : net.minecraft.world.entity.HumanoidArm.values()) {
			net.minecraft.world.item.ItemStack stack = arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? arms.rightHandItemStack : arms.leftHandItemStack;
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			JsonObject hand = new JsonObject();
			if (!item(hand, stack, BlockPos.containing(state.x, state.y, state.z))) {
				continue;
			}
			pose.pushPose();
			armed.translateToHand(state, arm, pose);
			pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90.0F));
			pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F));
			boolean left = arm == net.minecraft.world.entity.HumanoidArm.LEFT;
			pose.translate((left ? -1 : 1) / 16.0F, 0.125F, -0.625F);
			if (hand.get("kind").getAsString().equals("block")) {
				// block items in third person: 2.5 px up, turned 75/45 degrees, 3/8 size
				pose.translate(0.0F, 2.5F / 16.0F, 0.0F);
				pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(75.0F));
				pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(left ? 225.0F : 45.0F));
				pose.scale(0.375F, 0.375F, 0.375F);
			} else {
				// flat items: held out in front, 0.55 size
				pose.translate(0.0F, 3.0F / 16.0F, 1.0F / 16.0F);
				pose.scale(0.55F, 0.55F, 0.55F);
			}
			float[] m = new float[16];
			pose.last().pose().get(m);
			pose.popPose();
			JsonArray matrix = new JsonArray();
			for (float v : m) {
				matrix.add(Math.round(v * 10000.0) / 10000.0);
			}
			hand.add("m", matrix);
			hand.addProperty("hand", left ? "left" : "right");
			hands.add(hand);
		}
		return hands;
	}

	private static byte[] floats(FloatArrayList values) {
		ByteBuffer buffer = ByteBuffer.allocate(values.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < values.size(); i++) {
			buffer.putFloat(values.getFloat(i));
		}
		return buffer.array();
	}
}
