package dev.christopherbell.music.radio;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Owns exact-identity persistence for the two independently versioned runtime documents.
 * The kind-scoped boundary owns independent optimistic compare-and-set for each legacy identity.
 */
@Component
public final class MusicRuntimeStateStore {
  private final KindScopedMongoOperations<MusicRuntimeStateDocument> states;

  public MusicRuntimeStateStore(DomainMongoOperationsFactory factory) {
    this.states = factory.forType(MusicRuntimeStateDocument.class);
  }

  public Optional<MusicQueueState> findQueue() {
    return states.findById(MusicRuntimeStateDocument.QUEUE_ID)
        .map(MusicRuntimeStateDocument::toQueueState);
  }

  public MusicQueueState saveQueue(MusicQueueState state) {
    return states.save(MusicRuntimeStateDocument.forQueue(state)).toQueueState();
  }

  public Optional<MusicRadioState> findRadio() {
    return states.findById(MusicRuntimeStateDocument.RADIO_ID)
        .map(MusicRuntimeStateDocument::toRadioState);
  }

  public MusicRadioState saveRadio(MusicRadioState state) {
    return states.save(MusicRuntimeStateDocument.forRadio(state)).toRadioState();
  }
}
