package com.libra.streaming.core.profiles.application;

import com.libra.streaming.core.events.application.EventPort;
import com.libra.streaming.core.events.domain.CoreEventTopic;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Profile limits, version checks and outbox event intent, within the caller's transaction. */
public final class ProfileUseCase {
    private final ProfileStore store;
    private final EventPort events;

    public ProfileUseCase(ProfileStore store, EventPort events) {
        this.store = store;
        this.events = events;
    }

    public void initialize(UUID accountId, String name) {
        insert(accountId, name, true, UUID.randomUUID());
    }

    public List<ProfileOperations.ProfileView> list(UUID accountId) {
        return store.owned(accountId);
    }

    public ProfileOperations.ProfileView get(UUID accountId, UUID id) {
        return requireOwned(accountId, id);
    }

    public ProfileOperations.ProfileView create(UUID accountId, String name, UUID correlationId) {
        if (store.owned(accountId).size() >= 5) { throw new Failure("PROFILE_LIMIT_REACHED"); }
        return insert(accountId, name, false, correlationId);
    }

    public ProfileOperations.ProfileView rename(UUID accountId, UUID id, String name,
            long version, UUID correlationId) {
        var existing = requireOwned(accountId, id);
        checkVersion(existing, version);
        store.rename(id, validName(name));
        events.append(CoreEventTopic.PROFILES, "ProfileUpdated", id, version + 1, correlationId,
                Map.of("accountId", accountId, "profileId", id));
        return requireOwned(accountId, id);
    }

    public void delete(UUID accountId, UUID id, long version, UUID correlationId) {
        var existing = requireOwned(accountId, id);
        checkVersion(existing, version);
        var profiles = store.owned(accountId);
        if (profiles.size() == 1) { throw new Failure("LAST_PROFILE"); }
        store.delete(id);
        if (existing.isDefault()) {
            UUID successor = profiles.stream().filter(profile -> !profile.id().equals(id)).findFirst().orElseThrow().id();
            store.promote(successor);
            var changed = requireOwned(accountId, successor);
            events.append(CoreEventTopic.PROFILES, "ProfileUpdated", successor, changed.version(), correlationId,
                    Map.of("accountId", accountId, "profileId", successor));
        }
        events.append(CoreEventTopic.PROFILES, "ProfileDeleted", id, version + 1, correlationId,
                Map.of("accountId", accountId, "profileId", id));
    }

    private ProfileOperations.ProfileView insert(UUID accountId, String name, boolean isDefault, UUID correlationId) {
        UUID id = UUID.randomUUID();
        store.insert(id, accountId, validName(name), isDefault);
        events.append(CoreEventTopic.PROFILES, "ProfileCreated", id, 1, correlationId,
                Map.of("accountId", accountId, "profileId", id));
        return requireOwned(accountId, id);
    }

    private ProfileOperations.ProfileView requireOwned(UUID accountId, UUID id) {
        var profile = store.findOwned(accountId, id);
        if (profile == null) { throw new Failure("NOT_FOUND"); }
        return profile;
    }

    private static void checkVersion(ProfileOperations.ProfileView profile, long version) {
        if (profile.version() != version) { throw new Failure("VERSION_CONFLICT"); }
    }

    private static String validName(String name) {
        if (name == null || name.isBlank() || name.length() > 80) { throw new Failure("INVALID_REQUEST"); }
        return name.strip();
    }

    public static final class Failure extends RuntimeException {
        public Failure(String code) { super(code); }
    }
}
