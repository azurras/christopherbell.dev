package dev.christopherbell.music.radio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MusicRuntimeStateDocumentTest {
  @Test
  void queueRoundTripPreservesEntriesAndVersion() {
    var entry = new MusicQueueState.Entry(
        "entry-1", "track-1", "token-1", "account-1", Instant.EPOCH);
    var state = new MusicQueueState(MusicQueueState.ID, List.of(entry), 7L);

    var document = MusicRuntimeStateDocument.forQueue(state);

    assertThat(document.id()).isEqualTo(MusicRuntimeStateDocument.QUEUE_ID);
    assertThat(document.kind()).isEqualTo(MusicRuntimeStateDocument.Kind.QUEUE);
    assertThat(document.toQueueState()).isEqualTo(state);
    assertThat(document.version()).isEqualTo(7L);
  }

  @Test
  void radioRoundTripPreservesTimelineAndVersion() {
    var state = new MusicRadioState(
        MusicRadioState.ID, 12, "track-1", "token-1", Instant.EPOCH, 90,
        MusicRadioState.Source.QUEUE, "entry-1", 8L);

    var document = MusicRuntimeStateDocument.forRadio(state);

    assertThat(document.id()).isEqualTo(MusicRuntimeStateDocument.RADIO_ID);
    assertThat(document.kind()).isEqualTo(MusicRuntimeStateDocument.Kind.RADIO);
    assertThat(document.toRadioState()).isEqualTo(state);
    assertThat(document.version()).isEqualTo(8L);
  }

  @Test
  void rejectsMissingQueueEntries() {
    assertThatThrownBy(() -> new MusicRuntimeStateDocument.QueuePayload(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMixedIdentityKindAndPayload() {
    var queue = new MusicRuntimeStateDocument.QueuePayload(List.of());
    var radio = new MusicRuntimeStateDocument.RadioPayload(
        1, "track-1", "token-1", Instant.EPOCH, 90,
        MusicRadioState.Source.RADIO, null);

    assertThatThrownBy(() -> new MusicRuntimeStateDocument(
        MusicRuntimeStateDocument.QUEUE_ID,
        MusicRuntimeStateDocument.Kind.QUEUE,
        queue,
        radio,
        0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MusicRuntimeStateDocument(
        MusicRuntimeStateDocument.RADIO_ID,
        MusicRuntimeStateDocument.Kind.QUEUE,
        queue,
        null,
        0L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
