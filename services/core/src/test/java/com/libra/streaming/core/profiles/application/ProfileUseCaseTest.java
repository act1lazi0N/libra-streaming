package com.libra.streaming.core.profiles.application;

import com.libra.streaming.core.events.application.EventPort;
import com.libra.streaming.core.events.domain.CoreEventTopic;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProfileUseCaseTest {
    @Test
    void lastProfileCannotBeDeletedOrEmitEvent() {
        UUID account = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        AtomicInteger deletes = new AtomicInteger();
        AtomicInteger events = new AtomicInteger();
        ProfileStore store = new ProfileStore() {
            private final ProfileOperations.ProfileView only = new ProfileOperations.ProfileView(profile, "Main", true, 1);
            @Override public List<ProfileOperations.ProfileView> owned(UUID ignored) { return List.of(only); }
            @Override public ProfileOperations.ProfileView findOwned(UUID ignored, UUID id) { return id.equals(profile) ? only : null; }
            @Override public void insert(UUID id, UUID accountId, String name, boolean isDefault) { throw new AssertionError(); }
            @Override public void rename(UUID id, String name) { throw new AssertionError(); }
            @Override public void delete(UUID id) { deletes.incrementAndGet(); }
            @Override public void promote(UUID id) { throw new AssertionError(); }
        };
        EventPort outbox = (CoreEventTopic topic, String type, UUID id, long version, UUID correlationId,
                Object payload) -> events.incrementAndGet();
        var useCase = new ProfileUseCase(store, outbox);

        assertThatThrownBy(() -> useCase.delete(account, profile, 1, UUID.randomUUID()))
                .isInstanceOf(ProfileUseCase.Failure.class).hasMessage("LAST_PROFILE");
        assertThat(deletes).hasValue(0);
        assertThat(events).hasValue(0);
    }
}
