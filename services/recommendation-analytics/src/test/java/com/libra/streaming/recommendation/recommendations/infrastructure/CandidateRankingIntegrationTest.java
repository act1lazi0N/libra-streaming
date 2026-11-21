package com.libra.streaming.recommendation.recommendations.infrastructure;

import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.infrastructure.RecommendationFixtures;
import com.libra.streaming.recommendation.internalapi.infrastructure.InternalApiFixture;
import com.libra.streaming.recommendation.recommendations.application.CandidateStore;
import com.libra.streaming.recommendation.recommendations.domain.TitleCandidate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "libra.analytics.consumers.enabled=false",
        "libra.analytics.freshness.checkpoints.enabled=false",
        "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"
})
@DirtiesContext
class CandidateRankingIntegrationTest extends InternalApiFixture {
    @Autowired EventIntake intake;
    @Autowired CandidateStore candidates;
    private RecommendationFixtures events;
    private final Instant at = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);

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
    void titlesRequirePublicationAndReadyBindingAlongThePlayableSeriesChain() {
        UUID movie = title("MOVIE", null, "READY", true);
        UUID series = title("SERIES", null, null, true);
        UUID season = title("SEASON", series, null, true);
        title("EPISODE", season, "READY", true);
        title("MOVIE", null, "READY", false);
        title("MOVIE", null, "PROCESSING", true);
        title("MOVIE", null, "FAILED", true);
        title("MOVIE", null, null, true);
        UUID hiddenSeasonSeries = title("SERIES", null, null, true);
        UUID hiddenSeason = title("SEASON", hiddenSeasonSeries, null, false);
        title("EPISODE", hiddenSeason, "READY", true);
        UUID unreadySeries = title("SERIES", null, null, true);
        UUID unreadySeason = title("SEASON", unreadySeries, null, true);
        title("EPISODE", unreadySeason, "PROCESSING", true);
        title("EPISODE", unreadySeason, "FAILED", true);
        title("EPISODE", unreadySeason, "READY", false);
        UUID hiddenSeries = title("SERIES", null, null, false);
        UUID itsSeason = title("SEASON", hiddenSeries, null, true);
        title("EPISODE", itsSeason, "READY", true);
        assertThat(candidates.titles()).containsExactlyInAnyOrder(
                new TitleCandidate(movie, TitleCandidate.Kind.MOVIE, java.util.List.of("drama"), at),
                new TitleCandidate(series, TitleCandidate.Kind.SERIES, java.util.List.of("drama"), at));
    }

    @Test
    void viewersAreDistinctAccountsAndEpisodeViewsResolveToSeriesWithinTheQualifyingWindow() {
        UUID movie = title("MOVIE", null, "READY", true);
        UUID series = title("SERIES", null, null, true);
        UUID season = title("SEASON", series, null, true);
        UUID first = title("EPISODE", season, "READY", true);
        UUID second = title("EPISODE", season, "READY", true);
        UUID account = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        Instant since = at.minusSeconds(60);
        events.watch(account, profile, movie, 30000, 30000, at);
        events.watch(account, profile, movie, 30000, 30000, at.plusSeconds(1));
        events.watch(UUID.randomUUID(), UUID.randomUUID(), movie, 30000, 30000, since);
        UUID oldAccount = UUID.randomUUID();
        UUID oldProfile = UUID.randomUUID();
        UUID oldSession = UUID.randomUUID();
        events.accept(events.playback(oldAccount, oldProfile, movie, oldSession, 1,
                30000, 30000, 30000, since.minusSeconds(1)));
        events.accept(events.playback(oldAccount, oldProfile, movie, oldSession, 2,
                5000, 35000, 35000, at));
        events.watch(UUID.randomUUID(), UUID.randomUUID(), movie, 29000, 29000, at);
        events.watch(account, profile, first, 30000, 30000, at);
        events.watch(account, profile, second, 30000, 30000, at);
        assertThat(candidates.viewersSince(since)).containsExactlyInAnyOrderEntriesOf(Map.of(movie, 2L, series, 1L));
        assertThat(candidates.viewersSince(at.plusSeconds(2))).isEmpty();
    }

    @Test
    void completionUsesNinetyFivePercentAndEveryPlayableEpisodeAndIsAccountScoped() {
        UUID account = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        UUID done = title("MOVIE", null, "READY", true);
        UUID unfinished = title("MOVIE", null, "READY", true);
        UUID series = title("SERIES", null, null, true);
        UUID season = title("SEASON", series, null, true);
        UUID first = title("EPISODE", season, "READY", true);
        UUID second = title("EPISODE", season, "READY", true);
        title("EPISODE", season, "FAILED", true); // Not part of the playable completion set.
        events.watch(account, profile, done, 10000, 114000, at); // Fixture duration is 120 s.
        events.watch(account, profile, unfinished, 10000, 112800, at);
        events.watch(account, profile, first, 10000, 114000, at);
        events.watch(account, profile, second, 10000, 112800, at);
        assertThat(candidates.completed(account, profile)).containsExactly(done);
        events.watch(account, profile, second, 10000, 114000, at.plusSeconds(1));
        assertThat(candidates.completed(account, profile)).containsExactlyInAnyOrder(done, series);
        assertThat(candidates.completed(UUID.randomUUID(), profile)).isEmpty();
        assertThat(candidates.completed(account, UUID.randomUUID())).isEmpty();
    }

    @Test
    void movieCompletionUsesLatestOccurredAtEvenWhenFactsArriveInReverseOrder() {
        UUID account = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        UUID reopened = title("MOVIE", null, "READY", true);
        UUID completed = title("MOVIE", null, "READY", true);
        // Duration is 120 s: 115200 is 96%, 36000 is 30%. Insert the later fact first.
        events.watch(account, profile, reopened, 10000, 36000, at.plusSeconds(1));
        events.watch(account, profile, reopened, 10000, 115200, at);
        events.watch(account, profile, completed, 10000, 115200, at.plusSeconds(1));
        events.watch(account, profile, completed, 10000, 36000, at);
        assertThat(candidates.completed(account, profile)).containsExactly(completed).doesNotContain(reopened);
    }

    @Test
    void seriesCompletionReopensWhenAnyPlayableEpisodesLatestPositionFallsBelowNinetyFivePercent() {
        UUID account = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        UUID series = title("SERIES", null, null, true);
        UUID season = title("SEASON", series, null, true);
        UUID first = title("EPISODE", season, "READY", true);
        UUID second = title("EPISODE", season, "READY", true);
        title("EPISODE", season, "FAILED", true);
        events.watch(account, profile, first, 10000, 115200, at.plusSeconds(1));
        events.watch(account, profile, first, 10000, 36000, at);
        events.watch(account, profile, second, 10000, 115200, at);
        assertThat(candidates.completed(account, profile)).containsExactly(series);
        events.watch(account, profile, second, 10000, 36000, at.plusSeconds(2));
        // An older completion arriving after the reopen must not close the episode again.
        events.watch(account, profile, second, 10000, 115200, at.plusSeconds(1));
        assertThat(candidates.completed(account, profile)).isEmpty();
        events.watch(account, profile, second, 10000, 115200, at.plusSeconds(3));
        assertThat(candidates.completed(account, profile)).containsExactly(series);
    }

    private UUID title(String kind, UUID parent, String state, boolean published) {
        return events.title(kind, parent, "drama", at, state, published);
    }
}
