package com.libra.streaming.core.community;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CommunityModels {
    private CommunityModels() {}
    public enum Visibility { HIDDEN, VISIBLE }
    public record WriteReview(@NotNull @Min(0) Long expectedVersion,
            @NotNull @Min(1) @Max(5) Integer stars, @Size(max = 2000) String text) {}
    public record Report(@NotBlank @Size(max = 500) String reason) {}
    public record Moderate(@NotNull @Min(1) Long expectedVersion, @NotNull Visibility visibility,
            @NotBlank @Size(max = 500) String reason) {}
    public record OwnReview(UUID id, UUID contentId, Integer stars, String text, boolean hidden,
            boolean deleted, long version, Instant updatedAt) {}
    public record PublicReview(UUID id, String displayName, int stars, String text, Instant updatedAt) {}
    public record ReviewPage(long count, BigDecimal averageStars, List<PublicReview> items) {}
    public record AdminReview(OwnReview review, long reportCount) {}
    public record ReportView(UUID id, String reason, long reviewVersion, Instant createdAt) {}
    public record AuditView(UUID id, UUID administratorId, Visibility action, String reason,
            boolean previousHidden, long reviewVersion, UUID correlationId, Instant createdAt) {}
}
