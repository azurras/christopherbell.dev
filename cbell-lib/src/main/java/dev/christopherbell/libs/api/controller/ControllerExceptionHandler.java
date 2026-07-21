package dev.christopherbell.libs.api.controller;

import dev.christopherbell.libs.api.model.Message;
import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.InvalidTokenException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global controller advice translating exceptions into consistent API responses.
 *
 * <p>Builds {@link Response} envelopes with {@link Message} entries and
 * appropriate HTTP statuses for common error types.</p>
 */
@RestControllerAdvice
@Slf4j
public class ControllerExceptionHandler {
  private static final String INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";
  private static final String RESOURCE_EXISTS = "RESOURCE_EXISTS";
  private static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
  private static final String INVALID_REQUEST = "INVALID_REQUEST";
  private static final String INVALID_TOKEN = "INVALID_TOKEN";
  private static final String ACCESS_DENIED = "ACCESS_DENIED";
  private static final String REQUEST_ERROR = "REQUEST_ERROR";

  /**
   * Fallback handler for unanticipated exceptions. Returns HTTP 500 with a generic error message.
   *
   * @param e the exception
   * @return a {@link Response} with {@code success=false} and a single error {@link Message}
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Response<?>> handleGenericException(Exception e) {
    var frameworkStatus = statusForFrameworkException(e);
    if (frameworkStatus != null) {
      log.error(REQUEST_ERROR, e);
      return errorResponse(REQUEST_ERROR, e.getMessage(), frameworkStatus);
    }

    log.error(INTERNAL_SERVER_ERROR, e);
    return errorResponse(
        INTERNAL_SERVER_ERROR,
        "An unexpected error occurred. Please try again later.",
        HttpStatus.INTERNAL_SERVER_ERROR);
  }

  /**
   * Handles denied controller method authorization. Returns HTTP 403 with a standard envelope.
   *
   * @param e the exception
   * @return a {@link Response} with {@code success=false} and an error {@link Message}
   */
  @ExceptionHandler(AccessDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public Response<?> handleAccessDeniedException(AccessDeniedException e) {
    log.error(ACCESS_DENIED, e);
    return Response.builder()
        .messages(List.of(Message.builder()
            .code(ACCESS_DENIED)
            .description("Access is denied.")
            .build()))
        .success(false)
        .build();
  }

  /**
   * Handles Spring MVC request exceptions that already carry an HTTP status.
   *
   * @param e the exception
   * @return a {@link Response} with {@code success=false} and an error {@link Message}
   */
  @ExceptionHandler(ErrorResponseException.class)
  public ResponseEntity<Response<?>> handleErrorResponseException(ErrorResponseException e) {
    log.error(REQUEST_ERROR, e);
    return errorResponse(REQUEST_ERROR, e.getMessage(), e.getStatusCode());
  }

  /**
   * Handles {@link ResourceExistsException}. Returns HTTP 409 with error details.
   *
   * @param e the exception
   * @return a {@link Response} with {@code success=false} and an error {@link Message}
   */
  @ExceptionHandler(ResourceExistsException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Response<?> handleResourceExistsException(ResourceExistsException e) {
    log.error(RESOURCE_EXISTS, e);
    return Response.builder()
            .messages(List.of(Message.builder()
                .code(RESOURCE_EXISTS)
                .description(e.getMessage())
                .build()))
            .success(false)
            .build();
  }

  /**
   * Handles {@link ResourceNotFoundException}. Returns HTTP 404 with error details.
   *
   * @param e the exception
   * @return a {@link Response} with {@code success=false} and an error {@link Message}
   */
  @ExceptionHandler(ResourceNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Response<?> handleResourceNotFoundException(ResourceNotFoundException e) {
    log.error(RESOURCE_NOT_FOUND, e);
    return Response.builder()
        .messages(List.of(Message.builder()
            .code(RESOURCE_NOT_FOUND)
            .description(e.getMessage())
            .build()))
        .success(false)
        .build();
  }

  /**
   * Handles {@link InvalidRequestException}. Returns HTTP 400 with error details.
   *
   * @param e the exception
   * @return a {@link Response} with {@code success=false} and an error {@link Message}
   */
  @ExceptionHandler(InvalidRequestException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Response<?> handleInvalidRequestException(InvalidRequestException e) {
    log.error(INVALID_REQUEST, e);
    return Response.builder()
            .messages(List.of(Message.builder()
                .code(INVALID_REQUEST)
                .description(e.getMessage())
                .build()))
            .success(false)
            .build();
  }

  /**
   * Handles {@link InvalidTokenException}. Returns HTTP 401 with error details.
   *
   * @param e the exception
   * @return a {@link Response} with {@code success=false} and an error {@link Message}
   */
  @ExceptionHandler(InvalidTokenException.class)
  @ResponseStatus(HttpStatus.UNAUTHORIZED)
  public Response<?> handleInvalidTokenException(InvalidTokenException e) {
    log.error(INVALID_TOKEN, e);
    return Response.builder()
            .messages(List.of(Message.builder()
                .code(INVALID_TOKEN)
                .description(e.getMessage())
                .build()))
            .success(false)
            .build();
  }

  private ResponseEntity<Response<?>> errorResponse(String code, String description, HttpStatus status) {
    return errorResponse(code, description, (org.springframework.http.HttpStatusCode) status);
  }

  private ResponseEntity<Response<?>> errorResponse(
      String code,
      String description,
      org.springframework.http.HttpStatusCode status
  ) {
    var body = Response.builder()
        .messages(List.of(Message.builder()
            .code(code)
            .description(description)
            .build()))
        .success(false)
        .build();
    return new ResponseEntity<>(body, status);
  }

  private HttpStatus statusForFrameworkException(Exception e) {
    return switch (e.getClass().getName()) {
      case "org.springframework.web.HttpMediaTypeNotSupportedException" -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
      case "org.springframework.web.HttpMediaTypeNotAcceptableException" -> HttpStatus.NOT_ACCEPTABLE;
      case "org.springframework.http.converter.HttpMessageNotReadableException",
          "org.springframework.web.bind.MethodArgumentNotValidException",
          "org.springframework.web.method.annotation.HandlerMethodValidationException" -> HttpStatus.BAD_REQUEST;
      default -> null;
    };
  }
}
