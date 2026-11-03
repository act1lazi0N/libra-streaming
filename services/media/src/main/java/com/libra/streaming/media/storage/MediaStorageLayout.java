package com.libra.streaming.media.storage;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The buckets and prefixes every object key is derived from, recorded once in PostgreSQL (V9). Only an upload's
 * selected source and output are stored as keys; the keys of every other attempt are named from this layout, by the
 * worker that writes them and by the cleanup that deletes them. Created before any adapter reaches storage and before
 * the worker or the sweeper starts, so an instance configured with another layout never starts at all.
 */
public class MediaStorageLayout {
    private static final List<String> COLUMNS = List.of("source_bucket", "hls_bucket", "staging_prefix",
            "source_prefix", "hls_prefix");

    MediaStorageLayout(JdbcTemplate jdbc, MediaStorageProperties properties) {
        var configured = List.of(properties.sourceBucket(), properties.hlsBucket(), properties.stagingPrefix(),
                properties.sourcePrefix(), properties.hlsPrefix());
        jdbc.update("""
                INSERT INTO media_storage_layout (source_bucket, hls_bucket, staging_prefix, source_prefix, hls_prefix)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (singleton) DO NOTHING
                """, configured.toArray());
        var recorded = jdbc.queryForObject("""
                SELECT source_bucket, hls_bucket, staging_prefix, source_prefix, hls_prefix FROM media_storage_layout
                """, (rs, row) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5)));
        // The message names the differing settings, never their values: bucket names stay out of logs like every
        // other storage detail.
        var differing = java.util.stream.IntStream.range(0, COLUMNS.size())
                .filter(index -> !configured.get(index).equals(recorded.get(index))).mapToObj(COLUMNS::get).toList();
        if (!differing.isEmpty()) {
            throw new IllegalStateException("Media storage layout differs from the layout recorded in the database: "
                    + String.join(", ", differing));
        }
    }
}
