package com.flooring.salesportal.common.error;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.flooring.salesportal.common.api.ErrorBody;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.api.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final PropertyNamingStrategies.NamingBase SNAKE_CASE =
            (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.SNAKE_CASE;

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

    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ErrorResponse> handleErrorResponseException(ErrorResponseException ex) {
        HttpStatusCode statusCode = ex.getStatusCode();
        ErrorCode errorCode = mapStatusToErrorCode(statusCode);
        ErrorBody body = new ErrorBody(errorCode.name(), errorCode.defaultMessage(), null);
        return ResponseEntity.status(statusCode).body(new ErrorResponse(body));
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

    private ErrorCode mapStatusToErrorCode(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 400 -> ErrorCode.VALIDATION_FAILED;
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            case 422 -> ErrorCode.BUSINESS_RULE_VIOLATION;
            default -> {
                if (statusCode.is4xxClientError()) {
                    yield ErrorCode.VALIDATION_FAILED;
                }
                if (statusCode.is5xxServerError()) {
                    yield ErrorCode.INTERNAL_SERVER_ERROR;
                }
                yield ErrorCode.INTERNAL_SERVER_ERROR;
            }
        };
    }

    private ResponseEntity<ErrorResponse> badRequest(ErrorCode code, List<ErrorDetail> details) {
        ErrorBody body = new ErrorBody(code.name(), code.defaultMessage(), details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(body));
    }

    private ErrorDetail toDetail(FieldError fe) {
        return new ErrorDetail(null, toSnakeCase(fe.getField()), fe.getDefaultMessage());
    }

    private ErrorDetail toDetail(ConstraintViolation<?> cv) {
        return new ErrorDetail(null, toSnakeCase(lastPathNode(cv)), cv.getMessage());
    }

    private String lastPathNode(ConstraintViolation<?> cv) {
        String last = null;
        for (var node : cv.getPropertyPath()) {
            last = node.getName();
        }
        return last;
    }

    private String toSnakeCase(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        return SNAKE_CASE.translate(fieldName);
    }
}
