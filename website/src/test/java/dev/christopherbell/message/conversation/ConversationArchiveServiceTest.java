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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith(MockitoExtension.class)
class ConversationArchiveServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
  @Mock private MongoTemplate mongo;

  @Test
  void archive_upsertsOnlyTheOwnersConversationView() {
    var service = new ConversationArchiveService(
        mongo, Clock.fixed(NOW, ZoneOffset.UTC));

    when(mongo.findOne(any(Query.class), eq(Message.class)))
        .thenReturn(Message.builder().id("latest-message").build());

    var result = service.archive("owner", "other:owner", Set.of("owner", "other"));

    var query = ArgumentCaptor.forClass(Query.class);
    var update = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).upsert(query.capture(), update.capture(), eq(ConversationArchiveState.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("ownerAccountId=owner", "conversationKey=other:owner");
    assertThat(update.getValue().getUpdateObject().toString())
        .contains("participantIds", "owner", "other", "latest-message", NOW.toString());
    assertThat(result.conversationKey()).isEqualTo("other:owner");
    assertThat(result.archivedAt()).isEqualTo(NOW);
  }
}
