package dev.christopherbell.libs.api.controller;

import dev.christopherbell.libs.api.model.Message;
import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.libs.api.exception.InternalServiceException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.InvalidTokenException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;
import java.util.Set;
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
  private static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
  private static final String RESOURCE_EXISTS = "RESOURCE_EXISTS";
  private static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
  private static final String INVALID_REQUEST = "INVALID_REQUEST";
  private static final String INVALID_TOKEN = "INVALID_TOKEN";
  private static final String ACCESS_DENIED = "ACCESS_DENIED";
  private static final String REQUEST_ERROR = "REQUEST_ERROR";
  private static final Set<Integer> SECURITY_RELEVANT_CLIENT_STATUSES = Set.of(
      HttpStatus.UNAUTHORIZED.value(),
      HttpStatus.FORBIDDEN.value(),
      HttpStatus.TOO_MANY_REQUESTS.value());

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
      logHttpFailure(REQUEST_ERROR, frameworkStatus, e);
      return errorResponse(
          REQUEST_ERROR, publicFrameworkDescription(e, frameworkStatus), frameworkStatus);
    }

    log.error(INTERNAL_SERVER_ERROR, e);
    return errorResponse(
        INTERNAL_SERVER_ERROR,
        "An unexpected error occurred. Please try again later.",
        HttpStatus.INTERNAL_SERVER_ERROR);
  }

  /** Maps known temporary infrastructure failures without exposing diagnostic context. */
  @ExceptionHandler(ServiceUnavailableException.class)
  public ResponseEntity<Response<?>> handleServiceUnavailableException(
      ServiceUnavailableException e
  ) {
    log.error(SERVICE_UNAVAILABLE, e);
    return errorResponse(
        SERVICE_UNAVAILABLE,
        "The service is temporarily unavailable. Please try again later.",
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  /** Maps known internal operational failures to the same safe generic 500 contract. */
  @ExceptionHandler(InternalServiceException.class)
  public ResponseEntity<Response<?>> handleInternalServiceException(InternalServiceException e) {
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
    logHttpFailure(ACCESS_DENIED, HttpStatus.FORBIDDEN, e);
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
    logHttpFailure(REQUEST_ERROR, e.getStatusCode(), e);
    return errorResponse(
        REQUEST_ERROR, publicFrameworkDescription(e, e.getStatusCode()), e.getStatusCode());
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
    logHttpFailure(RESOURCE_EXISTS, HttpStatus.CONFLICT, e);
    return Response.builder()
            .messages(List.of(Message.builder()
                .code(RESOURCE_EXISTS)
                .description("The resource already exists.")
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
    logHttpFailure(RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, e);
    return Response.builder()
        .messages(List.of(Message.builder()
            .code(RESOURCE_NOT_FOUND)
            .description("The requested resource was not found.")
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
    logHttpFailure(INVALID_REQUEST, HttpStatus.BAD_REQUEST, e);
    return Response.builder()
            .messages(List.of(Message.builder()
                .code(INVALID_REQUEST)
                .description("The request is invalid.")
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
    logHttpFailure(INVALID_TOKEN, HttpStatus.UNAUTHORIZED, e);
    return Response.builder()
            .messages(List.of(Message.builder()
                .code(INVALID_TOKEN)
                .description("Authentication is required.")
                .build()))
            .success(false)
            .build();
  }

  private void logHttpFailure(String code, HttpStatusCode status, Exception failure) {
    if (status.is5xxServerError()) {
      log.error("{} status={} type={}", code, status.value(), failure.getClass().getSimpleName(),
          failure);
      return;
    }
    if (SECURITY_RELEVANT_CLIENT_STATUSES.contains(status.value())) {
      log.warn("{} status={} type={}", code, status.value(),
          failure.getClass().getSimpleName());
      return;
    }
    log.debug("{} status={} type={}", code, status.value(), failure.getClass().getSimpleName());
  }

  private ResponseEntity<Response<?>> errorResponse(String code, String description, HttpStatus status) {
    return errorResponse(code, description, (HttpStatusCode) status);
  }

  private ResponseEntity<Response<?>> errorResponse(
      String code,
      String description,
      HttpStatusCode status
  ) {
    var body = Response.builder()
        .messages(List.of(Message.builder()
            .code(code)
            .description(description)
            .build()))
        .success(false)
        .build();
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new ResponseEntity<>(body, headers, status);
  }

  private HttpStatus statusForFrameworkException(Exception e) {
    return switch (e.getClass().getName()) {
      case "org.springframework.web.HttpMediaTypeNotSupportedException" -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
      case "org.springframework.web.HttpMediaTypeNotAcceptableException" -> HttpStatus.NOT_ACCEPTABLE;
      case "org.springframework.http.converter.HttpMessageNotReadableException",
          "org.springframework.web.bind.MethodArgumentNotValidException",
          "org.springframework.web.method.annotation.MethodArgumentTypeMismatchException",
          "org.springframework.web.method.annotation.HandlerMethodValidationException" -> HttpStatus.BAD_REQUEST;
      default -> null;
    };
  }

  private String publicFrameworkDescription(
      Exception failure,
      HttpStatusCode status) {
    if (failure instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
      return "The request body is malformed or invalid.";
    }
    return switch (status.value()) {
      case 400 -> "The request is invalid.";
      case 401 -> "Authentication is required.";
      case 403 -> "Access is denied.";
      case 404 -> "The requested resource was not found.";
      case 406 -> "The requested response format is not available.";
      case 409 -> "The resource already exists.";
      case 415 -> "The request media type is not supported.";
      case 429 -> "Too many requests. Please try again later.";
      default -> "The request could not be processed.";
    };
  }
}
