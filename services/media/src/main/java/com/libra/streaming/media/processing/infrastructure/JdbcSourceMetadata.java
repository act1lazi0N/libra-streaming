package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.SourceMetadataStore;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
// Short fenced transactions like the lease and selection adapters: the probe itself never runs inside one.
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
public class JdbcSourceMetadata implements SourceMetadataStore {
    private static final String COLUMNS = """
            m.duration_millis, m.video_profile, m.coded_width, m.coded_height, m.rotation, m.display_width,
            m.display_height, m.frame_rate_numerator, m.frame_rate_denominator, m.audio_channels,
            m.audio_sample_rate""";
    private final JdbcTemplate jdbc;

    public JdbcSourceMetadata(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<SourceMetadata> find(JobLease lease) {
        if (lease == null) { return Optional.empty(); }
        return jdbc.query("SELECT " + COLUMNS + """

                FROM media_jobs j JOIN media_uploads u ON u.id = j.upload_id
                    JOIN media_assets a ON a.asset_id = u.asset_id AND a.asset_version = u.asset_version
                    JOIN media_source_metadata m ON m.asset_id = a.asset_id AND m.asset_version = a.asset_version
                        AND m.source_key = a.selected_source_key
                """ + " WHERE " + LeaseFence.CURRENT + " AND j.lease_until > clock_timestamp()",
                (rs, row) -> read(rs), LeaseFence.arguments(lease)).stream().findFirst();
    }

    @Override
    public boolean record(JobLease lease, String sourceKey, SourceMetadata metadata) {
        if (sourceKey == null || sourceKey.isBlank() || sourceKey.length() > 512) {
            throw new IllegalArgumentException("Invalid source key");
        }
        var now = LeaseFence.lockCurrent(jdbc, lease);
        if (now == null) { return false; }
        // Selection also takes the job row lock, so the selected key cannot change under this transaction.
        // The column is NULL before selection; a list keeps that null where a stream would reject it.
        var rows = jdbc.queryForList("""
                SELECT selected_source_key FROM media_assets
                WHERE asset_id = ? AND asset_version = ? AND state = 'PROCESSING'
                """, String.class, lease.assetId(), lease.assetVersion());
        var selected = rows.isEmpty() ? null : rows.getFirst();
        if (!sourceKey.equals(selected)) { throw new IllegalStateException("Metadata describes the selected source"); }
        var audio = metadata.audio();
        int inserted = jdbc.update("""
                INSERT INTO media_source_metadata (asset_id, asset_version, source_key, duration_millis,
                    video_profile, coded_width, coded_height, rotation, display_width, display_height,
                    frame_rate_numerator, frame_rate_denominator, audio_channels, audio_sample_rate, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (asset_id, asset_version) DO NOTHING
                """, lease.assetId(), lease.assetVersion(), sourceKey, metadata.durationMillis(),
                metadata.videoProfile(), metadata.codedWidth(), metadata.codedHeight(), metadata.rotation(),
                metadata.displayWidth(), metadata.displayHeight(), metadata.frameRateNumerator(),
                metadata.frameRateDenominator(), audio == null ? null : audio.channels(),
                audio == null ? null : audio.sampleRate(), Timestamp.from(now));
        if (inserted == 0 && !metadata.equals(existing(lease, sourceKey))) {
            throw new IllegalStateException("Source metadata is immutable");
        }
        return true;
    }

    private SourceMetadata existing(JobLease lease, String sourceKey) {
        return jdbc.query("SELECT " + COLUMNS
                + " FROM media_source_metadata m WHERE m.asset_id = ? AND m.asset_version = ? AND m.source_key = ?",
                (rs, row) -> read(rs), lease.assetId(), lease.assetVersion(), sourceKey).stream().findFirst()
                .orElse(null);
    }

    private static SourceMetadata read(java.sql.ResultSet rs) throws java.sql.SQLException {
        int channels = rs.getInt(10);
        var audio = rs.wasNull() ? null : new SourceMetadata.Audio(channels, rs.getInt(11));
        return new SourceMetadata(rs.getInt(1), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getString(2), audio);
    }
}
