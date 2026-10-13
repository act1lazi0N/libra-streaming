package com.libra.streaming.media.delivery.domain;

import com.libra.streaming.media.processing.application.HlsPackage;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StreamNameTest {
    @Test
    void onlyTheNamesThePipelineWritesAreNamesAtAll() {
        assertThat(StreamName.parse("master.m3u8").orElseThrow().kind()).isEqualTo(StreamName.Kind.MASTER);
        assertThat(StreamName.parse("rendition.m3u8").orElseThrow().kind()).isEqualTo(StreamName.Kind.RENDITION);
        assertThat(StreamName.parse("segment-00000.ts").orElseThrow().kind()).isEqualTo(StreamName.Kind.SEGMENT);
        assertThat(StreamName.parse("segment-00299.ts")).isPresent();
        for (var name : List.of("", " master.m3u8", "master.m3u8 ", "Master.m3u8", "master.M3U8", "master.m3u",
                "../master.m3u8", "./master.m3u8", "x/master.m3u8", "master.m3u8/", "master.m3u8%00", "rendition.m3u8.bak",
                "segment-00300.ts", "segment-99999.ts", "segment-0000.ts", "segment-000000.ts", "segment-00000.TS",
                "segment-00000.ts.part", "segment--0001.ts", "segment-+0001.ts", "segment-٠٠٠٠٠.ts",
                "segment-00000.mp4", "source.mp4", "staging", "..", ".", "%2e%2e", "hls", "attempt-1")) {
            assertThat(StreamName.parse(name)).as(name).isEmpty();
        }
        assertThat(StreamName.parse(null)).isEmpty();
    }

    @Test
    void theVocabularyMatchesThePipelinesLimitsAndTypes() {
        // The processing side writes the names and enforces the cap; delivery must agree with it, not import it.
        assertThat(StreamName.MAX_SEGMENTS).isEqualTo(HlsPackage.MAX_SEGMENTS);
        assertThat(HlsPackage.MASTER).isEqualTo("master.m3u8");
        assertThat(HlsPackage.VARIANT).isEqualTo("rendition.m3u8");
        assertThat(String.format(HlsPackage.SEGMENT_TEMPLATE, 7)).isEqualTo("segment-00007.ts");
        assertThat(StreamName.Kind.MASTER.contentType()).isEqualTo("application/vnd.apple.mpegurl");
        assertThat(StreamName.Kind.SEGMENT.contentType()).isEqualTo("video/mp2t");
        assertThat(StreamName.Kind.RENDITION.maxBytes()).isEqualTo(256 * 1024);
        assertThatThrownBy(() -> new StreamName("source.mp4", StreamName.Kind.SEGMENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StreamName("master.m3u8", StreamName.Kind.SEGMENT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
