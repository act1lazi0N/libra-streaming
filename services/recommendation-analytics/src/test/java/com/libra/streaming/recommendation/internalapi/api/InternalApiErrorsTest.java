package com.libra.streaming.recommendation.internalapi.api;

import com.libra.streaming.recommendation.freshness.application.CheckpointStore;
import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.statistics.application.PlatformStatistics;
import com.libra.streaming.recommendation.recommendations.application.ProfileRecommendations;
import com.libra.streaming.recommendation.statistics.application.StatisticsStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Mocked store: the failure mapping only. Real database outages are in {@code AnalyticsStartupIntegrationTest}. */
@ExtendWith(OutputCaptureExtension.class)
class InternalApiErrorsTest {
    static final String STATISTICS = "/internal/v1/statistics/query";
    static final String CANARY = "jdbc:postgresql://canary-host/canary_db?user=canary&password=canary-secret";

    @Test
    void storeFailuresBecomeSanitizedProblems(CapturedOutput output) throws Exception {
        for (var failure : Map.<String, Supplier<RuntimeException>>of(
                "ANALYTICS_UNAVAILABLE:503:a", () -> new DataAccessResourceFailureException(CANARY),
                "ANALYTICS_UNAVAILABLE:503:b", () -> new CannotCreateTransactionException(CANARY),
                "INTERNAL_ERROR:500:c", () -> new IllegalStateException(CANARY)).entrySet()) {
            String[] expected = failure.getKey().split(":");
            mvc(failure.getValue()).perform(post(STATISTICS).contentType("application/json").content(statisticsBody()))
                    .andExpect(status().is(Integer.parseInt(expected[1])))
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("code").value(expected[0]))
                    .andExpect(jsonPath("type").value("about:blank"))
                    .andExpect(jsonPath("correlationId").value(matchesPattern("[0-9a-f-]{36}")))
                    .andExpect(jsonPath("$.length()").value(6))
                    .andExpect(content().string(not(containsString("canary"))));
        }
        String correlation = UUID.randomUUID().toString();
        mvc(() -> new IllegalStateException(CANARY)).perform(post(STATISTICS).contentType("application/json").content(statisticsBody())
                .header("X-Correlation-ID", correlation))
                .andExpect(jsonPath("correlationId").value(correlation));
        assertThat(output.getAll()).contains("at " + InternalApiErrorsTest.class.getName())
                .contains("correlationId=" + correlation);
        assertThat(output.getAll()).doesNotContain("canary")
                .contains("Internal query failed: database unavailable (DataAccessResourceFailureException)")
                .contains("Internal query failed unexpectedly (java.lang.IllegalStateException)");
    }

    @Test
    void correlationIsEchoedOnlyWhenItIsACanonicalUuid() throws Exception {
        String id = UUID.randomUUID().toString();
        var mvc = mvc(null);
        mvc.perform(post(STATISTICS).contentType("application/json").content(statisticsBody()).header("X-Correlation-ID", id))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("ANALYTICS_NOT_READY"))
                .andExpect(jsonPath("detail").value("Analytics has not completed its first checkpoint."))
                .andExpect(jsonPath("correlationId").value(id));
        for (String forged : new String[] {"<script>canary</script>", id.toUpperCase(), id + "\r\nX-Canary: 1", "canary"}) {
            mvc.perform(post(STATISTICS).contentType("application/json").content(statisticsBody()).header("X-Correlation-ID", forged))
                    .andExpect(jsonPath("correlationId").value(matchesPattern("[0-9a-f-]{36}")))
                    .andExpect(jsonPath("correlationId").value(not(id)))
                    .andExpect(content().string(not(containsString("anary"))));
        }
    }

    @Test
    void unsupportedMethodIsAProblemToo() throws Exception {
        mvc(null).perform(get(STATISTICS)).andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("code").value("METHOD_NOT_ALLOWED"));
    }

    private static String statisticsBody() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return "{\"from\":\"" + today.minusDays(1) + "\",\"to\":\"" + today + "\"}";
    }

    private static MockMvc mvc(Supplier<RuntimeException> failure) {
        CheckpointStore store = new CheckpointStore() {
            @Override public Map<InputTopic, Instant> confirmed() {
                if (failure != null) { throw failure.get(); }
                return Map.of();
            }
            @Override public boolean advance(InputTopic topic, Instant at) { throw new AssertionError("A query advanced a checkpoint"); }
        };
        var problems = new Problems(new ObjectMapper());
        Clock clock = Clock.systemUTC();
        var freshness = new ProjectionFreshness(store, clock);
        return MockMvcBuilders.standaloneSetup(new InternalQueryController(
                new PlatformStatistics(mock(StatisticsStore.class), freshness), mock(ProfileRecommendations.class), problems, clock))
                .setControllerAdvice(new InternalApiErrors(problems)).build();
    }
}
