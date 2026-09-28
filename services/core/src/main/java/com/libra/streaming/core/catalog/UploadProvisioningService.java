package com.libra.streaming.core.catalog;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.IdentityPrincipal;
import com.libra.streaming.core.integration.media.MediaControlClient;
import com.libra.streaming.core.integration.media.MediaControlModels;
import java.time.Instant;
import java.time.Duration;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** The remote exchange runs after the local reservation transaction returns. */
@Service
public class UploadProvisioningService {
    private final UploadIntentService intents;
    private final MediaControlClient media;
    private final Clock clock;

    public UploadProvisioningService(UploadIntentService intents, MediaControlClient media, Clock clock) {
        this.intents = intents;
        this.media = media;
        this.clock = clock;
    }

    public Created create(IdentityPrincipal actor, UUID contentId, Create request, UUID correlationId) {
        var reserved = intents.reserveDetailed(actor, contentId, request.requestId(), request.expectedVersion(),
                request.byteLength(), request.sha256());
        return new Created(provision(reserved.reservation(), correlationId), reserved.created());
    }

    public UploadStatus read(IdentityPrincipal actor, UUID uploadId, UUID correlationId) {
        var intent = intents.read(actor, uploadId);
        var result = media.read(uploadId, correlationId);
        if (result.failure() == MediaControlModels.Failure.NOT_FOUND) {
            return provision(intents.readCurrentCandidate(actor, uploadId), correlationId);
        }
        return status(intent, result);
    }

    public MediaControlModels.UploadUrl issueUrl(IdentityPrincipal actor, UUID uploadId, UUID correlationId) {
        var intent = intents.readCurrentCandidate(actor, uploadId);
        var current = read(actor, uploadId, correlationId);
        if (current.uploadState() != MediaControlModels.UploadState.OPEN
                || current.assetState() != MediaControlModels.AssetState.UPLOADING) {
            throw DomainException.conflict("UPLOAD_STATE_CONFLICT");
        }
        var result = media.issueUrl(uploadId, correlationId);
        if (result.failure() != null) {
            throw switch (result.failure()) {
                case CONFLICT, NOT_FOUND -> DomainException.conflict("UPLOAD_STATE_CONFLICT");
                case EXPIRED -> new DomainException(HttpStatus.GONE, "UPLOAD_EXPIRED");
                default -> new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE");
            };
        }
        var grant = result.value();
        var now = clock.instant();
        var headers = grant == null ? null : grant.requiredHeaders();
        String checksum = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(intent.sha256()));
        if (grant == null || !grant.expiresAt().isAfter(now)
                || grant.expiresAt().isAfter(intent.expiresAt())
                || grant.expiresAt().isAfter(now.plus(Duration.ofMinutes(15)))
                || headers == null || headers.size() != 2
                || !"video/mp4".equals(headers.get("Content-Type"))
                || !checksum.equals(headers.get("x-amz-checksum-sha256"))) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE");
        }
        return grant;
    }

    private UploadStatus provision(UploadIntentService.Reservation intent, UUID correlationId) {
        var command = new MediaControlModels.EnsureUpload(intent.requestId(), intent.contentId(),
                intent.bindingId(), intent.assetId(), intent.assetVersion(), intent.byteLength(),
                intent.sha256(), intent.expiresAt());
        return status(intent, media.ensure(intent.uploadId(), command, correlationId));
    }

    private static UploadStatus status(UploadIntentService.Reservation intent, MediaControlModels.Result result) {
        if (result.failure() != null) {
            throw switch (result.failure()) {
                case CONFLICT -> DomainException.conflict("UPLOAD_STATE_CONFLICT");
                case NOT_FOUND -> DomainException.conflict("UPLOAD_STATE_CONFLICT");
                case EXPIRED -> new DomainException(HttpStatus.GONE, "UPLOAD_EXPIRED");
                // A failed or ambiguous remote call never rolls back the committed intent.
                default -> new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE");
            };
        }
        var media = result.value();
        if (media == null || !intent.uploadId().equals(media.uploadId())
                || !intent.contentId().equals(media.contentId()) || !intent.bindingId().equals(media.bindingId())
                || !intent.assetId().equals(media.assetId()) || intent.assetVersion() != media.assetVersion()
                || !intent.expiresAt().equals(media.expiresAt())) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE");
        }
        return new UploadStatus(intent.uploadId(), intent.contentId(), intent.bindingId(), intent.assetId(),
                intent.assetVersion(), intent.catalogVersionAtReservation(), media.uploadState(),
                media.assetState(), media.jobId(), media.attemptCount(), media.failureCode(), intent.expiresAt());
    }

    public record Create(UUID requestId, long expectedVersion, long byteLength, String sha256) {}
    public record Created(UploadStatus status, boolean created) {}
    public record UploadStatus(UUID uploadId, UUID contentId, UUID bindingId, UUID assetId,
            long assetVersion, long catalogVersionAtReservation, MediaControlModels.UploadState uploadState,
            MediaControlModels.AssetState assetState, UUID jobId, int attemptCount,
            MediaControlModels.FailureCode failureCode, Instant expiresAt) {}
}
