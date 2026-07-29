package dev.christopherbell.libs.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.InternalServiceException;
import dev.christopherbell.libs.api.exception.InvalidTokenException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.exception.ServiceUnavailableException;
import dev.christopherbell.libs.api.model.Response;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.slf4j.LoggerFactory;

class ControllerExceptionHandlerTest {
  private final ControllerExceptionHandler handler = new ControllerExceptionHandler();

  @Test
  void serviceUnavailableUsesSafeConsistentEnvelopeAndPreservesInternalCause() {
    var cause = new IllegalStateException("database host secret");
    var exception = new ServiceUnavailableException("restaurant save failed", cause);

    var response = handler.handleServiceUnavailableException(exception);

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("SERVICE_UNAVAILABLE", response.getBody().getMessages().getFirst().getCode());
    String description = response.getBody().getMessages().getFirst().getDescription();
    assertFalse(description.contains("database"));
    assertFalse(description.contains("secret"));
    assertFalse(description.contains("restaurant"));
    assertSame(cause, exception.getCause());
  }

  @Test
  void internalServiceFailureUsesGenericInternalEnvelope() {
    var response = handler.handleInternalServiceException(
        new InternalServiceException("credential hashing failed", new Exception("provider")));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("INTERNAL_SERVER_ERROR", response.getBody().getMessages().getFirst().getCode());
  }

  @Test
  void ordinaryClientErrorsLogAtDebugWithoutThrowable() {
    var frameworkCases = List.of(
        entityCase(
            () -> handler.handleGenericException(new HttpMessageNotReadableException(
                "parser detail: secret-field", emptyInput())),
            HttpStatus.BAD_REQUEST,
            "REQUEST_ERROR",
            "The request body is malformed or invalid.",
            Level.DEBUG,
            null),
        entityCase(
            () -> handler.handleGenericException(
                new HttpMediaTypeNotAcceptableException("raw accept header detail")),
            HttpStatus.NOT_ACCEPTABLE,
            "REQUEST_ERROR",
            "The requested response format is not available.",
            Level.DEBUG,
            null),
        entityCase(
            () -> handler.handleGenericException(
                new HttpMediaTypeNotSupportedException("raw content type detail")),
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "REQUEST_ERROR",
            "The request media type is not supported.",
            Level.DEBUG,
            null),
        entityCase(
            () -> handler.handleErrorResponseException(
                new ErrorResponseException(HttpStatus.NOT_ACCEPTABLE)),
            HttpStatus.NOT_ACCEPTABLE,
            "REQUEST_ERROR",
            "The requested response format is not available.",
            Level.DEBUG,
            null),
        entityCase(
            () -> handler.handleErrorResponseException(
                new ErrorResponseException(HttpStatus.UNSUPPORTED_MEDIA_TYPE)),
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "REQUEST_ERROR",
            "The request media type is not supported.",
            Level.DEBUG,
            null));
    var domainCases = List.of(
        bodyCase(
            () -> handler.handleInvalidRequestException(
                new InvalidRequestException("raw validation detail")),
            "handleInvalidRequestException",
            InvalidRequestException.class,
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "The request is invalid.",
            Level.DEBUG,
            null),
        bodyCase(
            () -> handler.handleResourceNotFoundException(
                new ResourceNotFoundException("raw lookup detail")),
            "handleResourceNotFoundException",
            ResourceNotFoundException.class,
            HttpStatus.NOT_FOUND,
            "RESOURCE_NOT_FOUND",
            "The requested resource was not found.",
            Level.DEBUG,
            null),
        bodyCase(
            () -> handler.handleResourceExistsException(
                new ResourceExistsException("raw conflict detail")),
            "handleResourceExistsException",
            ResourceExistsException.class,
            HttpStatus.CONFLICT,
            "RESOURCE_EXISTS",
            "The resource already exists.",
            Level.DEBUG,
            null));

    frameworkCases.forEach(this::assertEntityCase);
    domainCases.forEach(this::assertBodyCase);
  }

  @Test
  void securityRelevantClientErrorsLogAtWarnWithoutThrowable() {
    var entityCases = List.of(
        entityCase(
            () -> handler.handleErrorResponseException(
                new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS)),
            HttpStatus.TOO_MANY_REQUESTS,
            "REQUEST_ERROR",
            "Too many requests. Please try again later.",
            Level.WARN,
            null));
    var bodyCases = List.of(
        bodyCase(
            () -> handler.handleInvalidTokenException(
                new InvalidTokenException("token internals")),
            "handleInvalidTokenException",
            InvalidTokenException.class,
            HttpStatus.UNAUTHORIZED,
            "INVALID_TOKEN",
            "Authentication is required.",
            Level.WARN,
            null),
        bodyCase(
            () -> handler.handleAccessDeniedException(
                new AccessDeniedException("authorization internals")),
            "handleAccessDeniedException",
            AccessDeniedException.class,
            HttpStatus.FORBIDDEN,
            "ACCESS_DENIED",
            "Access is denied.",
            Level.WARN,
            null));

    entityCases.forEach(this::assertEntityCase);
    bodyCases.forEach(this::assertBodyCase);
  }

  @Test
  void unexpectedFailureLogsAtErrorWithThrowable() {
    var failure = new IllegalStateException("database unavailable");

    assertEntityCase(entityCase(
        () -> handler.handleGenericException(failure),
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_SERVER_ERROR",
        "An unexpected error occurred. Please try again later.",
        Level.ERROR,
        IllegalStateException.class));
  }

  @Test
  void frameworkServerFailureLogsAtErrorWithThrowable() {
    var failure = new ErrorResponseException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        new IllegalStateException("framework internals"));

    assertEntityCase(entityCase(
        () -> handler.handleErrorResponseException(failure),
        HttpStatus.INTERNAL_SERVER_ERROR,
        "REQUEST_ERROR",
        "The request could not be processed.",
        Level.ERROR,
        ErrorResponseException.class));
  }

  @Test
  void serviceUnavailableLogsAtErrorWithThrowable() {
    var failure = new ServiceUnavailableException(
        "storage unavailable", new IllegalStateException("database host secret"));

    assertEntityCase(entityCase(
        () -> handler.handleServiceUnavailableException(failure),
        HttpStatus.SERVICE_UNAVAILABLE,
        "SERVICE_UNAVAILABLE",
        "The service is temporarily unavailable. Please try again later.",
        Level.ERROR,
        ServiceUnavailableException.class));
  }

  private EntityCase entityCase(
      Supplier<ResponseEntity<Response<?>>> action,
      HttpStatus status,
      String code,
      String description,
      Level level,
      Class<? extends Exception> throwableClass
  ) {
    return new EntityCase(action, status, code, description, level, throwableClass);
  }

  private BodyCase bodyCase(
      Supplier<Response<?>> action,
      String handlerMethod,
      Class<? extends Exception> exceptionClass,
      HttpStatus status,
      String code,
      String description,
      Level level,
      Class<? extends Exception> throwableClass
  ) {
    return new BodyCase(
        action, handlerMethod, exceptionClass, status, code, description, level, throwableClass);
  }

  private void assertEntityCase(EntityCase expected) {
    var captured = capture(expected.action());

    assertEquals(expected.status(), captured.response().getStatusCode());
    assertResponse(captured.response().getBody(), expected.code(), expected.description());
    assertLog(captured.event(), expected.level(), expected.throwableClass());
  }

  private void assertBodyCase(BodyCase expected) {
    var captured = capture(expected.action());

    assertEquals(expected.status(), responseStatus(expected.handlerMethod(), expected.exceptionClass()));
    assertResponse(captured.response(), expected.code(), expected.description());
    assertLog(captured.event(), expected.level(), expected.throwableClass());
  }

  private void assertResponse(Response<?> response, String code, String description) {
    assertNotNull(response);
    assertFalse(response.isSuccess());
    var message = response.getMessages().getFirst();
    assertEquals(code, message.getCode());
    assertEquals(description, message.getDescription());
  }

  private void assertLog(
      ILoggingEvent event,
      Level level,
      Class<? extends Exception> throwableClass
  ) {
    assertEquals(level, event.getLevel());
    if (throwableClass == null) {
      assertNull(event.getThrowableProxy());
      return;
    }
    assertNotNull(event.getThrowableProxy());
    assertEquals(throwableClass.getName(), event.getThrowableProxy().getClassName());
  }

  private HttpStatus responseStatus(String handlerMethod, Class<? extends Exception> exceptionClass) {
    try {
      return ControllerExceptionHandler.class
          .getMethod(handlerMethod, exceptionClass)
          .getAnnotation(ResponseStatus.class)
          .value();
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  private <T> Captured<T> capture(Supplier<T> action) {
    var logger = (Logger) LoggerFactory.getLogger(ControllerExceptionHandler.class);
    Level originalLevel = logger.getLevel();
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.setLevel(Level.TRACE);
    logger.addAppender(appender);
    try {
      var response = action.get();
      assertEquals(1, appender.list.size());
      return new Captured<>(response, appender.list.getFirst());
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(originalLevel);
      appender.stop();
    }
  }

  private HttpInputMessage emptyInput() {
    return new HttpInputMessage() {
      @Override public ByteArrayInputStream getBody() { return new ByteArrayInputStream(new byte[0]); }
      @Override public HttpHeaders getHeaders() { return new HttpHeaders(); }
    };
  }

  private record EntityCase(
      Supplier<ResponseEntity<Response<?>>> action,
      HttpStatus status,
      String code,
      String description,
      Level level,
      Class<? extends Exception> throwableClass
  ) {}

  private record BodyCase(
      Supplier<Response<?>> action,
      String handlerMethod,
      Class<? extends Exception> exceptionClass,
      HttpStatus status,
      String code,
      String description,
      Level level,
      Class<? extends Exception> throwableClass
  ) {}

  private record Captured<T>(T response, ILoggingEvent event) {}
}
