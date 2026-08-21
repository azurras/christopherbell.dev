package dev.christopherbell.message;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.message.model.Message;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

/** Identical ordering and participant assertions run against both persistence engines. */
interface MessageRepositoryParityContract {
  Instant CREATED = Instant.parse("2026-08-13T14:00:00Z");

  MessageRepository parityMessages();

  @Test
  default void parityPreservesConversationAndParticipantOrdering() {
    parityMessages().save(message("message-parity-a", CREATED));
    parityMessages().save(message("message-parity-b", CREATED.plusSeconds(1)));

    assertThat(parityMessages().findByConversationKeyOrderByCreatedOnAsc(
        "message-parity-owner:message-parity-recipient", PageRequest.of(0, 10)))
        .extracting(Message::getId).containsExactly("message-parity-a", "message-parity-b");
    assertThat(parityMessages().findByParticipantIdsContainingOrderByCreatedOnDesc(
        "message-parity-recipient", PageRequest.of(0, 10)))
        .extracting(Message::getId).containsExactly("message-parity-b", "message-parity-a");
  }

  private static Message message(String id, Instant createdOn) {
    return Message.builder().id(id)
        .conversationKey("message-parity-owner:message-parity-recipient")
        .participantIds(Set.of("message-parity-owner", "message-parity-recipient"))
        .senderAccountId("message-parity-owner")
        .recipientAccountId("message-parity-recipient")
        .text(id).read(false).createdOn(createdOn).build();
  }
}
