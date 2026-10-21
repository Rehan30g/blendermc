# blendermc

Fabric mod for Minecraft 1.21.11. Levels made of meshes instead of blocks, an endless generated world, and a live link to Blender.

Side project, nothing serious. I wanted to know if a Minecraft level could just be a Blender model, and it kept growing from there. Most of the code was written with Claude Opus 5.5 in Claude Code. Expect rough edges.

## What it does

**Mesh levels.** The `blendermc:liminal` dimension loads `.glb` files exported from Blender (1 m = 1 block). Collision is per triangle, cells can be broken and placed back like blocks, and lighting is baked on the CPU. Built-in levels: `hallway`, `map79`, `room`. See [docs/CUSTOM-WORLD.md](docs/CUSTOM-WORLD.md) for making your own.

**Endless world.** `blendermc:liminal_endless` generates hallways forever.

**Blender live link.** The scene open in Blender becomes a level in the game and updates while you edit. It works both ways:

- Blender to game: meshes (after modifiers), materials, lights including their colour, the World as the sky, and the viewport camera (shown as a marker in the game).
- Game to Blender: the player, mobs and boats with their animation, placed blocks with their real models, dropped items, TNT, falling blocks, held items, signs, light-emitting blocks as lights, and cells broken in the game cut out of the Blender objects.

Details and the wire protocol are in [docs/BLENDER-LIVE.md](docs/BLENDER-LIVE.md).

## Running it

Java 21. Blender 5.2 for the live link.

```
gradlew runLiminalClient
```

In any world run `/blendermc enter hallway`. `-PquickPlay=<world folder>` opens a world on launch.

Blender add-on: copy `tools/blender/blendermc_live` into `%APPDATA%\Blender Foundation\Blender\5.2\scripts\addons\` and enable it. Then open the 3D view sidebar (N), go to the BlenderMC tab, and click Connect. Restart Blender after updating the add-on; the panel shows its version and the game warns if it is outdated.

On my machine this folder sits inside a bigger Gradle build so the Minecraft cache is shared. Cloned on its own, `settings.gradle` and `gradle.properties` make it a standalone build.

## Layout

```
src/main/java/dev/blendermc/
  liminal/         .glb loading, cells, collision, light baking
  endless/         endless world generator
  live/            Blender link: socket server, scenes built from Blender
src/client/java/dev/blendermc/
  liminal/client/  mesh renderer and baker
  client/          what the client sends to Blender (block models, entities, items)
tools/
  blender/blendermc_live/  Blender add-on
  liminal/                 scripts and .blend files for the built-in levels
docs/
```

Dev tasks: `glbCheck`, `cutCheck`, `endlessExport`, `liveSceneCheck`, and `runLiminalServer` (a headless server for testing the link without a game window).

## Limitations

- The live link only runs in a dev environment, in singleplayer, on localhost.
- Levels built from Blender are hollow. A broken cell is just a hole.
- Light colour only affects the level mesh. Entities and vanilla blocks still use the vanilla lightmap.
- Held items use an approximate pose, not each item's own display transform.
- Signs in Blender use Blender's default font.
- Animated block textures show their first frame in Blender.

## Credits

Code mostly by Claude Opus 5.5 (Claude Code); ideas and testing by me. Built on Fabric, Fabric API, Sodium and Mod Menu.

The textures in `tools/liminal/map79_textures` and `tools/liminal/room_textures` come from third-party downloads and scans. That is one reason this repo is private.

No license. All rights reserved.
