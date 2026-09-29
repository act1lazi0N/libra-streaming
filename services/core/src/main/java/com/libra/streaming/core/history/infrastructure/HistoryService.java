package com.libra.streaming.core.history.infrastructure;

import com.libra.streaming.core.history.application.HistoryOperations;
import com.libra.streaming.core.history.application.HistoryUseCase;
import com.libra.streaming.core.identity.IdentityAccess;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Preserves account-lock and transaction boundaries for history operations. */
@Service
public class HistoryService implements HistoryOperations {
    private final IdentityAccess access;
    private final HistoryUseCase useCase;

    public HistoryService(IdentityAccess access, HistoryUseCase useCase) {
        this.access = access;
        this.useCase = useCase;
    }

    /** Caller holds the account lock, so admission/clear/profile deletion have one server order. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long opened(UUID profileId, UUID contentId, UUID sessionId, long durationMs) {
        return useCase.opened(profileId, contentId, sessionId, durationMs);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void accepted(UUID sessionId, long positionMs, long durationMs, Instant now) {
        useCase.accepted(sessionId, positionMs, durationMs, now);
    }

    @Override
    @Transactional
    public List<HistoryView> list(IdentityPrincipal actor, UUID profileId, boolean continuing, int limit, int offset) {
        IdentityPrincipal current = access.lockCurrent(actor, false);
        return useCase.list(current.accountId(), profileId, continuing, limit, offset);
    }

    @Override
    @Transactional
    public void clear(IdentityPrincipal actor, UUID profileId, UUID contentId) {
        IdentityPrincipal current = access.lockCurrent(actor, false);
        useCase.clear(current.accountId(), profileId, contentId);
    }
}
