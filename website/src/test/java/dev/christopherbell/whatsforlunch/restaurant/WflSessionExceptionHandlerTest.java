package dev.christopherbell.whatsforlunch.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.whatsforlunch.restaurant.session.WflSessionConflictException;
import dev.christopherbell.whatsforlunch.restaurant.session.WflSessionExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WflSessionExceptionHandlerTest {
  private final WflSessionExceptionHandler handler = new WflSessionExceptionHandler();

  @Test
  void returnsStableConflictEnvelopeWithoutPersistenceDetails() {
    var response = handler.handleConflict(new WflSessionConflictException("WFL_SESSION_FULL"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().isSuccess()).isFalse();
    assertThat(response.getBody().getMessages()).singleElement().satisfies(message -> {
      assertThat(message.getCode()).isEqualTo("WFL_SESSION_FULL");
      assertThat(message.getDescription()).isEqualTo("This lunch session is full.");
      assertThat(message.getDescription()).doesNotContain("Mongo", "participantAccountIds");
    });
  }
}
