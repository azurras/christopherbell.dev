package dev.christopherbell.configuration.mongo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class MongoKindScopedOperationsTest {
  private static final DomainDocumentKind<SampleDocument> KIND =
      DomainDocumentKindRegistry.of(Map.of("sample_kind", "content"))
          .require("sample_kind", 1, SampleDocument.class);

  private MongoTemplate mongo;
  private MongoKindScopedOperations<SampleDocument> operations;

  @BeforeEach
  void setUp() throws Exception {
    mongo = mock(MongoTemplate.class);
    when(mongo.getConverter()).thenReturn(converter());
    operations = new MongoKindScopedOperations<>(mongo, KIND);
  }

  @Test
  void insertCreatesTheCanonicalEnvelopeAndReturnsInitializedVersion() {
    var value = new SampleDocument(
        "legacy-id", "Ada", Long.MAX_VALUE, new Decimal128(12345L), null);
    when(mongo.insert(any(Document.class), eq("content")))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var inserted = operations.insert(value);

    var envelope = documentCaptor();
    verify(mongo).insert(envelope.capture(), eq("content"));
    assertThat(envelope.getValue().keySet())
        .containsExactly("_id", "_kind", "schemaVersion", "payload");
    assertThat(envelope.getValue().get("_id", Document.class).keySet())
        .containsExactly("kind", "legacyId");
    assertThat(envelope.getValue().get("_kind")).isEqualTo("sample_kind");
    assertThat(envelope.getValue().get("schemaVersion")).isEqualTo(1);
    assertThat(envelope.getValue().get("payload", Document.class))
        .doesNotContainKey("_id")
        .containsEntry("display_name", "Ada")
        .containsEntry("visits", Long.MAX_VALUE)
        .containsEntry("amount", new Decimal128(12345L))
        .containsEntry("version", 0L);
    assertThat(inserted).isEqualTo(new SampleDocument(
        "legacy-id", "Ada", Long.MAX_VALUE, new Decimal128(12345L), 0L));
  }

  @Test
  void findMapsDomainFieldsAndSortUnderPayloadWhileInjectingKindScope() {
    when(mongo.find(any(Query.class), eq(Document.class), eq("content")))
        .thenReturn(List.of());
    var query = Query.query(Criteria.where("displayName").is("Ada"));
    var page = PageRequest.of(2, 5, Sort.by(Sort.Order.desc("visits")));

    assertThat(operations.find(query, page)).isEmpty();

    var queryCaptor = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(queryCaptor.capture(), eq(Document.class), eq("content"));
    assertThat(queryCaptor.getValue().getQueryObject()).isEqualTo(new Document("$and", List.of(
        new Document("_kind", "sample_kind"),
        new Document("payload.display_name", "Ada"))));
    assertThat(queryCaptor.getValue().getSortObject())
        .isEqualTo(new Document("payload.visits", -1));
    assertThat(queryCaptor.getValue().getSkip()).isEqualTo(10);
    assertThat(queryCaptor.getValue().getLimit()).isEqualTo(5);
  }

  @Test
  void findByIdScopesBothTheEnvelopeKindAndNamespacedIdentity() {
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("content")))
        .thenReturn(null);

    assertThat(operations.findById("legacy-id")).isEmpty();

    var queryCaptor = ArgumentCaptor.forClass(Query.class);
    verify(mongo).findOne(queryCaptor.capture(), eq(Document.class), eq("content"));
    assertThat(queryCaptor.getValue().getQueryObject()).isEqualTo(new Document("$and", List.of(
        new Document("_kind", "sample_kind"),
        new Document("_id", new Document("kind", "sample_kind")
            .append("legacyId", "legacy-id")))));
  }

  @Test
  void updateMapsQueryAndMutationFieldsWithoutExposingEnvelopeMetadata() {
    when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Document.class), eq("content")))
        .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

    operations.updateFirst(
        Query.query(Criteria.where("displayName").is("Ada")),
        Update.update("displayName", "Grace").inc("visits", 1));

    var queryCaptor = ArgumentCaptor.forClass(Query.class);
    var updateCaptor = ArgumentCaptor.forClass(Update.class);
    verify(mongo).updateFirst(
        queryCaptor.capture(), updateCaptor.capture(), eq(Document.class), eq("content"));
    assertThat(queryCaptor.getValue().getQueryObject()).isEqualTo(new Document("$and", List.of(
        new Document("_kind", "sample_kind"),
        new Document("payload.display_name", "Ada"))));
    assertThat(updateCaptor.getValue().getUpdateObject()).isEqualTo(new Document()
        .append("$set", new Document("payload.display_name", "Grace"))
        .append("$inc", new Document("payload.visits", 1)
            .append("payload.version", 1)));
  }

  @Test
  void findAndUpdateScopesTheAtomicMutationAndReturnsTheDecodedWinner() {
    var winner = new SampleDocument("legacy-id", "Grace", 2L, new Decimal128(4L), 1L);
    var winnerEnvelope = envelope(winner);
    when(mongo.findAndModify(
        any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(Document.class), eq("content")))
        .thenReturn(winnerEnvelope);

    assertThat(operations.findAndUpdate(
        Query.query(Criteria.where("displayName").is("Ada")),
        Update.update("displayName", "Grace")))
        .contains(winner);

    var queryCaptor = ArgumentCaptor.forClass(Query.class);
    var updateCaptor = ArgumentCaptor.forClass(Update.class);
    verify(mongo).findAndModify(
        queryCaptor.capture(), updateCaptor.capture(), any(FindAndModifyOptions.class),
        eq(Document.class), eq("content"));
    assertThat(queryCaptor.getValue().getQueryObject()).isEqualTo(new Document("$and", List.of(
        new Document("_kind", "sample_kind"),
        new Document("payload.display_name", "Ada"))));
    assertThat(updateCaptor.getValue().getUpdateObject()).isEqualTo(new Document()
        .append("$set", new Document("payload.display_name", "Grace"))
        .append("$inc", new Document("payload.version", 1)));
  }

  @Test
  void updateMultiScopesEveryMatchedDocumentToTheKind() {
    when(mongo.updateMulti(any(Query.class), any(Update.class), eq(Document.class), eq("content")))
        .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(2, 2L, null));

    operations.updateMulti(
        Query.query(Criteria.where("displayName").is("Ada")),
        Update.update("displayName", "Grace"));

    var queryCaptor = ArgumentCaptor.forClass(Query.class);
    verify(mongo).updateMulti(
        queryCaptor.capture(), any(Update.class), eq(Document.class), eq("content"));
    assertThat(queryCaptor.getValue().getQueryObject()).isEqualTo(new Document("$and", List.of(
        new Document("_kind", "sample_kind"),
        new Document("payload.display_name", "Ada"))));
  }

  @Test
  void aggregateMatchesTheKindBeforeExposingTheDomainDocumentShape() {
    when(mongo.aggregate(any(Aggregation.class), eq("content"), eq(Document.class)))
        .thenReturn(new AggregationResults<>(List.of(), new Document()));

    assertThat(operations.aggregate(
        KindScopedAggregation.local(
            Aggregation.newAggregation(Aggregation.match(Criteria.where("displayName").is("Ada")))),
        Document.class))
        .isEmpty();

    var aggregationCaptor = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(aggregationCaptor.capture(), eq("content"), eq(Document.class));
    assertThat(aggregationCaptor.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT))
        .startsWith(
            new Document("$match", new Document("_kind", "sample_kind")
                .append("schemaVersion", 1)
                .append("_id.kind", "sample_kind")
                .append("_id.legacyId", new Document("$exists", true))
                .append("payload", new Document("$type", "object"))),
            new Document("$replaceRoot", new Document("newRoot", new Document("$mergeObjects", List.of(
                "$payload", new Document("_id", "$_id.legacyId"))))))
        .contains(new Document("$match", new Document("displayName", "Ada")));
  }

  @Test
  void aggregateRejectsWriteAndCallerSelectedCollectionStages() {
    when(mongo.aggregate(any(Aggregation.class), eq("content"), eq(Document.class)))
        .thenReturn(new AggregationResults<>(List.of(), new Document()));

    for (var stage : List.of(
        new Document("$out", "attacker_selected"),
        new Document("$merge", new Document("into", "attacker_selected")),
        new Document("$unionWith", "attacker_selected"),
        new Document("$lookup", new Document("from", "attacker_selected")
            .append("pipeline", List.of())
            .append("as", "leak")))) {
      var aggregation = Aggregation.newAggregation(context -> stage);

      assertThatThrownBy(() -> operations.aggregate(
          KindScopedAggregation.local(aggregation), Document.class))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void aggregationRejectsARegisteredCollectionLookupWithoutExactForeignKindScope() {
    var aggregation = Aggregation.newAggregation(context -> new Document("$lookup", new Document()
        .append("from", "content")
        .append("pipeline", List.of(new Document("$match", new Document("payload.id", "x"))))
        .append("as", "leak")));

    assertThatThrownBy(() -> KindScopedAggregation.withForeignKinds(
        aggregation, KindScopedAggregation.ForeignKind.POST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Mongo domain aggregation stage is not approved.");
  }

  @Test
  void aggregationRejectsAForeignKindPredicateThatCanBeBypassedByAnOrBranch() {
    var aggregation = Aggregation.newAggregation(context -> new Document("$lookup", new Document()
        .append("from", "content")
        .append("pipeline", List.of(new Document("$match", new Document("$or", List.of(
            new Document("_kind", "post"),
            new Document("payload.accountId", "attacker-selected"))))))
        .append("as", "leak")));

    assertThatThrownBy(() -> KindScopedAggregation.withForeignKinds(
        aggregation, KindScopedAggregation.ForeignKind.POST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Mongo domain aggregation stage is not approved.");
  }

  @Test
  void rejectsCallerUpdatesToTheVersionProperty() {
    assertThatThrownBy(() -> operations.updateFirst(
        new Query(), Update.update("version", 99L)))
        .isInstanceOf(UnapprovedDomainFieldException.class)
        .hasMessage("Mongo domain field is not approved.");
  }

  @Test
  void rejectsUnknownAndEnvelopeFieldQueriesWithOneRedactedFailure() {
    for (var field : List.of("_kind", "schemaVersion", "payload.display_name", "unknown")) {
      assertThatThrownBy(() -> operations.exists(Query.query(Criteria.where(field).is("secret"))))
          .isInstanceOf(UnapprovedDomainFieldException.class)
          .hasMessage("Mongo domain field is not approved.")
          .hasMessageNotContaining(field)
          .hasMessageNotContaining("secret");
    }
  }

  @Test
  void staleVersionedSaveThrowsWithoutReplacingTheWinner() {
    var stale = new SampleDocument(
        "legacy-id", "stale", 1L, new Decimal128(1L), 3L);
    var stored = envelope(stale);
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("content")))
        .thenReturn(stored);
    when(mongo.findAndReplace(
        any(Query.class),
        any(Document.class),
        any(FindAndReplaceOptions.class),
        eq(Document.class),
        eq("content"),
        eq(Document.class)))
        .thenReturn(null);

    assertThatThrownBy(() -> operations.save(stale))
        .isInstanceOf(OptimisticLockingFailureException.class)
        .hasMessage("Mongo domain document was changed by another writer.")
        .hasMessageNotContaining("legacy-id")
        .hasMessageNotContaining("sample_kind");
  }

  @Test
  void missingVersionedDocumentRejectsANonnullVersionWithoutReinsertingIt() {
    var stale = new SampleDocument(
        "deleted-id", "stale", 1L, new Decimal128(1L), 3L);
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("content")))
        .thenReturn(null);
    when(mongo.insert(any(Document.class), eq("content")))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertThatThrownBy(() -> operations.save(stale))
        .isInstanceOf(OptimisticLockingFailureException.class)
        .hasMessage("Mongo domain document was changed by another writer.")
        .hasNoCause();
    verify(mongo, never()).insert(any(Document.class), eq("content"));
  }

  @Test
  void malformedConversionFailureRedactsTheEntireCauseChain() {
    var malformed = new Document(
        "_id", NamespacedMongoId.of(KIND.kind(), "legacy-id").toBson())
        .append("_kind", KIND.kind())
        .append("schemaVersion", KIND.schemaVersion())
        .append("payload", new Document("display_name", "Ada")
            .append("visits", "secret-payload-value")
            .append("amount", new Decimal128(1L))
            .append("version", 0L));
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("content")))
        .thenReturn(malformed);

    assertThatThrownBy(() -> operations.findById("legacy-id"))
        .isInstanceOf(MalformedDomainDocumentException.class)
        .hasMessage("Mongo domain document is malformed.")
        .hasNoCause()
        .satisfies(failure -> assertThat(stackTrace(failure))
            .doesNotContain("secret-payload-value", "legacy-id"));
  }

  @Test
  void duplicateInsertFailureRetainsTypeWithoutLeakingTheDriverMessage() {
    var value = new SampleDocument(
        "legacy-id", "Ada", 1L, new Decimal128(1L), null);
    when(mongo.insert(any(Document.class), eq("content")))
        .thenThrow(new DuplicateKeyException("duplicate secret-id payload"));

    assertThatThrownBy(() -> operations.insert(value))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessage("Mongo domain identity already exists.")
        .hasNoCause()
        .satisfies(failure -> assertThat(stackTrace(failure))
            .doesNotContain("secret-id", "payload"));
  }

  @Test
  void versionedInsertRaceIsRedactedAsOptimisticContention() {
    var value = new SampleDocument(
        "legacy-id", "Ada", 1L, new Decimal128(1L), null);
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("content")))
        .thenReturn(null);
    when(mongo.insert(any(Document.class), eq("content")))
        .thenThrow(new DuplicateKeyException("duplicate secret-id payload"));

    assertThatThrownBy(() -> operations.save(value))
        .isInstanceOf(OptimisticLockingFailureException.class)
        .hasMessage("Mongo domain document was changed by another writer.")
        .hasNoCause()
        .satisfies(failure -> assertThat(stackTrace(failure))
            .doesNotContain("secret-id", "payload"));
  }

  @Test
  void duplicateReplacementFailureRetainsTypeWithoutLeakingTheDriverMessage() {
    var value = new SampleDocument(
        "legacy-id", "Ada", 1L, new Decimal128(1L), 3L);
    var stored = envelope(value);
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("content")))
        .thenReturn(stored);
    when(mongo.findAndReplace(
        any(Query.class),
        any(Document.class),
        any(FindAndReplaceOptions.class),
        eq(Document.class),
        eq("content"),
        eq(Document.class)))
        .thenThrow(new DuplicateKeyException("duplicate secret-replacement payload"));

    assertThatThrownBy(() -> operations.save(value))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessage("Mongo domain identity already exists.")
        .hasNoCause()
        .satisfies(failure -> assertThat(stackTrace(failure))
            .doesNotContain("secret-replacement", "payload"));
  }

  @Test
  void duplicateUpdateFailureRetainsTypeWithoutLeakingTheDriverMessage() {
    when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Document.class), eq("content")))
        .thenThrow(new DuplicateKeyException("duplicate secret-update payload"));

    assertThatThrownBy(() -> operations.updateFirst(
        Query.query(Criteria.where("displayName").is("Ada")),
        Update.update("displayName", "Grace")))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessage("Mongo domain identity already exists.")
        .hasNoCause()
        .satisfies(failure -> assertThat(stackTrace(failure))
            .doesNotContain("secret-update", "payload"));
  }

  private Document envelope(SampleDocument value) {
    var payload = new Document();
    mongo.getConverter().write(value, payload);
    payload.remove("_id");
    return new Document("_id", NamespacedMongoId.of(KIND.kind(), value.id()).toBson())
        .append("_kind", KIND.kind())
        .append("schemaVersion", KIND.schemaVersion())
        .append("payload", payload);
  }

  private static ArgumentCaptor<Document> documentCaptor() {
    return ArgumentCaptor.forClass(Document.class);
  }

  private static String stackTrace(Throwable failure) {
    var buffer = new StringWriter();
    failure.printStackTrace(new PrintWriter(buffer));
    return buffer.toString();
  }

  private static MappingMongoConverter converter() throws Exception {
    var context = new MongoMappingContext();
    context.setInitialEntitySet(Set.of(SampleDocument.class));
    context.afterPropertiesSet();
    var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
    converter.setTypeMapper(new DefaultMongoTypeMapper(null));
    converter.afterPropertiesSet();
    return converter;
  }

  record SampleDocument(
      @Id Object id,
      @Field("display_name") String displayName,
      long visits,
      Decimal128 amount,
      @Version Long version) {}
}
