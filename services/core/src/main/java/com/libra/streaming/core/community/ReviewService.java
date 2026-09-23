package com.libra.streaming.core.community;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.*;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.libra.streaming.core.community.CommunityModels.*;

@Service
public class ReviewService {
    private final IdentityAccess access;
    private final CommunityCatalog catalog;
    private final ReviewStore store;
    private final IdentityRateLimiter rateLimiter;
    private final Validator validator;
    private final Clock clock;
    public ReviewService(IdentityAccess access, CommunityCatalog catalog, ReviewStore store,
            IdentityRateLimiter rateLimiter, Validator validator, Clock clock) {
        this.access = access; this.catalog = catalog; this.store = store;
        this.rateLimiter = rateLimiter; this.validator = validator; this.clock = clock;
    }

    @Transactional
    public OwnReview write(IdentityPrincipal actor, UUID contentId, WriteReview request) {
        verified(access.lockCurrent(actor, false));
        validate(request);
        rateLimiter.check("review-write", actor.accountId().toString(), 30, Duration.ofMinutes(10));
        catalog.lockPublishedTarget(contentId);
        if (!store.qualified(actor.accountId(), contentId)) {
            throw new DomainException(HttpStatus.FORBIDDEN, "QUALIFIED_VIEW_REQUIRED");
        }
        var existing = store.owned(actor.accountId(), contentId);
        if (existing.isEmpty()) {
            version(0, request.expectedVersion());
            return store.insert(actor.accountId(), contentId, request, clock.instant());
        }
        version(existing.get().version(), request.expectedVersion());
        return store.edit(existing.get().id(), request, clock.instant());
    }

    @Transactional
    public OwnReview mine(IdentityPrincipal actor, UUID contentId) {
        access.lockCurrent(actor, false);
        return store.owned(actor.accountId(), contentId).orElseThrow(DomainException::missing);
    }

    @Transactional
    public void delete(IdentityPrincipal actor, UUID contentId, long expectedVersion) {
        access.lockCurrent(actor, false);
        var existing = store.owned(actor.accountId(), contentId).orElseThrow(DomainException::missing);
        version(existing.version(), expectedVersion);
        if (!existing.deleted()) { store.delete(existing.id(), clock.instant()); }
    }

    // Count, mean, items and visibility come from one PostgreSQL snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReviewPage list(UUID contentId, int limit, int offset) {
        CommunityCatalog.page(limit, offset);
        catalog.publishedTarget(contentId);
        return store.publicPage(contentId, limit, offset);
    }

    @Transactional
    public void report(IdentityPrincipal actor, UUID reviewId, Report request) {
        verified(access.lockCurrent(actor, false));
        validate(request);
        rateLimiter.check("review-report", actor.accountId().toString(), 20, Duration.ofMinutes(10));
        // Preserve lock order: account -> catalog -> review, matching author writes.
        catalog.lockPublishedTarget(store.get(reviewId).contentId());
        var review = store.lock(reviewId);
        if (review.hidden() || review.deleted()) { throw DomainException.missing(); }
        if (store.authoredBy(reviewId, actor.accountId())) { throw DomainException.invalid(); }
        store.report(reviewId, actor.accountId(), request.reason(), review.version(), clock.instant());
    }

    @Transactional
    public OwnReview moderate(IdentityPrincipal actor, UUID reviewId, Moderate request, UUID correlationId) {
        access.lockCurrent(actor, true);
        validate(request);
        if (correlationId == null) { throw DomainException.invalid(); }
        var existing = store.lock(reviewId);
        version(existing.version(), request.expectedVersion());
        // Restoration changes moderation only: it never resurrects author-deleted text.
        return store.moderate(existing, new ReviewStore.IdentityAudit(actor.accountId(), correlationId), request, clock.instant());
    }

    @Transactional
    public List<AdminReview> adminList(IdentityPrincipal actor, boolean reportedOnly, int limit, int offset) {
        access.lockCurrent(actor, true);
        CommunityCatalog.page(limit, offset);
        return store.adminPage(reportedOnly, limit, offset);
    }
    @Transactional
    public List<ReportView> reports(IdentityPrincipal actor, UUID id, int limit, int offset) {
        access.lockCurrent(actor, true);
        CommunityCatalog.page(limit, offset);
        store.get(id);
        return store.reports(id, limit, offset);
    }
    @Transactional
    public List<AuditView> audit(IdentityPrincipal actor, UUID id, int limit, int offset) {
        access.lockCurrent(actor, true);
        CommunityCatalog.page(limit, offset);
        store.get(id);
        return store.audit(id, limit, offset);
    }
    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) { throw DomainException.invalid(); }
    }
    private static void verified(IdentityPrincipal actor) {
        if (!actor.emailVerified()) { throw new DomainException(HttpStatus.FORBIDDEN, "EMAIL_VERIFICATION_REQUIRED"); }
    }
    private static void version(long current, long expected) {
        if (current != expected) { throw DomainException.conflict("VERSION_CONFLICT"); }
    }
}
