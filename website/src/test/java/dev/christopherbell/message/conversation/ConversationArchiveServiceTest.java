package dev.christopherbell.message.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import dev.christopherbell.message.model.Message;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class ConversationArchiveServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
  @Mock private MongoTemplate mongo;

  @Test
  void archive_upsertsOnlyTheOwnersConversationView() {
    var service = new ConversationArchiveService(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo),
        Clock.fixed(NOW, ZoneOffset.UTC));

    var latest = Message.builder().id("latest-message").build();
    var latestEnvelope =
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
            .envelope(mongo, latest);
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("communications")))
        .thenReturn(latestEnvelope);
    when(mongo.insert(any(Document.class), eq("sessions")))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.archive("owner", "other:owner", Set.of("owner", "other"));

    var inserted = ArgumentCaptor.forClass(Document.class);
    verify(mongo).insert(inserted.capture(), eq("sessions"));
    assertThat(inserted.getValue().toString())
        .contains("_kind=conversation_archive_state", "ownerAccountId=owner",
            "conversationKey=other:owner", "participantIds", "owner", "other",
            "latest-message", "archivedAt");
    assertThat(result.conversationKey()).isEqualTo("other:owner");
    assertThat(result.archivedAt()).isEqualTo(NOW);
  }
}
