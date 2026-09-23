package com.libra.streaming.core.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ApiProblems problems;

    public ApiExceptionHandler(ApiProblems problems) {
        this.problems = problems;
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpHeaders safeHeaders = new HttpHeaders();
        safeHeaders.putAll(headers);
        safeHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        safeHeaders.setCacheControl("no-store");
        return new ResponseEntity<>(problems.create(status,
                ((ServletWebRequest) request).getRequest()), safeHeaders, status);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Request failed: exceptionType={}, correlationId={}",
                exception.getClass().getSimpleName(), request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).cacheControl(
                        org.springframework.http.CacheControl.noStore())
                .body(problems.create(HttpStatus.INTERNAL_SERVER_ERROR, request));
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<Object> domain(DomainException exception, HttpServletRequest request) {
        var problem = problems.create(exception.status(), request);
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(exception.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(problem);
    }

    @ExceptionHandler(com.libra.streaming.core.identity.IdentityException.class)
    ResponseEntity<Object> identity(com.libra.streaming.core.identity.IdentityException exception,
            HttpServletRequest request) {
        var problem = problems.create(exception.status(), request);
        problem.setProperty("code", exception.code());
        var response = ResponseEntity.status(exception.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(org.springframework.http.CacheControl.noStore());
        if (exception.status() == HttpStatus.TOO_MANY_REQUESTS) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
        }
        return response.body(problem);
    }
}
