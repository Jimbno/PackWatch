# Plan and status

## Why this is a reimplementation, not a port

Upstream [ScaldingHot](https://github.com/BasiqueEvangelist/ScaldingHot) targets Minecraft 1.21.11 on Fabric and
hot-reloads three things, all of which are specific to modern Minecraft:

- **JSON block/item models and texture atlases** -- 1.7.10 predates the JSON model system entirely; blocks/items
  render via the old `IIcon`/`TextureMap` atlas system.
- **Data packs** (loot tables, JSON recipes, advancements, functions) -- introduced in 1.9-1.13. None of it exists
  in 1.7.10: recipes are hardcoded Java via `GameRegistry`, there are no loot tables, no advancements.
- Its Mixin targets (`TextureAtlasAccessor`, `ReloadableResourceManagerMixin`, etc.) are classes/fields that don't
  exist in the 1.7.10 codebase.

So the only thing that actually carries over is the *goal*: edit a file on disk, see it live in a running client.
Everything else here is new code written directly against 1.7.10's own (much older, but structurally similar)
resource-reload plumbing.

## What's implemented (MVP)

Verified against the real decompiled 1.7.10 source (via `./gradlew build`, not guessed):

- `Minecraft#refreshResources()` (public) is the exact call F3+T makes -- restitches the texture atlas, reloads
  lang/sounds. We call this directly; no Mixin/ASM/reflection needed.
- A resource pack is just `resourcepacks/<name>/` with a `pack.mcmeta`; vanilla already scans and can load it as
  an unpacked folder pack.
- Enabling a pack is adding its folder name to `GameSettings#resourcePacks` (public field) and pushing the
  matching `ResourcePackRepository.Entry` into the repository's active list via the (unfortunately unmapped)
  `func_148527_a` -- the same thing the in-game Resource Packs screen does when you click a pack.
- `HotReloadWatcher` (`src/main/java/com/packwatch/client/HotReloadWatcher.java`) is a plain `java.nio`
  `WatchService` loop, deliberately with zero Minecraft API surface, so it can't break on an MC/Forge update. It
  doesn't decide *what* to watch, though -- `syncRoots(Set<String>)` just reconciles against whatever it's told.
- `EnabledPackSync` (`src/main/java/com/packwatch/client/EnabledPackSync.java`) is what decides what to watch:
  a vanilla `IResourceManagerReloadListener`, registered on `Minecraft#getResourceManager()` (cast to
  `IReloadableResourceManager`, since the declared return type is the narrower interface but the real object is
  always a `SimpleReloadableResourceManager`). Every time resources reload -- F3+T, the Resource Packs screen,
  our own `/pw enable` -- it re-derives the set of enabled, folder-backed packs from
  `ResourcePackRepository#getRepositoryEntries()` and hands it to the watcher. No separate watch/unwatch
  bookkeeping, no config file of our own: `options.txt`'s `resourcePacks` list (vanilla's own state) is the
  single source of truth, and registering the listener fires an initial sync for free (verified against
  `SimpleReloadableResourceManager#registerReloadListener`, which calls `onResourceManagerReload` immediately on
  registration).
- `/pw enable <folder>` wires the user-facing side: creates `pack.mcmeta` if missing, enables the pack, reloads
  once (which triggers the sync above). `/pw reload` just forces a reload on demand. Disabling a pack from the
  vanilla Resource Packs screen stops it being watched automatically, for free, via the same listener.

- `SpritePatcher` (`src/main/java/com/packwatch/client/SpritePatcher.java`) is the fast path: for a plain
  in-place edit of an already-registered, already-uploaded, non-animated sprite (no anisotropic filtering, no
  size change, no explicit mip overrides), it patches just that sprite's pixels directly into the atlas --
  `TextureAtlasSprite#loadSprite`/`#generateMipmaps` to rebuild the sprite's data, `TextureUtil#uploadTextureMipmap`
  (a single `glTexSubImage2D`) to push it to the GPU. Nothing else -- other mods' icons, lang, sounds -- is
  touched. This is the same sequence vanilla already uses every tick to patch animated sprites (torches, water)
  into place, so no Mixin is needed; one private field read (`TextureMap#mipmapLevels`, no public getter exists)
  is done via reflection. Anything it can't confidently handle -- new/removed files, size changes, animated
  sprites, non-`.png` changes (lang/sound/pack.mcmeta) -- falls back to a full `refreshResources()`.

This gets the core workflow working end-to-end, patching most texture-only edits in milliseconds instead of
paying a full atlas restitch on every change.

## Open work

1. **GregTech 5 (Unofficial) / GTNH compatibility** -- not yet tested against an actual GT5u install. In theory
   this should just work: GT5u registers its icons through the same vanilla `TextureMap`/`IIconRegister` pipeline
   as any other mod, and our reload path is the same one vanilla's own F3+T uses, so nothing GT-specific should be
   needed for correctness. Still needs verifying against a real GTNH dev environment (`dependencies.gradle` has a
   commented-out `compileOnly` line for GT5-Unofficial once we pin a version).
2. **Per-sprite live patch (no full-atlas hitch)** -- implemented (`SpritePatcher`, described above). Tried once,
   reverted for being "not worth it" against a lightweight 4-mod dev environment, then revived after real-world
   testing against the full GTNH pack (245 mods) + Angelica showed a full `refreshResources()` costing ~14s per
   edit -- not a minor hitch there, a real workflow cost. `TextureUtil#bindTexture` turned out to be
   package-private (not public as first assumed); worked around by binding directly via `GL11.glBindTexture`
   instead of going through it.
3. **Watching directories outside `resourcepacks/`** -- for a dev iterating on a mod's own `src/main/resources`
   directly (upstream's other big use case). `ResourcePackRepository` only scans its one configured directory, so
   this needs either a symlink under `resourcepacks/` (works today, zero code) or injecting a custom
   `IResourcePack`/`FolderResourcePack` into `Minecraft`'s pack list, which does need reflection or Mixin since
   `defaultResourcePacks` is private.
4. **Server-side data reload** -- deliberately out of scope. 1.7.10 has no data pack equivalent; there's nothing
   analogous on the server to hot-reload.
5. Git-based versioning is disabled implicitly right now (no tags yet, builds report version `NO-GIT-TAG-SET`).
   Run `git init` and tag a `0.0.1` (or similar) once this is under version control for real.

## Verifying claims about 1.7.10 APIs

Everything above citing a specific class/method was checked against the actual decompiled source, not recalled
from memory: run `./gradlew build` once (slow -- downloads and decompiles MC+Forge) and the readable source lands
under `build/rfg/minecraft-src/java/`. Grep there before trusting any API claim in this doc or in code comments.
