package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.catalog.CatalogModels.Kind;
import com.libra.streaming.core.catalog.CatalogQueries;
import com.libra.streaming.core.identity.IdentityAccess;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.libra.streaming.core.integration.analytics.AnalyticsModels.*;

/** Short local transactions before/after HTTP; no account lock is held during downstream I/O. */
@Service
public class AnalyticsReadAccess {
    private final IdentityAccess access;
    private final JdbcTemplate jdbc;
    private final CatalogQueries catalog;
    public AnalyticsReadAccess(IdentityAccess access, JdbcTemplate jdbc, CatalogQueries catalog) {
        this.access = access; this.jdbc = jdbc; this.catalog = catalog;
    }
    @Transactional
    public void profile(IdentityPrincipal actor, UUID profileId) { requireProfile(actor, profileId); }
    @Transactional
    public void administrator(IdentityPrincipal actor) { access.lockCurrent(actor, true); }
    /**
     * Rechecks the live ADMIN role (revocation during the HTTP call must still deny disclosure), then names the
     * reported titles from Core's own catalog in the same transaction. Administrators see unpublished titles too:
     * the numbers describe what was watched, whatever its visibility today.
     */
    @Transactional
    public Map<UUID, TitleLabel> administratorLabels(IdentityPrincipal actor, List<UUID> contentIds) {
        access.lockCurrent(actor, true);
        if (contentIds.isEmpty()) { return Map.of(); }
        String placeholders = String.join(",", Collections.nCopies(contentIds.size(), "?"));
        Map<UUID, TitleLabel> labels = new HashMap<>();
        jdbc.query("""
                SELECT c.id, c.kind, c.published_revision IS NOT NULL AS published,
                    COALESCE(published.metadata->>'title', draft.metadata->>'title') AS title
                FROM catalog_contents c
                LEFT JOIN catalog_revisions published ON published.content_id = c.id
                    AND published.revision = c.published_revision
                LEFT JOIN catalog_revisions draft ON draft.content_id = c.id AND draft.revision = c.draft_revision
                WHERE c.id IN (%s)
                """.formatted(placeholders), row -> {
            labels.put(row.getObject("id", UUID.class), new TitleLabel(Kind.valueOf(row.getString("kind")),
                    row.getString("title"), row.getBoolean("published")));
        }, contentIds.toArray());
        return labels;
    }
    /**
     * For a SIMILAR list: the owned profile, and a seed Core shows publicly as a movie or series (404 otherwise, the
     * same answer as for a hidden title).
     */
    @Transactional
    public void profileAndSeed(IdentityPrincipal actor, UUID profileId, UUID seed) {
        requireProfile(actor, profileId);
        if (seed != null) {
            var view = catalog.get(seed);
            if (view.kind() != Kind.MOVIE && view.kind() != Kind.SERIES) { throw DomainException.missing(); }
        }
    }
    /**
     * Keeps, in Analytics' order, the titles Core can still show and recommend, up to {@code limit}, with the reason
     * Analytics gave. When Analytics failed, or none of its titles is visible, the fallback is honest: Core's newest
     * titles labelled NEWLY_PUBLISHED for the lists that hold new titles, and nothing for popular and similar, for which
     * Core has no data of its own.
     */
    @Transactional
    public RecommendationList projectList(IdentityPrincipal actor, UUID profileId, ListKind list, int limit,
            Result<RecommendationListResponse> response) {
        requireProfile(actor, profileId);
        String name = list.name().toLowerCase(java.util.Locale.ROOT);
        Failure reason = response.failure();
        if (reason == null) {
            var value = response.value();
            if (value.items().isEmpty() && (list == ListKind.SIMILAR || list == ListKind.POPULAR)) {
                return new RecommendationList(Source.ANALYTICS, null, name, value.algorithmVersion(), value.updatedAt(),
                        List.of());
            }
            var reasons = new HashMap<UUID, Reason>();
            value.items().forEach(item -> reasons.put(item.contentId(), item.reason()));
            var visible = catalog.recommendedCandidates(value.items().stream().map(RecommendedTitle::contentId).toList(),
                    limit);
            if (!visible.isEmpty()) {
                return new RecommendationList(Source.ANALYTICS, null, name, value.algorithmVersion(), value.updatedAt(),
                        visible.stream().map(view -> new ListItem(view, reasons.get(view.id()))).toList());
            }
            reason = Failure.NO_VISIBLE_CANDIDATES;
        }
        List<ListItem> fallback = list == ListKind.PERSONAL || list == ListKind.NEW
                ? catalog.newestRecommendable(limit).stream().map(view -> new ListItem(view, Reason.NEWLY_PUBLISHED)).toList()
                : List.of();
        return new RecommendationList(Source.FALLBACK, reason, name, null, null, fallback);
    }
    @Transactional
    public Recommendations project(IdentityPrincipal actor, UUID profileId, int limit, Result<CandidateResponse> response) {
        requireProfile(actor, profileId);
        Failure reason = response.failure();
        if (reason == null) {
            var items = catalog.recommendedCandidates(response.value().contentIds(), limit);
            if (!items.isEmpty()) { return new Recommendations(Source.ANALYTICS, null, response.value().updatedAt(), items); }
            reason = Failure.NO_VISIBLE_CANDIDATES;
        }
        return new Recommendations(Source.FALLBACK, reason, null, catalog.newestRecommendable(limit));
    }
    private void requireProfile(IdentityPrincipal actor, UUID profileId) {
        access.lockCurrent(actor, false);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM profiles WHERE id = ? AND account_id = ?)",
                Boolean.class, profileId, actor.accountId()))) { throw DomainException.missing(); }
    }
}
