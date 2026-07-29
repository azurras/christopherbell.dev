package dev.christopherbell.libs.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.InternalServiceException;
import dev.christopherbell.libs.api.exception.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.io.ByteArrayInputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
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
  void routineClientErrorLogsWithoutErrorLevelOrThrowable() {
    var logger = (Logger) LoggerFactory.getLogger(ControllerExceptionHandler.class);
    var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      handler.handleInvalidRequestException(new InvalidRequestException("invalid value"));

      assertEquals(1, appender.list.size());
      assertEquals(Level.WARN, appender.list.getFirst().getLevel());
      assertEquals(null, appender.list.getFirst().getThrowableProxy());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  private HttpInputMessage emptyInput() {
    return new HttpInputMessage() {
      @Override public ByteArrayInputStream getBody() { return new ByteArrayInputStream(new byte[0]); }
      @Override public HttpHeaders getHeaders() { return new HttpHeaders(); }
    };
  }
}
