package com.libra.streaming.media.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * One locked directory per running instance under the configured scratch root. The operating system
 * releases the lock when a process dies, so a later instance reclaims exactly the directories whose owner
 * is gone. No age threshold is involved: a live owner, in this or another process, is never swept.
 */
final class ScratchSpace implements AutoCloseable {
    private static final String PREFIX = "instance-";
    private static final String LOCK_SUFFIX = ".lock";
    private final Path root;
    private final Path directory;
    private final Path lockFile;
    private final FileChannel channel;
    private final FileLock lock;
    private final LongSupplier usableSpace;

    ScratchSpace(Path configured) throws IOException { this(configured, null); }

    /** {@code usableSpace} replaces the file-store query in tests; null uses the real file store. */
    ScratchSpace(Path configured, LongSupplier usableSpace) throws IOException {
        Path normalized = configured.normalize();
        if (Files.isSymbolicLink(normalized)) { throw new IllegalArgumentException("Scratch directory is a link"); }
        Files.createDirectories(normalized);
        root = normalized.toRealPath();
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            throw new IllegalArgumentException("Scratch directory is not writable");
        }
        reclaim(root);
        String name = PREFIX + UUID.randomUUID();
        // Lock first, then create the directory: a directory is never visible without its owner's lock file.
        lockFile = root.resolve(name + LOCK_SUFFIX);
        channel = FileChannel.open(lockFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        lock = channel.tryLock();
        if (lock == null) { channel.close(); throw new IOException("Scratch lock unavailable"); }
        directory = Files.createDirectory(root.resolve(name));
        this.usableSpace = usableSpace != null ? usableSpace : () -> {
            try { return Files.getFileStore(directory).getUsableSpace(); }
            catch (IOException exception) { return 0; }
        };
    }

    Path newFile() throws IOException { return Files.createTempFile(directory, "media-", ".part"); }

    /** Uploads accept only scratch files, as before per-instance directories existed: anywhere under the root. */
    boolean contains(Path real) { return real.startsWith(root); }

    /** Fails before any transfer starts, so a full disk never produces a partial copy or a false rejection. */
    void requireSpace(long bytes) throws IOException {
        if (usableSpace.getAsLong() < bytes) { throw new IOException("Scratch space exhausted"); }
    }

    Path directory() { return directory; }

    @Override
    public void close() throws IOException {
        try { deleteTree(directory); }
        finally {
            try { lock.release(); }
            finally { channel.close(); Files.deleteIfExists(lockFile); }
        }
    }

    /** Deletes the directories of instances whose lock is no longer held. Returns how many were reclaimed. */
    static int reclaim(Path root) throws IOException {
        int reclaimed = 0;
        try (DirectoryStream<Path> locks = Files.newDirectoryStream(root, PREFIX + "*" + LOCK_SUFFIX)) {
            for (Path candidate : locks) {
                if (reclaim(candidate, root.resolve(candidate.getFileName().toString()
                        .replace(LOCK_SUFFIX, "")))) { reclaimed++; }
            }
        }
        return reclaimed;
    }

    private static boolean reclaim(Path lockFile, Path owned) throws IOException {
        try (var channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            FileLock acquired;
            try { acquired = channel.tryLock(); }
            catch (OverlappingFileLockException live) { return false; } // held by this JVM
            if (acquired == null) { return false; } // held by another live process
            try { deleteTree(owned); }
            finally { acquired.release(); }
        } catch (java.nio.file.NoSuchFileException raced) {
            return false; // another instance reclaimed it first
        }
        // A directory is deleted before its lock file, so a crash here leaves only a lock file to retry.
        Files.deleteIfExists(lockFile);
        return true;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) { return; }
        try (Stream<Path> tree = Files.walk(path)) {
            for (Path entry : tree.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(entry); }
        }
    }
}
