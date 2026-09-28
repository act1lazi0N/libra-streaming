package com.libra.streaming.media.control;

import com.libra.streaming.media.persistence.MediaPersistenceException;
import com.libra.streaming.media.persistence.MediaPersistenceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
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

    public UploadControlController(MediaPersistenceService uploads, CoreBindingClient core) {
        this.uploads = uploads;
        this.core = core;
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
