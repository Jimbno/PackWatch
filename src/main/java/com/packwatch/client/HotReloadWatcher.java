package com.packwatch.client;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.packwatch.PackWatch;

/**
 * Watches whatever set of directories it's told to, and records which files changed underneath any of them.
 * Deliberately MC-API-free: this class only knows about java.nio, so it can't break when Minecraft/Forge
 * internals change. {@link EnabledPackSync} decides *which* directories that should be (the ones currently
 * enabled as resource packs); this class just watches them and hands back {@link ChangeBatch}es for
 * {@link SpritePatcher} (or a full reload, when a batch can't be patched) to act on.
 */
public class HotReloadWatcher {

    private final WatchService watchService;
    private final Map<Path, WatchKey> registeredKeys = new HashMap<Path, WatchKey>();
    private final Set<Path> watchedRoots = new HashSet<Path>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Set<Path> dirtyFiles = new LinkedHashSet<Path>();
    private final AtomicBoolean forceFullReload = new AtomicBoolean(false);
    // Last content hash seen per file, to drop byte-identical re-saves. Only ever touched on the watcher thread.
    private final Map<Path, Long> lastContentHash = new HashMap<Path, Long>();

    public HotReloadWatcher() {
        try {
            watchService = FileSystems.getDefault()
                .newWatchService();
        } catch (IOException e) {
            throw new RuntimeException("Couldn't create a WatchService for hot reloading", e);
        }
    }

    public void start() {
        Thread thread = new Thread(new Runnable() {

            @Override
            public void run() {
                watchLoop();
            }
        }, "PackWatch Watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Reconciles the watched set against {@code desiredPaths}: starts watching anything new, stops watching
     * anything no longer present. Safe to call repeatedly with the same or a changed set.
     */
    public synchronized void syncRoots(Set<String> desiredPaths) {
        Set<Path> desired = new HashSet<Path>();
        for (String path : desiredPaths) {
            desired.add(
                Paths.get(path)
                    .toAbsolutePath()
                    .normalize());
        }

        Iterator<Path> rootsIterator = watchedRoots.iterator();
        while (rootsIterator.hasNext()) {
            Path root = rootsIterator.next();
            if (desired.contains(root)) continue;

            unregisterUnder(root);
            rootsIterator.remove();
            PackWatch.LOG.info("No longer watching {}", root);
        }

        for (Path root : desired) {
            if (watchedRoots.contains(root)) continue;
            if (!Files.isDirectory(root)) continue;

            watchedRoots.add(root);
            registerRecursively(root);
            PackWatch.LOG.info("Watching {} for hot reload", root);
        }
    }

    /**
     * Polled from the client tick handler. Returns {@code null} if nothing changed since the last poll,
     * otherwise a snapshot of what did -- the specific files touched, plus whether the batch contains a change
     * (a new/removed file, an overflow, anything non-texture) that can't be handled by patching individual
     * sprites and needs a full {@code refreshResources()} instead.
     */
    public ChangeBatch pollChanges() {
        if (!dirty.compareAndSet(true, false)) return null;

        Set<Path> files;
        synchronized (dirtyFiles) {
            files = new LinkedHashSet<Path>(dirtyFiles);
            dirtyFiles.clear();
        }
        return new ChangeBatch(files, forceFullReload.getAndSet(false));
    }

    public static final class ChangeBatch {

        public final Set<Path> changedFiles;
        public final boolean forceFullReload;

        ChangeBatch(Set<Path> changedFiles, boolean forceFullReload) {
            this.changedFiles = changedFiles;
            this.forceFullReload = forceFullReload;
        }
    }

    private synchronized void unregisterUnder(Path root) {
        Iterator<Map.Entry<Path, WatchKey>> it = registeredKeys.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<Path, WatchKey> entry = it.next();
            if (!entry.getKey()
                .startsWith(root)) continue;

            entry.getValue()
                .cancel();
            it.remove();
        }
    }

    private void registerRecursively(Path start) {
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    register(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            PackWatch.LOG.error("Couldn't walk directory tree of {}", start, e);
        }
    }

    private synchronized void register(Path dir) {
        if (registeredKeys.containsKey(dir)) return;

        try {
            WatchKey key = dir.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
            registeredKeys.put(dir, key);
        } catch (IOException e) {
            PackWatch.LOG.error("Couldn't register watch for {}", dir, e);
        }
    }

    private void watchLoop() {
        // Deliberately NOT synchronized: this loop blocks forever on watchService.take(), and holding the
        // monitor for that long would starve syncRoots() (called from the client thread) out of the lock
        // forever. The individual mutating calls below (register/unregisterKey) are synchronized instead.
        // noinspection InfiniteLoopStatement
        while (true) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                return;
            }

            Path base = (Path) key.watchable();

            for (java.nio.file.WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    PackWatch.LOG.warn("Missed some watch events under {}, forcing a reload to be safe", base);
                    forceFullReload.set(true);
                    dirty.set(true);
                    continue;
                }

                Path name = (Path) event.context();
                Path child = base.resolve(name);

                if (child.getFileName()
                    .toString()
                    .endsWith("~")) continue; // editor swap/backup file

                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                    registerRecursively(child);
                    continue; // the directory itself isn't a sprite; its future contents will fire their own events
                }

                // Only files under an assets/ tree are actually loaded as game resources. Pack-level files like
                // pack.mcmeta and pack.png (which export tools tend to rewrite on every save, right alongside the
                // texture) don't affect anything in-world, so a change to them needs neither a patch nor a reload.
                // Dropping them here keeps a metadata-only save a no-op, and stops a texture+metadata save from
                // being dragged into a full reload by the metadata. Pure java.nio, so this stays MC-API-free.
                if (!isUnderAssets(child)) continue;

                // Content de-dupe: export tools (and some editors) rewrite files -- a CTM .properties, a texture's
                // .mcmeta, even the tile PNGs -- on every save, frequently with byte-identical content. Skip an
                // event whose bytes match what we last saw for this path, so a re-saved-but-unchanged .properties
                // no longer drags the whole batch into a full reload; a genuine edit (different bytes) still flows
                // through. Only meaningful for MODIFY -- create/delete change which files exist, not just content.
                if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                    Long hash = hashFile(child);
                    if (hash != null && hash.equals(lastContentHash.put(child, hash))) continue;
                }

                // A file appearing/disappearing changes which sprites exist at all, not just their pixels --
                // SpritePatcher can only safely patch an existing, still-registered sprite's data in place.
                if (event.kind() != StandardWatchEventKinds.ENTRY_MODIFY) {
                    PackWatch.LOG.info(
                        "PackWatch: {} fired {} (not MODIFY) -- will force a full reload for this batch",
                        child.getFileName(),
                        event.kind()
                            .name());
                    forceFullReload.set(true);
                }

                synchronized (dirtyFiles) {
                    dirtyFiles.add(child);
                }
                dirty.set(true);
            }

            boolean valid = key.reset();
            if (!valid) {
                removeKey(base);
            }
        }
    }

    private synchronized void removeKey(Path base) {
        registeredKeys.remove(base);
    }

    /**
     * True if the path lies under an {@code assets/} directory -- i.e. it's an actual loaded game resource rather
     * than pack-level metadata ({@code pack.mcmeta}, {@code pack.png}, readmes) that has no in-world effect.
     * Deliberately pure {@code java.nio} so this class stays free of any Minecraft/Forge API.
     */
    private static boolean isUnderAssets(Path file) {
        for (int i = 0; i < file.getNameCount(); i++) {
            if ("assets".equals(
                file.getName(i)
                    .toString()))
                return true;
        }
        return false;
    }

    /**
     * A 64-bit FNV-1a hash of the file's bytes, or {@code null} if it can't be read right now (locked mid-write,
     * a directory, already gone) -- in which case the caller treats it as changed and lets the normal path deal
     * with it. Cheap and collision-resistant enough for its only job: recognising a byte-identical re-save.
     */
    private static Long hashFile(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            long hash = 0xcbf29ce484222325L;
            for (byte b : bytes) {
                hash ^= (b & 0xff);
                hash *= 0x100000001b3L;
            }
            return hash;
        } catch (IOException e) {
            return null;
        }
    }
}
