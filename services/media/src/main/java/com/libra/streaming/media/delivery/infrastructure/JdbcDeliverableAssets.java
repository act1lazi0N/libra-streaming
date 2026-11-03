package com.libra.streaming.media.delivery.infrastructure;

import com.libra.streaming.media.delivery.application.DeliverableAssets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
// A short read of one row; no storage I/O runs inside it.
@Transactional(readOnly = true, timeout = 5)
public class JdbcDeliverableAssets implements DeliverableAssets {
    private final JdbcTemplate jdbc;

    public JdbcDeliverableAssets(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Deliverable> find(UUID assetId, long assetVersion) {
        return jdbc.query("""
                SELECT content_id, binding_id, state, output_prefix, master_manifest_key
                FROM media_assets WHERE asset_id = ? AND asset_version = ?
                """, (row, index) -> new Deliverable(row.getObject("content_id", UUID.class),
                        row.getObject("binding_id", UUID.class), row.getString("state"),
                        row.getString("output_prefix"), row.getString("master_manifest_key")),
                assetId, assetVersion).stream().findFirst();
    }
}
