"""BlenderMC Live: this Blender scene and a running BlenderMC level, merged in real time.

Blender -> game: every visible mesh-like object (modifiers applied) is sent as its own small .glb whenever it changes,
plus the scene's custom properties and the `spawn` / `sun` empties. The game rebuilds only the cells that changed.

Game -> Blender (collection MC_LIVE, never sent back):
- MC_Player / MC_Eye: the player's body and a camera at their eyes (look through it to see what they see).
- MC_Blocks: blocks placed in the level with their real model (the game's baked quads of the block's JSON model:
  every face its own texture, grass and leaves tinted like the biome, stairs, slabs, torches, plants).
- MC_Entities: the player, mobs and boats in the level, their real model and skin, animated in sync with the game
  (the vertices the game poses each tick); MC_Held: the items in their hands.
- MC_Things: dropped items (spinning, bobbing), primed TNT (swelling, then a blast) and falling blocks.
- Signs: board in the sign's wood with its real text, front and back, colour and glow.
- The game sees Blender: the viewport camera is a glowing observer with a "Blender" label in the level.
- The Blender World is the game's sky: a plain colour (the default grey too) is the sky and fog colour, an HDRI
  becomes a small (256x128) skybox around the player.
- MC_Pieces: liminal mesh pieces placed back into the world, as their real triangles.
- Holes: a cell broken in the game is cut out of the objects that had geometry there at that moment (a Geometry
  Nodes modifier "MC holes" per object, with its own hidden cutter in MC_Cutters, in the object's local space so the
  hole moves with the object). Objects added later are never cut. Live levels are hollow: a hole is just a hole.
- Lights: Blender point/spot/area lights become real light blocks in the game (level from the power, or the "mc_level"
  custom property; a Sun light is the level's sun), and light-emitting blocks placed in the game become point lights here.

Same rules as a level file: 1 m = 1 block, Z up, materials ending _sky/_nocollide/_thin, "light" materials glow,
scene custom properties (sky_color, fog_*, time_of_day, stone_fill, unlit, ...) are the level settings.
"""

bl_info = {
	"name": "BlenderMC Live",
	"author": "BlenderMC",
	"version": (0, 2, 0),
	"blender": (4, 2, 0),
	"location": "3D View > Sidebar > BlenderMC",
	"description": "Live link between this scene and a running BlenderMC (Minecraft) level",
	"category": "3D View",
}

import hashlib
import json
import math
import os
import socket
import struct
import tempfile
import time
import zlib

import bpy
import bmesh
import numpy as np
from mathutils import Matrix, Vector

PROTOCOL = 2
MIRROR = "MC_LIVE"
# Blender (x, y, z) -> glTF / Minecraft (x, z, -y)
AXIS = Matrix(((1, 0, 0, 0), (0, 0, 1, 0), (0, -1, 0, 0), (0, 0, 0, 1)))
AXIS_INV = AXIS.inverted()
MESHLIKE = {"MESH", "CURVE", "SURFACE", "FONT", "META"}
TICK = 0.05
# At most one batch this often while something keeps changing (dragging), and never before the game applied the last.
THROTTLE = 0.12
ACK_TIMEOUT = 6.0
HOLE_PAD = 0.002
STREAMED = {"player", "entities"}
CAMERA_EVERY = 0.1
SKY_SIZE = (256, 128)


# --------------------------------------------------------------------------- framing


class Link:
	"""Non-blocking socket with length-prefixed frames: [u32 header len][JSON][u32 payload len][payload]."""

	def __init__(self, host, port):
		self.sock = socket.create_connection((host, port), timeout=2.0)
		self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
		self.sock.setblocking(False)
		self.inbox = bytearray()
		self.outbox = bytearray()

	def send(self, header, payload=b""):
		h = json.dumps(header, separators=(",", ":")).encode("utf-8")
		self.outbox += struct.pack(">I", len(h)) + h + struct.pack(">I", len(payload)) + payload

	def pump(self):
		"""Writes what it can, reads what is there; returns complete frames as (header, payload)."""
		while self.outbox:
			try:
				sent = self.sock.send(self.outbox[: 1 << 20])
			except (BlockingIOError, InterruptedError):
				break
			if sent <= 0:
				break
			del self.outbox[:sent]
		while True:
			try:
				chunk = self.sock.recv(1 << 20)
			except (BlockingIOError, InterruptedError):
				break
			if not chunk:
				raise ConnectionError("game closed the link")
			self.inbox += chunk
		frames = []
		while True:
			if len(self.inbox) < 4:
				break
			hl = struct.unpack_from(">I", self.inbox, 0)[0]
			if len(self.inbox) < 8 + hl:
				break
			pl = struct.unpack_from(">I", self.inbox, 4 + hl)[0]
			end = 8 + hl + pl
			if len(self.inbox) < end:
				break
			header = json.loads(bytes(self.inbox[4 : 4 + hl]).decode("utf-8"))
			payload = bytes(self.inbox[8 + hl : end])
			del self.inbox[:end]
			frames.append((header, payload))
		return frames

	def close(self):
		try:
			self.sock.close()
		except OSError:
			pass


# --------------------------------------------------------------------------- glb writing


def png_from_pixels(width, height, rgba):
	"""rgba: uint8 (height, width, 4), top row first."""
	raw = np.zeros((height, width * 4 + 1), np.uint8)
	raw[:, 1:] = rgba.reshape(height, width * 4)

	def chunk(kind, data):
		return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

	ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
	return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw.tobytes(), 6)) + chunk(b"IEND", b"")


_png_cache = {}


def image_png(image):
	"""PNG bytes of a Blender image: its file or packed data when that is a PNG, else its pixels encoded."""
	try:
		path = bpy.path.abspath(image.filepath) if image.filepath else ""
		stamp = (image.name, image.is_dirty, path, os.path.getmtime(path) if path and os.path.isfile(path) else 0, tuple(image.size))
	except Exception:
		stamp = (image.name, image.is_dirty, tuple(image.size))
	cached = _png_cache.get(image.name)
	if cached and cached[0] == stamp:
		return cached[1]
	data = None
	if not image.is_dirty:
		if image.packed_file and bytes(image.packed_file.data[:4]) == b"\x89PNG":
			data = bytes(image.packed_file.data)
		elif stamp[2] and os.path.isfile(stamp[2]) and stamp[2].lower().endswith(".png"):
			with open(stamp[2], "rb") as f:
				data = f.read()
	if data is None:
		w, h = image.size
		if w == 0 or h == 0:
			return None
		px = np.empty(w * h * 4, np.float32)
		image.pixels.foreach_get(px)
		rgba = (np.clip(px, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8).reshape(h, w, 4)[::-1]
		data = png_from_pixels(w, h, np.ascontiguousarray(rgba))
	_png_cache[image.name] = (stamp, data)
	return data


def find_image(socket_in, depth=0):
	"""The image texture feeding a shader socket (through simple nodes such as Mix or Hue/Saturation)."""
	if socket_in is None or not socket_in.is_linked or depth > 4:
		return None
	node = socket_in.links[0].from_node
	if node.type == "TEX_IMAGE":
		return node.image
	for inp in node.inputs:
		if inp.is_linked:
			found = find_image(inp, depth + 1)
			if found is not None:
				return found
	return None


class Glb:
	def __init__(self):
		self.json = {"asset": {"version": "2.0", "generator": "blendermc_live"}, "buffers": [], "bufferViews": [], "accessors": []}
		self.bin = bytearray()
		self.textures = {}

	def view(self, data):
		while len(self.bin) % 4:
			self.bin += b"\x00"
		self.json["bufferViews"].append({"buffer": 0, "byteOffset": len(self.bin), "byteLength": len(data)})
		self.bin += data
		return len(self.json["bufferViews"]) - 1

	def accessor(self, array, kind, component, bounds=False):
		entry = {"bufferView": self.view(array.tobytes()), "componentType": component, "count": int(array.shape[0]), "type": kind}
		if bounds and array.shape[0]:
			entry["min"] = [float(v) for v in array.min(axis=0)]
			entry["max"] = [float(v) for v in array.max(axis=0)]
		self.json["accessors"].append(entry)
		return len(self.json["accessors"]) - 1

	def texture(self, png):
		key = hashlib.sha1(png).hexdigest()
		if key not in self.textures:
			self.json.setdefault("images", []).append({"bufferView": self.view(png), "mimeType": "image/png"})
			self.json.setdefault("textures", []).append({"source": len(self.json["images"]) - 1})
			self.textures[key] = len(self.json["textures"]) - 1
		return self.textures[key]

	def material(self, mat):
		entry = {"name": mat.name if mat else "default"}
		color = [1.0, 1.0, 1.0, 1.0]
		image = None
		strength = 0.0
		tree = getattr(mat, "node_tree", None) if mat else None
		if tree is not None:
			shader = None
			for node in tree.nodes:
				if node.type == "BSDF_PRINCIPLED":
					shader = node
					break
			if shader is not None:
				base = shader.inputs.get("Base Color")
				image = find_image(base)
				if image is None and base is not None:
					color = list(base.default_value)
				alpha = shader.inputs.get("Alpha")
				if alpha is not None and not alpha.is_linked:
					color[3] = float(alpha.default_value)
				es = shader.inputs.get("Emission Strength")
				ec = shader.inputs.get("Emission Color") or shader.inputs.get("Emission")
				if es is not None and es.default_value > 0 and ec is not None and (ec.is_linked or max(ec.default_value[:3]) > 0):
					strength = float(es.default_value)
			else:
				for node in tree.nodes:
					if node.type == "EMISSION":
						strength = float(node.inputs["Strength"].default_value)
						image = find_image(node.inputs["Color"])
						if image is None:
							color = list(node.inputs["Color"].default_value)
						break
					if node.type == "BSDF_DIFFUSE":
						image = find_image(node.inputs["Color"])
						if image is None:
							color = list(node.inputs["Color"].default_value)
						break
		elif mat is not None:
			color = list(mat.diffuse_color)
		pbr = {"baseColorFactor": [float(c) for c in color[:4]]}
		if image is not None:
			png = image_png(image)
			if png:
				pbr["baseColorTexture"] = {"index": self.texture(png)}
				pbr["baseColorFactor"] = [1.0, 1.0, 1.0, float(color[3])]
		entry["pbrMetallicRoughness"] = pbr
		if strength > 0:
			entry["emissiveFactor"] = [1.0, 1.0, 1.0]
			entry["extensions"] = {"KHR_materials_emissive_strength": {"emissiveStrength": strength}}
		self.json.setdefault("materials", []).append(entry)
		return len(self.json["materials"]) - 1

	def finish(self):
		if self.bin:
			self.json["buffers"] = [{"byteLength": len(self.bin)}]
		else:
			del self.json["buffers"]
			del self.json["bufferViews"]
			del self.json["accessors"]
		text = json.dumps(self.json, separators=(",", ":")).encode("utf-8")
		text += b" " * (-len(text) % 4)
		while len(self.bin) % 4:
			self.bin += b"\x00"
		out = bytearray()
		total = 12 + 8 + len(text) + (8 + len(self.bin) if self.bin else 0)
		out += struct.pack("<III", 0x46546C67, 2, total)
		out += struct.pack("<II", len(text), 0x4E4F534A) + text
		if self.bin:
			out += struct.pack("<II", len(self.bin), 0x004E4942) + self.bin
		return bytes(out)


def object_glb(obj, depsgraph):
	"""One object as a .glb: world transform baked in (glTF axes), one primitive per material slot."""
	ev = obj.evaluated_get(depsgraph)
	glb = Glb()
	me = ev.to_mesh()
	try:
		me.calc_loop_triangles()
		nt = len(me.loop_triangles)
		node = {"name": obj.name}
		glb.json["nodes"] = [node]
		glb.json["scenes"] = [{"nodes": [0]}]
		glb.json["scene"] = 0
		if nt == 0:
			return glb.finish()
		nl = len(me.loops)
		tri_loops = np.empty(nt * 3, np.int32)
		me.loop_triangles.foreach_get("loops", tri_loops)
		tri_mat = np.empty(nt, np.int32)
		me.loop_triangles.foreach_get("material_index", tri_mat)
		loop_vert = np.empty(nl, np.int32)
		me.loops.foreach_get("vertex_index", loop_vert)
		co = np.empty(len(me.vertices) * 3, np.float32)
		me.vertices.foreach_get("co", co)
		cn = np.empty(nl * 3, np.float32)
		me.corner_normals.foreach_get("vector", cn)
		uv = np.zeros(nl * 2, np.float32)
		layer = me.uv_layers.active
		if layer is not None:
			try:
				layer.uv.foreach_get("vector", uv)
			except AttributeError:
				layer.data.foreach_get("uv", uv)
		if obj.get("drop_outside"):
			# A solid block carved from inside (level_shell): its outside is never seen, and would only cost cells.
			local = co.reshape(-1, 3)
			lo, hi = local.min(axis=0), local.max(axis=0)
			corner = local[loop_vert[tri_loops]].reshape(nt, 3, 3)
			outside = np.zeros(nt, bool)
			for axis in range(3):
				for bound in (lo[axis], hi[axis]):
					outside |= np.all(np.abs(corner[:, :, axis] - bound) < 1e-4, axis=1)
			tri_loops = tri_loops.reshape(nt, 3)[~outside].reshape(-1)
			tri_mat = tri_mat[~outside]
			nt = len(tri_mat)
			if nt == 0:
				return glb.finish()

		m = AXIS @ ev.matrix_world
		rot = np.array(m.to_3x3(), np.float64)
		nrm = np.array(m.to_3x3().inverted_safe().transposed(), np.float64)
		pos = co.reshape(-1, 3)[loop_vert[tri_loops]].astype(np.float64) @ rot.T + np.array(m.translation)
		nor = cn.reshape(-1, 3)[tri_loops].astype(np.float64) @ nrm.T
		nor /= np.maximum(np.linalg.norm(nor, axis=1, keepdims=True), 1e-12)
		tex = uv.reshape(-1, 2)[tri_loops].copy()
		tex[:, 1] = 1.0 - tex[:, 1]

		corners = np.arange(nt * 3, dtype=np.int64).reshape(nt, 3)
		if m.to_3x3().determinant() < 0:
			corners = corners[:, [0, 2, 1]]
		# Share corners that are the same vertex with the same normal and uv (keeps the mesh small for the game).
		key = np.concatenate([loop_vert[tri_loops][:, None].astype(np.float64), np.round(nor, 4), np.round(tex, 5)], axis=1)
		_, first, inverse = np.unique(key, axis=0, return_index=True, return_inverse=True)
		inverse = inverse.reshape(-1)
		v_pos = pos[first].astype(np.float32)
		v_nor = nor[first].astype(np.float32)
		v_tex = tex[first].astype(np.float32)
		a_pos = glb.accessor(v_pos, "VEC3", 5126, bounds=True)
		a_nor = glb.accessor(v_nor, "VEC3", 5126)
		a_tex = glb.accessor(v_tex, "VEC2", 5126)

		slots = ev.material_slots
		primitives = []
		materials = {}
		for index in np.unique(tri_mat):
			mat = slots[index].material if 0 <= index < len(slots) else None
			mkey = mat.name if mat else None
			if mkey not in materials:
				materials[mkey] = glb.material(mat)
			idx = inverse[corners[tri_mat == index]].reshape(-1).astype(np.uint32)
			primitives.append({
				"attributes": {"POSITION": a_pos, "NORMAL": a_nor, "TEXCOORD_0": a_tex},
				"indices": glb.accessor(idx, "SCALAR", 5125),
				"material": materials[mkey],
			})
		glb.json["meshes"] = [{"name": obj.name, "primitives": primitives}]
		node["mesh"] = 0
		return glb.finish()
	finally:
		ev.to_mesh_clear()


def gltf_matrix(matrix_world):
	m = AXIS @ matrix_world @ AXIS_INV
	return [float(m[r][c]) for c in range(4) for r in range(4)]


def scene_glb(scene, settings):
	"""Scene settings (custom properties as extras) and the spawn / sun empties, no meshes."""
	glb = Glb()
	nodes = []
	for obj in scene.objects:
		base = obj.name.split(".")[0].lower()
		if base in ("spawn", "sun") and obj.type == "EMPTY" and not in_mirror(obj):
			nodes.append({"name": base, "matrix": gltf_matrix(obj.matrix_world)})
	view_layer = bpy.context.view_layer or scene.view_layers[0]
	for obj in scene.objects:
		if obj.type != "LIGHT" or in_mirror(obj) or obj.hide_render or not visible(obj, view_layer):
			continue
		if obj.data.type == "SUN":
			# A Sun light is the level's sun (unless a "sun" empty already says where it is).
			if not any(node["name"] == "sun" for node in nodes):
				nodes.append({"name": "sun", "matrix": gltf_matrix(obj.matrix_world)})
			continue
		position = AXIS @ obj.matrix_world.translation
		# The colour as it shows on screen (sRGB), brightest channel 1: the game tints what the lamp reaches with it.
		color = [linear_to_srgb(c) for c in obj.data.color]
		peak = max(max(color), 1e-6)
		nodes.append({"name": f"mc_light_{len(nodes)}", "translation": [float(v) for v in position],
			"extras": {"mc_light": light_level(obj), "mc_color": [round(c / peak, 4) for c in color]}})
	extras = {}
	for key in scene.keys():
		if key.startswith("_") or key.startswith("blendermc"):
			continue
		value = scene[key]
		if hasattr(value, "to_list"):
			value = value.to_list()
		elif hasattr(value, "to_dict"):
			continue
		try:
			json.dumps(value)
		except TypeError:
			continue
		extras[key] = value
	if settings.draft:
		extras["unlit"] = True
	# The Blender World is the sky (scene custom properties sky_color / fog_color still win).
	if S.sky_color:
		extras.setdefault("sky_color", S.sky_color)
		extras.setdefault("fog_color", S.sky_color)
	glb.json["nodes"] = nodes
	glb.json["scenes"] = [{"nodes": list(range(len(nodes))), "extras": extras}]
	glb.json["scene"] = 0
	return glb.finish()


# --------------------------------------------------------------------------- what is part of the level


def in_mirror(obj):
	"""Made by the link: in an MC_ collection and tagged with its own name (a copy the user makes gets another name)."""
	if not any(collection.name.startswith("MC_") for collection in obj.users_collection):
		return False
	tag = obj.get("mc_mirror")
	return tag == obj.name if tag is not None else "." not in obj.name


def mirror_link(scene, collection, obj):
	obj["mc_mirror"] = obj.name
	mirror_collection(scene, collection).objects.link(obj)


def adopt_copies(scene):
	"""Objects the user copied from the mirror (Shift+D on a block, a piece, a mob) are level objects: out of MC_."""
	for obj in list(scene.objects):
		mirror = [c for c in obj.users_collection if c.name.startswith("MC_")]
		if not mirror or in_mirror(obj):
			continue
		if obj.name not in scene.collection.objects:
			scene.collection.objects.link(obj)
		for collection in mirror:
			collection.objects.unlink(obj)
		for key in [k for k in obj.keys() if k.startswith("mc_")]:
			del obj[key]
		S.dirty.add(obj.name)


def visible(obj, view_layer):
	try:
		return obj.visible_get(view_layer=view_layer)
	except TypeError:
		return obj.visible_get()


def light_level(obj):
	"""Minecraft block light (1-15) of a Blender light: its "mc_level" property, else from its power (1000 W = 15)."""
	if "mc_level" in obj:
		return max(1, min(15, int(obj["mc_level"])))
	return max(1, min(15, round(15.0 * math.sqrt(max(obj.data.energy, 0.0) / 1000.0))))


def exported(obj, view_layer):
	if obj.type not in MESHLIKE or obj.hide_render or in_mirror(obj):
		return False
	if not visible(obj, view_layer):
		return False
	for collection in obj.users_collection:
		if collection.hide_render or collection.name == "AIR_cutters":
			return False
	return True


# --------------------------------------------------------------------------- session


class Session:
	def __init__(self):
		self.link = None
		self.level = ""
		self.origin = (0, 0, 0)
		self.ready = False
		self.dirty = set()
		self.full = True
		self.scene_dirty = True
		self.last_change = 0.0
		self.last_send = 0.0
		self.waiting = False
		self.waiting_since = 0.0
		self.hashes = {}
		self.scene_hash = None
		self.names = None
		self.status = "not connected"
		self.applied = ""
		self.player_in = False
		self.holes = set()
		self.holes_new = []
		self.holes_closed = []
		self.textures = {}
		self.block_meshes = {}
		self.camera_sent = None
		self.camera_time = 0.0
		self.explosions = []
		self.sky_key = None
		self.sky_glb = None
		self.sky_color = None
		self.sky_sent = None
		self.texture_dir = os.path.join(tempfile.gettempdir(), "blendermc_live")

	def connected(self):
		return self.link is not None


S = Session()


def settings():
	return bpy.context.scene.blendermc


def is_mirror_data(idb):
	name = idb.name.lower()
	if name.startswith(("mc_", "mc:", "mcpiece:", "mcq:", "mce:", "mct:", "mcb:", "mc ", "mc holes")):
		return True
	if isinstance(idb, bpy.types.Image):
		return idb.filepath.replace("\\", "/").find("blendermc_live") >= 0
	if isinstance(idb, bpy.types.NodeTree):
		pointer = idb.as_pointer()
		for mat in bpy.data.materials:
			if mat.node_tree is not None and mat.node_tree.as_pointer() == pointer:
				return is_mirror_data(mat)
	return False


def mark_all():
	S.full = True
	S.scene_dirty = True
	S.last_change = time.monotonic()


@bpy.app.handlers.persistent
def on_depsgraph(scene, depsgraph):
	if not S.ready:
		return
	for update in depsgraph.updates:
		idb = update.id
		if isinstance(idb, bpy.types.Object):
			original = idb.original
			if in_mirror(original):
				continue
			if original.type == "LIGHT" or original.name.split(".")[0].lower() in ("spawn", "sun"):
				S.scene_dirty = True
				continue
			S.dirty.add(original.name)
		elif isinstance(idb, bpy.types.Light):
			S.scene_dirty = True
		elif isinstance(idb, (bpy.types.Material, bpy.types.Image, bpy.types.NodeTree, bpy.types.Collection)):
			# What the game mirrors here (MC_ collections, mc: materials, their node trees and textures) is not level
			# data: reacting to it would send the level again for every block the player places (the game flickered).
			if not is_mirror_data(idb.original):
				S.full = True
		elif isinstance(idb, bpy.types.Scene):
			S.scene_dirty = True
		elif isinstance(idb, bpy.types.World):
			S.scene_dirty = True
		S.last_change = time.monotonic()


def sync(scene):
	view_layer = bpy.context.view_layer or scene.view_layers[0]
	try:
		depsgraph = bpy.context.evaluated_depsgraph_get()
	except (AttributeError, RuntimeError):
		depsgraph = view_layer.depsgraph
	adopt_copies(scene)
	objects = {obj.name: obj for obj in scene.objects if exported(obj, view_layer)}
	update_world_sky(scene)
	names = sorted(objects) + ([WORLD_SKY] if S.sky_glb else [])
	changed = False
	if names != S.names:
		S.link.send({"t": "objects", "names": names})
		for gone in set(S.hashes) - set(names):
			del S.hashes[gone]
		S.names = names
		changed = True
	todo = names if S.full else [name for name in S.dirty if name in objects]
	sent = 0
	for name in todo:
		try:
			data = object_glb(objects[name], depsgraph)
		except Exception as e:  # one broken object must not stop the link
			S.status = f"{name}: {e}"
			continue
		digest = hashlib.sha1(data).digest()
		if S.hashes.get(name) == digest:
			continue
		S.hashes[name] = digest
		S.link.send({"t": "object", "name": name}, data)
		sent += 1
		changed = True
	if S.sky_glb is not S.sky_sent:
		if S.sky_glb:
			S.link.send({"t": "object", "name": WORLD_SKY}, S.sky_glb)
		else:
			S.link.send({"t": "remove", "name": WORLD_SKY})
		S.sky_sent = S.sky_glb
		changed = True
	if S.scene_dirty:
		data = scene_glb(scene, scene.blendermc)
		digest = hashlib.sha1(data).digest()
		if digest != S.scene_hash:
			S.scene_hash = digest
			S.link.send({"t": "scene"}, data)
			changed = True
	S.dirty.clear()
	S.full = False
	S.scene_dirty = False
	if changed:
		S.link.send({"t": "commit"})
		S.waiting = True
		S.waiting_since = time.monotonic()
		S.last_send = S.waiting_since
		S.status = f"sent {sent} object(s)"


def tick():
	if S.link is None:
		return None
	scene = bpy.context.scene
	try:
		frames = S.link.pump()
		# Poses stream every tick: when Blender falls behind only the newest counts (no backlog, so no growing
		# delay); blocks, holes and everything else are never dropped.
		newest = {}
		for i, (header, _) in enumerate(frames):
			if header.get("t") in STREAMED:
				newest[header["t"]] = i
		for i, (header, payload) in enumerate(frames):
			if header.get("t") in STREAMED and newest[header["t"]] != i:
				continue
			receive(scene, header, payload)
		if frames:
			redraw()
		now = time.monotonic()
		send_camera(scene, now)
		animate_explosions(now)
		if S.waiting and now - S.waiting_since > ACK_TIMEOUT:
			S.waiting = False
		if S.ready and scene.blendermc.auto and not S.waiting and (S.dirty or S.full or S.scene_dirty) and now - S.last_send >= THROTTLE:
			sync(scene)
		if S.holes_new or S.holes_closed:
			apply_holes(scene)
		S.link.pump()
	except (ConnectionError, OSError) as e:
		disconnect(f"link lost: {e}")
		return None
	return TICK


def connect(context):
	props = context.scene.blendermc
	level = (props.level or "live").strip().lower()
	S.__init__()
	try:
		S.link = Link("127.0.0.1", props.port)
	except OSError as e:
		S.link = None
		S.status = f"cannot reach the game on port {props.port}: {e}. Is a world open?"
		return False
	S.level = level
	S.link.send({"t": "hello", "level": level, "protocol": PROTOCOL})
	S.status = "waiting for the game..."
	if not bpy.app.timers.is_registered(tick):
		bpy.app.timers.register(tick, first_interval=TICK, persistent=True)
	return True


def disconnect(reason="disconnected"):
	if S.link is not None:
		S.link.close()
	S.link = None
	S.ready = False
	S.status = reason
	if bpy.app.timers.is_registered(tick):
		bpy.app.timers.unregister(tick)


# --------------------------------------------------------------------------- game -> Blender




def mc_to_blender(x, y, z):
	return Vector((x, -z, y))


def mirror_collection(scene, name):
	root = bpy.data.collections.get(MIRROR)
	if root is None:
		root = bpy.data.collections.new(MIRROR)
	if root.name not in scene.collection.children:
		scene.collection.children.link(root)
	# Mirrored blocks, mobs and the player render (F12) like the rest; only the hole cutters stay out.
	root.hide_render = False
	if name == MIRROR:
		return root
	child = bpy.data.collections.get(name)
	if child is None:
		child = bpy.data.collections.new(name)
	if child.name not in root.children:
		root.children.link(child)
	child.hide_render = name == CUTTERS
	return child


def flat_material(name, color, emission=0.0):
	mat = bpy.data.materials.get(name)
	if mat is None:
		mat = bpy.data.materials.new(name)
		mat.diffuse_color = color
		try:
			mat.use_nodes = True
		except AttributeError:
			pass
		bsdf = mat.node_tree.nodes.get("Principled BSDF") if mat.node_tree else None
		if bsdf is not None:
			bsdf.inputs["Base Color"].default_value = color
			if emission > 0:
				bsdf.inputs["Emission Color"].default_value = color
				bsdf.inputs["Emission Strength"].default_value = emission
	return mat


def texture_material(name, key, color):
	"""A material that shows texture `key` (nearest filtering) once it arrives; flat colour until then."""
	mat = bpy.data.materials.get(name)
	if mat is None:
		mat = flat_material(name, color)
		mat["blendermc_texture"] = key
	image = S.textures.get(key)
	if image is not None:
		apply_texture(mat, image)
	return mat


def tinted_image(image, tint):
	"""A copy of image multiplied by the biome colour, like the game does to grass, leaves and water (kept in the .blend)."""
	name = short_name("mct:", f"{image.name}|{tint}")
	tinted = bpy.data.images.get(name)
	if tinted is not None:
		return tinted
	w, h = image.size
	if w == 0 or h == 0:
		return image
	px = np.empty(w * h * 4, np.float32)
	image.pixels.foreach_get(px)
	px = px.reshape(-1, 4)
	px[:, :3] *= np.array([((tint >> shift) & 255) / 255.0 for shift in (16, 8, 0)], np.float32)
	tinted = bpy.data.images.new(name, w, h, alpha=True)
	tinted.colorspace_settings.name = image.colorspace_settings.name
	tinted.pixels.foreach_set(px.reshape(-1))
	tinted.pack()
	return tinted


def apply_texture(mat, image):
	"""Image texture (nearest) into the base colour; with a biome tint (mc_tint) a tinted copy of it, so every view shows the colour."""
	tree = mat.node_tree
	if tree is None:
		return
	tint = mat.get("mc_tint", -1)
	if tint is not None and tint >= 0:
		image = tinted_image(image, tint)
	bsdf = tree.nodes.get("Principled BSDF")
	node = tree.nodes.get("MC texture")
	if node is None:
		node = tree.nodes.new("ShaderNodeTexImage")
		node.name = "MC texture"
		node.interpolation = "Closest"
		node.location = (-320, 300)
		if bsdf is not None:
			color = node.outputs["Color"]
			tree.links.new(color, bsdf.inputs["Base Color"])
			tree.links.new(node.outputs["Alpha"], bsdf.inputs["Alpha"])
			if mat.get("mc_emissive"):
				tree.links.new(color, bsdf.inputs["Emission Color"])
				bsdf.inputs["Emission Strength"].default_value = 1.0
	node.image = image
	try:
		mat.blend_method = "CLIP"
	except (AttributeError, TypeError):
		pass


def receive_texture(key, png):
	os.makedirs(S.texture_dir, exist_ok=True)
	path = os.path.join(S.texture_dir, hashlib.sha1(key.encode("utf-8")).hexdigest() + ".png")
	with open(path, "wb") as f:
		f.write(png)
	image = bpy.data.images.load(path, check_existing=True)
	image.reload()
	image.name = "mc:" + key.split(":", 1)[-1][:48]
	S.textures[key] = image
	for mat in bpy.data.materials:
		if mat.get("blendermc_texture") == key:
			apply_texture(mat, image)


def cube_mesh(name):
	mesh = bpy.data.meshes.get(name)
	if mesh is None:
		mesh = bpy.data.meshes.new(name)
		# Minecraft cell [0,1]^3 in Blender axes: x 0..1, y -1..0, z 0..1, with per-face UVs.
		v = [(0, -1, 0), (1, -1, 0), (1, 0, 0), (0, 0, 0), (0, -1, 1), (1, -1, 1), (1, 0, 1), (0, 0, 1)]
		f = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]
		mesh.from_pydata(v, [], f)
		mesh.materials.append(None)
		uv = mesh.uv_layers.new(name="UVMap")
		for poly in mesh.polygons:
			for corner, loop in zip(((0, 0), (1, 0), (1, 1), (0, 1)), poly.loop_indices):
				uv.data[loop].uv = corner
		mesh.update()
	return mesh


def cell_name(p):
	return f"mc_{p[0]}_{p[1]}_{p[2]}"


def remove_cell_object(p):
	obj = bpy.data.objects.get(cell_name(p))
	if obj is not None and in_mirror(obj):
		mesh = obj.data if obj.type == "MESH" and obj.data.users == 1 and obj.data.name.startswith("mc_piece") else None
		for child in list(obj.children_recursive):
			data = child.data
			bpy.data.objects.remove(child)
			if isinstance(data, bpy.types.Curve) and data.users == 0:
				bpy.data.curves.remove(data)
		bpy.data.objects.remove(obj)
		if mesh is not None:
			bpy.data.meshes.remove(mesh)
	lamp = bpy.data.objects.get("mcl" + cell_name(p)[2:])
	if lamp is not None and in_mirror(lamp):
		data = lamp.data
		bpy.data.objects.remove(lamp)
		if data is not None and data.users == 0:
			bpy.data.lights.remove(data)


def short_name(prefix, key):
	name = prefix + key
	return name if len(name) <= 60 else prefix + hashlib.sha1(key.encode("utf-8")).hexdigest()[:20]


def quad_material(tex, tint, emissive):
	name = short_name("mcq:", f"{tex}|{tint}")
	mat = bpy.data.materials.get(name)
	if mat is None:
		mat = flat_material(name, (1.0, 1.0, 1.0, 1.0))
		mat["blendermc_texture"] = tex
		mat["mc_tint"] = tint
		mat["mc_emissive"] = bool(emissive)
	image = S.textures.get(tex)
	if image is not None:
		apply_texture(mat, image)
	return mat


def receive_block_model(header, payload):
	"""The game's baked model of one block state: quads in cell space (0..1), UVs local to each face's texture."""
	key = header["key"]
	data = np.frombuffer(payload, dtype="<f4").reshape(-1, 4, 5)
	quads = header.get("quads", [])
	name = short_name("mcb:", key)
	old = bpy.data.meshes.get(name)
	mesh = bpy.data.meshes.new(name)
	if len(quads) == len(data) and len(data):
		pos = data[:, :, 0:3].reshape(-1, 3)
		verts = np.stack([pos[:, 0], -pos[:, 2], pos[:, 1]], axis=1)
		faces = np.arange(len(data) * 4).reshape(-1, 4)
		mesh.from_pydata(verts.tolist(), [], faces.tolist())
		layer = mesh.uv_layers.new(name="UVMap")
		uv = data[:, :, 3:5].reshape(-1, 2).astype(np.float32).copy()
		uv[:, 1] = 1.0 - uv[:, 1]
		layer.uv.foreach_set("vector", uv.reshape(-1))
		slots = {}
		indices = []
		for quad in quads:
			mkey = (quad["tex"], quad.get("tint", -1), bool(quad.get("emissive")))
			if mkey not in slots:
				slots[mkey] = len(mesh.materials)
				mesh.materials.append(quad_material(*mkey))
			indices.append(slots[mkey])
		mesh.polygons.foreach_set("material_index", np.array(indices, np.int32))
		mesh.update()
	mesh["mc_model"] = key
	S.block_meshes[key] = mesh
	for obj in bpy.data.objects:
		if obj.get("mc_model") == key and in_mirror(obj) and obj.type == "MESH" and not obj.get("mc_sign"):
			use_block_mesh(obj, mesh)
	if old is not None and old.users == 0:
		bpy.data.meshes.remove(old)


def use_block_mesh(obj, mesh):
	obj.data = mesh
	for slot in obj.material_slots:
		slot.link = "DATA"


def receive_block(scene, header):
	p = header["p"]
	remove_cell_object(p)
	block_id = header["id"]
	c = header.get("color", 0x808080)
	color = (((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0, 1.0)
	if header.get("sign"):
		build_sign(scene, p, header)
		return
	model = S.block_meshes.get(header.get("model"))
	if model is not None and model.name not in bpy.data.meshes:
		model = None
	obj = bpy.data.objects.new(cell_name(p), model if model is not None else cube_mesh("mc_cube"))
	obj.location = mc_to_blender(p[0], p[1], p[2])
	obj["mc_block"] = block_id
	obj["mc_model"] = header.get("model", "")
	if model is None:
		# Until the real model arrives: a cube in the block's map colour (object-level, the cube mesh is shared).
		obj.material_slots[0].link = "OBJECT"
		obj.material_slots[0].material = texture_material("mc:" + block_id, block_id, color)
	mirror_link(scene, "MC_Blocks", obj)
	level = header.get("light", 0)
	if level > 0:
		# The lamp sits inside the block: a glowstone cube casting shadows would keep all its light in.
		obj.visible_shadow = False
		# A torch, glowstone, lantern...: a point light here too, as bright as the game makes it (15 = 1000 W).
		data = bpy.data.lights.new("mcl" + cell_name(p)[2:], "POINT")
		data.energy = 1000.0 * (level / 15.0) ** 2
		data.color = (1.0, 0.82, 0.55)
		data.shadow_soft_size = 0.25
		lamp = bpy.data.objects.new(data.name, data)
		lamp.location = mc_to_blender(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5)
		mirror_link(scene, "MC_Blocks", lamp)


def receive_piece(scene, header, payload):
	p = header["p"]
	remove_cell_object(p)
	data = np.frombuffer(payload, dtype="<f4").reshape(-1, 25)
	if data.shape[0] == 0:
		return
	base = np.array([p[0], p[1], p[2]], np.float64)
	verts = []
	faces = []
	uvs = []
	mats = []
	for row in data:
		face = []
		for k in range(3):
			x, y, z = row[k * 3 : k * 3 + 3] + base
			verts.append((x, -z, y))
			face.append(len(verts) - 1)
			uvs.append((row[18 + k * 2], 1.0 - row[19 + k * 2]))
		faces.append(face)
		mats.append(int(row[24]))
	mesh = bpy.data.meshes.new("mc_piece")
	mesh.from_pydata(verts, [], faces)
	layer = mesh.uv_layers.new(name="UVMap")
	layer.uv.foreach_set("vector", np.array(uvs, np.float32).reshape(-1))
	for material in header.get("materials", []):
		color = tuple(material.get("color", [1, 1, 1, 1]))
		name = "mcpiece:" + material["name"]
		if material.get("tex"):
			mesh.materials.append(texture_material(name, material["tex"], color))
		else:
			mesh.materials.append(flat_material(name, color, 3.0 if material.get("emissive") else 0.0))
	mesh.polygons.foreach_set("material_index", np.array(mats, np.int32))
	mesh.update()
	obj = bpy.data.objects.new(cell_name(p), mesh)
	obj["mc_piece"] = True
	mirror_link(scene, "MC_Pieces", obj)


def player_objects(scene):
	eye = bpy.data.objects.get("MC_Eye")
	if eye is None:
		cam = bpy.data.cameras.new("MC_Eye")
		cam.sensor_fit = "VERTICAL"
		cam.angle_y = math.radians(70.0)
		cam.clip_start = 0.05
		eye = bpy.data.objects.new("MC_Eye", cam)
		mirror_link(scene, MIRROR, eye)
	return eye


def receive_player(scene, header):
	S.player_in = bool(header.get("in"))
	if not S.player_in:
		return
	x, y, z = header["p"]
	yaw = math.radians(header.get("yaw", 0.0))
	pitch = math.radians(header.get("pitch", 0.0))
	eye = player_objects(scene)
	feet = mc_to_blender(x, y, z)
	eye.location = feet + Vector((0.0, 0.0, header.get("eye", 1.62)))
	eye.rotation_euler = (math.pi / 2 - pitch, 0.0, math.pi - yaw)


def entity_name(entity_id):
	return f"mce_{entity_id}"


def receive_entity_mesh(scene, header, payload):
	"""A living entity's model: quads (4 vertices each) with their UVs; positions come every tick with "entities"."""
	name = entity_name(header["id"])
	n = header["n"]
	uv = np.frombuffer(payload, dtype="<f4").reshape(-1, 2).astype(np.float32).copy()
	uv[:, 1] = 1.0 - uv[:, 1]
	mesh = bpy.data.meshes.new(name)
	mesh.from_pydata(np.zeros((n, 3)).tolist(), [], np.arange(n - n % 4).reshape(-1, 4).tolist())
	layer = mesh.uv_layers.new(name="UVMap")
	layer.uv.foreach_set("vector", uv[: len(mesh.loops)].reshape(-1))
	mesh.materials.append(texture_material(short_name("mce:", header["tex"]), header["tex"], (1.0, 1.0, 1.0, 1.0)))
	obj = bpy.data.objects.get(name)
	if obj is None:
		obj = bpy.data.objects.new(name, mesh)
		mirror_link(scene, "MC_Entities", obj)
	else:
		old = obj.data
		obj.data = mesh
		if old.users == 0:
			bpy.data.meshes.remove(old)
	obj["mc_entity"] = header.get("type", "")
	if header.get("player"):
		obj["mc_player"] = True


def receive_entities(header, payload):
	"""This tick's pose of every mirrored entity: vertex positions in the order of its mesh; the rest are gone."""
	positions = np.frombuffer(payload, dtype="<f4").reshape(-1, 3)
	keep = set()
	at = 0
	hands_keep = set()
	for entry in header.get("list", []):
		entity_id, n = entry[0], entry[1]
		for hand in (entry[2] if len(entry) > 2 else []):
			hands_keep.add(place_held(bpy.context.scene, entity_id, hand))
		name = entity_name(entity_id)
		keep.add(name)
		obj = bpy.data.objects.get(name)
		chunk = positions[at : at + n]
		at += n
		if obj is None or len(obj.data.vertices) != n or len(chunk) != n:
			continue
		co = np.stack([chunk[:, 0], -chunk[:, 2], chunk[:, 1]], axis=1).astype(np.float32)
		obj.data.vertices.foreach_set("co", co.reshape(-1))
		obj.data.update()
	receive_things(bpy.context.scene, header.get("things", []))
	prune("MC_Held", hands_keep)
	collection = bpy.data.collections.get("MC_Entities")
	if collection is not None:
		for obj in list(collection.objects):
			if obj.name not in keep:
				mesh = obj.data
				bpy.data.objects.remove(obj)
				if mesh is not None and mesh.users == 0:
					bpy.data.meshes.remove(mesh)


WORLD_SKY = "__world_sky__"


def viewport_matrix(scene):
	"""Where the first 3D viewport looks from (its camera when it looks through one); None in the player's eyes."""
	wm = bpy.context.window_manager
	for window in wm.windows:
		for area in window.screen.areas:
			if area.type != "VIEW_3D":
				continue
			r3d = area.spaces.active.region_3d
			if r3d is None:
				continue
			if r3d.view_perspective == "CAMERA":
				if scene.camera is None or scene.camera.name == "MC_Eye":
					return None
				return scene.camera.matrix_world.copy()
			return r3d.view_matrix.inverted()
	return None


def send_camera(scene, now):
	if not S.ready or not scene.blendermc.show_camera or now - S.camera_time < CAMERA_EVERY:
		return
	S.camera_time = now
	m = viewport_matrix(scene)
	if m is None:
		return
	loc = AXIS @ m.translation
	f = m.to_3x3() @ Vector((0.0, 0.0, -1.0))
	d = Vector((f.x, f.z, -f.y)).normalized()
	yaw = math.degrees(math.atan2(-d.x, d.z))
	pitch = math.degrees(-math.asin(max(-1.0, min(1.0, d.y))))
	now_view = (round(loc.x, 2), round(loc.y, 2), round(loc.z, 2), round(yaw, 1), round(pitch, 1))
	if now_view == S.camera_sent:
		return
	S.camera_sent = now_view
	S.link.send({"t": "camera", "p": list(now_view[:3]), "yaw": now_view[3], "pitch": now_view[4]})


def linear_to_srgb(c):
	c = max(0.0, min(1.0, c))
	return c * 12.92 if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055


def world_background(scene):
	"""(colour (linear rgb), strength, environment image or None) of the scene's World."""
	world = scene.world
	if world is None:
		return (0.05, 0.05, 0.05), 1.0, None
	tree = getattr(world, "node_tree", None)
	if tree is not None:
		for node in tree.nodes:
			if node.type == "OUTPUT_WORLD" and node.is_active_output and node.inputs["Surface"].is_linked:
				bg = node.inputs["Surface"].links[0].from_node
				if bg.type == "BACKGROUND":
					strength = float(bg.inputs["Strength"].default_value)
					color = bg.inputs["Color"]
					if color.is_linked:
						image = find_image(color) if color.links[0].from_node.type != "TEX_ENVIRONMENT" else color.links[0].from_node.image
						if image is not None:
							return tuple(color.default_value[:3]), strength, image
					return tuple(color.default_value[:3]), strength, None
	return tuple(world.color[:3]), 1.0, None


def sky_sphere_glb(png):
	"""A sphere around the eye with the equirectangular sky inside, material "world_sky" (drawn by the game as its skybox)."""
	segments, rings, radius = 48, 24, 100.0
	us = np.linspace(0.0, 1.0, segments + 1)
	vs = np.linspace(0.0, 1.0, rings + 1)
	uu, vv = np.meshgrid(us, vs)
	theta = (uu - 0.5) * 2.0 * math.pi
	phi = (vv - 0.5) * math.pi
	# Blender's equirectangular mapping: u = atan2(y, -x) / 2pi + 0.5, v = atan2(z, hypot(x, y)) / pi + 0.5
	bx = -np.cos(theta) * np.cos(phi)
	by = np.sin(theta) * np.cos(phi)
	bz = np.sin(phi)
	blender = np.stack([bx, by, bz], axis=-1).reshape(-1, 3)
	pos = np.stack([blender[:, 0], blender[:, 2], -blender[:, 1]], axis=1) * radius
	nor = -pos / radius
	tex = np.stack([uu.reshape(-1), 1.0 - vv.reshape(-1)], axis=1)
	idx = []
	w = segments + 1
	for r in range(rings):
		for c in range(segments):
			a, b, cc, d = r * w + c, r * w + c + 1, (r + 1) * w + c + 1, (r + 1) * w + c
			# wound to face inwards
			idx += [a, cc, b, a, d, cc]
	glb = Glb()
	glb.json["nodes"] = [{"name": WORLD_SKY, "mesh": 0}]
	glb.json["scenes"] = [{"nodes": [0]}]
	glb.json["scene"] = 0
	glb.json["materials"] = [{"name": "world_sky", "pbrMetallicRoughness": {"baseColorFactor": [1, 1, 1, 1], "baseColorTexture": {"index": glb.texture(png)}}}]
	glb.json["meshes"] = [{"name": WORLD_SKY, "primitives": [{
		"attributes": {"POSITION": glb.accessor(pos.astype(np.float32), "VEC3", 5126, bounds=True),
			"NORMAL": glb.accessor(nor.astype(np.float32), "VEC3", 5126),
			"TEXCOORD_0": glb.accessor(tex.astype(np.float32), "VEC2", 5126)},
		"indices": glb.accessor(np.array(idx, np.uint32), "SCALAR", 5125),
		"material": 0}]}]
	return glb.finish()


def update_world_sky(scene):
	"""World colour -> S.sky_color (sky and fog); an environment image -> S.sky_glb (a small skybox)."""
	color, strength, image = world_background(scene)
	key = (tuple(round(c, 4) for c in color), round(strength, 3), image.name if image else None, tuple(image.size) if image else None,
		image.is_dirty if image else None)
	if key == S.sky_key:
		return
	S.sky_key = key
	if image is None or image.size[0] == 0:
		S.sky_glb = None
		S.sky_color = "#%02x%02x%02x" % tuple(round(255 * linear_to_srgb(c * strength)) for c in color)
		return
	w, h = image.size
	if w * h <= 4_000_000:
		# Small enough to read: sample it down here (a copy of a generated image would lose its pixels).
		full = np.empty(w * h * 4, np.float32)
		image.pixels.foreach_get(full)
		full = full.reshape(h, w, 4)
		ys = ((np.arange(SKY_SIZE[1]) + 0.5) * h / SKY_SIZE[1]).astype(int)
		xs = ((np.arange(SKY_SIZE[0]) + 0.5) * w / SKY_SIZE[0]).astype(int)
		px = full[ys][:, xs]
	else:
		# A big HDRI file: let Blender scale a copy (re-read from its file) instead of loading every pixel.
		small = image.copy()
		try:
			small.scale(*SKY_SIZE)
			px = np.empty(SKY_SIZE[0] * SKY_SIZE[1] * 4, np.float32)
			small.pixels.foreach_get(px)
		finally:
			bpy.data.images.remove(small)
		px = px.reshape(SKY_SIZE[1], SKY_SIZE[0], 4)
	rgb = px[:, :, :3] * strength
	if image.is_float:
		rgb = 1.0 - np.exp(-rgb)              # HDR to display range
		rgb = np.where(rgb <= 0.0031308, rgb * 12.92, 1.055 * np.power(np.clip(rgb, 0, 1), 1 / 2.4) - 0.055)
	rgba = np.concatenate([np.clip(rgb, 0, 1), np.ones((SKY_SIZE[1], SKY_SIZE[0], 1), np.float32)], axis=2)
	pixels = (rgba * 255.0 + 0.5).astype(np.uint8)[::-1]
	S.sky_glb = sky_sphere_glb(png_from_pixels(SKY_SIZE[0], SKY_SIZE[1], np.ascontiguousarray(pixels)))
	avg = pixels[:, :, :3].reshape(-1, 3).mean(axis=0)
	S.sky_color = "#%02x%02x%02x" % tuple(int(v) for v in avg)


def prune(collection_name, keep):
	collection = bpy.data.collections.get(collection_name)
	if collection is None:
		return
	for obj in list(collection.objects):
		if obj.name not in keep:
			bpy.data.objects.remove(obj)


def offset_mesh(prefix, key, shift):
	"""A copy of block model `key` moved by `shift` (Blender axes): pivot at the bottom centre or the centre."""
	name = short_name(prefix, key)
	mesh = bpy.data.meshes.get(name)
	if mesh is not None:
		return mesh
	source = S.block_meshes.get(key)
	if source is None or source.name not in bpy.data.meshes:
		return None
	mesh = source.copy()
	mesh.name = name
	mesh.transform(Matrix.Translation(shift))
	return mesh


def sprite_mesh(tex, centered):
	"""A flat item (its texture on a 1x1 upright quad), standing on its base or centred (held)."""
	name = short_name("mcsc:" if centered else "mcsp:", tex)
	mesh = bpy.data.meshes.get(name)
	if mesh is not None:
		return mesh
	z0 = -0.5 if centered else 0.0
	mesh = bpy.data.meshes.new(name)
	mesh.from_pydata([(-0.5, 0, z0), (0.5, 0, z0), (0.5, 0, z0 + 1), (-0.5, 0, z0 + 1)], [], [(0, 1, 2, 3)])
	layer = mesh.uv_layers.new(name="UVMap")
	layer.uv.foreach_set("vector", [0, 0, 1, 0, 1, 1, 0, 1])
	mesh.materials.append(quad_material(tex, -1, False))
	return mesh


def thing_mesh(thing, centered):
	if thing.get("kind") == "block":
		return offset_mesh("mcbi:" if centered else "mcbc:", thing["model"], (-0.5, 0.5, -0.5 if centered else 0.0))
	if thing.get("tex"):
		return sprite_mesh(thing["tex"], centered)
	return None


def receive_things(scene, things):
	"""Dropped items, primed TNT and falling blocks this tick; the ones not listed are gone."""
	keep = set()
	for thing in things:
		name = f"mct_{thing['id']}"
		mesh = thing_mesh(thing, False)
		if mesh is None:
			continue
		keep.add(name)
		obj = bpy.data.objects.get(name)
		if obj is None:
			obj = bpy.data.objects.new(name, mesh)
			mirror_link(scene, "MC_Things", obj)
		elif obj.data != mesh:
			obj.data = mesh
		x, y, z = thing["p"]
		obj.location = mc_to_blender(x, y, z)
		size = thing.get("s", 1.0)
		obj.scale = (size, size, size)
		obj.rotation_euler = (0.0, 0.0, math.radians(thing.get("yaw", 0.0)))
	prune("MC_Things", keep)


def place_held(scene, entity_id, hand):
	"""An item in a hand: its mesh centred, placed by the game's hand matrix (column-major, level space)."""
	name = f"mch_{entity_id}_{hand.get('hand', 'right')}"
	mesh = thing_mesh(hand, True)
	if mesh is None:
		return name
	obj = bpy.data.objects.get(name)
	if obj is None:
		obj = bpy.data.objects.new(name, mesh)
		mirror_link(scene, "MC_Held", obj)
	elif obj.data != mesh:
		obj.data = mesh
	m = hand["m"]
	matrix = Matrix(((m[0], m[4], m[8], m[12]), (m[1], m[5], m[9], m[13]), (m[2], m[6], m[10], m[14]), (m[3], m[7], m[11], m[15])))
	obj.matrix_world = AXIS_INV @ matrix @ AXIS
	return name


def start_explosion(scene, header):
	"""TNT went off: a fireball that swells and fades with a flash of light."""
	x, y, z = header["p"]
	mesh = bpy.data.meshes.get("MC explosion")
	if mesh is None:
		mesh = bpy.data.meshes.new("MC explosion")
		bm = bmesh.new()
		bmesh.ops.create_uvsphere(bm, u_segments=16, v_segments=8, radius=1.0)
		bm.to_mesh(mesh)
		bm.free()
		mesh.materials.append(flat_material("MC explosion", (1.0, 0.55, 0.15, 1.0), 8.0))
	ball = bpy.data.objects.new("mcx_ball", mesh)
	ball.location = mc_to_blender(x, y, z)
	ball.scale = (0.01, 0.01, 0.01)
	mirror_link(scene, "MC_Effects", ball)
	light = bpy.data.lights.new("mcx_flash", "POINT")
	light.color = (1.0, 0.7, 0.4)
	flash = bpy.data.objects.new(light.name, light)
	flash.location = ball.location
	mirror_link(scene, "MC_Effects", flash)
	S.explosions.append((ball.name, flash.name, time.monotonic(), float(header.get("r", 4.0))))


def animate_explosions(now):
	alive = []
	for ball_name, flash_name, start, radius in S.explosions:
		t = (now - start) / 0.6
		ball = bpy.data.objects.get(ball_name)
		flash = bpy.data.objects.get(flash_name)
		if t >= 1.0 or ball is None:
			for obj in (ball, flash):
				if obj is not None:
					data = obj.data
					bpy.data.objects.remove(obj)
					if isinstance(data, bpy.types.Light) and data.users == 0:
						bpy.data.lights.remove(data)
			continue
		size = radius * 0.5 * math.sqrt(t)
		ball.scale = (size, size, size)
		if flash is not None:
			flash.data.energy = 40000.0 * (1.0 - t)
		alive.append((ball_name, flash_name, start, radius))
	S.explosions = alive


# MC sign texture layouts (pixels): board or post cube faces -> (u0, v0, u1, v1) on a 64x32 sheet.
SIGN_BOARD = {"front": (2, 2, 26, 14), "back": (28, 2, 52, 14), "top": (2, 0, 26, 2), "bottom": (26, 0, 50, 2), "left": (0, 2, 2, 14), "right": (26, 2, 28, 14)}
SIGN_POST = {"front": (2, 16, 4, 30), "back": (6, 16, 8, 30), "top": (2, 14, 4, 16), "bottom": (4, 14, 6, 16), "left": (0, 16, 2, 30), "right": (4, 16, 6, 30)}
HANGING_BOARD = {"front": (2, 14, 16, 24), "back": (18, 14, 32, 24), "top": (2, 12, 16, 14), "bottom": (16, 12, 30, 14), "left": (0, 14, 2, 24), "right": (16, 14, 18, 24)}


def sign_box(verts, faces, uvs, lo, hi, layout):
	"""A box in sign space (MC axes, x right, y up, z towards the reader) with each face mapped to its sheet area."""
	x0, y0, z0 = lo
	x1, y1, z1 = hi
	quads = {
		"front": [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)],
		"back": [(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)],
		"top": [(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)],
		"bottom": [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)],
		"left": [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)],
		"right": [(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)],
	}
	for face, corners in quads.items():
		u0, v0, u1, v1 = layout[face]
		start = len(verts)
		for (x, y, z) in corners:
			verts.append((x, -z, y))
		faces.append((start, start + 1, start + 2, start + 3))
		uvs += [(u0 / 64, 1 - v1 / 32), (u1 / 64, 1 - v1 / 32), (u1 / 64, 1 - v0 / 32), (u0 / 64, 1 - v0 / 32)]


def sign_mesh(kind, wood):
	"""Board (and post) of one kind of sign in one wood, front facing -Y, pivot at the cell's bottom centre."""
	name = short_name("mcsign:", f"{kind}|{wood}")
	mesh = bpy.data.meshes.get(name)
	if mesh is not None:
		return mesh
	verts, faces, uvs = [], [], []
	t = 1 / 24.0
	if kind == "standing":
		sign_box(verts, faces, uvs, (-0.5, 7 / 12, -t), (0.5, 13 / 12, t), SIGN_BOARD)
		sign_box(verts, faces, uvs, (-t, 0.0, -t), (t, 7 / 12, t), SIGN_POST)
	elif kind == "wall":
		sign_box(verts, faces, uvs, (-0.5, 0.2708, -0.5), (0.5, 0.7708, -0.5 + 2 * t), SIGN_BOARD)
	else:
		top = 0.625 if kind == "hanging" else 0.875
		sign_box(verts, faces, uvs, (-0.4375, top - 0.625, -0.0625), (0.4375, top, 0.0625), HANGING_BOARD)
	mesh = bpy.data.meshes.new(name)
	mesh.from_pydata(verts, [], faces)
	layer = mesh.uv_layers.new(name="UVMap")
	layer.uv.foreach_set("vector", np.array(uvs, np.float32).reshape(-1))
	folder = "entity/signs/hanging/" if kind in ("hanging", "wall_hanging") else "entity/signs/"
	mesh.materials.append(quad_material("minecraft:" + folder + wood, -1, False))
	return mesh


def sign_text_color(side):
	c = side.get("color", 0)
	rgb = [((c >> shift) & 255) / 255.0 for shift in (16, 8, 0)]
	if side.get("glow"):
		if c == 0:
			rgb = [0xF0 / 255, 0xEB / 255, 0xCC / 255]
	else:
		rgb = [v * 0.4 for v in rgb]
	return (*rgb, 1.0)


def build_sign(scene, p, header):
	"""A sign: its board and post in the sign's wood, and its four lines of text on both sides, as in the game."""
	sign = header["sign"]
	kind = sign.get("kind", "standing")
	board = bpy.data.objects.new(cell_name(p), sign_mesh(kind, sign.get("wood", "oak")))
	board.location = mc_to_blender(p[0] + 0.5, p[1], p[2] + 0.5)
	board.rotation_euler = (0.0, 0.0, -math.radians(sign.get("yaw", 0.0)))
	board["mc_block"] = header.get("id", "")
	board["mc_model"] = header.get("model", "")
	board["mc_sign"] = True
	mirror_link(scene, "MC_Blocks", board)
	if kind == "standing":
		centre, front, spacing, size = 0.8333, 1 / 24.0, 0.104, 0.12
	elif kind == "wall":
		centre, front, spacing, size = 0.5208, -0.5 + 1 / 12.0, 0.104, 0.12
	else:
		top = 0.625 if kind == "hanging" else 0.875
		centre, front, spacing, size = top - 0.3125, 0.0625, 0.09, 0.1
	for side_name, sign_side in (("front", 1.0), ("back", -1.0)):
		side = sign.get(side_name) or {}
		lines = side.get("lines", [])
		if not any(line.strip() for line in lines):
			continue
		color = sign_text_color(side)
		mat = flat_material(short_name("mcsigntext:", "%.3f,%.3f,%.3f|%d" % (*color[:3], bool(side.get("glow")))), color,
			1.0 if side.get("glow") else 0.0)
		depth = front + 0.003 if sign_side > 0 else (front - (2 / 24.0 if kind != "wall" else 2 / 24.0)) - 0.003
		if kind in ("hanging", "wall_hanging"):
			depth = 0.0625 + 0.003 if sign_side > 0 else -0.0625 - 0.003
		for i, line in enumerate(lines[:4]):
			if not line.strip():
				continue
			curve = bpy.data.curves.new(f"mcs_{p[0]}_{p[1]}_{p[2]}_{side_name}{i}", "FONT")
			curve.body = line
			curve.size = size
			curve.align_x = "CENTER"
			curve.align_y = "CENTER"
			curve.materials.append(mat)
			text = bpy.data.objects.new(curve.name, curve)
			text.parent = board
			# sign space (x, y, z) -> object space (x, -z, y); the front reads facing -Y, the back facing +Y
			text.location = (0.0, -depth, centre + (1.5 - i) * spacing)
			text.rotation_euler = (math.pi / 2, 0.0, 0.0 if sign_side > 0 else math.pi)
			mirror_link(scene, "MC_Blocks", text)


CUTTERS = "MC_Cutters"
HOLES_GROUP = "MC holes"
CORNERS = np.array([[0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0], [0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]], np.float64)
QUADS = np.array([(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)])


def cell_box(c):
	"""Minecraft cell c as a Blender world box (lo, hi), a hair bigger so faces on its sides go too."""
	lo = np.array([c[0], -(c[2] + 1), c[1]], np.float64) - HOLE_PAD
	hi = np.array([c[0] + 1, -c[2], c[1] + 1], np.float64) + HOLE_PAD
	return lo, hi


def holes_group():
	"""Geometry Nodes: the object minus its cutter, with the walls the Boolean adds removed again (a hole stays open)."""
	group = bpy.data.node_groups.get(HOLES_GROUP)
	if group is not None:
		allow_overlap(group)
		return group
	group = bpy.data.node_groups.new(HOLES_GROUP, "GeometryNodeTree")
	group.interface.new_socket("Geometry", in_out="INPUT", socket_type="NodeSocketGeometry")
	group.interface.new_socket("Cutter", in_out="INPUT", socket_type="NodeSocketObject")
	group.interface.new_socket("Geometry", in_out="OUTPUT", socket_type="NodeSocketGeometry")
	nodes, links = group.nodes, group.links
	inp = nodes.new("NodeGroupInput")
	out = nodes.new("NodeGroupOutput")

	def mark(value):
		node = nodes.new("GeometryNodeStoreNamedAttribute")
		node.data_type = "BOOLEAN"
		node.domain = "FACE"
		node.inputs["Name"].default_value = "mc_cap"
		node.inputs["Value"].default_value = value
		return node

	keep = mark(False)
	cap = mark(True)
	info = nodes.new("GeometryNodeObjectInfo")
	info.transform_space = "ORIGINAL"
	boolean = nodes.new("GeometryNodeMeshBoolean")
	boolean.operation = "DIFFERENCE"
	try:
		boolean.solver = "EXACT"
		boolean.inputs["Hole Tolerant"].default_value = True
	except (KeyError, TypeError, AttributeError):
		pass
	attribute = nodes.new("GeometryNodeInputNamedAttribute")
	attribute.data_type = "BOOLEAN"
	attribute.inputs["Name"].default_value = "mc_cap"
	delete = nodes.new("GeometryNodeDeleteGeometry")
	delete.domain = "FACE"
	remove = nodes.new("GeometryNodeRemoveAttribute")
	remove.inputs["Name"].default_value = "mc_cap"
	links.new(inp.outputs["Geometry"], keep.inputs["Geometry"])
	links.new(keep.outputs["Geometry"], boolean.inputs["Mesh 1"])
	links.new(inp.outputs["Cutter"], info.inputs["Object"])
	links.new(info.outputs["Geometry"], cap.inputs["Geometry"])
	links.new(cap.outputs["Geometry"], boolean.inputs["Mesh 2"])
	links.new(boolean.outputs["Mesh"], delete.inputs["Geometry"])
	links.new(attribute.outputs["Attribute"], delete.inputs["Selection"])
	links.new(delete.outputs["Geometry"], remove.inputs["Geometry"])
	links.new(remove.outputs["Geometry"], out.inputs["Geometry"])
	allow_overlap(group)
	return group


def allow_overlap(group):
	"""Hole boxes of neighbouring cells overlap a hair: without Self Intersection the Boolean wrecks the mesh around them."""
	for node in group.nodes:
		if node.type == "MESH_BOOLEAN":
			for name in ("Self Intersection", "Hole Tolerant"):
				socket = node.inputs.get(name)
				if socket is not None and not socket.default_value:
					socket.default_value = True


def cutter_socket(group):
	for item in group.interface.items_tree:
		if getattr(item, "in_out", None) == "INPUT" and getattr(item, "socket_type", "") == "NodeSocketObject":
			return item.identifier
	return None


def modifier_input(modifier, socket, value=...):
	"""Reads (or, given a value, sets) a Geometry Nodes modifier input: Blender 5 has modifier.properties.inputs, 4.x ID properties."""
	try:
		item = getattr(modifier.properties.inputs, socket)
		if value is not ...:
			item.value = value
		return item.value
	except AttributeError:
		if value is not ...:
			modifier[socket] = value
		return modifier.get(socket)


def modifier_cutter(modifier):
	if modifier is None or modifier.type != "NODES" or modifier.node_group is None:
		return None
	socket = cutter_socket(modifier.node_group)
	return modifier_input(modifier, socket) if socket else None


def triangle_bounds(obj, depsgraph):
	"""Per-triangle world bounds (min, max) of the evaluated object (its current holes already cut), or None."""
	ev = obj.evaluated_get(depsgraph)
	me = ev.to_mesh()
	try:
		me.calc_loop_triangles()
		nt = len(me.loop_triangles)
		if nt == 0:
			return None
		tv = np.empty(nt * 3, np.int32)
		me.loop_triangles.foreach_get("vertices", tv)
		co = np.empty(len(me.vertices) * 3, np.float32)
		me.vertices.foreach_get("co", co)
		m = np.array(ev.matrix_world, np.float64)
		pos = co.reshape(-1, 3).astype(np.float64) @ m[:3, :3].T + m[:3, 3]
		tri = pos[tv].reshape(nt, 3, 3)
		return tri.min(axis=1), tri.max(axis=1)
	finally:
		ev.to_mesh_clear()


def set_cutter(scene, obj, add=(), remove=()):
	"""Adds / removes hole cells on obj's own cutter (boxes stored in obj's local space, in cell order)."""
	group = holes_group()
	socket = cutter_socket(group)
	modifier = obj.modifiers.get("MC holes")
	if modifier is not None and modifier.type != "NODES":
		obj.modifiers.remove(modifier)
		modifier = None
	cutter = modifier_cutter(modifier)
	cells = []
	if cutter is not None and "mc_cells" in cutter:
		flat = list(cutter["mc_cells"])
		cells = [tuple(flat[i : i + 3]) for i in range(0, len(flat), 3)]
	verts = np.zeros((0, 3), np.float64)
	if cutter is not None and len(cutter.data.vertices) == len(cells) * 8:
		verts = np.empty(len(cutter.data.vertices) * 3, np.float64)
		cutter.data.vertices.foreach_get("co", verts)
		verts = verts.reshape(-1, 3)
	changed = False
	for cell in remove:
		if cell in cells:
			i = cells.index(cell)
			verts = np.delete(verts, range(i * 8, i * 8 + 8), axis=0)
			cells.pop(i)
			changed = True
	inv = np.array(obj.matrix_world.inverted(), np.float64)
	for cell in add:
		if cell in cells:
			continue
		lo, hi = cell_box(cell)
		world = lo + CORNERS * (hi - lo)
		verts = np.concatenate([verts, world @ inv[:3, :3].T + inv[:3, 3]])
		cells.append(cell)
		changed = True
	if not changed:
		return
	if not cells:
		if modifier is not None:
			obj.modifiers.remove(modifier)
		if cutter is not None:
			mesh = cutter.data
			bpy.data.objects.remove(cutter)
			bpy.data.meshes.remove(mesh)
		S.dirty.add(obj.name)
		return
	if cutter is None:
		cutter = bpy.data.objects.new(("MC_cut " + obj.name)[:63], bpy.data.meshes.new("MC_cut"))
		mirror_link(scene, CUTTERS, cutter)
		cutter.display_type = "WIRE"
		cutter.hide_render = True
		cutter.hide_select = True
	mesh = bpy.data.meshes.new("MC_cut")
	faces = (QUADS[None, :, :] + 8 * np.arange(len(cells))[:, None, None]).reshape(-1, 4)
	mesh.from_pydata(verts.tolist(), [], faces.tolist())
	old = cutter.data
	cutter.data = mesh
	bpy.data.meshes.remove(old)
	cutter["mc_cells"] = [int(v) for cell in cells for v in cell]
	cutter["mc_target"] = obj.name
	try:
		cutter.hide_set(True)
	except RuntimeError:
		pass
	if modifier is None:
		modifier = obj.modifiers.new("MC holes", "NODES")
		modifier.node_group = group
		modifier_input(modifier, socket, cutter)
	S.dirty.add(obj.name)
	obj.update_tag()


def apply_holes(scene):
	"""New holes cut the objects that have geometry in them right now; closed holes (restored in the game) come back."""
	new, closed = S.holes_new, S.holes_closed
	S.holes_new, S.holes_closed = [], []
	view_layer = bpy.context.view_layer or scene.view_layers[0]
	meshes = [obj for obj in scene.objects if obj.type == "MESH" and exported(obj, view_layer)]
	if closed:
		for obj in meshes:
			set_cutter(scene, obj, remove=closed)
	if not new:
		return
	try:
		depsgraph = bpy.context.evaluated_depsgraph_get()
	except (AttributeError, RuntimeError):
		depsgraph = view_layer.depsgraph
	boxes = [cell_box(cell) for cell in new]
	hits = {}
	for obj in meshes:
		bounds = triangle_bounds(obj, depsgraph)
		if bounds is None:
			continue
		tlo, thi = bounds
		for cell, (lo, hi) in zip(new, boxes):
			if np.any(np.all(tlo <= hi, axis=1) & np.all(thi >= lo, axis=1)):
				hits.setdefault(obj.name, []).append(cell)
	for name, cells in hits.items():
		set_cutter(scene, bpy.data.objects[name], add=cells)


def clear_holes(scene):
	"""Every hole back (removes all "MC holes" modifiers and cutters)."""
	for obj in scene.objects:
		modifier = obj.modifiers.get("MC holes") if obj.type == "MESH" else None
		if modifier is None:
			continue
		cutter = modifier_cutter(modifier)
		obj.modifiers.remove(modifier)
		if cutter is not None:
			mesh = cutter.data
			bpy.data.objects.remove(cutter)
			bpy.data.meshes.remove(mesh)
		S.dirty.add(obj.name)
	S.holes.clear()


def remove_old_markers(scene):
	"""Leftovers of earlier versions: Boolean "MC holes" modifiers and the visible MC_Holes wireframe."""
	for obj in scene.objects:
		modifier = obj.modifiers.get("MC holes") if obj.type == "MESH" else None
		if modifier is not None and modifier.type == "BOOLEAN":
			obj.modifiers.remove(modifier)
			S.dirty.add(obj.name)
	old = bpy.data.objects.get("MC_Holes")
	if old is not None:
		mesh = old.data
		bpy.data.objects.remove(old)
		if mesh is not None and mesh.users == 0:
			bpy.data.meshes.remove(mesh)


def receive(scene, header, payload):
	kind = header.get("t")
	if kind == "hello":
		if header.get("protocol") != PROTOCOL:
			S.status = f"game speaks protocol {header.get('protocol')}, add-on {PROTOCOL}: update both and restart Blender"
		S.origin = tuple(header.get("origin", (0, 0, 0)))
		S.ready = True
		remove_old_markers(scene)
		box = bpy.data.objects.get("MC_Player")
		if box is not None:
			bpy.data.objects.remove(box)
		# Cells already cut here (an earlier session) are known: a snapshot listing them must not cut new objects.
		for obj in scene.objects:
			cutter = modifier_cutter(obj.modifiers.get("MC holes")) if obj.type == "MESH" else None
			if cutter is not None and "mc_cells" in cutter:
				flat = list(cutter["mc_cells"])
				S.holes.update(tuple(flat[i : i + 3]) for i in range(0, len(flat), 3))
		mark_all()
		if header.get("protocol") == PROTOCOL:
			S.status = f"linked: level '{header.get('level')}' at {S.origin}"
	elif kind == "applied":
		S.waiting = False
		S.applied = f"{header.get('triangles', 0)} tris, {header.get('objects', 0)} objects, {header.get('ms', 0)} ms" + (" (partial)" if header.get("partial") else "")
	elif kind == "status":
		S.status = header.get("text", "")
	elif kind == "player":
		receive_player(scene, header)
	elif kind == "texture":
		receive_texture(header["key"], payload)
	elif kind == "block":
		receive_block(scene, header)
	elif kind == "unblock":
		remove_cell_object(header["p"])
	elif kind == "piece":
		receive_piece(scene, header, payload)
	elif kind == "blockmodel":
		receive_block_model(header, payload)
	elif kind == "entity_mesh":
		receive_entity_mesh(scene, header, payload)
	elif kind == "entities":
		receive_entities(header, payload)
	elif kind == "explosion":
		start_explosion(scene, header)
	elif kind == "clear_blocks":
		for name in ("MC_Blocks", "MC_Pieces"):
			collection = bpy.data.collections.get(name)
			if collection is not None:
				for obj in list(collection.objects):
					bpy.data.objects.remove(obj)
	elif kind == "hole":
		cell = tuple(header["p"])
		if header.get("on"):
			if cell not in S.holes:
				S.holes.add(cell)
				S.holes_new.append(cell)
		elif cell in S.holes:
			S.holes.discard(cell)
			S.holes_closed.append(cell)
	elif kind == "holes":
		# Only adds: once Blender has cut a hole, the game forgets it (the cell has no geometry left).
		for c in header.get("cells", []):
			cell = tuple(c)
			if cell not in S.holes:
				S.holes.add(cell)
				S.holes_new.append(cell)


def redraw():
	for window in bpy.context.window_manager.windows:
		for area in window.screen.areas:
			if area.type == "VIEW_3D":
				area.tag_redraw()


# --------------------------------------------------------------------------- UI


def camera_toggled(self, context):
	if S.ready and not self.show_camera:
		S.link.send({"t": "camera_off"})
		S.camera_sent = None


def resend_scene(self, context):
	S.scene_dirty = True


class BlenderMCProps(bpy.types.PropertyGroup):
	level: bpy.props.StringProperty(name="Level", default="live", description="Liminal level name in the game (lowercase, digits, _ or -)")
	port: bpy.props.IntProperty(name="Port", default=47660, min=1, max=65535)
	auto: bpy.props.BoolProperty(name="Live sync", default=True, description="Send every change as it happens")
	show_camera: bpy.props.BoolProperty(name="Show camera in game", default=True, update=camera_toggled,
		description="The game shows where this viewport looks from (a glowing observer labelled Blender)")
	draft: bpy.props.BoolProperty(name="Draft lighting", default=False, update=resend_scene,
		description="Skip the game's light bake while editing (textures full bright); turn off for the real look")


class BLENDERMC_OT_connect(bpy.types.Operator):
	bl_idname = "blendermc.connect"
	bl_label = "Connect"
	bl_description = "Link this scene with the running game (a world must be open)"

	def execute(self, context):
		if not connect(context):
			self.report({"ERROR"}, S.status)
			return {"CANCELLED"}
		return {"FINISHED"}


class BLENDERMC_OT_disconnect(bpy.types.Operator):
	bl_idname = "blendermc.disconnect"
	bl_label = "Disconnect"

	def execute(self, context):
		disconnect()
		return {"FINISHED"}


class BLENDERMC_OT_sync(bpy.types.Operator):
	bl_idname = "blendermc.sync"
	bl_label = "Send everything"
	bl_description = "Send every object and the scene settings again"

	def execute(self, context):
		if S.ready:
			S.hashes.clear()
			S.scene_hash = None
			S.names = None
			mark_all()
			S.waiting = False
			sync(context.scene)
		return {"FINISHED"}


class BLENDERMC_OT_enter(bpy.types.Operator):
	bl_idname = "blendermc.enter"
	bl_label = "Put player in level"
	bl_description = "Teleport the player to this level's spawn"

	def execute(self, context):
		if S.ready:
			S.link.send({"t": "enter"})
		return {"FINISHED"}


class BLENDERMC_OT_look(bpy.types.Operator):
	bl_idname = "blendermc.look"
	bl_label = "Look through player"
	bl_description = "Make MC_Eye the scene camera and look through it"

	def execute(self, context):
		eye = bpy.data.objects.get("MC_Eye")
		if eye is None:
			self.report({"WARNING"}, "No player seen yet")
			return {"CANCELLED"}
		context.scene.camera = eye
		for area in context.screen.areas:
			if area.type == "VIEW_3D":
				area.spaces.active.region_3d.view_perspective = "CAMERA"
		return {"FINISHED"}


class BLENDERMC_PT_panel(bpy.types.Panel):
	bl_label = "BlenderMC Live"
	bl_space_type = "VIEW_3D"
	bl_region_type = "UI"
	bl_category = "BlenderMC"

	def draw(self, context):
		props = context.scene.blendermc
		layout = self.layout
		col = layout.column(align=True)
		col.enabled = not S.connected()
		col.prop(props, "level")
		col.prop(props, "port")
		if S.connected():
			layout.operator("blendermc.disconnect", icon="UNLINKED")
		else:
			layout.operator("blendermc.connect", icon="LINKED")
		layout.prop(props, "auto")
		layout.prop(props, "draft")
		layout.prop(props, "show_camera")
		row = layout.row(align=True)
		row.enabled = S.ready
		row.operator("blendermc.sync", icon="FILE_REFRESH")
		row = layout.row(align=True)
		row.enabled = S.ready
		row.operator("blendermc.enter", icon="PLAY")
		row.operator("blendermc.look", icon="CAMERA_DATA", text="Eyes")
		layout.operator("blendermc.restore_holes", icon="LOOP_BACK")
		obj = context.active_object
		if obj is not None and obj.type in MESHLIKE and not in_mirror(obj):
			row = layout.row()
			row.label(text=obj.name, icon="OBJECT_DATA")
			if "drop_outside" in obj:
				row.prop(obj, '["drop_outside"]', text="Drop outside")
			else:
				row.operator("blendermc.drop_outside", text="Drop outside")
		box = layout.box()
		box.label(text="add-on v%d.%d.%d, protocol %d" % (bl_info["version"] + (PROTOCOL,)))
		box.label(text=S.status)
		if S.applied:
			box.label(text="game: " + S.applied)
		if S.ready:
			box.label(text=("player in level" if S.player_in else "player not in level"))
			blocks = bpy.data.collections.get("MC_Blocks")
			pieces = bpy.data.collections.get("MC_Pieces")
			box.label(text=f"mirrored: {len(blocks.objects) if blocks else 0} blocks, {len(pieces.objects) if pieces else 0} pieces, {len(S.holes)} holes")


class BLENDERMC_OT_restore_holes(bpy.types.Operator):
	bl_idname = "blendermc.restore_holes"
	bl_label = "Restore holes"
	bl_description = "Put back everything broken in the game (removes the MC holes modifiers and their cutters)"

	def execute(self, context):
		clear_holes(context.scene)
		return {"FINISHED"}


class BLENDERMC_OT_drop_outside(bpy.types.Operator):
	bl_idname = "blendermc.drop_outside"
	bl_label = "Drop outside faces"
	bl_description = "This object is a solid block carved from inside: do not send the faces on its bounding box"

	def execute(self, context):
		obj = context.active_object
		if obj is not None:
			obj["drop_outside"] = True
			obj.update_tag()
		return {"FINISHED"}


CLASSES = (BlenderMCProps, BLENDERMC_OT_drop_outside, BLENDERMC_OT_restore_holes, BLENDERMC_OT_connect, BLENDERMC_OT_disconnect, BLENDERMC_OT_sync, BLENDERMC_OT_enter,
	BLENDERMC_OT_look, BLENDERMC_PT_panel)


def register():
	for cls in CLASSES:
		bpy.utils.register_class(cls)
	bpy.types.Scene.blendermc = bpy.props.PointerProperty(type=BlenderMCProps)
	if on_depsgraph not in bpy.app.handlers.depsgraph_update_post:
		bpy.app.handlers.depsgraph_update_post.append(on_depsgraph)


def unregister():
	disconnect()
	if on_depsgraph in bpy.app.handlers.depsgraph_update_post:
		bpy.app.handlers.depsgraph_update_post.remove(on_depsgraph)
	del bpy.types.Scene.blendermc
	for cls in reversed(CLASSES):
		bpy.utils.unregister_class(cls)
