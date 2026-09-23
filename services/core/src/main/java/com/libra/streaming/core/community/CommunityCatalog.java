package com.libra.streaming.core.community;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.catalog.CatalogModels.Kind;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Community targets are top-level movies/series; no playback entitlement is implied. */
@Component
public class CommunityCatalog {
    private final JdbcTemplate jdbc;
    public CommunityCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public Kind publishedTarget(UUID id) {
        return target(id, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Kind lockPublishedTarget(UUID id) {
        return target(id, true);
    }

    private Kind target(UUID id, boolean lock) {
        // Serialize mutations against unpublication; hierarchy/kind cannot be edited.
        var kind = jdbc.query("""
                SELECT kind FROM catalog_contents WHERE id = ? AND published_revision IS NOT NULL
                """ + (lock ? " FOR SHARE" : ""), (rs, row) -> Kind.valueOf(rs.getString("kind")), id)
                .stream().findFirst().orElseThrow(DomainException::missing);
        if (kind != Kind.MOVIE && kind != Kind.SERIES) { throw DomainException.invalid(); }
        return kind;
    }

    public static void page(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) { throw DomainException.invalid(); }
    }
}
