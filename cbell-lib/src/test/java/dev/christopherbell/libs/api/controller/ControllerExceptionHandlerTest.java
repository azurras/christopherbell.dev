package dev.christopherbell.libs.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.christopherbell.libs.api.exception.InternalServiceException;
import dev.christopherbell.libs.api.exception.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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
}
