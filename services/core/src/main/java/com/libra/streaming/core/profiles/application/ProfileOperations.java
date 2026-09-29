package com.libra.streaming.core.profiles.application;

import com.libra.streaming.core.identity.IdentityPrincipal;
import java.util.List;
import java.util.UUID;

public interface ProfileOperations {
    void initialize(UUID accountId, String name);

    List<ProfileView> list(IdentityPrincipal actor);

    ProfileView get(IdentityPrincipal actor, UUID id);

    ProfileView create(IdentityPrincipal actor, String name, UUID correlationId);

    ProfileView rename(IdentityPrincipal actor, UUID id, String name, long version, UUID correlationId);

    void delete(IdentityPrincipal actor, UUID id, long version, UUID correlationId);

    record ProfileView(UUID id, String name, boolean isDefault, long version) {}
}
