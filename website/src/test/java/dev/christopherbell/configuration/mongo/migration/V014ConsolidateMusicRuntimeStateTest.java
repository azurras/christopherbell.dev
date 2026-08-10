package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoCollection;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class V014ConsolidateMusicRuntimeStateTest {
  private static final String LEGACY_QUEUE = "music_queue_state";
  private static final String LEGACY_RADIO = "music_radio_state";
  private static final String TARGET = "music_runtime_state";

  @Mock private MongoTemplate mongo;
  @Mock private MongoCollection<Document> targetCollection;
  @Captor private ArgumentCaptor<List<Document>> inserted;

  @BeforeEach
  void exposeRawTargetCollection() {
    lenient().when(mongo.getCollection(TARGET)).thenReturn(targetCollection);
  }

  @Test
  void exposesImmutableMigrationIdentity() {
    var migration = new V014ConsolidateMusicRuntimeState();

    assertThat(migration.id()).isEqualTo("014-consolidate-music-runtime-state");
    assertThat(migration.checksum())
        .isEqualTo("11a69bdd4556cfc38060ccdda5075fb9d6bc36f1cc414edd7b26cd61a74b5cbb");
    assertThat(migration.description()).isEqualTo("Consolidate Music queue and radio runtime state");
  }

  @Test
  void copiesValidatedRawSourcesInTargetOrderWithoutChangingVersions() {
    var queue = queueSource(4L);
    var radio = radioSource(9L);
    var expectedQueue = queueTarget(4L);
    var expectedRadio = radioTarget(9L);
    validSources(queue, radio);
    when(mongo.findAll(Document.class, TARGET))
        .thenReturn(List.of())
        .thenReturn(List.of(expectedQueue, expectedRadio));

    new V014ConsolidateMusicRuntimeState().apply(mongo);

    verify(targetCollection).insertMany(inserted.capture());
    assertThat(inserted.getValue()).containsExactly(expectedQueue, expectedRadio);
    verify(mongo).findAll(Document.class, LEGACY_QUEUE);
    verify(mongo).findAll(Document.class, LEGACY_RADIO);
    verify(mongo, times(2)).findAll(Document.class, TARGET);
    verify(mongo).getCollection(TARGET);
    verifyNoMoreInteractions(mongo, targetCollection);
  }

  @Test
  void acceptsOnlyACompleteEquivalentRawDestinationRegardlessOfReadOrder() {
    validSources(queueSource(4L), radioSource(9L));
    when(mongo.findAll(Document.class, TARGET))
        .thenReturn(List.of(radioTarget(9L), queueTarget(4L)));

    assertThatCode(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .doesNotThrowAnyException();

    verifyNoTargetInsert();
  }

  @Test
  void preservesAbsentVersionsAsAbsentRawFields() {
    var expectedQueue = queueTarget(null);
    var expectedRadio = radioTarget(null);
    validSources(queueSource(null), radioSource(null));
    when(mongo.findAll(Document.class, TARGET))
        .thenReturn(List.of())
        .thenReturn(List.of(expectedQueue, expectedRadio));

    new V014ConsolidateMusicRuntimeState().apply(mongo);

    verify(targetCollection).insertMany(inserted.capture());
    assertThat(inserted.getValue()).containsExactly(expectedQueue, expectedRadio)
        .allSatisfy(document -> assertThat(document).doesNotContainKey("version"));
  }

  @ParameterizedTest
  @MethodSource("sourcePresenceCombinations")
  void migratesEveryValidSourcePresenceCombination(
      List<Document> queues, List<Document> radios, List<Document> expected) {
    sources(queues, radios);
    if (expected.isEmpty()) {
      when(mongo.findAll(Document.class, TARGET)).thenReturn(List.of());
    } else {
      when(mongo.findAll(Document.class, TARGET))
          .thenReturn(List.of())
          .thenReturn(expected);
    }

    assertThatCode(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .doesNotThrowAnyException();

    if (expected.isEmpty()) {
      verifyNoTargetInsert();
      verify(mongo, never()).getCollection(TARGET);
    } else {
      verify(targetCollection).insertMany(inserted.capture());
      assertThat(inserted.getValue()).containsExactlyElementsOf(expected);
    }
  }

  @ParameterizedTest
  @MethodSource("sourcePresenceCombinations")
  void acceptsOnlyTheExactEquivalentTargetMembershipForPresentSources(
      List<Document> queues, List<Document> radios, List<Document> expected) {
    sources(queues, radios);
    when(mongo.findAll(Document.class, TARGET)).thenReturn(expected);

    assertThatCode(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .doesNotThrowAnyException();

    verifyNoTargetInsert();
  }

  @Test
  void rejectsTargetMembershipThatDoesNotMatchPresentSources() {
    assertInvalidMembership(
        List.of(queueSource(4L)), List.of(), List.of(queueTarget(4L), radioTarget(9L)));
    assertInvalidMembership(
        List.of(), List.of(radioSource(9L)), List.of(queueTarget(4L), radioTarget(9L)));
    assertInvalidMembership(List.of(), List.of(), List.of(queueTarget(4L)));
    assertInvalidMembership(
        List.of(queueSource(4L)), List.of(radioSource(9L)), List.of(queueTarget(4L)));
  }

  @Test
  void rejectsPartialDuplicateExtraAndDivergentDestinationsBeforeWriting() {
    assertInvalidDestination(List.of(queueTarget(4L)));
    assertInvalidDestination(List.of(queueTarget(4L), queueTarget(4L)));
    assertInvalidDestination(List.of(queueTarget(4L), radioTarget(9L), queueTarget(4L)));
    assertInvalidDestination(List.of(queueTarget(5L), radioTarget(9L)));
  }

  @ParameterizedTest
  @MethodSource("invalidVersions")
  void rejectsFractionalOrWrongTypeSourceVersionBeforeWriting(Object invalidVersion) {
    var queue = queueSource(4L).append("version", invalidVersion);

    assertInvalidQueueSource(queue);
  }

  @ParameterizedTest
  @MethodSource("invalidVersions")
  void rejectsFractionalOrWrongTypeDestinationVersionBeforeWriting(Object invalidVersion) {
    var queue = queueTarget(4L).append("version", invalidVersion);

    assertInvalidDestination(List.of(queue, radioTarget(9L)));
  }

  @ParameterizedTest
  @MethodSource("missingOrNullQueueEntries")
  void rejectsMissingOrNullSourceQueueEntriesBeforeWriting(Document queue) {
    assertInvalidQueueSource(queue);
  }

  @ParameterizedTest
  @MethodSource("missingOrNullTargetQueueEntries")
  void rejectsMissingOrNullDestinationQueueEntriesBeforeWriting(Document queue) {
    assertInvalidDestination(List.of(queue, radioTarget(9L)));
  }

  @Test
  void rejectsFractionalIntegralRadioFieldsInSourceAndDestinationBeforeWriting() {
    var source = radioSource(9L).append("stationSequence", 3.5);
    assertInvalidRadioSource(source);

    var target = radioTarget(9L);
    target.get("radio", Document.class).append("stationSequence", 3.5);
    assertInvalidDestination(List.of(queueTarget(4L), target));
  }

  @Test
  void rejectsUnknownSourceFieldsAndWrongTargetDiscriminatorBeforeWriting() {
    assertInvalidQueueSource(queueSource(4L).append("unexpected", true));

    assertInvalidDestination(List.of(
        queueTarget(4L).append("kind", "RADIO"), radioTarget(9L)));
  }

  @Test
  void rejectsDomainInvalidQueueEntryBeforeWriting() {
    var queue = queueSource(4L);
    queue.getList("entries", Document.class).getFirst().put("id", "");

    assertInvalidQueueSource(queue);
  }

  @Test
  void rejectsUnexpectedSourceCardinalityOrIdentityBeforeReadingDestination() {
    when(mongo.findAll(Document.class, LEGACY_QUEUE))
        .thenReturn(List.of(queueSource(4L), queueSource(4L)));

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source", "cardinality");
    verify(mongo, never()).findAll(Document.class, TARGET);
    verifyNoTargetInsert();

    org.mockito.Mockito.reset(mongo, targetCollection);
    exposeRawTargetCollection();
    when(mongo.findAll(Document.class, LEGACY_QUEUE))
        .thenReturn(List.of(queueSource(4L).append("_id", "other")));
    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source", "identity");
    verify(mongo, never()).findAll(Document.class, TARGET);
    verifyNoTargetInsert();
  }

  @Test
  void rejectsMoreThanOneRadioSourceBeforeReadingDestination() {
    sources(
        List.of(),
        List.of(radioSource(9L), radioSource(10L)));

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source", "cardinality");
    verify(mongo, never()).findAll(Document.class, TARGET);
    verifyNoTargetInsert();
  }

  @Test
  void rejectsNonEquivalentRawReadbackAfterTheOnlyPermittedInsert() {
    validSources(queueSource(4L), radioSource(9L));
    when(mongo.findAll(Document.class, TARGET))
        .thenReturn(List.of())
        .thenReturn(List.of(queueTarget(4L)));

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("destination");

    verify(targetCollection).insertMany(anyList());
  }

  private void assertInvalidQueueSource(Document queue) {
    when(mongo.findAll(Document.class, LEGACY_QUEUE)).thenReturn(List.of(queue));

    assertInvalidSource();
  }

  private void assertInvalidRadioSource(Document radio) {
    validSources(queueSource(4L), radio);

    assertInvalidSource();
  }

  private void assertInvalidSource() {

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source");

    verify(mongo, never()).findAll(Document.class, TARGET);
    verifyNoTargetInsert();
  }

  private void assertInvalidDestination(List<Document> documents) {
    org.mockito.Mockito.reset(mongo, targetCollection);
    exposeRawTargetCollection();
    validSources(queueSource(4L), radioSource(9L));
    when(mongo.findAll(Document.class, TARGET)).thenReturn(documents);

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("destination");

    verifyNoTargetInsert();
  }

  private void assertInvalidMembership(
      List<Document> queues, List<Document> radios, List<Document> targets) {
    org.mockito.Mockito.reset(mongo, targetCollection);
    exposeRawTargetCollection();
    sources(queues, radios);
    when(mongo.findAll(Document.class, TARGET)).thenReturn(targets);

    assertThatThrownBy(() -> new V014ConsolidateMusicRuntimeState().apply(mongo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("destination");

    verifyNoTargetInsert();
  }

  private void validSources(Document queue, Document radio) {
    sources(List.of(queue), List.of(radio));
  }

  private void sources(List<Document> queues, List<Document> radios) {
    when(mongo.findAll(Document.class, LEGACY_QUEUE)).thenReturn(queues);
    when(mongo.findAll(Document.class, LEGACY_RADIO)).thenReturn(radios);
  }

  private void verifyNoTargetInsert() {
    verify(targetCollection, never()).insertMany(anyList());
  }

  private static Stream<Object> invalidVersions() {
    return Stream.of(4.5, "4");
  }

  private static Stream<Arguments> sourcePresenceCombinations() {
    return Stream.of(
        Arguments.of(List.of(), List.of(), List.of()),
        Arguments.of(
            List.of(queueSource(4L)), List.of(), List.of(queueTarget(4L))),
        Arguments.of(
            List.of(), List.of(radioSource(9L)), List.of(radioTarget(9L))),
        Arguments.of(
            List.of(queueSource(4L)),
            List.of(radioSource(9L)),
            List.of(queueTarget(4L), radioTarget(9L))));
  }

  private static Stream<Arguments> missingOrNullQueueEntries() {
    return Stream.of(
        Arguments.of(new Document("_id", "global").append("version", 4L)),
        Arguments.of(new Document("_id", "global")
            .append("entries", null)
            .append("version", 4L)));
  }

  private static Stream<Arguments> missingOrNullTargetQueueEntries() {
    return Stream.of(
        Arguments.of(new Document("_id", "queue")
            .append("kind", "QUEUE")
            .append("queue", new Document())
            .append("version", 4L)),
        Arguments.of(new Document("_id", "queue")
            .append("kind", "QUEUE")
            .append("queue", new Document("entries", null))
            .append("version", 4L)));
  }

  private static Document queueSource(Long version) {
    var entry = new Document("id", "entry-1")
        .append("trackId", "track-queue")
        .append("observedToken", "token-queue")
        .append("enqueuedByAccountId", "account-1")
        .append("enqueuedAt", Date.from(Instant.EPOCH));
    var document = new Document("_id", "global").append("entries", List.of(entry));
    return withVersion(document, version);
  }

  private static Document radioSource(Long version) {
    var document = new Document("_id", "global")
        .append("stationSequence", 3L)
        .append("trackId", "track-radio")
        .append("observedToken", "token-radio")
        .append("startedAt", Date.from(Instant.EPOCH))
        .append("durationSeconds", 90.0)
        .append("source", "RADIO");
    return withVersion(document, version);
  }

  private static Document queueTarget(Long version) {
    var document = new Document("_id", "queue")
        .append("kind", "QUEUE")
        .append("queue", new Document("entries", queueSource(null).getList("entries", Document.class)));
    return withVersion(document, version);
  }

  private static Document radioTarget(Long version) {
    var source = radioSource(null);
    source.remove("_id");
    var document = new Document("_id", "radio")
        .append("kind", "RADIO")
        .append("radio", source);
    return withVersion(document, version);
  }

  private static Document withVersion(Document document, Long version) {
    if (version != null) {
      document.append("version", version);
    }
    return document;
  }
}
