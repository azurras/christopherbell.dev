package dev.christopherbell.configuration.mongo.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.Aggregation;

/** Validated aggregation stages with manifest-backed foreign-kind access only. */
public final class KindScopedAggregation {
  private static final Set<String> CALLER_TAIL_STAGES = Set.of(
      "$match", "$sort", "$group", "$project", "$limit", "$skip",
      "$unwind", "$replaceRoot", "$replaceWith", "$set", "$addFields", "$unset", "$count");
  private static final Set<String> LOOKUP_KEYS = Set.of("from", "let", "pipeline", "as");

  private final Document trustedSelector;
  private final List<Document> pipeline;

  private KindScopedAggregation(Aggregation aggregation, List<ForeignKind> foreignKinds) {
    Objects.requireNonNull(aggregation, "aggregation");
    var foreignKindsByCollection =
        new HashMap<String, List<DomainCollectionManifest.KindDefinition>>();
    var retainedForeignKinds = new ArrayList<DomainCollectionManifest.KindDefinition>();
    for (var foreignKind : List.copyOf(foreignKinds)) {
      var definition = foreignKind.definition();
      retainedForeignKinds.add(definition);
      foreignKindsByCollection.computeIfAbsent(
          definition.collection(), ignored -> new ArrayList<>()).add(definition);
    }
    var requestedPipeline = aggregation.toPipeline(Aggregation.DEFAULT_CONTEXT).stream()
        .map(Document::new)
        .toList();
    var selector = leadingMatch(requestedPipeline);
    this.trustedSelector = selector.orElseGet(Document::new);
    var callerTail = selector.isPresent()
        ? requestedPipeline.subList(1, requestedPipeline.size())
        : requestedPipeline;
    validatePipeline(callerTail, foreignKindsByCollection, true);
    this.pipeline = callerTail.stream()
        .map(stage -> inlineForeignValidation(stage, retainedForeignKinds))
        .toList();
  }

  /** Uses a leading match as the trusted selector, then accepts approved non-writing stages. */
  public static KindScopedAggregation local(Aggregation aggregation) {
    return new KindScopedAggregation(aggregation, List.of());
  }

  /** Accepts an approved tail whose lookups use closed manifest-backed selectors. */
  public static KindScopedAggregation withForeignKinds(
      Aggregation aggregation, ForeignKind... foreignKinds) {
    return new KindScopedAggregation(aggregation, List.of(foreignKinds));
  }

  List<Document> pipeline() {
    return pipeline.stream().map(Document::new).toList();
  }

  Document trustedSelector() {
    return new Document(trustedSelector);
  }

  private static void validatePipeline(
      List<?> stages,
      Map<String, List<DomainCollectionManifest.KindDefinition>> foreignKindsByCollection,
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
      } else if (!CALLER_TAIL_STAGES.contains(operator)) {
        throw unsafe();
      }
    }
  }

  private static void validateLookup(
      Object value,
      Map<String, List<DomainCollectionManifest.KindDefinition>> foreignKindsByCollection) {
    if (!(value instanceof Document lookup)
        || !LOOKUP_KEYS.containsAll(lookup.keySet())
        || !(lookup.get("from") instanceof String collection)
        || !(lookup.get("pipeline") instanceof List<?> foreignPipeline)
        || !(lookup.get("as") instanceof String alias)
        || alias.isBlank()
        || (lookup.containsKey("let") && !(lookup.get("let") instanceof Document))) {
      throw unsafe();
    }
    var approvedKinds = foreignKindsByCollection.getOrDefault(collection, List.of());
    if (approvedKinds.isEmpty() || foreignPipeline.isEmpty()) {
      throw unsafe();
    }
    var firstStage = foreignPipeline.getFirst();
    if (!(firstStage instanceof Document first)
        || !(first.get("$match") instanceof Document match)) {
      throw unsafe();
    }
    var definition = approvedKinds.stream()
        .filter(kind -> requiresExactKind(match, kind.kind()))
        .findFirst()
        .orElseThrow(KindScopedAggregation::unsafe);
    var bindings = lookup.get("let") instanceof Document declaredBindings
        ? declaredBindings
        : new Document();
    validateLookupBindings(bindings);
    var variables = Set.copyOf(bindings.keySet());
    validateTrustedForeignSelector(match, definition.kind(), variables);
    validatePipeline(foreignPipeline.subList(1, foreignPipeline.size()), Map.of(), false);
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
    var trustedSelector = originalPipeline.getFirst().get("$match", Document.class);
    guardedPipeline.addAll(DomainEnvelopeAggregationValidation.stages(
        trustedSelector, definition.kind(), definition.schemaVersion()));
    originalPipeline.stream().skip(1).map(Document::new).forEach(guardedPipeline::add);
    lookup.put("pipeline", List.copyOf(guardedPipeline));
    return new Document("$lookup", lookup);
  }

  private static Optional<Document> leadingMatch(List<Document> pipeline) {
    if (pipeline.isEmpty()) {
      return Optional.empty();
    }
    var first = pipeline.getFirst();
    return first.size() == 1 && first.get("$match") instanceof Document match
        ? Optional.of(new Document(match))
        : Optional.empty();
  }

  private static void validateLookupBindings(Document bindings) {
    for (var entry : bindings.entrySet()) {
      if (!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")
          || !(entry.getValue() instanceof String reference)
          || !reference.matches("\\$[A-Za-z_][A-Za-z0-9_.]*")) {
        throw unsafe();
      }
    }
  }

  private static void validateTrustedForeignSelector(
      Document selector, String expectedKind, Set<String> variables) {
    for (var entry : selector.entrySet()) {
      var key = entry.getKey();
      if ("_kind".equals(key)) {
        if (!expectedKind.equals(entry.getValue())) {
          throw unsafe();
        }
      } else if ("$and".equals(key)) {
        if (!(entry.getValue() instanceof List<?> terms) || terms.isEmpty()) {
          throw unsafe();
        }
        for (var term : terms) {
          if (!(term instanceof Document document)) {
            throw unsafe();
          }
          validateTrustedForeignSelector(document, expectedKind, variables);
        }
      } else if ("$expr".equals(key)) {
        if (!(entry.getValue() instanceof Document expression)) {
          throw unsafe();
        }
        validateTrustedForeignExpression(expression, expectedKind, variables);
      } else if (!isForeignPath(key) || !isLiteral(entry.getValue())) {
        throw unsafe();
      }
    }
  }

  private static void validateTrustedForeignExpression(
      Document expression, String expectedKind, Set<String> variables) {
    if (expression.size() != 1) {
      throw unsafe();
    }
    if (expression.get("$and") instanceof List<?> terms && !terms.isEmpty()) {
      for (var term : terms) {
        if (!(term instanceof Document document)) {
          throw unsafe();
        }
        validateTrustedForeignExpression(document, expectedKind, variables);
      }
      return;
    }
    if (!(expression.get("$eq") instanceof List<?> terms) || terms.size() != 2) {
      throw unsafe();
    }
    var left = terms.get(0);
    var right = terms.get(1);
    var leftIsForeignPath = isForeignReference(left);
    var foreignPath = leftIsForeignPath ? (String) left
        : isForeignReference(right) ? (String) right : null;
    var operand = leftIsForeignPath ? right : left;
    if (foreignPath == null || !isTrustedOperand(operand, variables)) {
      throw unsafe();
    }
    if ("$_kind".equals(foreignPath) && !expectedKind.equals(operand)) {
      throw unsafe();
    }
  }

  private static boolean isForeignReference(Object value) {
    return value instanceof String reference
        && reference.startsWith("$")
        && isForeignPath(reference.substring(1));
  }

  private static boolean isForeignPath(String path) {
    return "_kind".equals(path)
        || "_id.legacyId".equals(path)
        || (path.startsWith("payload.") && path.length() > "payload.".length());
  }

  private static boolean isTrustedOperand(Object value, Set<String> variables) {
    if (value instanceof String string && string.startsWith("$$")) {
      return variables.contains(string.substring(2));
    }
    return isLiteral(value);
  }

  private static boolean isLiteral(Object value) {
    return !(value instanceof Document)
        && !(value instanceof Map<?, ?>)
        && !(value instanceof Iterable<?>)
        && (value == null || !value.getClass().isArray())
        && (!(value instanceof String string) || !string.startsWith("$"));
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
