package com.libra.streaming.media.processing.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RenditionPlanTest {
    private static SourceMetadata source(int width, int height, SourceMetadata.Audio audio) {
        return new SourceMetadata(10_000, width, height, 0, width, height, 30, 1, "High", audio);
    }

    private static RenditionPlan plan(int width, int height) { return RenditionPlan.of(source(width, height, null)); }

    @Test
    void aLargeLandscapeSourceIsFittedInsideTheBoxKeepingItsAspectRatio() {
        assertThat(plan(1920, 1080)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(1280, 720);
        // 1920x1080 is 16:9; a wider source is limited by width and a taller one by height.
        assertThat(plan(1920, 800)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(1280, 532);
        assertThat(plan(1440, 1080)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(960, 720);
        assertThat(plan(1920, 16)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(1280, 10);
    }

    @Test
    void aPortraitSourceUsesThePortraitBox() {
        assertThat(plan(1080, 1920)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(720, 1280);
        assertThat(plan(720, 1280)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(720, 1280);
        // A tall, narrow clip is limited by its height, a wide-ish portrait one by its width.
        assertThat(plan(800, 1080)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(720, 972);
        assertThat(plan(16, 1920)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(10, 1280);
        assertThat(plan(360, 640)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(360, 640);
    }

    @Test
    void aSquareSourceIsFittedLikeALandscapeOne() {
        assertThat(plan(1080, 1080)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(720, 720);
        assertThat(plan(400, 400)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(400, 400);
    }

    @Test
    void aSourceInsideTheBoxIsNeverEnlarged() {
        assertThat(plan(1280, 720)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(1280, 720);
        assertThat(plan(640, 360)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(640, 360);
        assertThat(plan(160, 96)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(160, 96);
        assertThat(plan(16, 16)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(16, 16);
    }

    @Test
    void oddResultsAreRoundedDownToEvenSizesAndNeverUp() {
        assertThat(plan(161, 97)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(160, 96);
        // 1279x719 is inside the box, so only the evenness rule applies.
        assertThat(plan(1279, 719)).extracting(RenditionPlan::width, RenditionPlan::height).containsExactly(1278, 718);
        for (int width = 16; width <= 1920; width += 37) {
            for (int height = 16; height <= 1080; height += 41) {
                var result = plan(width, height);
                assertThat(result.width() % 2).isZero();
                assertThat(result.height() % 2).isZero();
                assertThat(result.width()).isLessThanOrEqualTo(width);
                assertThat(result.height()).isLessThanOrEqualTo(height);
                assertThat(Math.max(result.width(), result.height())).isLessThanOrEqualTo(1280);
                assertThat(Math.min(result.width(), result.height())).isLessThanOrEqualTo(720);
                // The orientation never flips.
                assertThat(result.height() > result.width()).isEqualTo(height > width && result.height() != result.width());
            }
        }
    }

    @Test
    void theDisplayRectangleDecidesTheSizeSoRotationAndPixelShapeAreAlreadyApplied() {
        // A 1280x720 coded clip rotated a quarter turn displays as 720x1280, which the portrait box holds as it is.
        var rotated = new SourceMetadata(10_000, 1280, 720, 90, 720, 1280, 30, 1, "High", null);
        assertThat(RenditionPlan.of(rotated)).extracting(RenditionPlan::width, RenditionPlan::height)
                .containsExactly(720, 1280);
        // 1440x1080 with 4:3 pixels displays as 1920x1080.
        var wide = new SourceMetadata(10_000, 1440, 1080, 0, 1920, 1080, 30, 1, "High", null);
        assertThat(RenditionPlan.of(wide)).extracting(RenditionPlan::width, RenditionPlan::height)
                .containsExactly(1280, 720);
    }

    @Test
    void audioIsKeptAtMostStereoAndASilentSourceStaysSilent() {
        assertThat(RenditionPlan.of(source(640, 360, null)).hasAudio()).isFalse();
        assertThat(RenditionPlan.of(source(640, 360, new SourceMetadata.Audio(1, 44100))).audioChannels()).isEqualTo(1);
        assertThat(RenditionPlan.of(source(640, 360, new SourceMetadata.Audio(2, 48000))).audioChannels()).isEqualTo(2);
        assertThat(RenditionPlan.of(source(640, 360, new SourceMetadata.Audio(6, 48000))).audioChannels()).isEqualTo(2);
    }

    @Test
    void theSourceFrameRateIsCarriedUnchanged() {
        var ntsc = new SourceMetadata(10_000, 640, 360, 0, 640, 360, 30000, 1001, "High", null);
        assertThat(RenditionPlan.of(ntsc)).extracting(RenditionPlan::frameRateNumerator,
                RenditionPlan::frameRateDenominator).containsExactly(30000, 1001);
    }

    @Test
    void aPlanOutsideTheBoxOrWithOddSidesCannotBeBuilt() {
        assertThatThrownBy(() -> new RenditionPlan(1282, 720, 30, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenditionPlan(1280, 722, 30, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenditionPlan(722, 1280, 30, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenditionPlan(720, 1282, 30, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new RenditionPlan(720, 1280, 30, 1, 0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new RenditionPlan(641, 360, 30, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenditionPlan(640, 360, 30, 1, 3)).isInstanceOf(IllegalArgumentException.class);
    }
}
