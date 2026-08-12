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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import dev.christopherbell.message.model.Message;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

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
    var archived = new ConversationArchiveState();
    archived.setId("owner:other:owner");
    archived.setOwnerAccountId("owner");
    archived.setConversationKey("other:owner");
    archived.setParticipantIds(Set.of("owner", "other"));
    archived.setArchivedThroughMessageId("latest-message");
    archived.setArchivedAt(NOW);
    var archivedEnvelope =
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
            .envelope(mongo, archived);
    when(mongo.findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(Document.class), eq("sessions")))
        .thenReturn(archivedEnvelope);

    var result = service.archive("owner", "other:owner", Set.of("owner", "other"));

    var query = ArgumentCaptor.forClass(Query.class);
    var update = ArgumentCaptor.forClass(UpdateDefinition.class);
    var options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
    verify(mongo).findAndModify(
        query.capture(), update.capture(), options.capture(),
        eq(Document.class), eq("sessions"));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("_kind=conversation_archive_state", "_id=Document",
            "kind=conversation_archive_state", "legacyId=owner:other:owner");
    assertThat(update.getValue().getUpdateObject().toString())
        .contains("ownerAccountId", "conversationKey", "participantIds",
            "latest-message", "archivedAt");
    assertThat(options.getValue().isUpsert()).isTrue();
    assertThat(options.getValue().isReturnNew()).isTrue();
    assertThat(result.conversationKey()).isEqualTo("other:owner");
    assertThat(result.archivedAt()).isEqualTo(NOW);
  }

  @Test
  void concurrentFirstArchiveCallsAreDuplicateSafe() throws Exception {
    var service = new ConversationArchiveService(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo),
        Clock.fixed(NOW, ZoneOffset.UTC));
    var latest = Message.builder().id("latest-message").build();
    var latestEnvelope =
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
            .envelope(mongo, latest);
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("communications")))
        .thenReturn(latestEnvelope);
    var winner = new ConversationArchiveState();
    winner.setId("owner:other:owner");
    winner.setOwnerAccountId("owner");
    winner.setConversationKey("other:owner");
    winner.setParticipantIds(Set.of("owner", "other"));
    winner.setArchivedThroughMessageId("latest-message");
    winner.setArchivedAt(NOW);
    var winnerEnvelope =
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
            .envelope(mongo, winner);
    when(mongo.findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(Document.class), eq("sessions")))
        .thenReturn(winnerEnvelope);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() ->
          service.archive("owner", "other:owner", Set.of("owner", "other")));
      var second = executor.submit(() ->
          service.archive("owner", "other:owner", Set.of("owner", "other")));

      assertThat(first.get(10, TimeUnit.SECONDS).archivedAt()).isEqualTo(NOW);
      assertThat(second.get(10, TimeUnit.SECONDS).archivedAt()).isEqualTo(NOW);
    }
    verify(mongo, org.mockito.Mockito.times(2)).findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(Document.class), eq("sessions"));
  }
}
