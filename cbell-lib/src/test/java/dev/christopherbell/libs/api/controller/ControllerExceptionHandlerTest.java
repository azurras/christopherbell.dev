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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponseException;
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
  void malformedJsonUsesStablePublicDescription() {
    var response = handler.handleGenericException(
        new HttpMessageNotReadableException(
            "parser detail: secret-field", emptyInput()));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertNotNull(response.getBody());
    var message = response.getBody().getMessages().getFirst();
    assertEquals("REQUEST_ERROR", message.getCode());
    assertEquals("The request body is malformed or invalid.", message.getDescription());
  }

  @Test
  void ordinaryClientErrorsLogAtDebugWithoutThrowable() {
    var cases = List.<Runnable>of(
        () -> handler.handleInvalidRequestException(
            new InvalidRequestException("raw validation detail")),
        () -> handler.handleResourceNotFoundException(
            new ResourceNotFoundException("raw lookup detail")),
        () -> handler.handleResourceExistsException(
            new ResourceExistsException("raw conflict detail")),
        () -> handler.handleErrorResponseException(
            new ErrorResponseException(HttpStatus.NOT_ACCEPTABLE)),
        () -> handler.handleErrorResponseException(
            new ErrorResponseException(HttpStatus.UNSUPPORTED_MEDIA_TYPE)));

    for (var action : cases) {
      var event = capture(action);
      assertEquals(Level.DEBUG, event.getLevel());
      assertNull(event.getThrowableProxy());
    }
  }

  @Test
  void securityRelevantClientErrorsLogAtWarnWithoutThrowable() {
    var cases = List.<Runnable>of(
        () -> handler.handleInvalidTokenException(
            new InvalidTokenException("token internals")),
        () -> handler.handleAccessDeniedException(
            new AccessDeniedException("authorization internals")),
        () -> handler.handleErrorResponseException(
            new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS)));

    for (var action : cases) {
      var event = capture(action);
      assertEquals(Level.WARN, event.getLevel());
      assertNull(event.getThrowableProxy());
    }
  }

  @Test
  void unexpectedFailureLogsAtErrorWithThrowable() {
    var failure = new IllegalStateException("database unavailable");

    var event = capture(() -> handler.handleGenericException(failure));

    assertEquals(Level.ERROR, event.getLevel());
    assertNotNull(event.getThrowableProxy());
    assertEquals(failure.getClass().getName(), event.getThrowableProxy().getClassName());
  }

  @Test
  void frameworkServerFailureLogsAtErrorWithThrowable() {
    var failure = new ErrorResponseException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        new IllegalStateException("framework internals"));

    var event = capture(() -> handler.handleErrorResponseException(failure));

    assertEquals(Level.ERROR, event.getLevel());
    assertNotNull(event.getThrowableProxy());
    assertEquals(failure.getClass().getName(), event.getThrowableProxy().getClassName());
  }

  @Test
  void serviceUnavailableLogsAtErrorWithThrowable() {
    var failure = new ServiceUnavailableException(
        "storage unavailable", new IllegalStateException("database host secret"));

    var event = capture(() -> handler.handleServiceUnavailableException(failure));

    assertEquals(Level.ERROR, event.getLevel());
    assertNotNull(event.getThrowableProxy());
    assertEquals(failure.getClass().getName(), event.getThrowableProxy().getClassName());
  }

  @Test
  void domainFailuresUseStableDescriptions() {
    assertEquals("The request is invalid.", message(handler.handleInvalidRequestException(
        new InvalidRequestException("field secret"))));
    assertEquals("Authentication is required.", message(handler.handleInvalidTokenException(
        new InvalidTokenException("token secret"))));
    assertEquals("Access is denied.", message(handler.handleAccessDeniedException(
        new AccessDeniedException("policy secret"))));
    assertEquals("The resource already exists.", message(handler.handleResourceExistsException(
        new ResourceExistsException("index secret"))));
    assertEquals("The requested resource was not found.", message(
        handler.handleResourceNotFoundException(
            new ResourceNotFoundException("lookup secret"))));
  }

  private ILoggingEvent capture(Runnable action) {
    var logger = (Logger) LoggerFactory.getLogger(ControllerExceptionHandler.class);
    Level originalLevel = logger.getLevel();
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.setLevel(Level.TRACE);
    logger.addAppender(appender);
    try {
      action.run();
      assertEquals(1, appender.list.size());
      return appender.list.getFirst();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(originalLevel);
      appender.stop();
    }
  }

  private String message(Response<?> response) {
    return response.getMessages().getFirst().getDescription();
  }

  private HttpInputMessage emptyInput() {
    return new HttpInputMessage() {
      @Override public ByteArrayInputStream getBody() { return new ByteArrayInputStream(new byte[0]); }
      @Override public HttpHeaders getHeaders() { return new HttpHeaders(); }
    };
  }
}
