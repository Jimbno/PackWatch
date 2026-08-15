# PackWatch

Hot-reloading for Minecraft 1.7.10 / Forge, built on [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle)
and GTNH's mod conventions. Whatever resource packs are enabled get watched automatically, and edits to their
textures/lang/sounds show up in the running client without restarting.

Inspired by [BasiqueEvangelist's ScaldingHot](https://github.com/BasiqueEvangelist/ScaldingHot), which provides the
same workflow for modern Minecraft -- but this isn't a port of it. 1.7.10 predates data packs, JSON models and
Fabric, so none of upstream's code, Mixin targets, or file formats carry over; this is a from-scratch
reimplementation of the same idea against 1.7.10's own (much older) resource-reload plumbing. See
[docs/PLAN.md](docs/PLAN.md) for the details, what's implemented, and what's still open (in particular, GregTech 5 /
GTNH compatibility testing).

## Building

```
./gradlew build
```

First run downloads and decompiles Minecraft + Forge (several minutes); later runs are fast. Output jar lands in
`build/libs/`.

## Using it in-game

1. Put your live texture/lang/sound folder under `resourcepacks/<name>/` (a symlink to somewhere else on disk
   works fine).
2. Run `/pw enable <name>` -- press Tab after `enable ` to autocomplete from whatever's actually sitting in
   `resourcepacks/`. This enables the pack if it wasn't already and reloads once immediately.
3. Edit files under that folder -- the running client picks up the change automatically, no F3+T needed.

There's no separate "watch"/"unwatch" step: whatever's currently enabled (via `/pw enable`, or the vanilla
Resource Packs screen) is watched automatically, and disabling a pack stops it being watched again. `/pw reload`
forces a one-off full reload (equivalent to F3+T) if you ever need it on demand. `pw` is a short alias for
`packwatch`; both work identically.
