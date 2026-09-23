package com.libra.streaming.core.integration.media;

import com.libra.streaming.core.api.DomainException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/media/bindings")
public class MediaBindingController {
    private final JdbcTemplate jdbc;
    public MediaBindingController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/{id}")
    Binding get(@PathVariable UUID id) {
        return jdbc.query("""
                SELECT b.id, b.content_id, b.asset_id, b.asset_version,
                    coalesce(c.candidate_binding = b.id, FALSE) AS candidate, coalesce(c.active_binding = b.id, FALSE) AS active
                FROM catalog_media_bindings b JOIN catalog_contents c ON c.id = b.content_id WHERE b.id = ?
                """, (rs, row) -> new Binding(rs.getObject("id", UUID.class), rs.getObject("content_id", UUID.class),
                        rs.getObject("asset_id", UUID.class), rs.getLong("asset_version"), rs.getBoolean("candidate"), rs.getBoolean("active")), id)
                .stream().findFirst().orElseThrow(DomainException::missing);
    }
    public record Binding(UUID bindingId, UUID contentId, UUID assetId, long assetVersion, boolean candidate, boolean active) {}
}
