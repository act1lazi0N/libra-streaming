package com.libra.streaming.recommendation.internalapi.api;

import com.libra.streaming.recommendation.statistics.application.PlatformStatistics;
import com.libra.streaming.recommendation.recommendations.application.ProfileRecommendations;
import com.libra.streaming.recommendation.recommendations.domain.RankedTitle;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.when;
import static com.libra.streaming.recommendation.recommendations.domain.RankedTitle.Rule.GENRE_INTEREST;
import static com.libra.streaming.recommendation.recommendations.domain.RankedTitle.Rule.POPULAR;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class InternalQueryControllerTest {
    @Test
    void recommendationsReturnOnlyTheV1KeysInRankOrderAndWholeSeconds() {
        var statistics = mock(PlatformStatistics.class);
        var recommendations = mock(ProfileRecommendations.class);
        var account = new UUID(0, 1);
        var profile = new UUID(0, 2);
        var first = new UUID(0, 9);
        var second = new UUID(0, 3);
        when(recommendations.forProfile(account, profile, 20)).thenReturn(Optional.of(
                new ProfileRecommendations.Snapshot(profile, Instant.parse("2026-09-07T10:00:00.999999Z"),
                        List.of(new RankedTitle(
                                first, GENRE_INTEREST),
                                new RankedTitle(
                                        second, POPULAR)))));
        var controller = new InternalQueryController(statistics, recommendations, new Problems(JsonMapper.builder().build()),
                Clock.fixed(Instant.parse("2026-09-07T10:01:00Z"), ZoneOffset.UTC));
        var response = controller.recommendations(recommendationRequest(account, profile));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getBody()).containsOnlyKeys("profileId", "updatedAt", "contentIds")
                .containsEntry("profileId", profile.toString())
                .containsEntry("updatedAt", "2026-09-07T10:00:00Z")
                .containsEntry("contentIds", List.of(first.toString(), second.toString()));
        verifyNoInteractions(statistics);
    }

    @Test
    void recommendationsWithoutSnapshotAreNotReady() {
        var recommendations = mock(ProfileRecommendations.class);
        var account = new UUID(0, 1);
        var profile = new UUID(0, 2);
        when(recommendations.forProfile(account, profile, 20)).thenReturn(Optional.empty());
        var controller = new InternalQueryController(mock(PlatformStatistics.class), recommendations,
                new Problems(JsonMapper.builder().build()), Clock.systemUTC());
        var response = controller.recommendations(recommendationRequest(account, profile));
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("code", "ANALYTICS_NOT_READY");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    private static MockHttpServletRequest recommendationRequest(UUID account, UUID profile) {
        var request = new MockHttpServletRequest("POST", "/internal/v1/recommendations/query");
        request.setContentType("application/json");
        request.setContent(("{\"accountId\":\"" + account + "\",\"profileId\":\"" + profile + "\",\"limit\":20}")
                .getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void anAbortedUploadIsTheCallersFailureNotAServerError() {
        var statistics = mock(PlatformStatistics.class);
        var recommendations = mock(ProfileRecommendations.class);
        var controller = new InternalQueryController(statistics, recommendations, new Problems(JsonMapper.builder().build()),
                Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC));
        var request = new MockHttpServletRequest("POST", "/internal/v1/statistics/query") {
            @Override
            public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() throws IOException { throw new IOException("canary-connection-reset"); }
                    @Override public boolean isFinished() { return false; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {}
                };
            }
        };
        request.setContentType("application/json");

        var response = controller.statistics(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("code", "INVALID_REQUEST");
        assertThat(response.getBody().toString()).doesNotContain("canary");
        verifyNoInteractions(statistics, recommendations);
    }
}
