package com.libra.streaming.recommendation.internalapi.infrastructure;

import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * One application context on one real PostgreSQL for the internal API suites, with service authentication enabled
 * for {@link ServiceTokens#CORE}. The spy counts handler work; it does not replace behavior.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
abstract class InternalApiFixture {
    static final String STATISTICS = "/internal/v1/statistics/query";
    static final String RECOMMENDATIONS = "/internal/v1/recommendations/query";
    static final String STATISTICS_SCOPE = "statistics:read";
    static final String RECOMMENDATIONS_SCOPE = "recommendations:read";

    // Started once and shared by the subclasses, so they also share the cached context. Ryuk removes it.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_internal_api").withUsername("analytics_internal_api")
            .withPassword(UUID.randomUUID().toString());
    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("libra.analytics.core-service.enabled", () -> true);
        registry.add("libra.analytics.core-service.public-key", () -> ServiceTokens.publicKey(ServiceTokens.CORE));
        registry.add("libra.analytics.core-service.key-id", () -> ServiceTokens.KID);
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ProjectionFreshness freshness;
    MockMvc mvc;
    List<Map<String, Object>> before;

    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("UPDATE input_checkpoints SET confirmed_at = NULL");
        before = rows();
        Mockito.clearInvocations(freshness);
    }

    /** No request to the internal API, accepted or refused, may write: queries are read-only. */
    @AfterEach
    void requestsNeverWrite() {
        if (!expectsCheckpointWrites()) { assertThat(rows()).isEqualTo(before); }
    }

    boolean expectsCheckpointWrites() { return false; }

    List<Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT topic, confirmed_at FROM input_checkpoints ORDER BY topic");
    }

    static String bearer(String token) { return "Bearer " + token; }
}
