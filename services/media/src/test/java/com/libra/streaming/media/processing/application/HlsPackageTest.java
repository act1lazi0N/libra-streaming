package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.RenditionPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** The inventory check on encoder output; the real encoder's files are covered by the fixture suite. */
class HlsPackageTest {
    private static final RenditionPlan PLAN = new RenditionPlan(1280, 720, 30000, 1001, 2);
    @TempDir Path directory;

    private static String playlist(String... entries) {
        var text = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        for (var entry : entries) { text.append(entry).append('\n'); }
        return text.append("#EXT-X-ENDLIST\n").toString();
    }

    private static List<String> segments(int count, String duration) {
        return IntStream.range(0, count).boxed()
                .flatMap(index -> Stream.of("#EXTINF:" + duration + ",", String.format("segment-%05d.ts", index)))
                .toList();
    }

    private void write(String playlist, int segmentCount, int bytes) throws IOException {
        Files.writeString(directory.resolve("rendition.m3u8"), playlist);
        for (int index = 0; index < segmentCount; index++) {
            Files.write(directory.resolve(String.format("segment-%05d.ts", index)), new byte[bytes]);
        }
    }

    private void sealMalformed(String reason) {
        assertThatThrownBy(() -> HlsPackage.seal(directory, PLAN)).isInstanceOf(HlsPackage.Malformed.class)
                .hasMessage(reason);
        assertThat(directory.resolve("master.m3u8")).doesNotExist();
    }

    @Test
    void aCompleteRenditionBecomesAClosedInventoryAndAMasterThatPointsAtTheVariant() throws IOException {
        write(playlist("#EXTINF:4.000000,", "segment-00000.ts", "#EXTINF:4.000000,", "segment-00001.ts",
                "#EXTINF:2.500000,", "segment-00002.ts"), 3, 100_000);

        var output = HlsPackage.seal(directory, PLAN);

        assertThat(output.segments()).extracting(HlsOutput.Segment::name)
                .containsExactly("segment-00000.ts", "segment-00001.ts", "segment-00002.ts");
        assertThat(output.segments()).extracting(HlsOutput.Segment::durationMillis).containsExactly(4000L, 4000L, 2500L);
        assertThat(output.durationMillis()).isEqualTo(10_500);
        assertThat(output.width()).isEqualTo(1280);
        assertThat(output.height()).isEqualTo(720);
        assertThat(output.hasAudio()).isTrue();
        assertThat(output.masterName()).isEqualTo("master.m3u8");
        assertThat(output.variantName()).isEqualTo("rendition.m3u8");
        // 100000 bytes in 2.5 s is the densest segment: 320000 bit/s; the average is 300000 bytes * 8 / 10.5 s.
        assertThat(output.peakBitsPerSecond()).isEqualTo(320_000);
        assertThat(output.averageBitsPerSecond()).isEqualTo(228_572);
        var master = Files.readString(directory.resolve("master.m3u8"));
        assertThat(master).contains("BANDWIDTH=320000").contains("AVERAGE-BANDWIDTH=228572")
                .contains("RESOLUTION=1280x720").contains("FRAME-RATE=29.970")
                .contains("CODECS=\"avc1.4d401f,mp4a.40.2\"");
        // The only reference is a relative name, never a path or URL.
        var references = master.lines().filter(line -> !line.startsWith("#")).toList();
        assertThat(references).containsExactly("rendition.m3u8");
        assertThat(output.totalBytes()).isEqualTo(Files.size(directory.resolve("rendition.m3u8"))
                + 300_000 + Files.size(directory.resolve("master.m3u8")));
    }

    @Test
    void aSilentRenditionAdvertisesNoAudioCodec() throws IOException {
        write(playlist(segments(1, "4.000000").toArray(String[]::new)), 1, 5000);
        HlsPackage.seal(directory, new RenditionPlan(160, 96, 15, 1, 0));
        assertThat(Files.readString(directory.resolve("master.m3u8"))).contains("CODECS=\"avc1.4d401f\"")
                .doesNotContain("mp4a").contains("FRAME-RATE=15.000").contains("RESOLUTION=160x96");
    }

    @Test
    void aMissingOrEmptyPlaylistOrSegmentIsNotARendition() throws IOException {
        sealMalformed("PLAYLIST_MISSING");
        write(playlist(segments(2, "4.000000").toArray(String[]::new)), 2, 10);
        Files.delete(directory.resolve("segment-00001.ts"));
        sealMalformed("SEGMENT_MISSING");
        Files.write(directory.resolve("segment-00001.ts"), new byte[0]);
        sealMalformed("SEGMENT_EMPTY");
    }

    @Test
    void aFileTheInventoryDoesNotNameIsRejected() throws IOException {
        write(playlist(segments(1, "4.000000").toArray(String[]::new)), 1, 10);
        Files.write(directory.resolve("segment-00001.ts"), new byte[10]);
        sealMalformed("UNEXPECTED_FILE");
        Files.delete(directory.resolve("segment-00001.ts"));
        Files.createDirectory(directory.resolve("nested"));
        sealMalformed("UNEXPECTED_FILE");
        Files.delete(directory.resolve("nested"));
        Files.writeString(directory.resolve("stray.txt"), "x");
        sealMalformed("UNEXPECTED_FILE");
    }

    @Test
    void anIncompletePlaylistIsRejected() throws IOException {
        var complete = playlist(segments(1, "4.000000").toArray(String[]::new));
        write(complete.replace("#EXT-X-ENDLIST\n", ""), 1, 10);
        sealMalformed("INCOMPLETE");
        write(complete.replace("#EXT-X-PLAYLIST-TYPE:VOD\n", ""), 1, 10);
        sealMalformed("INCOMPLETE");
        write(complete.replace("#EXT-X-INDEPENDENT-SEGMENTS\n", ""), 1, 10);
        sealMalformed("INCOMPLETE");
        write(complete.replace("#EXT-X-TARGETDURATION:4\n", ""), 1, 10);
        sealMalformed("INCOMPLETE");
        write("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-TARGETDURATION:4\n"
                + "#EXT-X-ENDLIST\n", 0, 0);
        sealMalformed("INCOMPLETE");
        write(complete.replace("segment-00000.ts\n", ""), 1, 10);
        sealMalformed("SEGMENT_NAME");
    }

    @Test
    void unknownTagsAndForeignReferencesAreRejected() throws IOException {
        for (var tag : List.of("#EXT-X-BYTERANGE:100@0", "#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-KEY:METHOD=AES-128",
                "#EXT-X-DISCONTINUITY", "#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00Z", "# a comment",
                "http://example.invalid/x.ts", "../segment-00000.ts", "/etc/passwd", "file:///etc/passwd")) {
            write(playlist(tag, "#EXTINF:4.000000,", "segment-00000.ts"), 1, 10);
            sealMalformed("TAG");
        }
    }

    @Test
    void segmentNamesMustBeTheGeneratedOnesInOrder() throws IOException {
        for (var name : List.of("segment-00001.ts", "segment-0.ts", "Segment-00000.ts", "segment-00000.TS",
                "sub/segment-00000.ts", "segment-00000.ts?x=1", "..\\segment-00000.ts", "segment-00000.ts ")) {
            write(playlist("#EXTINF:4.000000,", name), 1, 10);
            sealMalformed("SEGMENT_NAME");
        }
    }

    @Test
    void durationsMustBeFinitePositiveAndWithinTheTargetDuration() throws IOException {
        for (var duration : List.of("0.000000", "-1.0", "NaN", "Infinity", "1e3", "")) {
            write(playlist("#EXTINF:" + duration + ",", "segment-00000.ts"), 1, 10);
            sealMalformed("EXTINF");
        }
        write(playlist("#EXTINF:5.600000,", "segment-00000.ts"), 1, 10);
        sealMalformed("TARGET_DURATION");
        // 4.4 rounds to 4, which the target duration of 4 allows.
        write(playlist("#EXTINF:4.400000,", "segment-00000.ts"), 1, 10);
        assertThat(HlsPackage.seal(directory, PLAN).durationMillis()).isEqualTo(4400);
    }

    @Test
    void aRenditionLongerThanAnyAcceptedSourceOrWithTooManySegmentsIsRejected() throws IOException {
        write(playlist(segments(HlsPackage.MAX_SEGMENTS + 1, "1.000000").toArray(String[]::new)),
                HlsPackage.MAX_SEGMENTS + 1, 10);
        sealMalformed("SEGMENT_COUNT");
        for (var file : directory.toFile().listFiles()) { assertThat(file.delete()).isTrue(); }
        write(playlist(segments(160, "4.000000").toArray(String[]::new)), 160, 10);
        sealMalformed("DURATION");
    }

    @Test
    void aLinkInsteadOfAFileIsNotFollowed() throws IOException {
        var outside = Files.writeString(Files.createTempFile("outside-", ".ts"), "outside the workspace");
        try {
            write(playlist(segments(1, "4.000000").toArray(String[]::new)), 0, 0);
            try { Files.createSymbolicLink(directory.resolve("segment-00000.ts"), outside); }
            catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
                // Creating links needs a privilege on some hosts; the check is then not exercisable here.
                return;
            }
            sealMalformed("SEGMENT_MISSING");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void anOversizedOrUnreadablePlaylistIsRejected() throws IOException {
        Files.writeString(directory.resolve("rendition.m3u8"), "#EXTM3U\n" + "#".repeat((int) HlsPackage.MAX_PLAYLIST_BYTES));
        sealMalformed("PLAYLIST_SIZE");
        Files.write(directory.resolve("rendition.m3u8"), new byte[] {'#', 'E', (byte) 0xff, (byte) 0xfe, '\n'});
        sealMalformed("IO");
    }
}
