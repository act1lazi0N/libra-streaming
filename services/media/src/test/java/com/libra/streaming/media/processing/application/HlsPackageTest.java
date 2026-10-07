package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.RenditionPlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** The inventory check on encoder output; the real encoder's files are covered by the fixture suite. */
class HlsPackageTest {
    private static final RenditionPlan PLAN = new RenditionPlan(1280, 720, 30000, 1001, 2);
    /** 532 transport packets. */
    private static final int SEGMENT_BYTES = 532 * 188;
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

    /** Whole transport packets, each starting with the sync byte, the way a finished segment looks. */
    private static byte[] packets(int bytes) {
        var content = new byte[bytes];
        for (int at = 0; at < bytes; at += 188) { content[at] = 0x47; }
        return content;
    }

    private void write(String playlist, int segmentCount, int bytes) throws IOException {
        Files.writeString(directory.resolve("rendition.m3u8"), playlist);
        for (int index = 0; index < segmentCount; index++) {
            Files.write(directory.resolve(String.format("segment-%05d.ts", index)), packets(bytes));
        }
    }

    private HlsOutput seal(long sourceMillis) { return HlsPackage.seal(directory, PLAN, sourceMillis); }

    private void sealMalformed(String reason) { sealMalformed(reason, 4000); }

    private void sealMalformed(String reason, long sourceMillis) {
        var before = readVariant();
        assertThatThrownBy(() -> seal(sourceMillis)).isInstanceOf(HlsPackage.Malformed.class).hasMessage(reason);
        assertThat(directory.resolve("master.m3u8")).doesNotExist();
        // A rejected workspace is left exactly as the encoder wrote it: nothing is rewritten on the way out.
        assertThat(readVariant()).isEqualTo(before);
    }

    private String readVariant() {
        try {
            var variant = directory.resolve("rendition.m3u8");
            return Files.isRegularFile(variant) ? new String(Files.readAllBytes(variant), StandardCharsets.ISO_8859_1) : null;
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void aCompleteRenditionBecomesAClosedInventoryAndAMasterThatPointsAtTheVariant() throws IOException {
        write(playlist("#EXTINF:4.000000,", "segment-00000.ts", "#EXTINF:4.000000,", "segment-00001.ts",
                "#EXTINF:2.500000,", "segment-00002.ts"), 3, SEGMENT_BYTES);

        var output = seal(10_500);

        assertThat(output.segments()).extracting(HlsOutput.Segment::name)
                .containsExactly("segment-00000.ts", "segment-00001.ts", "segment-00002.ts");
        assertThat(output.segments()).extracting(HlsOutput.Segment::durationMillis).containsExactly(4000L, 4000L, 2500L);
        assertThat(output.durationMillis()).isEqualTo(10_500);
        assertThat(output.width()).isEqualTo(1280);
        assertThat(output.height()).isEqualTo(720);
        assertThat(output.hasAudio()).isTrue();
        assertThat(output.masterName()).isEqualTo("master.m3u8");
        assertThat(output.variantName()).isEqualTo("rendition.m3u8");
        // 100016 bytes in 2.5 s is the densest segment; the average is 300048 bytes * 8 over 10.5 s.
        assertThat(output.peakBitsPerSecond()).isEqualTo(320_052);
        assertThat(output.averageBitsPerSecond()).isEqualTo(228_608);
        var master = Files.readString(directory.resolve("master.m3u8"));
        assertThat(master).contains("BANDWIDTH=320052").contains("AVERAGE-BANDWIDTH=228608")
                .contains("RESOLUTION=1280x720").contains("FRAME-RATE=29.970")
                .contains("CODECS=\"avc1.4d401f,mp4a.40.2\"");
        // The only reference is a relative name, never a path or URL.
        var references = master.lines().filter(line -> !line.startsWith("#")).toList();
        assertThat(references).containsExactly("rendition.m3u8");
        assertThat(output.totalBytes()).isEqualTo(Files.size(directory.resolve("rendition.m3u8"))
                + 3L * SEGMENT_BYTES + Files.size(directory.resolve("master.m3u8")));
    }

    @Test
    void theVariantPlaylistIsRewrittenFromTheCheckedInventory() throws IOException {
        // Version, sequence and decimal places as ffmpeg writes them; the service's copy states its own.
        write("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n"
                + "#EXT-X-INDEPENDENT-SEGMENTS\n#EXTINF:4.004000,\nsegment-00000.ts\n#EXTINF:1.234567,\n"
                + "segment-00001.ts\n#EXT-X-ENDLIST\n", 2, SEGMENT_BYTES);

        var output = seal(5_238);

        assertThat(Files.readString(directory.resolve("rendition.m3u8"))).isEqualTo("#EXTM3U\n#EXT-X-VERSION:6\n"
                + "#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n"
                + "#EXT-X-INDEPENDENT-SEGMENTS\n#EXTINF:4.004,\nsegment-00000.ts\n#EXTINF:1.235,\nsegment-00001.ts\n"
                + "#EXT-X-ENDLIST\n");
        assertThat(output.segments()).extracting(HlsOutput.Segment::durationMillis).containsExactly(4004L, 1235L);
        // Every URI in either playlist is a name in the inventory.
        var names = new HashSet<String>(List.of("rendition.m3u8"));
        output.segments().forEach(segment -> names.add(segment.name()));
        for (var playlist : List.of("rendition.m3u8", "master.m3u8")) {
            assertThat(Files.readAllLines(directory.resolve(playlist)).stream().filter(line -> !line.startsWith("#")))
                    .as(playlist).isSubsetOf(names);
        }
    }

    @Test
    void aClipShorterThanHalfASecondGetsATargetDurationOfOne() throws IOException {
        // What ffmpeg writes for a one-frame clip: a target duration of 0.
        write(playlist("#EXTINF:0.033333,", "segment-00000.ts").replace("TARGETDURATION:4", "TARGETDURATION:0"),
                1, 188);

        var output = seal(33);

        assertThat(output.durationMillis()).isEqualTo(33);
        assertThat(Files.readString(directory.resolve("rendition.m3u8"))).contains("#EXT-X-TARGETDURATION:1\n")
                .contains("#EXTINF:0.033,\n");
    }

    @Test
    void aPositiveDurationBelowHalfAMillisecondIsNeverWrittenAsZero() throws IOException {
        write(playlist("#EXTINF:3.000000,", "segment-00000.ts", "#EXTINF:0.000400,", "segment-00001.ts"), 2, 188);

        var output = seal(3_000);

        assertThat(output.segments()).extracting(HlsOutput.Segment::durationMillis).containsExactly(3000L, 1L);
        assertThat(Files.readString(directory.resolve("rendition.m3u8"))).contains("#EXTINF:0.001,\n")
                .doesNotContain("0.000");
    }

    @Test
    void aSilentRenditionAdvertisesNoAudioCodec() throws IOException {
        write(playlist(segments(1, "4.000000").toArray(String[]::new)), 1, 188 * 27);
        HlsPackage.seal(directory, new RenditionPlan(160, 96, 15, 1, 0), 4000);
        assertThat(Files.readString(directory.resolve("master.m3u8"))).contains("CODECS=\"avc1.4d401f\"")
                .doesNotContain("mp4a").contains("FRAME-RATE=15.000").contains("RESOLUTION=160x96");
    }

    @Test
    void aMissingOrEmptyPlaylistOrSegmentIsNotARendition() throws IOException {
        sealMalformed("PLAYLIST_MISSING");
        Files.writeString(directory.resolve("rendition.m3u8"), "");
        sealMalformed("HEADER"); // what a full disk leaves behind
        write(playlist(segments(2, "4.000000").toArray(String[]::new)), 2, 188);
        Files.delete(directory.resolve("segment-00001.ts"));
        sealMalformed("SEGMENT_MISSING", 8000);
        Files.write(directory.resolve("segment-00001.ts"), new byte[0]);
        sealMalformed("SEGMENT_EMPTY", 8000);
    }

    @Test
    void aSegmentThatIsNotWholeTransportPacketsIsRejected() throws IOException {
        write(playlist(segments(2, "4.000000").toArray(String[]::new)), 2, SEGMENT_BYTES);
        // A write cut short by a full disk: the playlist still lists the segment, the file ends mid-packet.
        Files.write(directory.resolve("segment-00001.ts"), Arrays.copyOf(packets(SEGMENT_BYTES), 50_000));
        sealMalformed("SEGMENT_PACKETS", 8000);
        // The right length, but not transport packets.
        Files.write(directory.resolve("segment-00001.ts"), new byte[SEGMENT_BYTES]);
        sealMalformed("SEGMENT_PACKETS", 8000);
        Files.write(directory.resolve("segment-00001.ts"), packets(SEGMENT_BYTES));
        assertThat(seal(8000).segments()).hasSize(2);
    }

    @Test
    void aFileTheInventoryDoesNotNameIsRejected() throws IOException {
        write(playlist(segments(1, "4.000000").toArray(String[]::new)), 1, 188);
        Files.write(directory.resolve("segment-00001.ts"), packets(188));
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
        // What ffmpeg leaves when it is killed mid-output: the progressive playlist has no end tag yet.
        write(complete.replace("#EXT-X-ENDLIST\n", ""), 1, 188);
        sealMalformed("INCOMPLETE");
        write(complete.replace("#EXT-X-PLAYLIST-TYPE:VOD\n", ""), 1, 188);
        sealMalformed("INCOMPLETE");
        write(complete.replace("#EXT-X-INDEPENDENT-SEGMENTS\n", ""), 1, 188);
        sealMalformed("INCOMPLETE");
        write(complete.replace("#EXT-X-TARGETDURATION:4\n", ""), 1, 188);
        sealMalformed("INCOMPLETE");
        write("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-TARGETDURATION:4\n"
                + "#EXT-X-ENDLIST\n", 0, 0);
        sealMalformed("INCOMPLETE");
        write(complete.replace("segment-00000.ts\n", ""), 1, 188);
        sealMalformed("SEGMENT_NAME");
    }

    @Test
    void unknownTagsAndForeignReferencesAreRejected() throws IOException {
        for (var tag : List.of("#EXT-X-BYTERANGE:100@0", "#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-KEY:METHOD=AES-128",
                "#EXT-X-DISCONTINUITY", "#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00Z", "# a comment",
                "http://example.invalid/x.ts", "../segment-00000.ts", "/etc/passwd", "file:///etc/passwd")) {
            write(playlist(tag, "#EXTINF:4.000000,", "segment-00000.ts"), 1, 188);
            sealMalformed("TAG");
        }
    }

    @Test
    void segmentNamesMustBeTheGeneratedOnesInOrder() throws IOException {
        for (var name : List.of("segment-00001.ts", "segment-0.ts", "Segment-00000.ts", "segment-00000.TS",
                "sub/segment-00000.ts", "segment-00000.ts?x=1", "..\\segment-00000.ts", "segment-00000.ts ")) {
            write(playlist("#EXTINF:4.000000,", name), 1, 188);
            sealMalformed("SEGMENT_NAME");
        }
    }

    @Test
    void durationsMustBeFinitePositiveAndWithinTheTargetDuration() throws IOException {
        for (var duration : List.of("0.000000", "-1.0", "NaN", "Infinity", "1e3", "")) {
            write(playlist("#EXTINF:" + duration + ",", "segment-00000.ts"), 1, 188);
            sealMalformed("EXTINF");
        }
        write(playlist("#EXTINF:5.600000,", "segment-00000.ts"), 1, 188);
        sealMalformed("TARGET_DURATION");
        write(playlist("#EXTINF:0.600000,", "segment-00000.ts").replace("TARGETDURATION:4", "TARGETDURATION:0"), 1, 188);
        sealMalformed("TARGET_DURATION", 600);
        // 4.4 rounds to 4, which the target duration of 4 allows.
        write(playlist("#EXTINF:4.400000,", "segment-00000.ts"), 1, 188);
        assertThat(seal(4400).durationMillis()).isEqualTo(4400);
    }

    @Test
    void aRenditionThatDoesNotLastAsLongAsItsSourceIsRejected() throws IOException {
        // What a damaged tail or a caught signal leaves: a well-formed playlist covering 12 of 20 seconds.
        write(playlist(segments(3, "4.000000").toArray(String[]::new)), 3, 188);
        sealMalformed("DURATION", 20_000);
        // Two seconds is the slack below ten seconds; a tenth of the clip above it.
        assertThat(seal(14_000).durationMillis()).isEqualTo(12_000);
        Files.delete(directory.resolve("master.m3u8"));
        write(playlist(segments(3, "4.000000").toArray(String[]::new)), 3, 188);
        sealMalformed("DURATION", 14_001);
        sealMalformed("DURATION", 9_999);
        write(playlist(segments(140, "4.000000").toArray(String[]::new)), 140, 188);
        assertThat(seal(600_000).durationMillis()).isEqualTo(560_000);
        Files.delete(directory.resolve("master.m3u8"));
        write(playlist(segments(140, "4.000000").toArray(String[]::new)), 140, 188);
        sealMalformed("DURATION", 500_000);
    }

    @Test
    void aSourceDurationOutsideThePolicyIsAProgrammingError() throws IOException {
        write(playlist(segments(1, "4.000000").toArray(String[]::new)), 1, 188);
        assertThatThrownBy(() -> seal(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> seal(600_001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooManySegmentsAreRejected() throws IOException {
        write(playlist(segments(HlsPackage.MAX_SEGMENTS + 1, "1.000000").toArray(String[]::new)),
                HlsPackage.MAX_SEGMENTS + 1, 188);
        sealMalformed("SEGMENT_COUNT", 301_000);
    }

    @Test
    void aLinkInsteadOfAFileIsNotFollowed() throws IOException {
        var outside = Files.write(Files.createTempFile("outside-", ".ts"), packets(188));
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
