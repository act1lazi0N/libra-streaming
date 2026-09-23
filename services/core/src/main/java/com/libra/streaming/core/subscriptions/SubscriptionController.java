package com.libra.streaming.core.subscriptions;

import com.libra.streaming.core.identity.IdentityPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/subscriptions")
public class SubscriptionController {
    private final SubscriptionService subscriptions;

    public SubscriptionController(SubscriptionService subscriptions) { this.subscriptions = subscriptions; }

    @GetMapping
    SubscriptionService.StatusView status(@AuthenticationPrincipal IdentityPrincipal actor) {
        return subscriptions.status(actor);
    }

    @PostMapping("/simulate")
    SubscriptionService.PurchaseView activate(@AuthenticationPrincipal IdentityPrincipal actor,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Activation request) {
        return subscriptions.activate(actor, key, request.plan());
    }

    @GetMapping("/purchases")
    List<SubscriptionService.PurchaseView> purchases(@AuthenticationPrincipal IdentityPrincipal actor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return subscriptions.purchases(actor, limit, offset);
    }

    public record Activation(@NotBlank @Size(max = 32) String plan) {}
}
