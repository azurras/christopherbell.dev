package dev.christopherbell.music.radio;

import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Owns exact-identity persistence for the two independently versioned runtime documents. */
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
    return mongo.save(
        MusicRuntimeStateDocument.forRadio(state),
        MusicRuntimeStateDocument.COLLECTION).toRadioState();
  }
}
