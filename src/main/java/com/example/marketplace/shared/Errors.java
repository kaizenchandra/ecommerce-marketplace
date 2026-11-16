package com.example.marketplace.shared;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException e) {
        return ProblemDetail.forStatusAndDetail(
                e.getStatusCode(), e.getReason() == null ? "Request rejected" : e.getReason());
    }

    @ExceptionHandler({DataIntegrityViolationException.class, CannotAcquireLockException.class})
    ProblemDetail conflict(Exception e) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Concurrent update or constraint violation; retry using the same idempotency key");
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            ArithmeticException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class
    })
    ProblemDetail validation(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request fields");
    }

    @ExceptionHandler({
            org.springframework.web.client.RestClientException.class,
            java.util.concurrent.ExecutionException.class,
            java.util.concurrent.TimeoutException.class
    })
    ProblemDetail providerUnavailable(Exception e) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "External provider unavailable; quote requests may retry, financial operations must retain"
                        + " their existing idempotency key");
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    ProblemDetail missing(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found");
    }
}
