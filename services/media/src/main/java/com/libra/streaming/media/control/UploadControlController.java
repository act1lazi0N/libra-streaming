package com.libra.streaming.media.control;

import com.libra.streaming.media.persistence.MediaPersistenceException;
import com.libra.streaming.media.persistence.MediaPersistenceService;
import com.libra.streaming.media.storage.StagingUploadSigner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/uploads")
@ConditionalOnProperty(name = "libra.media.upload-control.enabled", havingValue = "true", matchIfMissing = true)
public class UploadControlController {
    private final MediaPersistenceService uploads;
    private final CoreBindingClient core;
    private final ObjectProvider<StagingUploadSigner> signer;
    private final Clock clock;

    public UploadControlController(MediaPersistenceService uploads, CoreBindingClient core,
            ObjectProvider<StagingUploadSigner> signer, Clock clock) {
        this.uploads = uploads;
        this.core = core;
        this.signer = signer;
        this.clock = clock;
    }

    @PutMapping("/{uploadId}")
    ResponseEntity<Status> ensure(@PathVariable UUID uploadId, @Valid @RequestBody EnsureUpload request) {
        if (request == null) { throw new MediaPersistenceException("INVALID_REQUEST"); }
        var command = new MediaPersistenceService.Ensure(uploadId, request.requestId(), request.contentId(),
                request.bindingId(), request.assetId(), request.assetVersion(), request.byteLength(),
                request.sha256(), request.expiresAt());
        // Exact candidate authority is checked before any local insert or idempotent result.
        core.requireCandidate(command);
        boolean existing = false;
        try {
            uploads.read(uploadId);
            existing = true;
        } catch (MediaPersistenceException exception) {
            if (!"NOT_FOUND".equals(exception.code())) { throw exception; }
        }
        return ResponseEntity.status(existing ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()).body(view(uploads.ensure(command)));
    }

    @GetMapping("/{uploadId}")
    ResponseEntity<Status> read(@PathVariable UUID uploadId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view(uploads.read(uploadId)));
    }

    @PostMapping("/{uploadId}/upload-url")
    ResponseEntity<StagingUploadSigner.Grant> issueUrl(@PathVariable UUID uploadId) {
        var source = uploads.grantSource(uploadId);
        var current = source.snapshot();
        core.requireCandidate(new MediaPersistenceService.Ensure(current.uploadId(), current.requestId(),
                current.contentId(), current.bindingId(), current.assetId(), current.assetVersion(),
                current.byteLength(), current.sha256(), current.expiresAt()));
        StagingUploadSigner available = signer.getIfAvailable();
        if (available == null) { throw new MediaPersistenceException("STORAGE_UNAVAILABLE"); }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(available.sign(uploadId, source.stagingKey(), current.byteLength(),
                            current.sha256(), current.expiresAt(), clock.instant()));
        } catch (IllegalArgumentException exception) {
            throw new MediaPersistenceException(current.expiresAt().isAfter(clock.instant())
                    ? "UPLOAD_STATE_CONFLICT" : "UPLOAD_EXPIRED");
        } catch (RuntimeException exception) {
            throw new MediaPersistenceException("STORAGE_UNAVAILABLE");
        }
    }

    private static Status view(MediaPersistenceService.Snapshot snapshot) {
        return new Status(snapshot.uploadId(), snapshot.contentId(), snapshot.bindingId(), snapshot.assetId(),
                snapshot.assetVersion(), snapshot.uploadState(), snapshot.assetState(), snapshot.jobId(),
                snapshot.attemptCount(), null, snapshot.expiresAt());
    }

    public record EnsureUpload(@NotNull UUID requestId, @NotNull UUID contentId, @NotNull UUID bindingId,
            @NotNull UUID assetId, @Min(1) long assetVersion, @Min(1) @Max(268435456) long byteLength,
            @NotNull @Pattern(regexp = "[a-f0-9]{64}") String sha256, @NotNull Instant expiresAt) {}
    public record Status(UUID uploadId, UUID contentId, UUID bindingId, UUID assetId, long assetVersion,
            String uploadState, String assetState, UUID jobId, int attemptCount,
            String failureCode, Instant expiresAt) {}
}
