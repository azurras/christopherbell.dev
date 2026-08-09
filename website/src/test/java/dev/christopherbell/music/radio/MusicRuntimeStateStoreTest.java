package dev.christopherbell.music.radio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith(MockitoExtension.class)
class MusicRuntimeStateStoreTest {
  @Mock private MongoTemplate mongo;
  @Captor private ArgumentCaptor<Query> query;
  @Captor private ArgumentCaptor<UpdateDefinition> update;
  @Captor private ArgumentCaptor<FindAndModifyOptions> options;

  @Test
  void firstQueueSaveAtomicallyInitializesAnExistingVersionlessEnvelope() {
    var state = queue(null, "entry-1");
    var persisted = MusicRuntimeStateDocument.forQueue(queue(0L, "entry-1"));
    when(mongo.findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(MusicRuntimeStateDocument.class), eq(MusicRuntimeStateDocument.COLLECTION)))
        .thenReturn(persisted);

    var saved = new MusicRuntimeStateStore(mongo).saveQueue(state);

    assertThat(saved).isEqualTo(persisted.toQueueState());
    verify(mongo).findAndModify(
        query.capture(), update.capture(), options.capture(),
        eq(MusicRuntimeStateDocument.class), eq(MusicRuntimeStateDocument.COLLECTION));
    assertVersionlessCas(query.getValue(), "queue", "QUEUE");
    assertThat(update.getValue().getUpdateObject().get("$set", Document.class))
        .containsEntry("version", 0L)
        .containsKey("queue")
        .doesNotContainKey("radio");
    assertThat(options.getValue().isReturnNew()).isTrue();
    assertThat(options.getValue().isUpsert()).isFalse();
    verify(mongo, never()).save(any(), eq(MusicRuntimeStateDocument.COLLECTION));
  }

  @Test
  void firstRadioSaveUsesOnlyTheRadioIdentityKindAndPayload() {
    var state = radio(null);
    var persisted = MusicRuntimeStateDocument.forRadio(radio(0L));
    when(mongo.findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(MusicRuntimeStateDocument.class), eq(MusicRuntimeStateDocument.COLLECTION)))
        .thenReturn(persisted);

    var saved = new MusicRuntimeStateStore(mongo).saveRadio(state);

    assertThat(saved).isEqualTo(persisted.toRadioState());
    verify(mongo).findAndModify(
        query.capture(), update.capture(), options.capture(),
        eq(MusicRuntimeStateDocument.class), eq(MusicRuntimeStateDocument.COLLECTION));
    assertVersionlessCas(query.getValue(), "radio", "RADIO");
    assertThat(update.getValue().getUpdateObject().get("$set", Document.class))
        .containsEntry("version", 0L)
        .containsKey("radio")
        .doesNotContainKey("queue");
  }

  @Test
  void staleVersionlessSaveCannotOverwriteAnExistingEnvelope() {
    when(mongo.findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(MusicRuntimeStateDocument.class), eq(MusicRuntimeStateDocument.COLLECTION)))
        .thenReturn(null);
    when(mongo.exists(any(Query.class), eq(MusicRuntimeStateDocument.COLLECTION)))
        .thenReturn(true);

    assertThatThrownBy(() -> new MusicRuntimeStateStore(mongo).saveQueue(queue(null, "stale")))
        .isInstanceOf(OptimisticLockingFailureException.class);

    verify(mongo, never()).save(any(), eq(MusicRuntimeStateDocument.COLLECTION));
  }

  @Test
  void genuinelyAbsentVersionlessQueueRetainsNormalInsertSemantics() {
    var requested = queue(null, "new-entry");
    var persisted = MusicRuntimeStateDocument.forQueue(queue(0L, "new-entry"));
    when(mongo.findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(MusicRuntimeStateDocument.class), eq(MusicRuntimeStateDocument.COLLECTION)))
        .thenReturn(null);
    when(mongo.exists(any(Query.class), eq(MusicRuntimeStateDocument.COLLECTION)))
        .thenReturn(false);
    when(mongo.save(
        MusicRuntimeStateDocument.forQueue(requested), MusicRuntimeStateDocument.COLLECTION))
        .thenReturn(persisted);

    var saved = new MusicRuntimeStateStore(mongo).saveQueue(requested);

    assertThat(saved).isEqualTo(persisted.toQueueState());
  }

  @Test
  void alreadyVersionedStateRetainsNormalOptimisticSaveSemantics() {
    var requested = queue(4L, "entry-1");
    var persisted = MusicRuntimeStateDocument.forQueue(queue(5L, "entry-1"));
    when(mongo.save(
        MusicRuntimeStateDocument.forQueue(requested), MusicRuntimeStateDocument.COLLECTION))
        .thenReturn(persisted);

    var saved = new MusicRuntimeStateStore(mongo).saveQueue(requested);

    assertThat(saved).isEqualTo(persisted.toQueueState());
    verify(mongo, never()).findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(MusicRuntimeStateDocument.class), eq(MusicRuntimeStateDocument.COLLECTION));
  }

  private static void assertVersionlessCas(Query query, String id, String kind) {
    assertThat(query.getQueryObject())
        .containsEntry("_id", id)
        .containsEntry("kind", kind);
    assertThat(query.getQueryObject().get("version", Document.class))
        .containsEntry("$exists", false);
  }

  private static MusicQueueState queue(Long version, String entryId) {
    var entry = new MusicQueueState.Entry(
        entryId, "track-1", "token-1", "account-1", Instant.EPOCH);
    return new MusicQueueState(MusicQueueState.ID, List.of(entry), version);
  }

  private static MusicRadioState radio(Long version) {
    return new MusicRadioState(
        MusicRadioState.ID, 3, "track-1", "token-1", Instant.EPOCH, 90,
        MusicRadioState.Source.RADIO, null, version);
  }
}
