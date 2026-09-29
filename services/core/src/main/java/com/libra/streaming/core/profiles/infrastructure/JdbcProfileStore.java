package com.libra.streaming.core.profiles.infrastructure;

import com.libra.streaming.core.profiles.application.ProfileOperations.ProfileView;
import com.libra.streaming.core.profiles.application.ProfileStore;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProfileStore implements ProfileStore {
    private static final RowMapper<ProfileView> VIEW = (rs, row) -> new ProfileView(rs.getObject("id", UUID.class),
            rs.getString("name"), rs.getBoolean("is_default"), rs.getLong("version"));
    private final JdbcTemplate jdbc;

    public JdbcProfileStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<ProfileView> owned(UUID accountId) {
        return jdbc.query("SELECT * FROM profiles WHERE account_id = ? ORDER BY created_at, id", VIEW, accountId);
    }

    @Override
    public ProfileView findOwned(UUID accountId, UUID id) {
        return jdbc.query("SELECT * FROM profiles WHERE account_id = ? AND id = ?", VIEW, accountId, id)
                .stream().findFirst().orElse(null);
    }

    @Override
    public void insert(UUID id, UUID accountId, String name, boolean isDefault) {
        jdbc.update("INSERT INTO profiles(id, account_id, name, is_default) VALUES (?, ?, ?, ?)",
                id, accountId, name, isDefault);
    }

    @Override
    public void rename(UUID id, String name) {
        jdbc.update("UPDATE profiles SET name = ?, version = version + 1 WHERE id = ?", name, id);
    }

    @Override
    public void delete(UUID id) {
        jdbc.update("DELETE FROM profiles WHERE id = ?", id);
    }

    @Override
    public void promote(UUID id) {
        jdbc.update("UPDATE profiles SET is_default = TRUE, version = version + 1 WHERE id = ?", id);
    }
}
