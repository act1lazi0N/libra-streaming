package com.libra.streaming.core.catalog;

import com.libra.streaming.core.api.CorrelationIdFilter;
import com.libra.streaming.core.identity.IdentityPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/admin")
@ConditionalOnProperty(name = "libra.media-control.upload-routes-enabled", havingValue = "true", matchIfMissing = true)
public class UploadController {
    private final UploadProvisioningService uploads;

    public UploadController(UploadProvisioningService uploads) { this.uploads = uploads; }

    @PostMapping("/catalog/{contentId}/uploads")
    ResponseEntity<UploadProvisioningService.UploadStatus> create(
            @AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID contentId,
            @Valid @RequestBody CreateUpload request,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        var result = uploads.create(actor, contentId, new UploadProvisioningService.Create(
                request.requestId(), request.expectedVersion(), request.byteLength(), request.sha256()), correlationId);
        return ResponseEntity.status(result.created() ? 201 : 200).cacheControl(CacheControl.noStore())
                .body(result.status());
    }

    @GetMapping("/uploads/{uploadId}")
    ResponseEntity<UploadProvisioningService.UploadStatus> read(
            @AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID uploadId,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(uploads.read(actor, uploadId, correlationId));
    }

    @PostMapping("/uploads/{uploadId}/upload-url")
    ResponseEntity<com.libra.streaming.core.integration.media.MediaControlModels.UploadUrl> issueUrl(
            @AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID uploadId,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(uploads.issueUrl(actor, uploadId, correlationId));
    }

    record CreateUpload(@NotNull UUID requestId, @Min(1) long expectedVersion,
            @Min(1) @Max(268435456) long byteLength,
            @NotNull @Pattern(regexp = "[a-f0-9]{64}") String sha256) {}
}
