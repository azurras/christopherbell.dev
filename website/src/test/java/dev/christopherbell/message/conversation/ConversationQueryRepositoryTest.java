package dev.christopherbell.message.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.message.model.Message;
import dev.christopherbell.pagination.StableCursor;
import dev.christopherbell.pagination.StableCursorCodec;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class ConversationQueryRepositoryTest {
  @Mock private MongoTemplate mongo;
  private StableCursorCodec cursorCodec;
  private ConversationQueryRepository repository;

  @BeforeEach
  void setUp() {
    cursorCodec = new StableCursorCodec();
    repository = new ConversationQueryRepository(mongo, cursorCodec);
  }

  @Test
  @DisplayName("Conversation summaries aggregate one latest message per distinct visible thread")
  void latestDistinctVisible_usesMongoGroupingAndOwnerArchiveLookup() {
    var latest = message("m3", "a:b", "2026-07-26T12:00:00Z");
    when(mongo.aggregate(any(Aggregation.class), eq("messages"), eq(Message.class)))
        .thenReturn(new AggregationResults<>(List.of(latest), new Document()));

    assertThat(repository.latestDistinctVisible("a", 30)).containsExactly(latest);

    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(aggregation.capture(), eq("messages"), eq(Message.class));
    assertThat(aggregation.getValue().toString())
        .contains("$group", "conversationKey", "$lookup", "conversation_archive_states")
        .contains("ownerAccountId", "$limit");
  }

  @Test
  @DisplayName("Unread counts group all requested senders in one immutable result")
  void unreadCounts_groupsAllRequestedSendersInOneAggregation() {
    when(mongo.aggregate(
            any(Aggregation.class), eq("messages"), eq(ConversationUnreadCount.class)))
        .thenReturn(new AggregationResults<>(
            List.of(new ConversationUnreadCount("other-a", 2L)), new Document()));

    var counts = repository.unreadCounts("self", List.of("other-a", "other-b"));

    assertThat(counts).containsExactlyEntriesOf(java.util.Map.of("other-a", 2L));
    assertThatThrownBy(() -> counts.put("other-b", 1L))
        .isInstanceOf(UnsupportedOperationException.class);
    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(
        aggregation.capture(), eq("messages"), eq(ConversationUnreadCount.class));
    assertThat(aggregation.getValue().toString())
        .contains(
            "recipientAccountId",
            "self",
            "senderAccountId",
            "$in",
            "other-a",
            "other-b",
            "read",
            "false",
            "$group",
            "count",
            "$sum");
  }

  @Test
  @DisplayName("Unread counts skip Mongo when no senders are requested")
  void unreadCounts_returnsEmptyWithoutQueryForNoSenders() {
    assertThat(repository.unreadCounts("self", List.of())).isEmpty();

    verifyNoInteractions(mongo);
  }

  @Test
  @DisplayName("Conversation history uses timestamp and id tie-breakers and returns a next cursor")
  void page_usesStableDescendingCursorAndReturnsChronologicalItems() throws Exception {
    var timestamp = Instant.parse("2026-07-26T12:00:00Z");
    var older = message("m1", "a:b", "2026-07-26T11:59:00Z");
    var boundary = message("m2", "a:b", timestamp.toString());
    when(mongo.find(any(Query.class), eq(Message.class))).thenReturn(List.of(boundary, older));

    var result = repository.page(
        "a:b", Optional.of(new StableCursor(timestamp, "m3")), 1);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Message.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("conversationKey=a:b", "createdOn", "$lt", "_id");
    assertThat(query.getValue().getSortObject().toString())
        .contains("createdOn=-1", "_id=-1");
    assertThat(query.getValue().getLimit()).isEqualTo(2);
    assertThat(result.items()).containsExactly(boundary);
    assertThat(cursorCodec.decode(result.nextCursor()))
        .contains(new StableCursor(timestamp, "m2"));
  }

  private Message message(String id, String conversationKey, String createdOn) {
    return Message.builder()
        .id(id)
        .conversationKey(conversationKey)
        .senderAccountId("a")
        .recipientAccountId("b")
        .participantIds(java.util.Set.of("a", "b"))
        .text(id)
        .read(false)
        .createdOn(Instant.parse(createdOn))
        .build();
  }
}
