package com.libra.streaming.recommendation.preferences.infrastructure;

import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.infrastructure.RecommendationFixtures;
import com.libra.streaming.recommendation.internalapi.infrastructure.InternalApiFixture;
import com.libra.streaming.recommendation.preferences.application.ProfilePreferences;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "libra.analytics.consumers.enabled=false",
        "libra.analytics.freshness.checkpoints.enabled=false",
        "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"
})
@DirtiesContext
class PreferenceRollupIntegrationTest extends InternalApiFixture {
    @Autowired EventIntake intake;
    @Autowired ProfilePreferences preferences;
    private RecommendationFixtures events;
    private final UUID account = UUID.randomUUID();
    private final UUID profile = UUID.randomUUID();
    private final Instant at = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC).minusDays(2)
            .atTime(12, 0).toInstant(ZoneOffset.UTC);

    @BeforeEach
    void setup() {
        clear();
        events = new RecommendationFixtures(intake);
    }

    @AfterEach
    void clear() {
        jdbc.execute("TRUNCATE profile_watch_days, statistics_account_days, playback_facts, playback_sessions, "
                + "processed_events, catalog_nodes, profiles");
    }

    @Test
    void acceptedIncrementsBucketByProfileContentAndUtcDayWithoutReplayOrZeroRows() {
        UUID movie = movie("drama", true);
        UUID other = UUID.randomUUID();
        events.profile(account, profile, false);
        events.profile(account, other, false);
        UUID session = UUID.randomUUID();
        var first = events.playback(account, profile, movie, session, 1, 10000, 10000, 10000, at);
        events.accept(first);
        events.accept(events.playback(account, profile, movie, session, 2, 20000, 30000, 30000, at.plusSeconds(10)));
        assertThat(intake.accept(first.topic(), first.key(), first.json())).isEqualTo(EventIntake.Outcome.DUPLICATE);
        events.watch(account, profile, movie, 7000, 7000, at.plusSeconds(86400));
        events.watch(account, other, movie, 5000, 5000, at);
        events.watch(account, profile, UUID.randomUUID(), 0, 0, at);
        assertThat(rows()).hasSize(3);
        assertThat(watched(profile, movie, LocalDate.ofInstant(at, ZoneOffset.UTC))).isEqualTo(30000);
        assertThat(watched(profile, movie, LocalDate.ofInstant(at, ZoneOffset.UTC).plusDays(1))).isEqualTo(7000);
        assertThat(watched(other, movie, LocalDate.ofInstant(at, ZoneOffset.UTC))).isEqualTo(5000);
        assertThat(preferences.interests(account, profile).weights()).containsEntry("drama", 37000.0);
        assertThat(preferences.interests(UUID.randomUUID(), profile).isEmpty()).isTrue();
    }

    @Test
    void tombstoneErasesRollupsAndLaterFactsLoseTheirProfileLink() {
        UUID movie = movie("drama", true);
        events.profile(account, profile, false);
        events.watch(account, profile, movie, 10000, 10000, at);
        events.profile(account, profile, true);
        events.watch(account, profile, movie, 10000, 20000, at.plusSeconds(20));
        assertThat(rows()).isEmpty();
        assertThat(jdbc.queryForList("SELECT profile_id FROM playback_facts", UUID.class)).containsOnlyNulls();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts", Integer.class)).isEqualTo(2);
        assertThat(preferences.interests(account, profile).isEmpty()).isTrue();
    }

    @Test
    void episodeResolvesSeasonThenSeriesGenresAndUnattributableTitlesContributeNothing() {
        UUID series = events.title("SERIES", null, "comedy", at, null, true);
        UUID season = events.title("SEASON", series, "drama", at, null, true);
        UUID episode = events.title("EPISODE", season, "drama", at, "READY", true);
        UUID orphan = events.title("EPISODE", UUID.randomUUID(), "horror", at, "READY", true);
        events.watch(account, profile, episode, 12000, 12000, at);
        events.watch(account, profile, orphan, 30000, 30000, at);
        events.watch(account, profile, movie("horror", false), 30000, 30000, at);
        assertThat(preferences.interests(account, profile).weights()).containsExactly(Map.entry("comedy", 12000.0));
    }

    @Test
    void thirtyDayWindowIncludesDayTwentyNineButExcludesDayThirty() {
        LocalDate today = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC);
        UUID included = movie("drama", true);
        UUID excluded = movie("comedy", true);
        for (int age : List.of(29, 30)) {
            jdbc.update("INSERT INTO profile_watch_days VALUES (?, ?, ?, ?, ?)", profile,
                    age == 29 ? included : excluded, java.sql.Date.valueOf(today.minusDays(age)), account, 10000);
        }
        assertThat(preferences.interests(account, profile).weights()).containsExactly(Map.entry("drama", 10000.0));
    }

    @Test
    void v7BackfillEqualsLiveRowsAndSkipsNullProfileFacts() throws Exception {
        UUID movie = movie("drama", true);
        events.watch(account, profile, movie, 10000, 10000, at);
        events.watch(account, profile, movie, 20000, 20000, at.plusSeconds(10));
        events.watch(account, profile, movie, 5000, 5000, at.plusSeconds(86400));
        UUID other = UUID.randomUUID();
        events.watch(account, other, movie, 7000, 7000, at);
        events.watch(account, other, UUID.randomUUID(), 0, 0, at);
        UUID deleted = UUID.randomUUID();
        events.watch(account, deleted, movie, 30000, 30000, at);
        events.profile(account, deleted, true);
        events.watch(account, deleted, movie, 10000, 10000, at.plusSeconds(20));
        var live = rows();
        assertThat(live).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts WHERE profile_id IS NULL", Integer.class))
                .isEqualTo(2);
        String migration;
        try (var stream = new ClassPathResource("db/migration/V7__create_profile_watch_days.sql").getInputStream()) {
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = migration.indexOf("INSERT INTO profile_watch_days");
        assertThat(start).isGreaterThanOrEqualTo(0);
        String insert = migration.substring(start, migration.indexOf(';', start) + 1);
        jdbc.execute("TRUNCATE profile_watch_days");
        jdbc.execute(insert);
        assertThat(rows()).isEqualTo(live);
    }

    private UUID movie(String genre, boolean published) {
        return events.title("MOVIE", null, genre, at, "READY", published);
    }

    private long watched(UUID ownerProfile, UUID content, LocalDate day) {
        return jdbc.queryForObject("SELECT accepted_watched_ms FROM profile_watch_days "
                + "WHERE profile_id = ? AND content_id = ? AND day = ?", Long.class,
                ownerProfile, content, java.sql.Date.valueOf(day));
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT * FROM profile_watch_days ORDER BY profile_id, content_id, day");
    }
}
