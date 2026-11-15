package com.libra.streaming.recommendation.internalapi.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The closed {@code Problem} body of analytics-internal.v1. Every text is fixed: no credential, URL, SQL,
 * exception message or request value is ever reflected, except a well-formed correlation UUID.
 */
@Component
public class Problems {
    public enum Code {
        AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "A valid service credential is required."),
        ACCESS_DENIED(HttpStatus.FORBIDDEN, "The service credential does not allow this operation."),
        NOT_FOUND(HttpStatus.NOT_FOUND, "The operation does not exist."),
        METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "The method is not allowed for this operation."),
        INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be processed."),
        ANALYTICS_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "Analytics has not completed its first checkpoint."),
        ANALYTICS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Analytics is temporarily unavailable.");

        private final HttpStatus status;
        private final String detail;

        Code(HttpStatus status, String detail) {
            this.status = status;
            this.detail = detail;
        }
    }

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String CORRELATION_ATTRIBUTE = Problems.class.getName() + ".correlationId";

    private final ObjectMapper mapper;

    public Problems(ObjectMapper mapper) { this.mapper = mapper; }

    /** For security handlers, which answer before any controller runs. */
    public void write(Code code, HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(code.status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (code == Code.AUTHENTICATION_REQUIRED) { response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer"); }
        mapper.writeValue(response.getOutputStream(), body(code, request));
    }

    public ResponseEntity<Map<String, Object>> entity(Code code, HttpServletRequest request) {
        return ResponseEntity.status(code.status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(body(code, request));
    }

    private static Map<String, Object> body(Code code, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", code.status.getReasonPhrase());
        body.put("status", code.status.value());
        body.put("detail", code.detail);
        body.put("code", code.name());
        body.put("correlationId", correlationId(request));
        return body;
    }

    /** Stable for the whole request, so a log line and the problem body name the same identifier. */
    public static String correlationId(HttpServletRequest request) {
        if (request.getAttribute(CORRELATION_ATTRIBUTE) instanceof String known) { return known; }
        String forwarded = request.getHeader(CORRELATION_HEADER);
        String id = forwarded != null && UUID_TEXT.matcher(forwarded).matches() ? forwarded : UUID.randomUUID().toString();
        request.setAttribute(CORRELATION_ATTRIBUTE, id);
        return id;
    }
}
