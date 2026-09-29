package com.libra.streaming.core.entitlement.infrastructure;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.entitlement.application.EntitlementOperations;
import com.libra.streaming.core.entitlement.application.EntitlementUseCase;
import com.libra.streaming.core.identity.IdentityException;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads one database snapshot per decision; retains the existing HTTP failure mapping. */
@Service
public class EntitlementService implements EntitlementOperations {
    private final EntitlementUseCase useCase;

    EntitlementService(EntitlementUseCase useCase) { this.useCase = useCase; }

    @Override
    @Transactional(readOnly = true)
    public EligibleContent requireEligible(IdentityPrincipal actor, UUID profileId, UUID contentId) {
        try {
            return useCase.requireEligible(actor, profileId, contentId);
        } catch (EntitlementUseCase.Failure failure) {
            throw switch (failure.kind()) {
                case UNAUTHENTICATED -> IdentityException.unauthenticated();
                case INVALID -> DomainException.invalid();
                case MISSING -> DomainException.missing();
                case CONFLICT -> DomainException.conflict(failure.getMessage());
                case FORBIDDEN -> new DomainException(HttpStatus.FORBIDDEN, failure.getMessage());
            };
        }
    }
}
