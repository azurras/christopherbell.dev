package dev.christopherbell.notification.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationDetail;
import dev.christopherbell.notification.model.NotificationType;
import dev.christopherbell.pagination.StableCursor;
import dev.christopherbell.pagination.StableCursorCodec;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class NotificationQueryRepositoryTest {
  @Mock private MongoTemplate mongo;
  private StableCursorCodec cursorCodec;
  private NotificationQueryRepository repository;

  @BeforeEach
  void setUp() {
    cursorCodec = new StableCursorCodec();
    repository = new NotificationQueryRepository(mongo, cursorCodec);
  }

  @Test
  @DisplayName("Notification pages use the timestamp and id tie-breaker")
  void page_usesStableDescendingCursorAndReturnsMetadata() throws Exception {
    var timestamp = Instant.parse("2026-07-26T12:00:00Z");
    var first = notification("n2", timestamp);
    var extra = notification("n1", timestamp.minusSeconds(1));
    when(mongo.find(any(Query.class), eq(Notification.class))).thenReturn(List.of(first, extra));

    var result = repository.page(
        "recipient", Optional.of(new StableCursor(timestamp, "n3")), 1);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Notification.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("accountId=recipient", "createdOn", "$lt", "_id");
    assertThat(query.getValue().getSortObject().toString())
        .contains("createdOn=-1", "_id=-1");
    assertThat(query.getValue().getLimit()).isEqualTo(2);
    assertThat(result.items()).extracting(NotificationDetail::id).containsExactly("n2");
    assertThat(cursorCodec.decode(result.nextCursor()))
        .contains(new StableCursor(timestamp, "n2"));
  }

  @Test
  @DisplayName("Mark all read updates only unread rows owned by the caller")
  void markAllRead_isAtomicAndOwnerScoped() {
    when(mongo.updateMulti(any(Query.class), any(Update.class), eq(Notification.class)))
        .thenReturn(UpdateResult.acknowledged(4, 3L, null));

    var result = repository.markAllRead("recipient");

    var query = ArgumentCaptor.forClass(Query.class);
    var update = ArgumentCaptor.forClass(Update.class);
    verify(mongo).updateMulti(query.capture(), update.capture(), eq(Notification.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("accountId=recipient", "read", "$ne");
    assertThat(update.getValue().getUpdateObject().toString()).contains("read=true");
    assertThat(result.updatedCount()).isEqualTo(3);
  }

  private Notification notification(String id, Instant createdOn) {
    return Notification.builder()
        .id(id)
        .accountId("recipient")
        .actorAccountId("actor")
        .actorUsername("writer")
        .notificationType(NotificationType.MENTION)
        .read(false)
        .createdOn(createdOn)
        .build();
  }
}
