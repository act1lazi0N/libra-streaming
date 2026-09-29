package com.libra.streaming.core.profiles.infrastructure;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.IdentityAccess;
import com.libra.streaming.core.identity.IdentityPrincipal;
import com.libra.streaming.core.profiles.application.ProfileOperations;
import com.libra.streaming.core.profiles.application.ProfileOperations.ProfileView;
import com.libra.streaming.core.profiles.application.ProfileUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Retains the account-lock and outbox transaction boundary. */
@Service
public class ProfileService implements ProfileOperations {
    private final IdentityAccess access;
    private final ProfileUseCase useCase;

    public ProfileService(IdentityAccess access, ProfileUseCase useCase) {
        this.access = access;
        this.useCase = useCase;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(UUID accountId, String name) {
        translate(() -> { useCase.initialize(accountId, name); return null; });
    }

    @Override
    @Transactional
    public List<ProfileView> list(IdentityPrincipal actor) {
        IdentityPrincipal current = access.lockCurrent(actor, false);
        return useCase.list(current.accountId());
    }

    @Override
    @Transactional
    public ProfileView get(IdentityPrincipal actor, UUID id) {
        IdentityPrincipal current = access.lockCurrent(actor, false);
        return translate(() -> useCase.get(current.accountId(), id));
    }

    @Override
    @Transactional
    public ProfileView create(IdentityPrincipal actor, String name, UUID correlationId) {
        IdentityPrincipal current = access.lockCurrent(actor, false);
        return translate(() -> useCase.create(current.accountId(), name, correlationId));
    }

    @Override
    @Transactional
    public ProfileView rename(IdentityPrincipal actor, UUID id, String name, long version, UUID correlationId) {
        IdentityPrincipal current = access.lockCurrent(actor, false);
        return translate(() -> useCase.rename(current.accountId(), id, name, version, correlationId));
    }

    @Override
    @Transactional
    public void delete(IdentityPrincipal actor, UUID id, long version, UUID correlationId) {
        IdentityPrincipal current = access.lockCurrent(actor, false);
        translate(() -> { useCase.delete(current.accountId(), id, version, correlationId); return null; });
    }

    private static <T> T translate(java.util.function.Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ProfileUseCase.Failure failure) {
            throw switch (failure.getMessage()) {
                case "NOT_FOUND" -> DomainException.missing();
                case "INVALID_REQUEST" -> DomainException.invalid();
                default -> DomainException.conflict(failure.getMessage());
            };
        }
    }
}
