# PackWatch

Edit a texture, hit save, and see it in-game right away no restart, no F3+T.

PackWatch keeps an eye on your resource packs while you play. When you change a
texture, it swaps it in on the spot, so you can tweak something and instantly
look at it on the actual block. Great for anyone building or polishing a pack.

## Getting it

Grab the latest jar from the [releases](https://github.com/Jimbno/PackWatch/releases)
and drop it in your `mods` folder. Minecraft 1.7.10.

Building it yourself:

```
./gradlew build
```

The jar shows up in `build/libs/`. (The first build takes a few minutes.)

## Using it

1. Put your pack in `resourcepacks/` as a regular folder and turn it on in
   **Options → Resource Packs**, like any other pack.
2. Edit and save.

Turn a pack on or off from that same screen and PackWatch follows along. Zipped
packs are left alone; keep yours as a folder while you're working on it.

Most texture edits pop in instantly. Bigger changes resizing a texture, adding
or removing files, animations, sounds, lang files do a quick full refresh
instead, same as pressing F3+T.

## Seeing which CTM tile is which

Working on connected textures and can't tell which tile a face is actually
picking? Run `/pw ctm` and every CTM tile gets stamped with its number, right
there on the block. Run it again to put everything back.

