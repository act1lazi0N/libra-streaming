package com.libra.streaming.recommendation.internalapi.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every failure after authentication into a contract problem. Without it the container would dispatch to
 * {@code /error}, which the default chain denies, and the caller would get an empty 403.
 */
@RestControllerAdvice
class InternalApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(InternalApiErrors.class);

    private final Problems problems;

    InternalApiErrors(Problems problems) { this.problems = problems; }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<Map<String, Object>> notFound(HttpServletRequest request) {
        return problems.entity(Problems.Code.NOT_FOUND, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> methodNotAllowed(HttpServletRequest request) {
        return problems.entity(Problems.Code.METHOD_NOT_ALLOWED, request);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Map<String, Object>> unavailable(Exception exception, HttpServletRequest request) {
        // Driver messages carry the JDBC URL and user; only the exception type is logged.
        LOG.warn("Internal query failed: database unavailable ({}) correlationId={}",
                exception.getClass().getSimpleName(), Problems.correlationId(request));
        return problems.entity(Problems.Code.ANALYTICS_UNAVAILABLE, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception exception, HttpServletRequest request) {
        // Type and throwing frame only: an exception message can quote request or database values.
        StackTraceElement[] stack = exception.getStackTrace();
        LOG.error("Internal query failed unexpectedly ({}) at {} correlationId={}", exception.getClass().getName(),
                stack.length > 0 ? stack[0] : "unknown", Problems.correlationId(request));
        return problems.entity(Problems.Code.INTERNAL_ERROR, request);
    }
}
