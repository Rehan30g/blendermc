# Contributing

Issues and pull requests are welcome. This is a side project, so replies can take a while.

## Reporting a problem

Open an issue with the bug report template. The most useful things to include:

- the smallest scene or steps that show the problem (a small `.blend` is ideal)
- the mod version, the add-on version from the Blender panel, and your Blender version
- `logs/latest.log` from the game (search for `blendermc`) and Blender's console output
- other mods you run, especially anything that changes rendering

Known limitations are listed in the README; check those first.

## Building

Java 21. From this folder:

```
gradlew build              # jar in build/libs
gradlew runLiminalClient   # dev client with the mod
gradlew runLiminalServer   # headless server, for testing the link without a game window
```

The Blender add-on lives in `tools/blender/blendermc_live`. Link or copy that folder into Blender's add-ons folder while working on it, and restart Blender after changes (it only loads add-on code at startup). To package it:

```
blender --command extension build --source-dir tools/blender/blendermc_live --output-dir build/release
```

## Code

- `src/main/java/dev/blendermc/live`: the socket server and the levels built from Blender.
- `src/client/java/dev/blendermc/client/LiveLinkClient.java`: what the client sends to Blender.
- `src/main/java/dev/blendermc/liminal` and its client side: mesh levels (loading, cells, collision, light baking).
- `docs/BLENDER-LIVE.md`: behaviour and the wire protocol. If you change a message, update the protocol table and bump `PROTOCOL` on both sides.

Keep changes focused, match the style of the surrounding code, and say in the pull request how you tested it (game, Blender, or both).

## License

By contributing you agree that your contribution is licensed under the MIT License (see `LICENSE`).
