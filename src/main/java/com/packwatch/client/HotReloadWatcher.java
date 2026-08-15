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

                // A file appearing/disappearing changes which sprites exist at all, not just their pixels --
                // SpritePatcher can only safely patch an existing, still-registered sprite's data in place.
                if (event.kind() != StandardWatchEventKinds.ENTRY_MODIFY) {
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
}
