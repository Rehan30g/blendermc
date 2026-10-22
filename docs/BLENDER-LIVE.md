# The Blender link

The scene open in Blender is a level in the game, rebuilt as you edit it. What happens in the game comes back into Blender.

The link is on whenever a world is open; start the game with `-Dblendermc.live=false` to turn it off. It listens on `127.0.0.1:47660` (change with `-Dblendermc.live.port`) and accepts one Blender at a time. The `command` message (run any command) only works in a development environment.

## Setup

1. `gradlew runLiminalClient` and open any world (`-PquickPlay=<world folder>` opens one on launch).
2. Install the add-on: from a release, Edit > Preferences > Get Extensions > Install from Disk with `blendermc_live-<version>.zip`; from the source, put `tools/blender/blendermc_live` in `%APPDATA%\Blender Foundation\Blender\5.2\scripts\addons\` (a junction works, so edits in the repo apply after restarting Blender) and enable it.
3. In the 3D view sidebar (N), BlenderMC tab: set **Level** (lowercase letters, digits, `_`, `-`) and click **Connect**.
4. Click **Put player in level**. The player is teleported to the `spawn` empty. If nobody has joined the world yet, it happens when they do.

Blender loads add-on code only at startup, so restart it after updating. The panel shows the add-on version and the game reports an outdated add-on.

While Blender is connected the game does not pause when its window loses focus (a paused singleplayer world stops sending anything). The setting is restored when the link closes.

## Blender to the game

- Every visible, renderable mesh, curve, text, metaball or grease pencil object is sent after its modifiers. Only objects whose result changed are sent again.
- The game rebuilds the cells around what changed and re-bakes the lighting in the background; the old version stays on screen until the new one is ready. On a small level an edit reaches the game in about 0.02–0.13 s, plus 0.4–0.6 s of baking.
- Scale and naming: 1 m = 1 block, Z up. Materials ending in `_sky` are drawn as a skybox around the eye, `_nocollide` have no collision, `_thin` have no solid behind them. Materials named with "light" or with emission glow. An empty named `spawn` is where the player enters (looking along its local -Y); an empty named `sun` sets the sun direction (its local -Z).
- Scene custom properties are level settings: `sky_color`, `fog_color`, `fog_start`, `fog_end`, `time_of_day`, `sun_visible`, `skylight`, `unlit`, `day_cycle`, `day_length`.
- **Grease pencil** objects are sent as they look at the current frame, modifiers included: each stroke becomes a thin tube following its points' radius, each fill a flat polygon, in the material's stroke or fill colour (layer tint applied). In the game they are drawn full bright like in Blender, have no collision and give no light. Changing the frame sends them again, so an animated drawing plays in the game along with Blender's timeline. Blender 5.2 reports every grease pencil material's Stroke/Fill switches as off, so the stroke decides instead: fill-only and closed strokes are filled.
- Plain material colours (no texture) are converted from Blender's linear values to what the viewport shows.
- Materials with `_flat` in the name are drawn full bright and light nothing (no light blocks, not a lamp in the bake).
- Not sent: the `AIR_cutters` collection, anything disabled in renders, and the `MC_*` collections the game writes into.
- **Drop outside** (panel button, or custom property `drop_outside` on the object): for a solid block carved from inside, skip the faces on its bounding box, which are never seen.
- **Draft lighting** sends `unlit` so the game skips the light bake while you move things around.
- Levels from Blender are hollow: no filler stone, and a cell broken in the game is just a hole.
- **Lights.** Point, spot and area lights become real `minecraft:light` blocks, so they light the player, mobs and blocks as well as the level. The light level comes from the power (1000 W = 15, 250 W = 8: 15·√(W/1000)) or from an `mc_level` custom property (1–15). Their colour tints the level mesh around them. A sun light sets the sun direction unless there is a `sun` empty.
- Light on the level mesh is computed like the vanilla lightmap but without its warm tint, so white surfaces stay white.
- **Sky.** The World is the sky: a plain colour (Blender's default grey included) becomes the sky and fog colour; an environment texture is scaled down to 256×128 and sent as a skybox, and its average colour becomes the sky and fog colour. `sky_color` / `fog_color` scene properties take precedence.
- **Camera.** The first 3D viewport's view is sent ten times a second and shown in the game as a glowing observer labelled "Blender". Toggle with **Show camera in game**; nothing is sent while you look through the player's eyes.
- The level is saved in `run/config/blendermc/live/<level>/`, so it is still there when Blender is closed, and `/blendermc enter <level>` works for it.

## The game to Blender

Everything goes into the `MC_LIVE` collection and is never sent back.

| Object or collection | What it is |
|---|---|
| `MC_Eye` | camera at the player's eyes (FOV 70). **Eyes** makes it the scene camera. |
| `MC_Entities` | the player, mobs, boats and rafts: their own model and texture, posed by the game every tick |
| `MC_Held` | items in their hands (block items as small blocks, other items as their texture) |
| `MC_Blocks` | placed blocks with their real model (the game's baked quads, every face with its own texture, biome tint baked into a copy of the texture). Light-emitting blocks also get a point light (level 15 = 1000 W) and cast no shadow so that light gets out. Signs are built from their wood texture with their text, front and back, dye colour and glow. Animated textures show their first frame. |
| `MC_Pieces` | mesh pieces placed back into the world, as their own triangles |
| `MC_Things` | dropped items (spinning and bobbing), primed TNT (swelling before it goes off) and falling blocks |
| `MC_Effects` | TNT explosions: a fireball and a flash for 0.6 s |
| `MC_Cutters` | hidden hole cutters, one per object that has holes |

Mirrored objects render with F12, except the cutters. Duplicating a mirrored object (Shift+D) makes the copy a normal level object: it leaves `MC_*` and is sent to the game.

**Holes.** When a cell is broken in the game, every object that has geometry in that cell at that moment gets a Geometry Nodes modifier `MC holes` that cuts the cell out and removes the walls the Boolean adds, so the hole stays open as in the game. The cutter lives in the object's local space, so the hole moves with the object. Once the cut geometry has reached the game, the game forgets the broken cell, which means anything added there later is not cut. **Restore holes** removes all of it.

Only real breaking counts (mining, explosions, `/setblock ... destroy`). Plain `/setblock` skips block entity side effects and is not seen as breaking.

## Protocol

Loopback TCP. Each frame: big-endian u32 header length, UTF-8 JSON header, big-endian u32 payload length, payload. Positions sent to Blender are glTF axes relative to the level origin. The game sends nothing until it has received `hello`.

| Direction | `t` | Content |
|---|---|---|
| to game | `hello` | `level`, `protocol` |
| to game | `object` / `remove` / `objects` | `name` + one object as a .glb / remove it / the full list of names |
| to game | `scene` | .glb with no meshes: scene extras and `spawn`, `sun`, `mc_light*` nodes |
| to game | `commit` | rebuild now; answered with `applied` |
| to game | `enter` / `command` | put the player in the level / run a command with full permission |
| to game | `camera` / `camera_off` | viewport position `p`, `yaw`, `pitch` / hide the marker |
| to Blender | `hello` / `applied` / `status` | level origin and protocol / build statistics / a message |
| to Blender | `player` | `in`, `p`, `yaw`, `pitch`, `eye`, every 2 ticks |
| to Blender | `block` / `unblock` / `texture` | block at cell `p` (`id`, `color`, `light`, `model`, `sign`) / removed / PNG for `key` |
| to Blender | `blockmodel` | `key` (block state), `quads` (`tex`, `tint`, `emissive`); payload 4 × (xyz, uv) little-endian floats per quad |
| to Blender | `piece` | cell `p`, `materials`; payload 25 little-endian floats per triangle |
| to Blender | `hole` / `holes` | one cell / all broken cells |
| to Blender | `entity_mesh` / `entities` | an entity's mesh (`id`, `type`, `tex`, `n`, UV payload) / this tick's poses (`list` of [id, n, held items], `things`, position payload) |
| to Blender | `explosion` | `p`, `r` |

## Dev tasks

- `gradlew :blendermc:liveSceneCheck -Pdir=<folder with obj_*.glb and scene.glb> -Pref=<level.glb>` builds a level the way the link does and compares it with a reference export.
- `gradlew :blendermc:glbCheck -Pglb=<file.glb>` prints what a .glb loads as and walks from its spawn.
- `gradlew runLiminalServer` starts a headless server (in `.tmp/liminal-server`) to test the link without a game window.
