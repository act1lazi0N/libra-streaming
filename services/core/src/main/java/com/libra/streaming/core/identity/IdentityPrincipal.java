package com.libra.streaming.core.identity;

import java.util.UUID;

public record IdentityPrincipal(UUID accountId, UUID sessionId, String role, boolean emailVerified) {
}
