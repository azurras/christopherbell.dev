package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KindMigrationEngineTest {
  private static final PostgresqlMigrationCatalog.Kind KIND = kind();
  private static final ValidatedMigrationContext CONTEXT = context();

  @Test
  void committedBatchesResumeAtTheDurableCursorAfterAnInjectedCrash() {
    var source = new FakeSource(5);
    var target = new FakeTarget(2);
    var engine = new KindMigrationEngine(source, target, sourceKind -> new StubTransformer());

    assertThatThrownBy(() -> engine.stageAndCheckpoint(CONTEXT, KIND))
        .isInstanceOf(InjectedFailure.class);
    assertThat(target.checkpoint.cursor()).isEqualTo("0002");
    assertThat(target.staged).hasSize(2);

    target.failCommit = -1;
    engine.stageAndCheckpoint(CONTEXT, KIND);

    assertThat(target.checkpoint.complete()).isTrue();
    assertThat(target.checkpoint.sourceCount()).isEqualTo(5);
    assertThat(target.staged).containsExactly("0001", "0002", "0003", "0004", "0005");
    assertThat(source.requestedLimits).containsOnly(2);
    assertThat(source.cursors).containsSequence(null, "0002", "0002", "0004", "0005");
  }

  @Test
  void transformationFailureAfterReadDoesNotAdvanceCheckpointOrStageRows() {
    var source = new FakeSource(1);
    var target = new FakeTarget(-1);
    var engine = new KindMigrationEngine(source, target, ignored -> new MigrationTransformer() {
      @Override
      public String sourceKind() {
        return "fixture";
      }

      @Override
      public TransformedMigrationDocument transform(MigrationSourceDocument document) {
        throw new InjectedFailure();
      }
    });

    assertThatThrownBy(() -> engine.stageAndCheckpoint(CONTEXT, KIND))
        .isInstanceOf(InjectedFailure.class);
    assertThat(target.checkpoint).isEqualTo(MigrationCheckpoint.initial());
    assertThat(target.staged).isEmpty();
  }

  @Test
  void publicationRequiresACompleteExactReconciliationAndIsIdempotent() {
    var target = new FakeTarget(-1);
    var reconciler = new MigrationReconciler(target);

    assertThatThrownBy(() -> reconciler.reconcileAndPublish(CONTEXT, KIND))
        .isInstanceOf(MigrationReconciliationException.class);
    assertThat(target.publishCalls).isZero();

    new KindMigrationEngine(new FakeSource(3), target, ignored -> new StubTransformer())
        .stageAndCheckpoint(CONTEXT, KIND);
    reconciler.reconcileAndPublish(CONTEXT, KIND);
    reconciler.reconcileAndPublish(CONTEXT, KIND);

    assertThat(target.publishCalls).isEqualTo(2);
    assertThat(target.published).containsExactly("0001", "0002", "0003");
  }

  @Test
  void thousandDocumentRunKeepsEverySourceReadAtTheConfiguredBound() {
    var source = new FakeSource(1_001);
    var target = new FakeTarget(-1);

    new KindMigrationEngine(source, target, ignored -> new StubTransformer())
        .stageAndCheckpoint(context(100), KIND);

    assertThat(target.staged).hasSize(1_001);
    assertThat(source.requestedLimits).hasSize(12).containsOnly(100);
    assertThat(target.commitCalls).isEqualTo(11);
  }

  private static PostgresqlMigrationCatalog.Kind kind() {
    return new PostgresqlMigrationCatalog.Kind(
        "configuration", "fixture", 1, 1, "string", "platform", List.of("fixture"),
        1,
        List.of(),
        new PostgresqlMigrationCatalog.KeyMapping(
            "id", "fixture.id", "exact"),
        Map.of(
            "id",
            new PostgresqlMigrationCatalog.FieldMapping(
                List.of("fixture.id"), "string", "reject", "reject")),
        "preserve",
        "none",
        "none",
        "sha256-rfc8785-v1",
        List.of("count"),
        List.of("by-id"),
        "dev.christopherbell.configuration.persistence.migration.AccountTransformer");
  }

  private static ValidatedMigrationContext context() {
    return context(2);
  }

  private static ValidatedMigrationContext context(int batchSize) {
    var request = new MigrationRequest(
        PostgresqlMigrationCommand.SHADOW,
        "mongodb://127.0.0.1:57018/test",
        "test",
        "jdbc:postgresql://127.0.0.1:55432/test",
        "test",
        "christopherbell_test",
        "cbtest_task6_",
        "a".repeat(64),
        "release-6",
        UUID.fromString("00000000-0000-0000-0000-000000000006"),
        null,
        batchSize);
    return new ValidatedMigrationContext(
        request,
        new MigrationDatabaseIdentity("127.0.0.1", 57018, "test", null),
        new MigrationDatabaseIdentity("127.0.0.1", 55432, "test", "christopherbell_test"),
        false);
  }

  static final class StubTransformer implements MigrationTransformer {
    @Override
    public String sourceKind() {
      return "fixture";
    }

    @Override
    public TransformedMigrationDocument transform(MigrationSourceDocument document) {
      var values = new LinkedHashMap<String, Object>();
      values.put("id", document.sourceId());
      return new TransformedMigrationDocument(
          "fixture", document.sourceId(), CanonicalMigrationHasher.sha256(document.payload()),
          List.of(new MigrationRelationalRow(
              "platform", "fixture", document.sourceId(), 0, values)));
    }
  }

  private static final class FakeSource implements MigrationSourceReader {
    private final List<MigrationSourceDocument> documents;
    private final List<String> cursors = new ArrayList<>();
    private final List<Integer> requestedLimits = new ArrayList<>();

    private FakeSource(int count) {
      var result = new ArrayList<MigrationSourceDocument>();
      for (var index = 1; index <= count; index++) {
        var id = "%04d".formatted(index);
        result.add(new MigrationSourceDocument("fixture", 1, id, Map.of("id", id)));
      }
      documents = List.copyOf(result);
    }

    @Override
    public SourceBatch readAfter(
        ValidatedMigrationContext context,
        PostgresqlMigrationCatalog.Kind kind,
        String cursor,
        int limit) {
      cursors.add(cursor);
      requestedLimits.add(limit);
      var values = documents.stream()
          .filter(document -> cursor == null || document.sourceId().compareTo(cursor) > 0)
          .limit(limit)
          .toList();
      return SourceBatch.of(values);
    }
  }

  private static final class FakeTarget implements MigrationTargetStore {
    private MigrationCheckpoint checkpoint = MigrationCheckpoint.initial();
    private final List<String> staged = new ArrayList<>();
    private final List<String> published = new ArrayList<>();
    private int failCommit;
    private int commitCalls;
    private int publishCalls;

    private FakeTarget(int failCommit) {
      this.failCommit = failCommit;
    }

    @Override
    public MigrationCheckpoint checkpoint(
        ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
      return checkpoint;
    }

    @Override
    public MigrationCheckpoint commitBatch(
        ValidatedMigrationContext context,
        PostgresqlMigrationCatalog.Kind kind,
        MigrationCheckpoint expected,
        List<TransformedMigrationDocument> documents,
        String nextCursor) {
      assertThat(expected).isEqualTo(checkpoint);
      commitCalls++;
      if (commitCalls == failCommit) {
        throw new InjectedFailure();
      }
      documents.forEach(document -> {
        if (!staged.contains(document.sourceId())) {
          staged.add(document.sourceId());
        }
      });
      checkpoint = checkpoint.advance(nextCursor, documents);
      return checkpoint;
    }

    @Override
    public MigrationCheckpoint completeStaging(
        ValidatedMigrationContext context,
        PostgresqlMigrationCatalog.Kind kind,
        MigrationCheckpoint expected) {
      assertThat(expected).isEqualTo(checkpoint);
      checkpoint = checkpoint.markComplete();
      return checkpoint;
    }

    @Override
    public MigrationReconciliation reconcile(
        ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
      return new MigrationReconciliation(
          checkpoint.complete(), checkpoint.sourceCount(), staged.size(),
          checkpoint.sourceDigest(), checkpoint.sourceDigest(), true, true);
    }

    @Override
    public void publish(
        ValidatedMigrationContext context,
        PostgresqlMigrationCatalog.Kind kind,
        MigrationReconciliation reconciliation) {
      publishCalls++;
      published.clear();
      published.addAll(staged);
    }

    @Override
    public List<MigrationKindStatus> statuses(ValidatedMigrationContext context) {
      return List.of(new MigrationKindStatus(
          "fixture", checkpoint, published.size(), checkpoint.complete()));
    }
  }

  private static final class InjectedFailure extends RuntimeException {}
}
