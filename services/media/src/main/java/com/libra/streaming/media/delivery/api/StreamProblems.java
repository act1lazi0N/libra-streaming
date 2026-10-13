package com.libra.streaming.media.delivery.api;

import com.libra.streaming.media.delivery.application.StreamDelivery;
import com.libra.streaming.media.delivery.application.StreamObjects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stream failures before any byte is sent. No key, prefix, asset state or storage detail is ever reflected. */
@RestControllerAdvice(assignableTypes = StreamController.class)
public class StreamProblems {
    @ExceptionHandler(StreamDelivery.NotFound.class)
    ResponseEntity<ProblemDetail> notFound(RuntimeException exception) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @ExceptionHandler(StreamDelivery.Denied.class)
    ResponseEntity<ProblemDetail> denied(RuntimeException exception) {
        return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }

    @ExceptionHandler({StreamObjects.Unavailable.class, org.springframework.dao.DataAccessException.class,
            org.springframework.transaction.TransactionException.class})
    ResponseEntity<ProblemDetail> unavailable(RuntimeException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE");
    }

    private static ResponseEntity<ProblemDetail> response(HttpStatus status, String code) {
        var body = ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase() + ".");
        body.setProperty("code", code);
        body.setProperty("correlationId", UUID.randomUUID().toString());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).body(body);
    }
}
