package com.libra.streaming.core.community;

import com.libra.streaming.core.api.CorrelationIdFilter;
import com.libra.streaming.core.identity.IdentityPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.libra.streaming.core.community.CommunityModels.*;

@RestController
@RequestMapping("/v1")
public class CommunityController {
    private final WatchlistService watchlist;
    private final ReviewService reviews;
    public CommunityController(WatchlistService watchlist, ReviewService reviews) {
        this.watchlist = watchlist; this.reviews = reviews;
    }
    @GetMapping("/profiles/{profileId}/watchlist")
    List<WatchlistService.Entry> watchlist(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID profileId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return watchlist.list(actor, profileId, limit, offset);
    }
    @PutMapping("/profiles/{profileId}/watchlist/{contentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void add(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID profileId, @PathVariable UUID contentId) {
        watchlist.add(actor, profileId, contentId);
    }
    @DeleteMapping("/profiles/{profileId}/watchlist/{contentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID profileId, @PathVariable UUID contentId) {
        watchlist.remove(actor, profileId, contentId);
    }
    @GetMapping("/catalog/{contentId}/reviews")
    ReviewPage publicReviews(@PathVariable UUID contentId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return reviews.list(contentId, limit, offset);
    }
    @GetMapping("/me/reviews/{contentId}")
    OwnReview mine(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID contentId) {
        return reviews.mine(actor, contentId);
    }
    @PutMapping("/me/reviews/{contentId}")
    OwnReview write(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID contentId,
            @Valid @RequestBody WriteReview request) { return reviews.write(actor, contentId, request); }
    @DeleteMapping("/me/reviews/{contentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID contentId,
            @RequestParam @Min(1) long expectedVersion) { reviews.delete(actor, contentId, expectedVersion); }
    @PostMapping("/reviews/{reviewId}/reports")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void report(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID reviewId,
            @Valid @RequestBody Report request) { reviews.report(actor, reviewId, request); }
    @GetMapping("/admin/reviews")
    List<AdminReview> adminReviews(@AuthenticationPrincipal IdentityPrincipal actor,
            @RequestParam(defaultValue = "true") boolean reportedOnly,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return reviews.adminList(actor, reportedOnly, limit, offset);
    }
    @PutMapping("/admin/reviews/{reviewId}/moderation")
    OwnReview moderate(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID reviewId,
            @Valid @RequestBody Moderate request, @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        return reviews.moderate(actor, reviewId, request, correlationId);
    }
    @GetMapping("/admin/reviews/{reviewId}/reports")
    List<ReportView> reports(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID reviewId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return reviews.reports(actor, reviewId, limit, offset);
    }
    @GetMapping("/admin/reviews/{reviewId}/audit")
    List<AuditView> audit(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID reviewId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return reviews.audit(actor, reviewId, limit, offset);
    }
}
