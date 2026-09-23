package com.libra.streaming.core.profiles;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.IdentityAccess;
import com.libra.streaming.core.identity.IdentityPrincipal;
import com.libra.streaming.core.integration.outbox.CoreEventTopic;
import com.libra.streaming.core.integration.outbox.DomainEvents;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
    private static final RowMapper<ProfileView> VIEW = (rs, row) -> new ProfileView(rs.getObject("id", UUID.class),
            rs.getString("name"), rs.getBoolean("is_default"), rs.getLong("version"));
    private final JdbcTemplate jdbc;
    private final IdentityAccess access;
    private final DomainEvents events;

    public ProfileService(JdbcTemplate jdbc, IdentityAccess access, DomainEvents events) {
        this.jdbc = jdbc;
        this.access = access;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(UUID accountId, String name) {
        insert(accountId, name, true, UUID.randomUUID());
    }

    @Transactional
    public List<ProfileView> list(IdentityPrincipal actor) {
        access.lockCurrent(actor, false);
        return owned(actor.accountId());
    }

    @Transactional
    public ProfileView get(IdentityPrincipal actor, UUID id) {
        access.lockCurrent(actor, false);
        return requireOwned(actor.accountId(), id);
    }

    @Transactional
    public ProfileView create(IdentityPrincipal actor, String name, UUID correlationId) {
        access.lockCurrent(actor, false);
        if (owned(actor.accountId()).size() >= 5) { throw DomainException.conflict("PROFILE_LIMIT_REACHED"); }
        return insert(actor.accountId(), name, false, correlationId);
    }

    @Transactional
    public ProfileView rename(IdentityPrincipal actor, UUID id, String name, long version, UUID correlationId) {
        access.lockCurrent(actor, false);
        ProfileView existing = requireOwned(actor.accountId(), id);
        checkVersion(existing, version);
        jdbc.update("UPDATE profiles SET name = ?, version = version + 1 WHERE id = ?", validName(name), id);
        events.append(CoreEventTopic.PROFILES, "ProfileUpdated", id, version + 1, correlationId,
                Map.of("accountId", actor.accountId(), "profileId", id));
        return requireOwned(actor.accountId(), id);
    }

    @Transactional
    public void delete(IdentityPrincipal actor, UUID id, long version, UUID correlationId) {
        access.lockCurrent(actor, false);
        ProfileView existing = requireOwned(actor.accountId(), id);
        checkVersion(existing, version);
        List<ProfileView> profiles = owned(actor.accountId());
        if (profiles.size() == 1) { throw DomainException.conflict("LAST_PROFILE"); }
        jdbc.update("DELETE FROM profiles WHERE id = ?", id);
        if (existing.isDefault()) {
            UUID successor = profiles.stream().filter(profile -> !profile.id().equals(id)).findFirst().orElseThrow().id();
            jdbc.update("UPDATE profiles SET is_default = TRUE, version = version + 1 WHERE id = ?", successor);
            ProfileView changed = requireOwned(actor.accountId(), successor);
            events.append(CoreEventTopic.PROFILES, "ProfileUpdated", successor, changed.version(), correlationId,
                    Map.of("accountId", actor.accountId(), "profileId", successor));
        }
        events.append(CoreEventTopic.PROFILES, "ProfileDeleted", id, version + 1, correlationId,
                Map.of("accountId", actor.accountId(), "profileId", id));
    }

    private ProfileView insert(UUID accountId, String name, boolean isDefault, UUID correlationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO profiles(id, account_id, name, is_default) VALUES (?, ?, ?, ?)",
                id, accountId, validName(name), isDefault);
        events.append(CoreEventTopic.PROFILES, "ProfileCreated", id, 1, correlationId,
                Map.of("accountId", accountId, "profileId", id));
        return requireOwned(accountId, id);
    }

    private List<ProfileView> owned(UUID accountId) {
        return jdbc.query("SELECT * FROM profiles WHERE account_id = ? ORDER BY created_at, id", VIEW, accountId);
    }

    private ProfileView requireOwned(UUID accountId, UUID id) {
        return jdbc.query("SELECT * FROM profiles WHERE account_id = ? AND id = ?", VIEW, accountId, id)
                .stream().findFirst().orElseThrow(DomainException::missing);
    }

    private static void checkVersion(ProfileView profile, long version) {
        if (profile.version() != version) { throw DomainException.conflict("VERSION_CONFLICT"); }
    }

    private static String validName(String name) {
        if (name == null || name.isBlank() || name.length() > 80) { throw DomainException.invalid(); }
        return name.strip();
    }

    public record ProfileView(UUID id, String name, boolean isDefault, long version) {}
}
