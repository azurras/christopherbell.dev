package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.music.radio.MusicQueueState;
import dev.christopherbell.music.radio.MusicRadioState;
import dev.christopherbell.music.radio.MusicRuntimeStateDocument;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class V014ConsolidateMusicRuntimeStateTest {
  private static final String LEGACY_QUEUE = "music_queue_state";
  private static final String LEGACY_RADIO = "music_radio_state";
  private static final String TARGET = "music_runtime_state";

  @Mock private MongoTemplate mongo;
  @Captor private ArgumentCaptor<Collection<MusicRuntimeStateDocument>> inserted;

  @Test
  void exposesImmutableMigrationIdentity() {
    var migration = new V014ConsolidateMusicRuntimeState();

    assertThat(migration.id()).isEqualTo("014-consolidate-music-runtime-state");
    assertThat(migration.checksum())
        .isEqualTo("11a69bdd4556cfc38060ccdda5075fb9d6bc36f1cc414edd7b26cd61a74b5cbb");
    assertThat(migration.description()).isEqualTo("Consolidate Music queue and radio runtime state");
  }

  @Test
  void copiesValidatedSourcesInTargetOrderAndPreservesLogicalStateAndVersions() {
    var queue = queue(4L);
    var radio = radio(9L);
    var expectedQueue = queueDocument(queue);
    var expectedRadio = radioDocument(radio);
    validSources(queue, radio);
    when(mongo.findAll(MusicRuntimeStateDocument.class, TARGET))
        .thenReturn(List.of())
        .thenReturn(List.of(expectedQueue, expectedRadio));

    new V014ConsolidateMusicRuntimeState().apply(mongo);

    verify(mongo).insert(inserted.capture(), eq(TARGET));
    assertThat(inserted.getValue()).containsExactly(expectedQueue, expectedRadio);
    verify(mongo).count(any(Query.class), eq(LEGACY_QUEUE));
    verify(mongo).count(any(Query.class), eq(LEGACY_RADIO));
    verify(mongo).findById("global", MusicQueueState.class, LEGACY_QUEUE);
    verify(mongo).findById("global", MusicRadioState.class, LEGACY_RADIO);
    verify(mongo, times(2)).findAll(MusicRuntimeStateDocument.class, TARGET);
    verifyNoMoreInteractions(mongo);
  }

  @Test
  void acceptsOnlyACompleteEquivalentDestinationRegardlessOfReadOrder() {
    var queue = queue(4L);
    var radio = radio(9L);
    validSources(queue, radio);
    when(mongo.findAll(MusicRuntimeStateDocument.class, TARGET))
        .thenReturn(List.of(radioDocument(radio), queueDocument(queue)));

    assertThatCode(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .doesNotThrowAnyException();

    verifyNoTargetInsert();
  }

  @Test
  void rejectsPartialDestinationBeforeWriting() {
    var queue = queue(4L);
    var radio = radio(9L);
    validSources(queue, radio);
    when(mongo.findAll(MusicRuntimeStateDocument.class, TARGET))
        .thenReturn(List.of(queueDocument(queue)));

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("destination");

    verifyNoTargetInsert();
  }

  @Test
  void rejectsDuplicateDestinationIdentityBeforeWriting() {
    var queue = queue(4L);
    var radio = radio(9L);
    validSources(queue, radio);
    when(mongo.findAll(MusicRuntimeStateDocument.class, TARGET))
        .thenReturn(List.of(queueDocument(queue), queueDocument(queue)));

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate");

    verifyNoTargetInsert();
  }

  @Test
  void rejectsExtraDestinationStateBeforeWriting() {
    var queue = queue(4L);
    var radio = radio(9L);
    validSources(queue, radio);
    when(mongo.findAll(MusicRuntimeStateDocument.class, TARGET))
        .thenReturn(List.of(
            queueDocument(queue),
            radioDocument(radio),
            queueDocument(queue)));

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("destination");

    verifyNoTargetInsert();
  }

  @Test
  void rejectsDivergentDestinationPayloadOrVersionBeforeWriting() {
    var queue = queue(4L);
    var radio = radio(9L);
    validSources(queue, radio);
    when(mongo.findAll(MusicRuntimeStateDocument.class, TARGET))
        .thenReturn(List.of(queueDocument(queue(5L)), radioDocument(radio)));

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("diverges");

    verifyNoTargetInsert();
  }

  @Test
  void propagatesMalformedDestinationMappingFailureBeforeWriting() {
    var queue = queue(4L);
    var radio = radio(9L);
    validSources(queue, radio);
    when(mongo.findAll(MusicRuntimeStateDocument.class, TARGET))
        .thenThrow(new IllegalArgumentException("malformed destination"));

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed destination");

    verifyNoTargetInsert();
  }

  @Test
  void rejectsMalformedNullDestinationDocumentBeforeWriting() {
    var queue = queue(4L);
    var radio = radio(9L);
    validSources(queue, radio);
    when(mongo.findAll(MusicRuntimeStateDocument.class, TARGET))
        .thenReturn(java.util.Arrays.asList(queueDocument(queue), null));

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("malformed");

    verifyNoTargetInsert();
  }

  @Test
  void rejectsUnexpectedQueueCardinalityBeforeReadingOrWritingDestination() {
    when(mongo.count(any(Query.class), eq(LEGACY_QUEUE))).thenReturn(2L);

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cardinality");

    verify(mongo, never()).findAll(MusicRuntimeStateDocument.class, TARGET);
    verifyNoTargetInsert();
  }

  @Test
  void rejectsUnexpectedRadioCardinalityBeforeReadingOrWritingDestination() {
    var queue = queue(4L);
    when(mongo.count(any(Query.class), eq(LEGACY_QUEUE))).thenReturn(1L);
    when(mongo.findById("global", MusicQueueState.class, LEGACY_QUEUE)).thenReturn(queue);
    when(mongo.count(any(Query.class), eq(LEGACY_RADIO))).thenReturn(0L);

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cardinality");

    verify(mongo, never()).findAll(MusicRuntimeStateDocument.class, TARGET);
    verifyNoTargetInsert();
  }

  @Test
  void rejectsUnexpectedLegacyIdentityBeforeReadingOrWritingDestination() {
    when(mongo.count(any(Query.class), eq(LEGACY_QUEUE))).thenReturn(1L);
    when(mongo.findById("global", MusicQueueState.class, LEGACY_QUEUE)).thenReturn(null);

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity");

    verify(mongo, never()).findAll(MusicRuntimeStateDocument.class, TARGET);
    verifyNoTargetInsert();
  }

  @Test
  void propagatesMalformedSourceMappingFailureBeforeReadingOrWritingDestination() {
    when(mongo.count(any(Query.class), eq(LEGACY_QUEUE))).thenReturn(1L);
    when(mongo.findById("global", MusicQueueState.class, LEGACY_QUEUE))
        .thenThrow(new IllegalArgumentException("malformed source"));

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed source");

    verify(mongo, never()).findAll(MusicRuntimeStateDocument.class, TARGET);
    verifyNoTargetInsert();
  }

  @Test
  void rejectsNonEquivalentReadbackAfterTheOnlyPermittedInsert() {
    var queue = queue(4L);
    var radio = radio(9L);
    validSources(queue, radio);
    when(mongo.findAll(MusicRuntimeStateDocument.class, TARGET))
        .thenReturn(List.of())
        .thenReturn(List.of(queueDocument(queue)));

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("destination");

    verify(mongo).insert(
        org.mockito.ArgumentMatchers.<MusicRuntimeStateDocument>anyCollection(), eq(TARGET));
  }

  private void validSources(MusicQueueState queue, MusicRadioState radio) {
    when(mongo.count(any(Query.class), eq(LEGACY_QUEUE))).thenReturn(1L);
    when(mongo.count(any(Query.class), eq(LEGACY_RADIO))).thenReturn(1L);
    when(mongo.findById("global", MusicQueueState.class, LEGACY_QUEUE)).thenReturn(queue);
    when(mongo.findById("global", MusicRadioState.class, LEGACY_RADIO)).thenReturn(radio);
  }

  private void verifyNoTargetInsert() {
    verify(mongo, never()).insert(
        org.mockito.ArgumentMatchers.<MusicRuntimeStateDocument>anyCollection(), eq(TARGET));
  }

  private static MusicQueueState queue(Long version) {
    var entry = new MusicQueueState.Entry(
        "entry-1", "track-queue", "token-queue", "account-1", Instant.EPOCH);
    return new MusicQueueState("global", List.of(entry), version);
  }

  private static MusicRadioState radio(Long version) {
    return new MusicRadioState(
        "global", 3, "track-radio", "token-radio", Instant.EPOCH, 90,
        MusicRadioState.Source.RADIO, null, version);
  }

  private static MusicRuntimeStateDocument queueDocument(MusicQueueState state) {
    return new MusicRuntimeStateDocument(
        "queue",
        MusicRuntimeStateDocument.Kind.QUEUE,
        new MusicRuntimeStateDocument.QueuePayload(state.entries()),
        null,
        state.version());
  }

  private static MusicRuntimeStateDocument radioDocument(MusicRadioState state) {
    return new MusicRuntimeStateDocument(
        "radio",
        MusicRuntimeStateDocument.Kind.RADIO,
        null,
        new MusicRuntimeStateDocument.RadioPayload(
            state.stationSequence(),
            state.trackId(),
            state.observedToken(),
            state.startedAt(),
            state.durationSeconds(),
            state.source(),
            state.queueEntryId()),
        state.version());
  }
}
