package com.libra.streaming.media.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Authentication failures never reflect credentials, URLs, or decoder messages. */
@Component
public class MediaProblems {
    private final ObjectMapper mapper;
    public MediaProblems(ObjectMapper mapper) { this.mapper = mapper; }

    void write(HttpStatus status, HttpServletResponse response) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        if (status == HttpStatus.UNAUTHORIZED) { response.setHeader("WWW-Authenticate", "Bearer"); }
        mapper.writeValue(response.getOutputStream(), Map.of(
                "type", "about:blank", "title", status.getReasonPhrase(), "status", status.value(),
                "detail", status.getReasonPhrase() + ".",
                "code", status == HttpStatus.UNAUTHORIZED ? "AUTHENTICATION_REQUIRED" : "ACCESS_DENIED",
                "correlationId", UUID.randomUUID().toString()));
    }
}
