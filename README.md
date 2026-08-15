# PackWatch

Hot-reloading for Minecraft 1.7.10 / Forge, built on [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle)
and GTNH's mod conventions. Whatever resource packs are enabled get watched automatically, and edits to their
textures/lang/sounds show up in the running client without restarting.

Inspired by [BasiqueEvangelist's ScaldingHot](https://github.com/BasiqueEvangelist/ScaldingHot), which provides the
same workflow for modern Minecraft -- but this isn't a port of it. 1.7.10 predates data packs, JSON models and
Fabric, so none of upstream's code, Mixin targets, or file formats carry over; this is a from-scratch
reimplementation of the same idea against 1.7.10's own (much older) resource-reload plumbing.

## Building

```
./gradlew build
```

First run downloads and decompiles Minecraft + Forge (several minutes); later runs are fast. Output jar lands in
`build/libs/`.

## Using it in-game

1. Put the resource pack you're working on in `resourcepacks/<name>/` as a normal unpacked folder (a symlink to
   wherever you actually edit works fine), and enable it from the vanilla **Options -> Resource Packs** screen.
2. Edit files under that folder and save. The running client picks up the change on its own -- no F3+T, no
   commands, no restart.

That's the whole workflow. Whatever packs are currently enabled are watched automatically; enabling or disabling a
pack from the Resource Packs screen starts or stops watching it, with no separate watch/unwatch step. Only unpacked
folders are watched -- a `.zip` resource pack is left alone.

### What reloads fast, and what doesn't

A plain pixel edit to an existing block/item texture is patched straight into the stitched atlas in a millisecond
or two, without touching anything else. PackWatch falls back to a full reload (the same cost as F3+T) only when it
can't safely patch in place -- for example:

- a texture's dimensions changed, or it's a newly added / removed file
- animated sprites (`.mcmeta` animation), explicit mipmap overrides, or anisotropic filtering being on
- non-texture changes (lang, sounds) or textures outside the block/item atlases

Changes to pack-level metadata alone (`pack.mcmeta`, `pack.png`) are ignored -- they don't affect anything
in-world, so they trigger neither a patch nor a reload.

### Seeing what it did

Every reacted-to save logs one line under the `packwatch` logger, e.g.
`PackWatch: patched 1 sprite(s) in place in 1 ms (no full reload)`, or a note explaining why a file fell back to a
full reload. Handy when a texture isn't updating the way you expect.
