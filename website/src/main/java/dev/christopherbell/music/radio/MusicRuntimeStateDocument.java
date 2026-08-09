package dev.christopherbell.music.radio;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** Collision-proof queue or radio state stored in the shared Music runtime collection. */
@Document(MusicRuntimeStateDocument.COLLECTION)
public record MusicRuntimeStateDocument(
    @Id String id,
    Kind kind,
    QueuePayload queue,
    RadioPayload radio,
    @Version Long version) {
  public static final String COLLECTION = "music_runtime_state";
  public static final String QUEUE_ID = "queue";
  public static final String RADIO_ID = "radio";

  public MusicRuntimeStateDocument {
    boolean validQueue = QUEUE_ID.equals(id) && kind == Kind.QUEUE
        && queue != null && radio == null;
    boolean validRadio = RADIO_ID.equals(id) && kind == Kind.RADIO
        && queue == null && radio != null;
    if ((!validQueue && !validRadio) || (version != null && version < 0)) {
      throw new IllegalArgumentException("Music runtime state is invalid.");
    }
  }

  public static MusicRuntimeStateDocument forQueue(MusicQueueState state) {
    return new MusicRuntimeStateDocument(
        QUEUE_ID, Kind.QUEUE, new QueuePayload(state.entries()), null, state.version());
  }

  public static MusicRuntimeStateDocument forRadio(MusicRadioState state) {
    return new MusicRuntimeStateDocument(
        RADIO_ID, Kind.RADIO, null, RadioPayload.from(state), state.version());
  }

  public MusicQueueState toQueueState() {
    if (kind != Kind.QUEUE) {
      throw new IllegalStateException("Music runtime state is not queue state.");
    }
    return new MusicQueueState(MusicQueueState.ID, queue.entries(), version);
  }

  public MusicRadioState toRadioState() {
    if (kind != Kind.RADIO) {
      throw new IllegalStateException("Music runtime state is not radio state.");
    }
    return radio.toState(version);
  }

  public enum Kind { QUEUE, RADIO }

  public record QueuePayload(List<MusicQueueState.Entry> entries) {
    public QueuePayload {
      entries = entries == null ? List.of() : List.copyOf(entries);
      new MusicQueueState(MusicQueueState.ID, entries, null);
    }
  }

  public record RadioPayload(
      long stationSequence,
      String trackId,
      String observedToken,
      Instant startedAt,
      double durationSeconds,
      MusicRadioState.Source source,
      String queueEntryId) {
    public RadioPayload {
      new MusicRadioState(
          MusicRadioState.ID, stationSequence, trackId, observedToken, startedAt,
          durationSeconds, source, queueEntryId, null);
    }

    static RadioPayload from(MusicRadioState state) {
      return new RadioPayload(
          state.stationSequence(), state.trackId(), state.observedToken(), state.startedAt(),
          state.durationSeconds(), state.source(), state.queueEntryId());
    }

    MusicRadioState toState(Long version) {
      return new MusicRadioState(
          MusicRadioState.ID, stationSequence, trackId, observedToken, startedAt,
          durationSeconds, source, queueEntryId, version);
    }
  }
}
