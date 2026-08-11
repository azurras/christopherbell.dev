package dev.christopherbell.configuration.mongo.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.Aggregation;

/** Validated aggregation stages with manifest-backed foreign-kind access only. */
public final class KindScopedAggregation {
  private static final Set<String> LOCAL_STAGES = Set.of(
      "$match", "$sort", "$group", "$project", "$limit", "$skip",
      "$unwind", "$replaceRoot", "$replaceWith", "$set", "$addFields", "$unset", "$count");

  private final List<Document> pipeline;

  private KindScopedAggregation(Aggregation aggregation, List<ForeignKind> foreignKinds) {
    Objects.requireNonNull(aggregation, "aggregation");
    var foreignKindsByCollection = new HashMap<String, List<String>>();
    var retainedForeignKinds = new ArrayList<DomainCollectionManifest.KindDefinition>();
    for (var foreignKind : List.copyOf(foreignKinds)) {
      var definition = foreignKind.definition();
      retainedForeignKinds.add(definition);
      foreignKindsByCollection.computeIfAbsent(
          definition.collection(), ignored -> new ArrayList<>()).add(definition.kind());
    }
    var requestedPipeline = aggregation.toPipeline(Aggregation.DEFAULT_CONTEXT).stream()
        .map(Document::new)
        .toList();
    validatePipeline(requestedPipeline, foreignKindsByCollection, true);
    this.pipeline = requestedPipeline.stream()
        .map(stage -> inlineForeignValidation(stage, retainedForeignKinds))
        .toList();
  }

  /** Accepts only local, non-writing stages. */
  public static KindScopedAggregation local(Aggregation aggregation) {
    return new KindScopedAggregation(aggregation, List.of());
  }

  /** Accepts local stages and lookups scoped to these exact manifest-backed kinds. */
  public static KindScopedAggregation withForeignKinds(
      Aggregation aggregation, ForeignKind... foreignKinds) {
    return new KindScopedAggregation(aggregation, List.of(foreignKinds));
  }

  List<Document> pipeline() {
    return pipeline.stream().map(Document::new).toList();
  }

  private static void validatePipeline(
      List<?> stages,
      Map<String, List<String>> foreignKindsByCollection,
      boolean allowLookup) {
    for (var value : stages) {
      if (!(value instanceof Document stage) || stage.size() != 1) {
        throw unsafe();
      }
      var operator = stage.keySet().iterator().next();
      if ("$lookup".equals(operator)) {
        if (!allowLookup) {
          throw unsafe();
        }
        validateLookup(stage.get("$lookup"), foreignKindsByCollection);
      } else if (!LOCAL_STAGES.contains(operator)) {
        throw unsafe();
      }
    }
  }

  private static void validateLookup(
      Object value, Map<String, List<String>> foreignKindsByCollection) {
    if (!(value instanceof Document lookup)
        || !(lookup.get("from") instanceof String collection)
        || !(lookup.get("pipeline") instanceof List<?> foreignPipeline)
        || !(lookup.get("as") instanceof String alias)
        || alias.isBlank()) {
      throw unsafe();
    }
    var approvedKinds = foreignKindsByCollection.getOrDefault(collection, List.of());
    if (approvedKinds.isEmpty() || foreignPipeline.isEmpty()) {
      throw unsafe();
    }
    var firstStage = foreignPipeline.getFirst();
    if (!(firstStage instanceof Document first)
        || !(first.get("$match") instanceof Document match)
        || approvedKinds.stream().noneMatch(kind -> requiresExactKind(match, kind))) {
      throw unsafe();
    }
    validatePipeline(foreignPipeline, Map.of(), false);
  }

  private static Document inlineForeignValidation(
      Document stage, List<DomainCollectionManifest.KindDefinition> foreignKinds) {
    if (!(stage.get("$lookup") instanceof Document originalLookup)) {
      return new Document(stage);
    }
    var lookup = new Document(originalLookup);
    var collection = lookup.getString("from");
    @SuppressWarnings("unchecked")
    var originalPipeline = (List<Document>) lookup.get("pipeline", List.class);
    var definition = foreignKinds.stream()
        .filter(candidate -> candidate.collection().equals(collection))
        .filter(candidate -> originalPipeline.getFirst().get("$match") instanceof Document match
            && requiresExactKind(match, candidate.kind()))
        .findFirst()
        .orElseThrow(KindScopedAggregation::unsafe);
    var guardedPipeline = new ArrayList<Document>();
    guardedPipeline.addAll(DomainEnvelopeAggregationValidation.stages(
        definition.kind(), definition.schemaVersion()));
    originalPipeline.stream().map(Document::new).forEach(guardedPipeline::add);
    lookup.put("pipeline", List.copyOf(guardedPipeline));
    return new Document("$lookup", lookup);
  }

  private static boolean requiresExactKind(Document match, String expectedKind) {
    if (expectedKind.equals(match.get("_kind"))) {
      return true;
    }
    if (match.get("$and") instanceof List<?> terms
        && terms.stream().anyMatch(term -> term instanceof Document document
            && requiresExactKind(document, expectedKind))) {
      return true;
    }
    return match.get("$expr") instanceof Document expression
        && expressionRequiresExactKind(expression, expectedKind);
  }

  private static boolean expressionRequiresExactKind(Document expression, String expectedKind) {
    if (expression.get("$eq") instanceof List<?> terms
        && terms.size() == 2
        && (("$_kind".equals(terms.get(0)) && expectedKind.equals(terms.get(1)))
            || ("$_kind".equals(terms.get(1)) && expectedKind.equals(terms.get(0))))) {
      return true;
    }
    return expression.get("$and") instanceof List<?> terms
        && terms.stream().anyMatch(term -> term instanceof Document document
            && expressionRequiresExactKind(document, expectedKind));
  }

  private static IllegalArgumentException unsafe() {
    return new IllegalArgumentException("Mongo domain aggregation stage is not approved.");
  }

  /** Closed set of cross-kind joins used by the social-domain adapters. */
  public enum ForeignKind {
    ACCOUNT("account"),
    ACCOUNT_FOLLOW("account_follow"),
    CONVERSATION_ARCHIVE_STATE("conversation_archive_state"),
    POST("post");

    private final String kind;

    ForeignKind(String kind) {
      this.kind = kind;
    }

    private DomainCollectionManifest.KindDefinition definition() {
      return DomainCollectionManifest.forKind(kind)
          .orElseThrow(() -> new IllegalStateException("Approved foreign Mongo kind is absent."));
    }
  }
}
