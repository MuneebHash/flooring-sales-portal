package com.flooring.salesportal.common.error;

import com.flooring.salesportal.common.api.ErrorBody;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.api.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        ErrorBody body = new ErrorBody(ex.getErrorCode().name(), ex.getMessage(), ex.getDetails());
        return ResponseEntity.status(ex.getStatus()).body(new ErrorResponse(body));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<ErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toDetail)
                .toList();
        return badRequest(ErrorCode.VALIDATION_FAILED, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<ErrorDetail> details = ex.getConstraintViolations().stream()
                .map(this::toDetail)
                .toList();
        return badRequest(ErrorCode.VALIDATION_FAILED, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        ErrorBody body = new ErrorBody(
                ErrorCode.MALFORMED_JSON.name(),
                ErrorCode.MALFORMED_JSON.defaultMessage(),
                null
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(body));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ErrorDetail detail = new ErrorDetail(null, ex.getName(), "Invalid value.");
        return badRequest(ErrorCode.VALIDATION_FAILED, List.of(detail));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        ErrorDetail detail = new ErrorDetail(null, ex.getParameterName(), "Required parameter is missing.");
        return badRequest(ErrorCode.VALIDATION_FAILED, List.of(detail));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ErrorBody body = new ErrorBody(
                ErrorCode.INTERNAL_SERVER_ERROR.name(),
                ErrorCode.INTERNAL_SERVER_ERROR.defaultMessage(),
                null
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(body));
    }

    private ResponseEntity<ErrorResponse> badRequest(ErrorCode code, List<ErrorDetail> details) {
        ErrorBody body = new ErrorBody(code.name(), code.defaultMessage(), details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(body));
    }

    private ErrorDetail toDetail(FieldError fe) {
        return new ErrorDetail(null, fe.getField(), fe.getDefaultMessage());
    }

    private ErrorDetail toDetail(ConstraintViolation<?> cv) {
        String field = lastPathNode(cv);
        return new ErrorDetail(null, field, cv.getMessage());
    }

    private String lastPathNode(ConstraintViolation<?> cv) {
        String last = null;
        for (var node : cv.getPropertyPath()) {
            last = node.getName();
        }
        return last;
    }
}
