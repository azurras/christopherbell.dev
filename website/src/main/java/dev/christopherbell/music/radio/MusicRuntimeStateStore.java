package dev.christopherbell.music.radio;

import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Owns exact-identity persistence for the two independently versioned runtime documents.
 * Migrated versionless envelopes acquire version zero through a non-upsert compare-and-set.
 */
@Component
public final class MusicRuntimeStateStore {
  private final MongoTemplate mongo;

  public MusicRuntimeStateStore(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  public Optional<MusicQueueState> findQueue() {
    return Optional.ofNullable(mongo.findById(
        MusicRuntimeStateDocument.QUEUE_ID,
        MusicRuntimeStateDocument.class,
        MusicRuntimeStateDocument.COLLECTION)).map(MusicRuntimeStateDocument::toQueueState);
  }

  public MusicQueueState saveQueue(MusicQueueState state) {
    if (state.version() == null) {
      var initialized = initializeVersionlessQueue(state);
      if (initialized != null) {
        return initialized.toQueueState();
      }
      requireAbsentOrThrowConflict(MusicRuntimeStateDocument.QUEUE_ID);
    }
    return mongo.save(
        MusicRuntimeStateDocument.forQueue(state),
        MusicRuntimeStateDocument.COLLECTION).toQueueState();
  }

  public Optional<MusicRadioState> findRadio() {
    return Optional.ofNullable(mongo.findById(
        MusicRuntimeStateDocument.RADIO_ID,
        MusicRuntimeStateDocument.class,
        MusicRuntimeStateDocument.COLLECTION)).map(MusicRuntimeStateDocument::toRadioState);
  }

  public MusicRadioState saveRadio(MusicRadioState state) {
    if (state.version() == null) {
      var initialized = initializeVersionlessRadio(state);
      if (initialized != null) {
        return initialized.toRadioState();
      }
      requireAbsentOrThrowConflict(MusicRuntimeStateDocument.RADIO_ID);
    }
    return mongo.save(
        MusicRuntimeStateDocument.forRadio(state),
        MusicRuntimeStateDocument.COLLECTION).toRadioState();
  }

  private MusicRuntimeStateDocument initializeVersionlessQueue(MusicQueueState state) {
    return initializeVersionless(
        MusicRuntimeStateDocument.QUEUE_ID,
        MusicRuntimeStateDocument.Kind.QUEUE,
        new Update()
            .set("queue", new MusicRuntimeStateDocument.QueuePayload(state.entries()))
            .set("version", 0L));
  }

  private MusicRuntimeStateDocument initializeVersionlessRadio(MusicRadioState state) {
    return initializeVersionless(
        MusicRuntimeStateDocument.RADIO_ID,
        MusicRuntimeStateDocument.Kind.RADIO,
        new Update()
            .set("radio", new MusicRuntimeStateDocument.RadioPayload(
                state.stationSequence(), state.trackId(), state.observedToken(), state.startedAt(),
                state.durationSeconds(), state.source(), state.queueEntryId()))
            .set("version", 0L));
  }

  private MusicRuntimeStateDocument initializeVersionless(
      String id, MusicRuntimeStateDocument.Kind kind, Update update) {
    Query query = Query.query(Criteria.where("_id").is(id)
        .and("kind").is(kind.name())
        .and("version").exists(false));
    return mongo.findAndModify(
        query,
        update,
        FindAndModifyOptions.options().returnNew(true),
        MusicRuntimeStateDocument.class,
        MusicRuntimeStateDocument.COLLECTION);
  }

  private void requireAbsentOrThrowConflict(String id) {
    if (mongo.exists(
        Query.query(Criteria.where("_id").is(id)), MusicRuntimeStateDocument.COLLECTION)) {
      throw new OptimisticLockingFailureException(
          "Music runtime state changed during version initialization.");
    }
  }
}
