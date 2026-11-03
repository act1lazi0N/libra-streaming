package com.libra.streaming.media.upload.api;

import com.libra.streaming.media.upload.application.UploadFailure;
import com.libra.streaming.media.upload.application.CandidateBindingFailure;
import com.libra.streaming.media.upload.application.UploadWorkflow;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.http.*;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice(assignableTypes = UploadControlController.class)
public class UploadControlProblems {
    private static final Logger LOG = LoggerFactory.getLogger(UploadControlProblems.class);

    @ExceptionHandler({org.springframework.dao.DataAccessException.class,
            org.springframework.transaction.TransactionException.class})
    ResponseEntity<ProblemDetail> persistenceUnavailable(RuntimeException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            org.springframework.beans.TypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalid(Exception exception) {
        // A converter the server lacks is its own defect, not the caller's.
        if (exception instanceof ConversionNotSupportedException unsupported) { return unexpected(unsupported); }
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    /** Framework client errors (a path or parameter constraint, a status exception) stay client errors. */
    @ExceptionHandler(ErrorResponseException.class)
    ResponseEntity<ProblemDetail> framework(ErrorResponseException exception) {
        var status = exception.getStatusCode();
        if (status.value() == 404) { return response(HttpStatus.NOT_FOUND, "NOT_FOUND"); }
        if (status.is4xxClientError()) { return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"); }
        return unexpected(exception);
    }

    @ExceptionHandler({CandidateBindingFailure.class, UploadFailure.class, UploadWorkflow.UploadNotFound.class})
    ResponseEntity<ProblemDetail> known(RuntimeException exception) {
        String code = exception.getMessage();
        HttpStatus status = switch (code) {
            case "INVALID_REQUEST" -> HttpStatus.BAD_REQUEST;
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "IDEMPOTENCY_CONFLICT", "UPLOAD_STATE_CONFLICT", "SOURCE_MISSING" -> HttpStatus.CONFLICT;
            case "UPLOAD_EXPIRED" -> HttpStatus.GONE;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(status, status == HttpStatus.SERVICE_UNAVAILABLE
                && !"STORAGE_UNAVAILABLE".equals(code) ? "CORE_UNAVAILABLE" : code);
    }

    /**
     * M32: anything no handler above names (a library defect, a wrapped driver error) is answered like a database
     * failure. Left to the container it was logged with its message and cause chain, and Core got an empty response.
     * The log line carries a fixed code, the problem's correlation id and the exception type; never its message.
     */
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ProblemDetail> unexpected(RuntimeException exception) {
        String correlationId = UUID.randomUUID().toString();
        LOG.error("UPLOAD_CONTROL_UNEXPECTED correlationId={} type={}", correlationId,
                exception.getClass().getName());
        return response(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE", correlationId);
    }

    private static ResponseEntity<ProblemDetail> response(HttpStatus status, String code) {
        return response(status, code, UUID.randomUUID().toString());
    }

    private static ResponseEntity<ProblemDetail> response(HttpStatus status, String code, String correlationId) {
        var body = ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase() + ".");
        body.setProperty("code", code);
        body.setProperty("correlationId", correlationId);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).body(body);
    }
}
