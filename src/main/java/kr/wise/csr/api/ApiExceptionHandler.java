package kr.wise.csr.api;

import java.time.OffsetDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import kr.wise.csr.approval.ApprovalRejectedException;
import kr.wise.csr.sqlcompare.SqlBaselineNotFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({ ProjectNotFoundException.class, SqlBaselineNotFoundException.class })
    ResponseEntity<ApiError> notFound(RuntimeException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({ ApprovalRejectedException.class, IllegalStateException.class })
    ResponseEntity<ApiError> conflict(RuntimeException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst().map(error -> error.getDefaultMessage()).orElse("요청 값이 올바르지 않습니다");
        return error(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), status.getReasonPhrase(), message, OffsetDateTime.now()));
    }

    record ApiError(int status, String error, String message, OffsetDateTime timestamp) {
    }
}
