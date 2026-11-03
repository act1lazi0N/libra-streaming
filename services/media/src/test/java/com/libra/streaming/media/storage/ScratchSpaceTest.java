package com.libra.streaming.media.storage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Scratch ownership is decided by operating-system file locks, never by file age. */
class ScratchSpaceTest {
    @TempDir Path root;

    @Test
    void eachInstanceOwnsALockedDirectoryAndRemovesItOnClose() throws Exception {
        var first = new ScratchSpace(root);
        var second = new ScratchSpace(root);
        Path file = first.newFile();
        assertThat(file.getParent()).isEqualTo(first.directory());
        assertThat(first.contains(file.toRealPath())).isTrue();
        Path outside = Files.createTempFile("outside-scratch", ".part");
        try { assertThat(first.contains(outside.toRealPath())).isFalse(); }
        finally { Files.delete(outside); }
        assertThat(second.directory()).isNotEqualTo(first.directory());
        first.close();
        assertThat(file).doesNotExist();
        assertThat(first.directory()).doesNotExist();
        assertThat(second.directory()).exists();
        second.close();
        assertThat(entries()).isEmpty();
    }

    @Test
    void anInstanceNeverReclaimsADirectoryWhoseOwnerIsAliveInThisJvm() throws Exception {
        try (var live = new ScratchSpace(root)) {
            Path partial = live.newFile();
            Files.write(partial, new byte[128]);
            assertThat(ScratchSpace.reclaim(root.toRealPath())).isZero();
            try (var other = new ScratchSpace(root)) {
                assertThat(partial).exists();
                assertThat(other.directory()).exists();
            }
        }
    }

    @Test
    void directoriesOfDeadOwnersAreReclaimedIncludingAnInterruptedReclaim() throws Exception {
        // A dead owner leaves its directory and an unlocked lock file.
        Path dead = Files.createDirectory(root.resolve("instance-dead"));
        Files.write(dead.resolve("media-1.part"), new byte[256]);
        Files.createFile(root.resolve("instance-dead.lock"));
        // A reclaim interrupted after deleting the directory leaves only its lock file.
        Files.createFile(root.resolve("instance-half.lock"));
        // Something without a lock file is not ours to judge; it is left alone.
        Path unknown = Files.createDirectory(root.resolve("instance-unknown"));

        try (var scratch = new ScratchSpace(root)) {
            assertThat(dead).doesNotExist();
            assertThat(root.resolve("instance-dead.lock")).doesNotExist();
            assertThat(root.resolve("instance-half.lock")).doesNotExist();
            assertThat(unknown).exists();
            assertThat(scratch.directory()).exists();
        }
    }

    @Test
    void spaceIsCheckedBeforeATransferStarts() throws Exception {
        try (var scratch = new ScratchSpace(root, () -> 1000)) {
            scratch.requireSpace(1000);
            assertThatThrownBy(() -> scratch.requireSpace(1001)).hasMessage("Scratch space exhausted");
        }
    }

    @Test
    void aKilledProcessReleasesItsLockAndTheNextInstanceReclaimsItsPartialCopy() throws Exception {
        Path launcher = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        String classpath = location(ScratchSpace.class) + java.io.File.pathSeparator + location(ScratchHolderFixture.class);
        Process holder = new ProcessBuilder(launcher.toString(), "-cp", classpath, ScratchHolderFixture.class.getName(),
                root.toString()).redirectErrorStream(true).start();
        try {
            Path partial;
            try (var output = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8))) {
                String line = output.readLine();
                assertThat(line).startsWith("SCRATCH_READY ");
                partial = Path.of(line.substring("SCRATCH_READY ".length()));
                assertThat(partial).exists();

                // Another process holds the lock: a new instance must leave the live copy alone.
                try (var neighbour = new ScratchSpace(root)) { assertThat(partial).exists(); }

                holder.destroyForcibly();
                assertThat(holder.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(partial).exists(); // a kill runs no cleanup

            try (var restarted = new ScratchSpace(root)) {
                assertThat(partial).doesNotExist();
                assertThat(partial.getParent()).doesNotExist();
                assertThat(entries()).hasSize(2); // only the restarted instance's directory and lock
            }
        } finally {
            holder.destroyForcibly();
        }
    }

    private Stream<Path> entriesStream() throws Exception { return Files.list(root); }

    private java.util.List<Path> entries() throws Exception {
        try (var stream = entriesStream()) { return stream.toList(); }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
