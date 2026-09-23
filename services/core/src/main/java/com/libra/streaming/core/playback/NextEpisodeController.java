package com.libra.streaming.core.playback;

import com.libra.streaming.core.identity.IdentityPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class NextEpisodeController {
    private final NextEpisodeService episodes;
    public NextEpisodeController(NextEpisodeService episodes) { this.episodes = episodes; }

    @GetMapping("/v1/profiles/{profileId}/next-episode/{contentId}")
    NextEpisodeService.NextEpisode next(@AuthenticationPrincipal IdentityPrincipal actor,
            @PathVariable UUID profileId, @PathVariable UUID contentId) {
        return episodes.next(actor, profileId, contentId);
    }
}
