package com.libra.streaming.core.identity;

import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Account lock is acquired before feature locks, consistently with session revocation. */
@Component
public class IdentityAccess {
    private final IdentityStore accounts;
    private final Clock clock;

    public IdentityAccess(IdentityStore accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IdentityPrincipal lockCurrent(IdentityPrincipal principal, boolean administrator) {
        if (principal == null) { throw IdentityException.unauthenticated(); }
        accounts.lock(principal.accountId());
        var current = accounts.principal(principal.accountId(), principal.sessionId(), clock.instant())
                .orElseThrow(IdentityException::unauthenticated);
        if (administrator && !current.role().equals("ADMIN")) {
            throw new IdentityException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return current;
    }
}
