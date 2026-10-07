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
    private static final String MARKER = ".complete-m22-1";
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

    static Path ffmpeg() {
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

            // M21 encode inputs. A 20 second 720p clip whose source has a single keyframe, so the output's four second
            // segments cannot come from the source's own structure; a portrait clip stored upright; mono 44.1 kHz and
            // 5.1 audio, to see channels and rate normalized.
            // -t is an output option here (after the last input), so audio and video are the same length.
            run(tool, join(of("-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30"), TONE, of("-t", "20"),
                    of("-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-b:v", "1500k", "-g", "600",
                            "-keyint_min", "600", "-sc_threshold", "0"), AAC, FAST_START,
                    of(out.resolve("valid-720p-20s-aac.mp4").toString())));
            run(tool, join(pattern("1080x1920", 30, 3), H264, of("-an"), FAST_START,
                    of(out.resolve("valid-portrait-1080x1920.mp4").toString())));
            run(tool, join(of("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25", "-f", "lavfi", "-i",
                    "sine=frequency=330:sample_rate=44100", "-ac", "1", "-t", "2"), H264, AAC, FAST_START,
                    of(out.resolve("valid-mono-44100.mp4").toString())));
            run(tool, join(of("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25", "-f", "lavfi", "-i",
                    "sine=frequency=330:sample_rate=48000", "-ac", "6", "-t", "2"), H264,
                    of("-c:a", "aac", "-b:a", "192k"), FAST_START,
                    of(out.resolve("valid-surround-6ch.mp4").toString())));

            // Pure noise at 1080p: the worst case for an encoder's output size (about 44 MB for ten seconds).
            run(tool, join(pattern("1920x1080", 30, 10), of("-vf", "noise=alls=100:allf=t+u", "-c:v", "libx264",
                    "-profile:v", "high", "-pix_fmt", "yuv420p", "-b:v", "15M", "-an"), FAST_START,
                    of(out.resolve("valid-noise-1080p-10s.mp4").toString())));

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

            // M20: hostile and contradictory shapes. Each is synthetic and local.
            // Rotation edge cases: a half turn, a three-quarter turn, and a tilt no player can honour.
            for (int degrees : new int[] {180, 270, 45}) {
                run(tool, join(of("-display_rotation:v:0", Integer.toString(degrees), "-i", upright.toString(),
                        "-c", "copy"), FAST_START, of(out.resolve((degrees == 45 ? "invalid" : "valid")
                        + "-rotated-" + degrees + ".mp4").toString())));
            }
            // Headers rewritten to say the clip lasts one second while its sample table still holds 601 frames.
            Files.write(out.resolve("invalid-lying-headers.mp4"), lieAboutDuration(Files.readAllBytes(
                    out.resolve("invalid-601s.mp4"))));
            // A one-second picture over thirty seconds of tone: the container is as long as its longest track.
            run(tool, join(of("-f", "lavfi", "-t", "1", "-i", "testsrc2=size=160x96:rate=15", "-f", "lavfi", "-t", "30",
                    "-i", "sine=frequency=440:sample_rate=48000", "-ac", "2"), H264, AAC, FAST_START,
                    of(out.resolve("invalid-track-lengths.mp4").toString())));
            // One video and seventy audio tracks: more streams than the parser keeps.
            var many = new ArrayList<String>(join(pattern("160x96", 15, 1), TONE, of("-map", "0:v")));
            for (int i = 0; i < 70; i++) { many.addAll(of("-map", "1:a")); }
            run(tool, join(many, H264, AAC, of("-shortest"), FAST_START,
                    of(out.resolve("invalid-seventy-streams.mp4").toString())));
            // A 300 KB comment: ffprobe's description of this file is larger than the output budget.
            var metadata = work.resolve("huge.ffmeta");
            Files.writeString(metadata, String.join("\n", ";FFMETADATA1", "comment=" + "x".repeat(300_000), ""),
                    StandardCharsets.US_ASCII);
            run(tool, join(of("-f", "lavfi", "-t", "1", "-i", "testsrc2=size=160x96:rate=15", "-i",
                    metadata.toString(), "-map", "0:v", "-map_metadata", "1"), H264, of("-an"), FAST_START, of(out.resolve("invalid-huge-metadata.mp4").toString())));
            run(tool, join(of("-f", "lavfi", "-i", "color=c=green:size=7680x4320:rate=1", "-t", "1"),
                    of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-an"), FAST_START,
                    of(out.resolve("invalid-8k.mp4").toString())));
            // A 660 KB file whose header declares 15000x15000 pixels: decoding its first frame needs over 500 MB.
            run(tool, join(of("-f", "lavfi", "-i", "color=c=black:size=15000x15000:rate=1", "-t", "1"),
                    of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-an"), FAST_START,
                    of(out.resolve("invalid-15k.mp4").toString())));
            // Brands: a QuickTime file, and the same file claiming isom through a metadata tag.
            run(tool, join(pattern("160x96", 15, 1), H264, of("-an", "-brand", "qt  "), FAST_START,
                    of(out.resolve("invalid-brand-qt.mp4").toString())));
            run(tool, join(pattern("160x96", 15, 1), H264, of("-an", "-brand", "qt  ", "-movflags",
                    "+faststart+use_metadata_tags", "-metadata", "major_brand=isom"),
                    of(out.resolve("invalid-brand-spoofed-tag.mp4").toString())));
            // Largest legitimate index: ten minutes at thirty frames per second, 18000 samples.
            run(tool, join(of("-f", "lavfi", "-i", "color=c=blue:size=64x64:rate=30", "-t", "600"),
                    of("-c:v", "libx264", "-preset", "ultrafast", "-profile:v", "high", "-pix_fmt", "yuv420p",
                            "-b:v", "50k", "-g", "60", "-an"), FAST_START,
                    of(out.resolve("valid-dense-600s.mp4").toString())));
            // More damage: cut off early, ftyp alone, and nothing at all.
            byte[] whole2 = Files.readAllBytes(out.resolve("valid-160x96-silent.mp4"));
            Files.write(out.resolve("corrupt-truncated-early.mp4"), Arrays.copyOf(whole2, 1000));
            Files.write(out.resolve("corrupt-ftyp-only.mp4"), Arrays.copyOf(whole2, 32));
            Files.write(out.resolve("corrupt-empty.mp4"), new byte[0]);

            // M22 boundaries. The shortest clips: one frame (33 ms), and 100 ms with audio. The smallest picture the
            // policy accepts, the most extreme aspect ratios it accepts in both orientations, and an odd display width
            // that only exists through the pixel aspect ratio (98x64 with 3:2 pixels displays 147x64).
            run(tool, join(of("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30", "-frames:v", "1"), H264, of("-an"),
                    FAST_START, of(out.resolve("valid-one-frame.mp4").toString())));
            run(tool, join(of("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30"), TONE, of("-t", "0.1"), H264, AAC,
                    FAST_START, of(out.resolve("valid-100ms-aac.mp4").toString())));
            run(tool, join(pattern("16x16", 30, 2), H264, of("-an"), FAST_START,
                    of(out.resolve("valid-16x16.mp4").toString())));
            run(tool, join(pattern("1920x16", 30, 2), H264, of("-an"), FAST_START,
                    of(out.resolve("valid-strip-1920x16.mp4").toString())));
            run(tool, join(pattern("16x1920", 30, 2), H264, of("-an"), FAST_START,
                    of(out.resolve("valid-strip-16x1920.mp4").toString())));
            run(tool, join(pattern("98x64", 30, 2), H264, of("-vf", "setsar=3/2", "-an"), FAST_START,
                    of(out.resolve("valid-odd-sar-147x64.mp4").toString())));
            // Variable frame rate: 60 fps timestamps for the first second, then every fourth frame (15 fps). The average
            // is 300/11 (27.3 fps, accepted) while the peak and the stream's nominal rate are 60.
            run(tool, join(of("-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=60", "-t", "4", "-vf",
                    "select=lt(t\\,1)+not(mod(n\\,4))", "-fps_mode", "vfr"), H264, of("-an"), FAST_START,
                    of(out.resolve("valid-vfr-27fps.mp4").toString())));
            // Synchronization: a white flash and a 1 kHz beep both start at exactly 2.0 s. In the late variant the
            // audio track itself starts 0.5 s after the video (its beep is at 1.5 s of its own time, 2.0 s presented);
            // in the long variant the audio runs 1.5 s past the video, inside the policy's track slack.
            var flash = "color=black:size=320x240:rate=30,drawbox=enable='between(t,2,2.1)':color=white:t=fill";
            var beep = "sine=frequency=1000:sample_rate=48000,volume='if(between(t,%s,%s),1,0)':eval=frame";
            run(tool, join(of("-f", "lavfi", "-t", "4", "-i", flash, "-f", "lavfi", "-t", "4", "-i",
                    String.format(beep, "2", "2.1")), H264, AAC, FAST_START,
                    of(out.resolve("valid-sync-flash-beep.mp4").toString())));
            run(tool, join(of("-f", "lavfi", "-t", "4", "-i", flash, "-itsoffset", "0.5", "-f", "lavfi", "-t", "3.5", "-i",
                    String.format(beep, "1.5", "1.6")), H264, AAC, FAST_START,
                    of(out.resolve("valid-sync-late-audio.mp4").toString())));
            run(tool, join(of("-f", "lavfi", "-t", "6", "-i", "testsrc2=size=320x240:rate=30", "-f", "lavfi", "-t", "7.5",
                    "-i", "sine=frequency=440:sample_rate=48000", "-ac", "2"), H264, AAC, FAST_START,
                    of(out.resolve("valid-audio-longer.mp4").toString())));
            // Damaged tails. The index is at the front (fast start), so the probe sees a whole, consistent clip; the
            // damage is only in the media data: cut at 60 % and at 95 % of the file, or its last 30 % overwritten.
            var tailSource = work.resolve("tail-source.mp4");
            run(tool, join(of("-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30"), TONE, of("-t", "20"),
                    of("-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-b:v", "1500k", "-g", "60"), AAC,
                    FAST_START, of(tailSource.toString())));
            byte[] tail = Files.readAllBytes(tailSource);
            Files.write(out.resolve("corrupt-tail-cut-60.mp4"), Arrays.copyOf(tail, tail.length * 6 / 10));
            Files.write(out.resolve("corrupt-tail-cut-95.mp4"), Arrays.copyOf(tail, tail.length * 95 / 100));
            byte[] scrambled = tail.clone();
            var random = new Random(22);
            for (int i = scrambled.length * 7 / 10; i < scrambled.length; i++) { scrambled[i] = (byte) random.nextInt(); }
            Files.write(out.resolve("corrupt-tail-garbage.mp4"), scrambled);

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

    /** Rewrites the movie, track and media headers to claim one second; the sample table is left as it was. */
    private static byte[] lieAboutDuration(byte[] file) {
        var copy = file.clone();
        // Offsets after the box type: mvhd and mdhd keep version, flags, two dates, timescale, duration; tkhd
        // keeps version, flags, two dates, track id, a reserved word, then duration in movie units (1000 here).
        patch(copy, "mvhd", 16, 12);
        patch(copy, "mdhd", 16, 12);
        patch(copy, "tkhd", 20, -1);
        return copy;
    }

    private static void patch(byte[] file, String type, int durationAt, int timescaleAt) {
        byte[] tag = type.getBytes(StandardCharsets.US_ASCII);
        for (int i = 4; i + durationAt + 4 <= file.length; i++) {
            if (file[i] != tag[0] || file[i + 1] != tag[1] || file[i + 2] != tag[2] || file[i + 3] != tag[3]) {
                continue;
            }
            if (file[i + 4] != 0) { throw new AssertionError(type + " is not version 0"); }
            int units = timescaleAt < 0 ? 1000 : java.nio.ByteBuffer.wrap(file, i + 4 + timescaleAt, 4).getInt();
            java.nio.ByteBuffer.wrap(file, i + 4 + durationAt, 4).putInt(units);
            return;
        }
        throw new AssertionError(type + " box not found");
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
