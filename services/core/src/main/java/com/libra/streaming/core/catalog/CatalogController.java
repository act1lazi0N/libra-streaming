package com.libra.streaming.core.catalog;

import com.libra.streaming.core.api.CorrelationIdFilter;
import com.libra.streaming.core.identity.IdentityPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.libra.streaming.core.catalog.CatalogModels.*;

@RestController
@RequestMapping("/v1")
public class CatalogController {
    private final CatalogService catalog;
    private final CatalogQueries queries;

    public CatalogController(CatalogService catalog, CatalogQueries queries) { this.catalog = catalog; this.queries = queries; }

    @GetMapping("/catalog")
    List<PublicView> search(@RequestParam(defaultValue = "") @Size(max = 200) String q,
            @RequestParam(required = false) Kind kind, @RequestParam(defaultValue = "") @Size(max = 40) String genre,
            @RequestParam(required = false) @Min(1888) @Max(2200) Integer year,
            @RequestParam(defaultValue = "") @Size(max = 10) String language, @RequestParam(required = false) Tier tier,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return queries.search(q, kind, genre, year, language, tier, limit, offset);
    }

    @GetMapping("/catalog/{id}")
    PublicView get(@PathVariable UUID id) { return queries.get(id); }

    @GetMapping("/catalog/{id}/children")
    List<PublicView> children(@PathVariable UUID id, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) { return queries.children(id, limit, offset); }

    @GetMapping("/admin/catalog")
    List<AdminView> adminList(@AuthenticationPrincipal IdentityPrincipal actor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) { return catalog.list(actor, limit, offset); }

    @PostMapping("/admin/catalog")
    @ResponseStatus(HttpStatus.CREATED)
    AdminView create(@AuthenticationPrincipal IdentityPrincipal actor, @Valid @RequestBody Create request) {
        return catalog.create(actor, request);
    }

    @GetMapping("/admin/catalog/{id}")
    AdminView adminGet(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id) { return catalog.get(actor, id); }

    @GetMapping("/admin/catalog/{id}/revisions")
    List<Revision> revisions(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) { return catalog.revisions(actor, id, limit, offset); }

    @PutMapping("/admin/catalog/{id}/draft")
    AdminView edit(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id, @Valid @RequestBody Edit request) {
        return catalog.edit(actor, id, request);
    }

    @PostMapping("/admin/catalog/{id}/media-bindings")
    AdminView bind(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id, @Valid @RequestBody Bind request) {
        return catalog.bind(actor, id, request);
    }

    @PostMapping("/admin/catalog/{id}/publication")
    AdminView publish(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id, @Valid @RequestBody Version request,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) { return catalog.publish(actor, id, request, correlationId); }

    @DeleteMapping("/admin/catalog/{id}/publication")
    AdminView unpublish(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id,
            @RequestParam @Min(1) long expectedVersion, @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        return catalog.unpublish(actor, id, new Version(expectedVersion), correlationId);
    }
}
