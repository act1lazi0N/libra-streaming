package com.libra.streaming.core.catalog;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.identity.*;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static com.libra.streaming.core.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Production filters and real reservations; the HTTP adapter is test-only until M09. */
@Testcontainers
@SpringBootTest(properties = "libra.media-control.upload-routes-enabled=false")
@Import(UploadAdminSecurityIntegrationTest.Probe.class)
class UploadAdminSecurityIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("upload_admin_auth").withUsername("upload_admin_auth").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogService catalog;
    @Autowired JwtEncoder encoder;
    @Autowired IdentityProperties properties;
    @Autowired ObjectMapper mapper;
    @Autowired Probe probe;
    MockMvc mvc;
    IdentityPrincipal admin;
    AdminView movie;
    Cookie csrfCookie;
    String csrfToken;

    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE identity_accounts, catalog_contents, outbox_events CASCADE");
        probe.calls.set(0);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = actor("ADMIN");
        movie = catalog.create(admin, new Create(Kind.MOVIE, null, null,
                new Metadata("Auth fixture", "Description", List.of("drama"), 2026, "en",
                        List.of("Cast"), List.of("posters/sample"), Tier.FREE)));
        var csrf = mvc.perform(get("/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        csrfCookie = csrf.getCookie("LIBRA_CSRF");
        csrfToken = mapper.readTree(csrf.getContentAsString()).path("token").asString();
    }

    @Test void currentAdminWithRealCsrfCreatesOneIntentAndBinding() throws Exception {
        mvc.perform(request(admin, true)).andExpect(status().isOk());
        assertThat(probe.calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_upload_intents", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_media_bindings", Integer.class)).isEqualTo(1);
        assertThat(catalog.get(admin, movie.id()).version()).isEqualTo(movie.version() + 1);
    }

    @Test void missingOrInvalidCsrfNeverReservesOrDispatches() throws Exception {
        mvc.perform(request(admin, false)).andExpect(status().isForbidden());
        mvc.perform(request(admin, false).cookie(csrfCookie).header("X-CSRF-TOKEN", "invalid-canary"))
                .andExpect(status().isForbidden());
        unchanged();
    }

    @Test void viewerAndForgedIdentityCannotReachReservation() throws Exception {
        mvc.perform(request(actor("USER"), true).header("X-Role", "ADMIN").header("X-User-ID", admin.accountId()))
                .andExpect(status().isForbidden());
        mvc.perform(request(null, true).header("X-Role", "ADMIN").header("X-User-ID", admin.accountId()))
                .andExpect(status().isUnauthorized());
        unchanged();
    }

    @Test void revokedExpiredSuspendedAndDemotedAdminAreCheckedAgainstCurrentDatabase() throws Exception {
        // Keep the same cryptographically valid access token across each database state change.
        String token = access(admin);
        jdbc.update("UPDATE identity_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", admin.sessionId());
        mvc.perform(request(null, true).cookie(new Cookie("LIBRA_ACCESS", token))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE identity_sessions SET revoked_at = NULL, created_at = CURRENT_TIMESTAMP - INTERVAL '1 hour', expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", admin.sessionId());
        mvc.perform(request(null, true).cookie(new Cookie("LIBRA_ACCESS", token))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE identity_sessions SET expires_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE id = ?", admin.sessionId());
        jdbc.update("UPDATE identity_accounts SET status = 'SUSPENDED' WHERE id = ?", admin.accountId());
        mvc.perform(request(null, true).cookie(new Cookie("LIBRA_ACCESS", token))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE identity_accounts SET status = 'ACTIVE', role = 'USER' WHERE id = ?", admin.accountId());
        mvc.perform(request(null, true).cookie(new Cookie("LIBRA_ACCESS", token))).andExpect(status().isForbidden());
        unchanged();
    }

    private void unchanged() {
        assertThat(probe.calls.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_upload_intents", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_media_bindings", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT candidate_binding FROM catalog_contents WHERE id = ?", UUID.class, movie.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT version FROM catalog_contents WHERE id = ?", Long.class, movie.id())).isEqualTo(movie.version());
    }
    private MockHttpServletRequestBuilder request(IdentityPrincipal actor, boolean csrf) {
        var request = post("/v1/admin/catalog/" + movie.id() + "/uploads");
        if (actor != null) { request.cookie(new Cookie("LIBRA_ACCESS", access(actor))); }
        if (csrf) { request.cookie(csrfCookie).header("X-CSRF-TOKEN", csrfToken); }
        return request;
    }
    private IdentityPrincipal actor(String role) {
        UUID id = UUID.randomUUID(), session = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_accounts(id, email, display_name, password_hash, role) VALUES (?, ?, 'Fixture', 'inert-fixture', ?)", id, id + "@example.test", role);
        jdbc.update("INSERT INTO identity_sessions(id, account_id, created_at, expires_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour')", session, id);
        return new IdentityPrincipal(id, session, role, false);
    }
    private String access(IdentityPrincipal actor) {
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(properties.audience()))
                .subject(actor.accountId().toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .id(UUID.randomUUID().toString()).claim("sid", actor.sessionId().toString()).claim("purpose", "access").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    @RestController static class Probe {
        final AtomicInteger calls = new AtomicInteger();
        @Autowired UploadIntentService uploads;
        @PostMapping("/v1/admin/catalog/{contentId}/uploads")
        UploadIntentService.Reservation reserve(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID contentId) {
            calls.incrementAndGet();
            return uploads.reserve(actor, contentId, UUID.randomUUID(), 1, 1024, "a".repeat(64));
        }
    }
}
