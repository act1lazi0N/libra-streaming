package com.libra.streaming.core.subscriptions.infrastructure;

import com.libra.streaming.core.identity.IdentityAccess;
import com.libra.streaming.core.identity.IdentityPrincipal;
import com.libra.streaming.core.subscriptions.application.SubscriptionOperations;
import com.libra.streaming.core.subscriptions.application.SubscriptionOperations.PurchaseView;
import com.libra.streaming.core.subscriptions.application.SubscriptionOperations.StatusView;
import com.libra.streaming.core.subscriptions.application.SubscriptionStore;
import com.libra.streaming.core.subscriptions.application.SubscriptionUseCase;
import com.libra.streaming.core.transaction.application.TransactionPort;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Transaction and identity adapter for the framework-free subscription use case. */
@Service
public class SubscriptionService implements SubscriptionOperations {
    private final IdentityAccess access;
    private final SubscriptionUseCase useCase;
    private final TransactionPort transactions;

    public SubscriptionService(IdentityAccess access, SubscriptionUseCase useCase, TransactionPort transactions) {
        this.access = access;
        this.useCase = useCase;
        this.transactions = transactions;
    }

    @Override
    public StatusView status(IdentityPrincipal actor) {
        return transactions.required(() -> {
            IdentityPrincipal current = access.lockCurrent(actor, false);
            var status = useCase.status(current.accountId());
            return new StatusView(status.status(), status.expiresAt(), status.simulated());
        });
    }

    @Override
    public PurchaseView activate(IdentityPrincipal actor, UUID key, String plan) {
        return transactions.required(() -> {
            // Lock the account before reading or writing subscriptions, including idempotent replays.
            IdentityPrincipal current = access.lockCurrent(actor, false);
            return view(useCase.activate(current.accountId(), current.emailVerified(), key, plan));
        });
    }

    @Override
    public List<PurchaseView> purchases(IdentityPrincipal actor, int limit, int offset) {
        return transactions.required(() -> {
            IdentityPrincipal current = access.lockCurrent(actor, false);
            return useCase.purchases(current.accountId(), limit, offset).stream()
                    .map(SubscriptionService::view).toList();
        });
    }

    private static PurchaseView view(SubscriptionStore.Purchase purchase) {
        return new PurchaseView(purchase.id(), purchase.plan(), purchase.simulated(),
                purchase.purchasedAt(), purchase.termStart(), purchase.expiresAt());
    }

}
