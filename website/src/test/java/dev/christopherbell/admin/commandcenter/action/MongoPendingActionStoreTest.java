package dev.christopherbell.admin.commandcenter.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class MongoPendingActionStoreTest {
  private static final Instant NOW = Instant.parse("2026-07-29T20:00:00Z");
  private static final PendingActionStore.Reservation RESTART =
      new PendingActionStore.Reservation(
          CommandCenterActionType.RESTART_COMPUTER, NOW, NOW.plusSeconds(60));

  @Test
  void reserveUsesOneFixedKeyAndMapsAtomicContentionToFalse() {
    var mongo = mock(MongoTemplate.class);
    var store = new MongoPendingActionStore(mongo);
    when(mongo.findAndModify(
        any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(PendingActionDocument.class))).thenReturn(document(RESTART));

    assertThat(store.reserve(RESTART, NOW)).isTrue();

    var query = ArgumentCaptor.forClass(Query.class);
    var update = ArgumentCaptor.forClass(Update.class);
    verify(mongo).findAndModify(
        query.capture(), update.capture(), any(FindAndModifyOptions.class),
        eq(PendingActionDocument.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains(PendingActionDocument.ID, "executeAt", "$lte");
    assertThat(update.getValue().getUpdateObject().toString())
        .contains("RESTART_COMPUTER", "acceptedAt", "executeAt");

    when(mongo.findAndModify(
        any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(PendingActionDocument.class)))
        .thenThrow(new DuplicateKeyException("fixed pending action is active"));
    assertThat(store.reserve(new PendingActionStore.Reservation(
        CommandCenterActionType.SHUTDOWN_COMPUTER, NOW, NOW.plusSeconds(60)), NOW)).isFalse();
  }

  @Test
  void activeReturnsAnUnexpiredReservationWithoutAWrite() {
    var mongo = mock(MongoTemplate.class);
    var store = new MongoPendingActionStore(mongo);
    when(mongo.findById(PendingActionDocument.ID, PendingActionDocument.class))
        .thenReturn(document(RESTART));

    assertThat(store.active(NOW)).contains(RESTART);

    verify(mongo, never()).remove(any(Query.class), eq(PendingActionDocument.class));
  }

  @Test
  void activeClearsAnElapsedReservationByExactIdentityAndReturnsEmpty() {
    var mongo = mock(MongoTemplate.class);
    var store = new MongoPendingActionStore(mongo);
    var expired = new PendingActionStore.Reservation(
        CommandCenterActionType.SHUTDOWN_COMPUTER, NOW.minusSeconds(60), NOW);
    when(mongo.findById(PendingActionDocument.ID, PendingActionDocument.class))
        .thenReturn(document(expired));
    when(mongo.remove(any(Query.class), eq(PendingActionDocument.class)))
        .thenReturn(DeleteResult.acknowledged(1));

    assertThat(store.active(NOW)).isEmpty();

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).remove(query.capture(), eq(PendingActionDocument.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains(
            PendingActionDocument.ID, "SHUTDOWN_COMPUTER", "acceptedAt", "executeAt");
  }

  @Test
  void clearRequiresTheExactActionAndBothTimestamps() {
    var mongo = mock(MongoTemplate.class);
    var store = new MongoPendingActionStore(mongo);
    when(mongo.remove(any(Query.class), eq(PendingActionDocument.class)))
        .thenReturn(DeleteResult.acknowledged(1));

    assertThat(store.clear(RESTART)).isTrue();

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).remove(query.capture(), eq(PendingActionDocument.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains(
            PendingActionDocument.ID, "RESTART_COMPUTER", "acceptedAt", "executeAt");
  }

  private static PendingActionDocument document(PendingActionStore.Reservation reservation) {
    var document = new PendingActionDocument();
    document.setId(PendingActionDocument.ID);
    document.setAction(reservation.action());
    document.setAcceptedAt(reservation.acceptedAt());
    document.setExecuteAt(reservation.executeAt());
    return document;
  }
}
