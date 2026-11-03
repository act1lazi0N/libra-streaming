package com.libra.streaming.media.storage;

import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only child process: owns a scratch instance, leaves a partial copy, and waits to be killed. */
public final class ScratchHolderFixture {
    private ScratchHolderFixture() {}

    public static void main(String[] args) throws Exception {
        var scratch = new ScratchSpace(Path.of(args[0]));
        Path partial = scratch.newFile();
        Files.write(partial, new byte[4096]);
        System.out.println("SCRATCH_READY " + partial);
        System.out.flush();
        Thread.sleep(120_000);
    }
}
