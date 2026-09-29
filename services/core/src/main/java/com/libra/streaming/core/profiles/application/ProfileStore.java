package com.libra.streaming.core.profiles.application;

import java.util.List;
import java.util.UUID;

public interface ProfileStore {
    List<ProfileOperations.ProfileView> owned(UUID accountId);

    ProfileOperations.ProfileView findOwned(UUID accountId, UUID id);

    void insert(UUID id, UUID accountId, String name, boolean isDefault);

    void rename(UUID id, String name);

    void delete(UUID id);

    void promote(UUID id);
}
