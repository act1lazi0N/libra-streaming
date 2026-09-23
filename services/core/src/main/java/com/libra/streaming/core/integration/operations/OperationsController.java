package com.libra.streaming.core.integration.operations;

import com.libra.streaming.core.api.CorrelationIdFilter;
import com.libra.streaming.core.identity.IdentityPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.libra.streaming.core.integration.operations.OperationsService.*;

@RestController
@RequestMapping("/v1/admin/operations")
public class OperationsController {
    private final OperationsService operations;
    public OperationsController(OperationsService operations) { this.operations = operations; }
    @GetMapping("/outbox")
    List<Work> outbox(@AuthenticationPrincipal IdentityPrincipal actor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) { return operations.work(actor, false, limit, offset); }
    @GetMapping("/media-dead-letters")
    List<Work> deadLetters(@AuthenticationPrincipal IdentityPrincipal actor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) { return operations.work(actor, true, limit, offset); }
    @GetMapping("/audit")
    List<Audit> audit(@AuthenticationPrincipal IdentityPrincipal actor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) { return operations.audit(actor, limit, offset); }
    @GetMapping("/summary")
    List<QueueCount> summary(@AuthenticationPrincipal IdentityPrincipal actor) { return operations.summary(actor); }
    @PostMapping("/outbox/{id}/retry")
    Audit retry(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id, @Valid @RequestBody Command request,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        return operations.command(actor, Action.OUTBOX_RETRY, id, request, correlationId);
    }
    @PostMapping("/media-dead-letters/{id}/redrive")
    Audit redrive(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id, @Valid @RequestBody Command request,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        return operations.command(actor, Action.MEDIA_DLT_REDRIVE, id, request, correlationId);
    }
}
