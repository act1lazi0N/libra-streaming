package com.libra.streaming.media.control;

import com.libra.streaming.media.persistence.MediaPersistenceException;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice(assignableTypes = UploadControlController.class)
public class UploadControlProblems {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @ExceptionHandler({CoreBindingException.class, MediaPersistenceException.class})
    ResponseEntity<ProblemDetail> known(RuntimeException exception) {
        String code = exception.getMessage();
        HttpStatus status = switch (code) {
            case "INVALID_REQUEST" -> HttpStatus.BAD_REQUEST;
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "IDEMPOTENCY_CONFLICT", "UPLOAD_STATE_CONFLICT" -> HttpStatus.CONFLICT;
            case "UPLOAD_EXPIRED" -> HttpStatus.GONE;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(status, status == HttpStatus.SERVICE_UNAVAILABLE
                && !"STORAGE_UNAVAILABLE".equals(code) ? "CORE_UNAVAILABLE" : code);
    }

    private static ResponseEntity<ProblemDetail> response(HttpStatus status, String code) {
        var body = ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase() + ".");
        body.setProperty("code", code);
        body.setProperty("correlationId", UUID.randomUUID().toString());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).body(body);
    }
}
