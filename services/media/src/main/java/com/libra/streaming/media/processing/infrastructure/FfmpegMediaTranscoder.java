package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.HlsPackage;
import com.libra.streaming.media.processing.application.MediaTranscoder;
import com.libra.streaming.media.processing.domain.RenditionPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Runs one trusted ffmpeg executable with a fixed, structured argument list on one local file. Only numbers taken
 * from the validated {@link RenditionPlan} and two generated absolute paths vary; no option, filter or name comes
 * from the upload. The input is pinned to the {@code file:} protocol and the MP4/MOV demuxer exactly as the probe is,
 * so a container cannot make the tool read anything else. The tool's wall time, its output size and file count, and
 * its descendants are all bounded here; its messages never leave this class.
 *
 * <p>An exit status of zero is not trusted: ffmpeg's HLS muxer reports success even when it could not open or fill its
 * output files. {@link HlsPackage#seal} judges what was actually written. A non-zero status is trusted only in one
 * direction: the tool's "invalid data" code is a verdict on the input, and every other status (a signal ffmpeg caught,
 * a process killed from outside, a full disk, a crash) says nothing about the input and is retried.
 */
final class FfmpegMediaTranscoder implements MediaTranscoder {
    /** An output directory should hold two playlists and about 150 segments; far more means a runaway encoder. */
    private static final int MAX_FILES = HlsPackage.MAX_SEGMENTS + 8;
    /**
     * ffmpeg exits with its error code: AVERROR_INVALIDDATA in full on Windows, and its low byte (183) where the
     * status is eight bits. Every undecodable source in the fixture matrix (truncated, garbage, empty, a playlist
     * disguised as MP4, a damaged tail under {@code -xerror}) exits with exactly this code.
     */
    private static final int INVALID_DATA = -0x41444E49;
    private static final int INVALID_DATA_LOW_BYTE = INVALID_DATA & 0xFF;
    private final Path executable;
    private final Duration timeout;
    private final int threads;
    private final long maxOutputBytes;

    FfmpegMediaTranscoder(Path executable, Duration timeout, int threads, long maxOutputBytes) {
        if (!executable.isAbsolute()) { throw new IllegalArgumentException("ffmpeg path must be absolute"); }
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("ffmpeg timeout must be up to 1 hour");
        }
        if (threads < 1 || threads > 16) { throw new IllegalArgumentException("ffmpeg threads must be 1 to 16"); }
        if (maxOutputBytes < 1024 * 1024 || maxOutputBytes > 1024L * 1024 * 1024) {
            throw new IllegalArgumentException("ffmpeg output budget must be 1 MiB to 1 GiB");
        }
        this.executable = executable;
        this.timeout = timeout;
        this.threads = threads;
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public void transcode(Path source, RenditionPlan plan, Path workspace, BooleanSupplier cancelled)
            throws InterruptedException {
        Path realSource;
        Path realWorkspace;
        try {
            realSource = source.toRealPath();
            realWorkspace = workspace.toRealPath();
            if (!Files.isRegularFile(realSource) || !Files.isDirectory(realWorkspace, LinkOption.NOFOLLOW_LINKS)) {
                throw new Unavailable();
            }
            // The workspace belongs to one attempt; anything already in it would end up in the inventory.
            try (Stream<Path> existing = Files.list(realWorkspace)) {
                if (existing.findAny().isPresent()) { throw new Unavailable(); }
            }
        } catch (IOException exception) {
            throw new Unavailable();
        }
        Process process;
        try {
            process = ToolProcesses.builder(command(executable, realSource, realWorkspace, plan, threads))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException exception) {
            throw new Unavailable();
        }
        try {
            process.getOutputStream().close();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                if (System.nanoTime() - deadline > 0) { throw new Unavailable(); }
                requireWithinBudget(realWorkspace);
            }
            requireWithinBudget(realWorkspace);
            int exit = process.exitValue();
            if (exit != 0) {
                // Only the decoder's own verdict blames the input. ffmpeg exits 255 after catching SIGTERM or SIGINT
                // (and still finalizes a short playlist), Windows reports 1 for a process terminated from outside,
                // and a write error carries its errno: none of these may reject a valid upload for good.
                throw undecodable(exit) ? new Undecodable() : new Unavailable();
            }
        } catch (IOException exception) {
            throw new Unavailable();
        } finally {
            ToolProcesses.terminate(process);
        }
    }

    /**
     * The one argument list ever run on an upload's bytes. Video is H.264 Main at level 3.1 in 4:2:0, capped by
     * rate so the output is bounded whatever the input; a keyframe is forced every segment length so segments start on
     * one and are independently decodable. The output is constant-rate at the source's validated average rate, so a
     * variable-rate source can neither be padded to its peak rate (past what level 3.1 allows at 720p) nor disagree
     * with the master playlist's FRAME-RATE. Audio, when the source has it, is AAC-LC at 48 kHz in at most two
     * channels; a silent source stays silent. Metadata, chapters, subtitles and data tracks are dropped. Rotation is
     * applied by the tool before the scale filter, so the plan's size is the upright size. {@code -xerror} makes any
     * decode error fatal: a damaged tail otherwise exits zero with a shorter, well-formed rendition.
     */
    static List<String> command(Path executable, Path source, Path workspace, RenditionPlan plan, int threads) {
        var command = new ArrayList<String>(List.of(executable.toString(), "-nostdin", "-hide_banner", "-v", "error",
                "-nostats", "-xerror", "-protocol_whitelist", "file", "-f", "mov", "-i", "file:" + source,
                "-map", "0:v:0"));
        if (plan.hasAudio()) { command.addAll(List.of("-map", "0:a:0")); }
        command.addAll(List.of("-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1",
                "-vf", "scale=" + plan.width() + ":" + plan.height() + ":flags=bicubic,setsar=1,format=yuv420p",
                "-fps_mode", "cfr", "-r", plan.frameRateNumerator() + "/" + plan.frameRateDenominator(),
                "-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main", "-level:v", "3.1", "-crf", "23",
                "-maxrate", RenditionPlan.MAX_VIDEO_KILOBITS + "k", "-bufsize", 2 * RenditionPlan.MAX_VIDEO_KILOBITS + "k",
                "-sc_threshold", "0",
                "-force_key_frames", "expr:gte(t,n_forced*" + RenditionPlan.SEGMENT_SECONDS + ")",
                "-threads", Integer.toString(threads)));
        if (plan.hasAudio()) {
            command.addAll(List.of("-c:a", "aac", "-b:a", RenditionPlan.AUDIO_KILOBITS + "k",
                    "-ar", Integer.toString(RenditionPlan.AUDIO_SAMPLE_RATE), "-ac", Integer.toString(plan.audioChannels()),
                    "-af", "aresample=async=1:first_pts=0"));
        }
        command.addAll(List.of("-f", "hls", "-hls_time", Integer.toString(RenditionPlan.SEGMENT_SECONDS),
                "-hls_playlist_type", "vod", "-hls_segment_type", "mpegts", "-hls_flags", "independent_segments",
                "-hls_list_size", "0",
                "-hls_segment_filename", workspace.resolve(HlsPackage.SEGMENT_TEMPLATE).toString(),
                workspace.resolve(HlsPackage.VARIANT).toString()));
        return List.copyOf(command);
    }

    /** The decoder's "invalid data" verdict, in full (Windows) or as the low byte of an eight-bit status. */
    static boolean undecodable(int exit) { return exit == INVALID_DATA || exit == INVALID_DATA_LOW_BYTE; }

    /** First line of {@code ffmpeg -version}, for the runtime log and the evidence record. */
    String version() throws InterruptedException { return ToolProcesses.version(executable); }

    private void requireWithinBudget(Path workspace) throws IOException {
        long total = 0;
        int files = 0;
        try (Stream<Path> entries = Files.list(workspace)) {
            for (var entry : (Iterable<Path>) entries::iterator) {
                files++;
                try { total += Files.size(entry); }
                catch (java.nio.file.NoSuchFileException renamed) { /* a playlist swapped in by the muxer */ }
            }
        }
        if (total > maxOutputBytes || files > MAX_FILES) { throw new OutputTooLarge(); }
    }
}
