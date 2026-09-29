package com.libra.streaming.media.upload.api;

import com.libra.streaming.media.upload.application.UploadFailure;
import com.libra.streaming.media.upload.application.UploadGrantSigner;
import com.libra.streaming.media.upload.application.UploadPersistence;
import com.libra.streaming.media.upload.application.UploadWorkflow;
import com.libra.streaming.media.upload.domain.UploadDescriptor;
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
    private final UploadWorkflow workflow;

    public UploadControlController(UploadWorkflow workflow) {
        this.workflow = workflow;
    }

    @PutMapping("/{uploadId}")
    ResponseEntity<Status> ensure(@PathVariable UUID uploadId, @Valid @RequestBody EnsureUpload request) {
        if (request == null) { throw new UploadFailure("INVALID_REQUEST"); }
        var command = new UploadDescriptor(uploadId, request.requestId(), request.contentId(),
                request.bindingId(), request.assetId(), request.assetVersion(), request.byteLength(),
                request.sha256(), request.expiresAt());
        var created = workflow.ensure(command);
        return ResponseEntity.status(created.existing() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()).body(view(created.snapshot()));
    }

    @GetMapping("/{uploadId}")
    ResponseEntity<Status> read(@PathVariable UUID uploadId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view(workflow.read(uploadId)));
    }

    @PostMapping("/{uploadId}/upload-url")
    ResponseEntity<UploadGrantSigner.Grant> issueUrl(@PathVariable UUID uploadId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workflow.issueUrl(uploadId));
    }

    private static Status view(UploadPersistence.UploadSnapshot snapshot) {
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
