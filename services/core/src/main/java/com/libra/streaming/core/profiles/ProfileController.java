package com.libra.streaming.core.profiles;

import com.libra.streaming.core.api.CorrelationIdFilter;
import com.libra.streaming.core.identity.IdentityPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/profiles")
public class ProfileController {
    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) { this.profiles = profiles; }

    @GetMapping
    List<ProfileService.ProfileView> list(@AuthenticationPrincipal IdentityPrincipal actor) {
        return profiles.list(actor);
    }

    @GetMapping("/{id}")
    ProfileService.ProfileView get(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id) {
        return profiles.get(actor, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProfileService.ProfileView create(@AuthenticationPrincipal IdentityPrincipal actor, @Valid @RequestBody Create request,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        return profiles.create(actor, request.name(), correlationId);
    }

    @PutMapping("/{id}")
    ProfileService.ProfileView rename(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody Rename request, @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        return profiles.rename(actor, id, request.name(), request.expectedVersion(), correlationId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id,
            @RequestParam @Min(1) long expectedVersion, @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        profiles.delete(actor, id, expectedVersion, correlationId);
    }

    public record Create(@NotBlank @Size(max = 80) String name) {}
    public record Rename(@NotBlank @Size(max = 80) String name, @Min(1) long expectedVersion) {}
}
