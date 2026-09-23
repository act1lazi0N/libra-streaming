package com.libra.streaming.core.api;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiProblems {
    private final ObjectMapper mapper;

    public ApiProblems(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ProblemDetail create(HttpStatusCode status, HttpServletRequest request) {
        HttpStatus known = HttpStatus.resolve(status.value());
        String title = known == null ? "Request failed" : known.getReasonPhrase();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                status.is5xxServerError() ? "An unexpected error occurred." : title + ".");
        problem.setTitle(title);
        problem.setProperty("code", switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 401 -> "AUTHENTICATION_REQUIRED";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 409 -> "CONFLICT";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 429 -> "RATE_LIMITED";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
        });
        Object id = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        problem.setProperty("correlationId", id == null ? UUID.randomUUID().toString() : id);
        // Do not reflect query strings, rejected values, or exception messages.
        return problem;
    }

    public void write(HttpStatus status, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), create(status, request));
    }
}
