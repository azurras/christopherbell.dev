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
import org.bson.Document;
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
    var store = new MongoPendingActionStore(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo));
    when(mongo.insert(any(Document.class), eq("admin_activity")))
        .thenAnswer(invocation -> invocation.getArgument(0))
        .thenThrow(new DuplicateKeyException("fixed pending action is active"));

    assertThat(store.reserve(RESTART, NOW)).isTrue();

    var inserted = ArgumentCaptor.forClass(Document.class);
    verify(mongo).insert(inserted.capture(), eq("admin_activity"));
    assertThat(inserted.getValue().toString())
        .contains("_kind=pending_action", PendingActionDocument.ID, "RESTART_COMPUTER",
            "acceptedAt", "executeAt");
    assertThat(store.reserve(new PendingActionStore.Reservation(
        CommandCenterActionType.SHUTDOWN_COMPUTER, NOW, NOW.plusSeconds(60)), NOW)).isFalse();
    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).findAndModify(
        query.capture(), any(Update.class), any(FindAndModifyOptions.class),
        eq(Document.class), eq("admin_activity"));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("_kind=pending_action", "_id.legacyId", PendingActionDocument.ID,
            "payload.executeAt", "$lte");
  }

  @Test
  void activeReturnsAnUnexpiredReservationWithoutAWrite() {
    var mongo = mock(MongoTemplate.class);
    var store = new MongoPendingActionStore(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo));
    var envelope = dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
        .envelope(mongo, document(RESTART));
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("admin_activity")))
        .thenReturn(envelope);

    assertThat(store.active(NOW)).contains(RESTART);

    verify(mongo, never()).remove(any(Query.class), eq(Document.class), eq("admin_activity"));
  }

  @Test
  void activeClearsAnElapsedReservationByExactIdentityAndReturnsEmpty() {
    var mongo = mock(MongoTemplate.class);
    var store = new MongoPendingActionStore(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo));
    var expired = new PendingActionStore.Reservation(
        CommandCenterActionType.SHUTDOWN_COMPUTER, NOW.minusSeconds(60), NOW);
    var envelope = dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory
        .envelope(mongo, document(expired));
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("admin_activity")))
        .thenReturn(envelope);
    when(mongo.remove(any(Query.class), eq(Document.class), eq("admin_activity")))
        .thenReturn(DeleteResult.acknowledged(1));

    assertThat(store.active(NOW)).isEmpty();

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).remove(query.capture(), eq(Document.class), eq("admin_activity"));
    assertThat(query.getValue().getQueryObject().toString())
        .contains(
            PendingActionDocument.ID, "SHUTDOWN_COMPUTER", "acceptedAt", "executeAt");
  }

  @Test
  void clearRequiresTheExactActionAndBothTimestamps() {
    var mongo = mock(MongoTemplate.class);
    var store = new MongoPendingActionStore(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo));
    when(mongo.remove(any(Query.class), eq(Document.class), eq("admin_activity")))
        .thenReturn(DeleteResult.acknowledged(1));

    assertThat(store.clear(RESTART)).isTrue();

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).remove(query.capture(), eq(Document.class), eq("admin_activity"));
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
