package dev.christopherbell.music.radio;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import java.util.Optional;

/** MongoDB transition adapter for independently versioned Music runtime state. */
@MongoPersistence
public class MongoMusicRuntimeStateRepository implements MusicRuntimeStateRepository {
  private final KindScopedMongoOperations<MusicRuntimeStateDocument> states;

  public MongoMusicRuntimeStateRepository(DomainMongoOperationsFactory factory) {
    this.states = factory.forType(MusicRuntimeStateDocument.class);
  }

  @Override public Optional<MusicQueueState> findQueue() {
    return states.findById(MusicRuntimeStateDocument.QUEUE_ID)
        .map(MusicRuntimeStateDocument::toQueueState);
  }

  @Override public MusicQueueState saveQueue(MusicQueueState state) {
    return states.save(MusicRuntimeStateDocument.forQueue(state)).toQueueState();
  }

  @Override public Optional<MusicRadioState> findRadio() {
    return states.findById(MusicRuntimeStateDocument.RADIO_ID)
        .map(MusicRuntimeStateDocument::toRadioState);
  }

  @Override public MusicRadioState saveRadio(MusicRadioState state) {
    return states.save(MusicRuntimeStateDocument.forRadio(state)).toRadioState();
  }
}
