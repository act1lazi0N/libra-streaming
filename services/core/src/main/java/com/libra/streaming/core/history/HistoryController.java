package com.libra.streaming.core.history;

import com.libra.streaming.core.identity.IdentityPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/profiles/{profileId}")
public class HistoryController {
    private final HistoryService history;
    public HistoryController(HistoryService history) { this.history = history; }

    @GetMapping("/history")
    List<HistoryService.HistoryView> history(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID profileId,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        return history.list(actor, profileId, false, limit, offset);
    }

    @GetMapping("/continue-watching")
    List<HistoryService.HistoryView> continuing(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID profileId,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        return history.list(actor, profileId, true, limit, offset);
    }

    @DeleteMapping("/history")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID profileId) {
        history.clear(actor, profileId, null);
    }

    @DeleteMapping("/history/{contentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID profileId, @PathVariable UUID contentId) {
        history.clear(actor, profileId, contentId);
    }
}
