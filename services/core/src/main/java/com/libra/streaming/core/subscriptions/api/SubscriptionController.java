package com.libra.streaming.core.subscriptions.api;

import com.libra.streaming.core.identity.IdentityPrincipal;
import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.subscriptions.application.SubscriptionOperations;
import com.libra.streaming.core.subscriptions.application.SubscriptionUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/subscriptions")
public class SubscriptionController {
    private final SubscriptionOperations subscriptions;

    public SubscriptionController(SubscriptionOperations subscriptions) { this.subscriptions = subscriptions; }

    @GetMapping
    SubscriptionOperations.StatusView status(@AuthenticationPrincipal IdentityPrincipal actor) {
        return subscriptions.status(actor);
    }

    @PostMapping("/simulate")
    SubscriptionOperations.PurchaseView activate(@AuthenticationPrincipal IdentityPrincipal actor,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Activation request) {
        try {
            return subscriptions.activate(actor, key, request.plan());
        } catch (SubscriptionUseCase.Failure failure) {
            throw problem(failure);
        }
    }

    @GetMapping("/purchases")
    List<SubscriptionOperations.PurchaseView> purchases(@AuthenticationPrincipal IdentityPrincipal actor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        try {
            return subscriptions.purchases(actor, limit, offset);
        } catch (SubscriptionUseCase.Failure failure) {
            throw problem(failure);
        }
    }

    private static DomainException problem(SubscriptionUseCase.Failure failure) {
        return switch (failure.code()) {
            case "EMAIL_VERIFICATION_REQUIRED" -> new DomainException(HttpStatus.FORBIDDEN, failure.code());
            case "IDEMPOTENCY_CONFLICT" -> DomainException.conflict(failure.code());
            default -> DomainException.invalid();
        };
    }

    public record Activation(@NotBlank @Size(max = 32) String plan) {}
}
