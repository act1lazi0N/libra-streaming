package com.libra.streaming.core.identity;

import java.util.UUID;

record IdentityAccount(UUID id, String email, String displayName, String passwordHash,
        String role, String status, boolean emailVerified) {
    @Override
    public String toString() { return "IdentityAccount[redacted]"; }
}
