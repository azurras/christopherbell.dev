package dev.christopherbell.music.radio;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Stable application facade for independently versioned Music runtime state. */
@Component
public final class MusicRuntimeStateStore {
  private final MusicRuntimeStateRepository states;

  @Autowired
  public MusicRuntimeStateStore(MusicRuntimeStateRepository states) {
    this.states = states;
  }

  public Optional<MusicQueueState> findQueue() { return states.findQueue(); }
  public MusicQueueState saveQueue(MusicQueueState state) { return states.saveQueue(state); }
  public Optional<MusicRadioState> findRadio() { return states.findRadio(); }
  public MusicRadioState saveRadio(MusicRadioState state) { return states.saveRadio(state); }
}
