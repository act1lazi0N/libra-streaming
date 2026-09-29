package com.libra.streaming.core.history.api;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.history.application.HistoryOperations;
import com.libra.streaming.core.history.application.HistoryUseCase;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/profiles/{profileId}")
public class HistoryController {
    private final HistoryOperations history;
    public HistoryController(HistoryOperations history) { this.history = history; }

    @GetMapping("/history")
    List<HistoryOperations.HistoryView> history(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID profileId,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        return translate(() -> history.list(actor, profileId, false, limit, offset));
    }

    @GetMapping("/continue-watching")
    List<HistoryOperations.HistoryView> continuing(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID profileId,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        return translate(() -> history.list(actor, profileId, true, limit, offset));
    }

    @DeleteMapping("/history")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID profileId) {
        translate(() -> { history.clear(actor, profileId, null); return null; });
    }

    @DeleteMapping("/history/{contentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID profileId, @PathVariable UUID contentId) {
        translate(() -> { history.clear(actor, profileId, contentId); return null; });
    }

    private static <T> T translate(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (HistoryUseCase.Failure failure) {
            throw "NOT_FOUND".equals(failure.getMessage()) ? DomainException.missing() : DomainException.invalid();
        }
    }
}
