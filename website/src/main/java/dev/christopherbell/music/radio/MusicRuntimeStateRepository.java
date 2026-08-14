package dev.christopherbell.music.radio;

import java.util.Optional;

/** Persistence-neutral boundary for independently versioned Music queue and radio state. */
public interface MusicRuntimeStateRepository {
  Optional<MusicQueueState> findQueue();

  MusicQueueState saveQueue(MusicQueueState state);

  Optional<MusicRadioState> findRadio();

  MusicRadioState saveRadio(MusicRadioState state);
}
