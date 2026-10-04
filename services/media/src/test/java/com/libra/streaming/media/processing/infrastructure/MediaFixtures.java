package com.libra.streaming.media.processing.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Synthetic probe fixtures, generated once per build into {@code target/media-fixtures} with ffmpeg (test
 * patterns, tones and flat colour only, so no third-party media is involved). Nothing is committed: the tool that
 * judges the files and the tool that makes them come from the same release. ffmpeg comes from LIBRA_FFMPEG_PATH,
 * else from the directory of the ffprobe in use; a missing tool fails the build with a clear message.
 */
final class MediaFixtures {
    private static final Path DIRECTORY = Path.of("target", "media-fixtures").toAbsolutePath();
    private static final String MARKER = ".complete";
    private static final List<String> H264 = List.of("-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p",
            "-b:v", "150k", "-g", "30");
    private static final List<String> AAC = List.of("-c:a", "aac", "-b:a", "48k");
    private static final List<String> TONE = List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-ac", "2");
    private static final List<String> FAST_START = List.of("-movflags", "+faststart");
    private static boolean ready;

    private MediaFixtures() {}

    static synchronized Path file(String name) throws IOException {
        if (!ready) {
            if (!Files.exists(DIRECTORY.resolve(MARKER))) { generate(); }
            ready = true;
        }
        return DIRECTORY.resolve(name);
    }

    static byte[] bytes(String name) throws IOException { return Files.readAllBytes(file(name)); }

    private static Path ffmpeg() {
        var configured = System.getenv("LIBRA_FFMPEG_PATH");
        if (configured != null && !configured.isBlank()) { return Path.of(configured).toAbsolutePath(); }
        var sibling = FfprobeLocator.locate().resolveSibling(
                System.getProperty("os.name").toLowerCase().contains("win") ? "ffmpeg.exe" : "ffmpeg");
        if (Files.isRegularFile(sibling)) { return sibling; }
        throw new AssertionError("ffmpeg not found: set LIBRA_FFMPEG_PATH, or install it beside ffprobe");
    }

    private static List<String> pattern(String size, int rate, double seconds) {
        return List.of("-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=" + rate, "-t", Double.toString(seconds));
    }

    @SafeVarargs
    private static List<String> join(List<String>... parts) {
        var all = new ArrayList<String>();
        for (var part : parts) { all.addAll(part); }
        return all;
    }

    private static List<String> of(String... values) { return Arrays.asList(values); }

    private static void generate() throws IOException {
        var tool = ffmpeg();
        var work = Files.createTempDirectory("libra-fixtures-");
        try {
            if (Files.exists(DIRECTORY)) {
                try (var old = Files.list(DIRECTORY)) { for (var path : old.toList()) { Files.deleteIfExists(path); } }
            }
            Files.createDirectories(DIRECTORY);
            var out = DIRECTORY;
            var tag = of("-metadata", "comment=libra-m19-synthetic");
            // Accepted sources.
            run(tool, join(pattern("1920x1080", 30, 1), TONE, H264, AAC, of("-shortest"), FAST_START, tag,
                    of(out.resolve("valid-1080p-aac.mp4").toString())));
            run(tool, join(pattern("160x96", 15, 2), H264, of("-an"), FAST_START, tag,
                    of(out.resolve("valid-160x96-silent.mp4").toString())));
            var upright = work.resolve("upright.mp4");
            run(tool, join(pattern("1280x720", 30, 1), TONE, H264, AAC, of("-shortest"), FAST_START, tag,
                    of(upright.toString())));
            // A phone-style clip: stored landscape, displayed rotated by a display matrix.
            run(tool, join(of("-display_rotation:v:0", "90", "-i", upright.toString(), "-c", "copy"), FAST_START,
                    of(out.resolve("valid-rotated-90.mp4").toString())));
            // Anamorphic: 1440x1080 coded with 4:3 pixels, displayed 1920x1080.
            run(tool, join(pattern("1440x1080", 25, 1), H264, of("-vf", "setsar=4/3", "-an"), FAST_START,
                    of(out.resolve("valid-anamorphic.mp4").toString())));
            run(tool, join(pattern("640x360", 30, 1), H264, of("-vf", "fps=30000/1001", "-an"), FAST_START,
                    of(out.resolve("valid-ntsc-rate.mp4").toString())));
            // Longest accepted clip: 600 seconds of a flat picture at 1 fps.
            run(tool, join(of("-f", "lavfi", "-i", "color=c=blue:size=64x64:rate=1", "-t", "600"), H264,
                    of("-an"), FAST_START, of(out.resolve("valid-600s.mp4").toString())));

            // Rejected for policy.
            run(tool, join(pattern("640x360", 30, 1), of("-c:v", "libx265", "-pix_fmt", "yuv420p", "-an", "-tag:v",
                    "hvc1"), FAST_START, of(out.resolve("invalid-hevc.mp4").toString())));
            run(tool, join(pattern("640x360", 30, 1), of("-c:v", "libx264", "-profile:v", "high10", "-pix_fmt",
                    "yuv420p10le", "-an"), FAST_START, of(out.resolve("invalid-10bit.mp4").toString())));
            run(tool, join(pattern("640x360", 30, 1), H264, of("-vf",
                    "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc", "-color_primaries",
                    "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-an"), FAST_START,
                    of(out.resolve("invalid-hdr.mp4").toString())));
            run(tool, join(pattern("3840x2160", 30, 1), of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt",
                    "yuv420p", "-b:v", "100k", "-an"), FAST_START, of(out.resolve("invalid-4k.mp4").toString())));
            run(tool, join(pattern("640x360", 60, 1), H264, of("-an"), FAST_START,
                    of(out.resolve("invalid-60fps.mp4").toString())));
            run(tool, join(of("-f", "lavfi", "-i", "color=c=blue:size=64x64:rate=1", "-t", "601"), H264, of("-an"),
                    FAST_START, of(out.resolve("invalid-601s.mp4").toString())));
            run(tool, join(pattern("640x360", 30, 1), TONE, TONE, of("-map", "0:v", "-map", "1:a", "-map", "2:a"),
                    H264, AAC, of("-shortest"), FAST_START, of(out.resolve("invalid-two-audio.mp4").toString())));
            run(tool, join(pattern("640x360", 30, 1), TONE, of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-b:v",
                    "150k", "-c:a", "libmp3lame", "-b:a", "48k", "-shortest"), FAST_START,
                    of(out.resolve("invalid-mp3-audio.mp4").toString())));
            run(tool, join(pattern("640x360", 30, 1), H264, of("-an", "-f", "matroska"),
                    of(out.resolve("invalid-container.mkv").toString())));
            // Cover art: an attached picture is an extra video stream that this policy rejects.
            var cover = work.resolve("cover.png");
            run(tool, of("-f", "lavfi", "-i", "testsrc2=size=64x64", "-frames:v", "1", cover.toString()));
            run(tool, join(pattern("640x360", 30, 1), of("-i", cover.toString(), "-map", "0:v", "-map", "1:v",
                    "-c:v:0", "libx264", "-profile:v:0", "high", "-pix_fmt:v:0", "yuv420p", "-b:v:0", "150k",
                    "-c:v:1", "png", "-disposition:v:1", "attached_pic", "-an", "-t", "1"), FAST_START,
                    of(out.resolve("invalid-cover-art.mp4").toString())));
            var subtitles = work.resolve("s.srt");
            Files.writeString(subtitles, "1\n00:00:00,000 --> 00:00:00,900\nhello\n", StandardCharsets.US_ASCII);
            run(tool, join(pattern("640x360", 30, 1), of("-i", subtitles.toString(), "-map", "0:v", "-map", "1:s"),
                    H264, of("-c:s", "mov_text", "-an", "-t", "1"), FAST_START,
                    of(out.resolve("invalid-subtitle.mp4").toString())));

            // Corrupt inputs: a file cut off before its index, and bytes that are not a container at all.
            var whole = work.resolve("whole.mp4");
            run(tool, join(pattern("640x360", 30, 1), H264, of("-an"), of(whole.toString())));
            byte[] bytes = Files.readAllBytes(whole);
            Files.write(out.resolve("corrupt-truncated.mp4"), Arrays.copyOf(bytes, bytes.length / 2));
            byte[] noise = new byte[4096];
            new Random(19).nextBytes(noise);
            Files.write(out.resolve("corrupt-garbage.bin"), noise);
            // Written last: a half-generated directory is regenerated rather than trusted.
            Files.writeString(out.resolve(MARKER), "ffmpeg fixtures\n");
        } finally {
            try (var leftovers = Files.walk(work)) {
                for (var path : leftovers.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void run(Path tool, List<String> arguments) throws IOException {
        var command = new ArrayList<String>();
        command.add(tool.toString());
        command.addAll(List.of("-hide_banner", "-loglevel", "error", "-y"));
        command.addAll(arguments);
        var process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("ffmpeg timed out generating a fixture");
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while generating fixtures");
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("ffmpeg failed generating fixture: " + String.join(" ", arguments));
        }
    }
}
