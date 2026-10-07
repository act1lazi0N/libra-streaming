package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.RenditionPlan;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Encodes one local file into the playlists and segments of {@code plan}, inside an empty private directory.
 * Implementations never expose process output, paths or tool messages, and leave the workspace for the caller to
 * delete. A normal return means the tool finished; {@link HlsPackage} still has to accept what it left.
 */
public interface MediaTranscoder {
    void transcode(Path source, RenditionPlan plan, Path workspace, BooleanSupplier cancelled)
            throws InterruptedException;

    /** The tool could not run, ran out of time, or was killed: retryable, says nothing about the input. */
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("TRANSCODE_UNAVAILABLE", null, false, false); }
    }

    /** The tool ran to completion but could not decode the input it had been probed and accepted for. */
    final class Undecodable extends RuntimeException {
        public Undecodable() { super("TRANSCODE_UNDECODABLE", null, false, false); }
    }

    /** The output grew past its budget; the encoder is bounded by bitrate, so this is not worth retrying. */
    final class OutputTooLarge extends RuntimeException {
        public OutputTooLarge() { super("TRANSCODE_OUTPUT_TOO_LARGE", null, false, false); }
    }
}
