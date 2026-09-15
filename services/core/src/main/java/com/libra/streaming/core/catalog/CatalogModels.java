package com.libra.streaming.core.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CatalogModels {
    private CatalogModels() {}

    public enum Kind { MOVIE, SERIES, SEASON, EPISODE;
        public boolean playable() { return this == MOVIE || this == EPISODE; }
    }
    public enum Tier { FREE, PREMIUM }
    public enum MediaState { PROCESSING, READY, FAILED }

    /** Image references are opaque relative identifiers, never fetched by Core. Text is plain text. */
    public record Metadata(@NotBlank @Size(max = 200) String title,
            @NotNull @Size(max = 5000) String description,
            @NotNull @Size(max = 20) List<@NotBlank @Pattern(regexp = "[a-z0-9-]{1,40}") String> genres,
            @NotNull @Min(1888) @Max(2200) Integer releaseYear,
            @NotBlank @Pattern(regexp = "[a-z]{2,3}(-[A-Z]{2})?") String language,
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 120) String> credits,
            @NotNull @Size(max = 10) List<@NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_/-]{0,199}") String> imageReferences,
            @NotNull Tier accessTier) {}
    public record Create(@NotNull Kind kind, UUID parentId, @Min(1) @Max(10000) Integer ordinal,
            @NotNull @Valid Metadata metadata) {}
    public record Edit(@Min(1) long expectedVersion, @NotNull @Valid Metadata metadata) {}
    public record Version(@Min(1) long expectedVersion) {}
    public record Bind(@Min(1) long expectedVersion, @NotNull UUID assetId, @Min(1) long assetVersion) {}
    public record Binding(UUID id, UUID contentId, UUID assetId, long assetVersion,
            long projectionVersion, String state, Integer durationSeconds) {}
    public record Revision(long revision, Metadata metadata, Instant createdAt) {}
    public record AdminView(UUID id, Kind kind, UUID parentId, Integer ordinal, long version,
            Revision draft, Revision published, Binding candidate, Binding active) {}
    public record PublicView(UUID id, Kind kind, UUID parentId, Integer ordinal, long revision,
            Metadata metadata, boolean mediaReady, Integer durationSeconds, Instant publishedAt) {}
}
