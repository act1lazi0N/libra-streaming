package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.MediaProber;
import com.libra.streaming.media.processing.application.ProbeReport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** The JSON reader on hand-written documents shaped like ffprobe output; real tool output is a separate suite. */
class FfprobeJsonTest {
    private static ProbeReport parse(String json) { return FfprobeJson.parse(json.getBytes(StandardCharsets.UTF_8)); }

    @Test
    void readsFormatAndBothTrackKindsIncludingQuotedNumbers() {
        var report = parse("""
                {"streams":[
                  {"index":0,"codec_name":"h264","profile":"High","codec_type":"video","width":1280,"height":720,
                   "pix_fmt":"yuv420p","sample_aspect_ratio":"1:1","avg_frame_rate":"30/1","color_transfer":"bt709",
                   "color_primaries":"bt709","disposition":{"attached_pic":0},
                   "side_data_list":[{"side_data_type":"Display Matrix","rotation":-90}]},
                  {"index":1,"codec_name":"aac","profile":"LC","codec_type":"audio","sample_rate":"48000","channels":2,
                   "disposition":{"attached_pic":0}}],
                 "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"10.016000",
                   "tags":{"major_brand":"isom","minor_version":"512"}}}
                """);
        assertThat(report.format()).isEqualTo(new ProbeReport.Format("mov,mp4,m4a,3gp,3g2,mj2", "isom",
                new BigDecimal("10.016000")));
        var video = report.tracks().get(0);
        assertThat(video.type()).isEqualTo("video");
        assertThat(video.width()).isEqualTo(1280);
        assertThat(video.rotationDegrees()).isEqualTo(-90.0);
        assertThat(video.attachedPicture()).isFalse();
        var audio = report.tracks().get(1);
        assertThat(audio.sampleRate()).isEqualTo(48000);
        assertThat(audio.channels()).isEqualTo(2);
    }

    @Test
    void fallsBackToTheLegacyRotateTagAndFlagsAttachedPictures() {
        var report = parse("""
                {"streams":[{"codec_type":"video","codec_name":"mjpeg","tags":{"rotate":"270"},
                  "disposition":{"attached_pic":1}}],"format":{"format_name":"mov,mp4"}}
                """);
        assertThat(report.tracks().getFirst().rotationDegrees()).isEqualTo(270.0);
        assertThat(report.tracks().getFirst().attachedPicture()).isTrue();
    }

    @Test
    void placeholdersAndNonFiniteValuesBecomeNullNotNumbers() {
        var report = parse("""
                {"streams":[{"codec_type":"video","width":"N/A","height":720.5,"avg_frame_rate":"0/0",
                  "channels":99999999999,"sample_rate":"abc"}],
                 "format":{"format_name":"mov,mp4","duration":"N/A"}}
                """);
        assertThat(report.format().durationSeconds()).isNull();
        var track = report.tracks().getFirst();
        assertThat(track.width()).isNull();
        assertThat(track.height()).isNull();
        assertThat(track.channels()).isNull();
        assertThat(track.sampleRate()).isNull();
        assertThat(track.averageFrameRate()).isEqualTo("0/0");
        for (String duration : new String[] {"\"nan\"", "\"inf\"", "\"-inf\"", "\"1e999999999\"", "\"1e-999999999\"",
                "\"123456789012345678901234567890\"", "true", "null", "[]"}) {
            assertThat(parse("{\"streams\":[],\"format\":{\"format_name\":\"mov,mp4\",\"duration\":" + duration + "}}")
                    .format().durationSeconds()).as(duration).isNull();
        }
    }

    @Test
    void wholeNumbersWrittenWithFractionalZerosAreStillIntegers() {
        var track = parse("{\"streams\":[{\"codec_type\":\"video\",\"width\":640.0}],\"format\":{}}")
                .tracks().getFirst();
        assertThat(track.width()).isEqualTo(640);
    }

    @Test
    void overlongTextIsDroppedAndOnlyTheFirstTracksAreKept() {
        var longName = "x".repeat(65);
        var report = parse("{\"streams\":[{\"codec_type\":\"" + longName + "\"}],\"format\":{\"format_name\":\"" + longName + "\"}}");
        assertThat(report.format().names()).isNull();
        assertThat(report.tracks().getFirst().type()).isNull();
        var many = new StringBuilder("{\"streams\":[");
        for (int i = 0; i < 200; i++) { many.append(i == 0 ? "" : ",").append("{\"codec_type\":\"data\"}"); }
        many.append("],\"format\":{}}");
        assertThat(parse(many.toString()).tracks()).hasSize(64);
    }

    @Test
    void documentsThatAreNotAProbeResultAreUnreadable() {
        for (String json : new String[] {"", "not json", "[]", "null", "{}", "{\"format\":{}}", "{\"streams\":[]}",
                "{\"format\":[],\"streams\":[]}", "{\"format\":{},\"streams\":{}}", "{\"streams\":[],\"format\":{}",
                "{\"streams\":[],\"format\":{},\"format\":{}}", "{\"a\":1}{\"b\":2}"}) {
            assertThatThrownBy(() -> parse(json)).as(json).isInstanceOf(MediaProber.Unreadable.class);
        }
    }

    @Test
    void anExceptionNeverCarriesInputText() {
        assertThatThrownBy(() -> parse("SECRET-TOKEN not json")).isInstanceOf(MediaProber.Unreadable.class)
                .hasMessageNotContaining("SECRET").hasNoCause();
    }

    private static String withRotations(String sideData, String tags) {
        return "{\"streams\":[{\"codec_type\":\"video\",\"side_data_list\":[" + sideData + "],\"tags\":{" + tags
                + "}}],\"format\":{}}";
    }

    @Test
    void rotationsThatAgreeAreKeptAndRotationsThatDisagreeBecomeNaN() {
        var matrix = "{\"rotation\":-90}";
        assertThat(parse(withRotations(matrix + "," + matrix, "")).tracks().getFirst().rotationDegrees())
                .isEqualTo(-90.0);
        assertThat(parse(withRotations(matrix + ",{\"rotation\":270}", "")).tracks().getFirst().rotationDegrees())
                .isEqualTo(-90.0);
        assertThat(parse(withRotations(matrix + ",{\"rotation\":90}", "")).tracks().getFirst().rotationDegrees())
                .isNaN();
        assertThat(parse(withRotations(matrix, "\"rotate\":\"0\"")).tracks().getFirst().rotationDegrees()).isNaN();
        assertThat(parse(withRotations(matrix, "\"rotate\":\"-90\"")).tracks().getFirst().rotationDegrees())
                .isEqualTo(-90.0);
        assertThat(parse(withRotations("", "\"rotate\":\"90\"")).tracks().getFirst().rotationDegrees())
                .isEqualTo(90.0);
    }

    @Test
    void trackDurationAndFrameCountAreReadLikeOtherNumbers() {
        var track = parse("{\"streams\":[{\"codec_type\":\"video\",\"duration\":\"10.016000\",\"nb_frames\":\"300\"},"
                + "{\"codec_type\":\"audio\",\"duration\":\"N/A\",\"nb_frames\":\"1e99\"}],\"format\":{}}").tracks();
        assertThat(track.get(0).durationSeconds()).isEqualByComparingTo("10.016");
        assertThat(track.get(0).frameCount()).isEqualTo(300);
        assertThat(track.get(1).durationSeconds()).isNull();
        assertThat(track.get(1).frameCount()).isNull();
    }

    @Test
    void absurdNestingOrSizeWithinTheOutputBudgetIsUnreadable() {
        for (int depth : new int[] {600, 5000}) {
            assertThatThrownBy(() -> parse("[".repeat(depth) + "]".repeat(depth))).as("depth " + depth)
                    .isInstanceOf(MediaProber.Unreadable.class);
            assertThatThrownBy(() -> parse("{\"streams\":[],\"format\":" + "{\"a\":".repeat(depth) + "1" + "}".repeat(depth) + "}"))
                    .as("object depth " + depth).isInstanceOf(MediaProber.Unreadable.class);
        }
        var huge = "x".repeat(200_000);
        var report = parse("{\"streams\":[],\"format\":{\"format_name\":\"" + huge + "\",\"tags\":{\"major_brand\":\""
                + huge + "\"}}}");
        assertThat(report.format().names()).isNull();
        assertThat(report.format().majorBrand()).isNull();
    }
}
