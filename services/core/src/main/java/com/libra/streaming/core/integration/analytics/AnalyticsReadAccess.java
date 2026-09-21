package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.catalog.CatalogQueries;
import com.libra.streaming.core.identity.IdentityAccess;
import com.libra.streaming.core.identity.IdentityPrincipal;
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
