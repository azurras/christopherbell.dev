package dev.christopherbell.configuration.mongo.domain;

import com.mongodb.MongoException;
import java.util.List;
import org.bson.Document;

/** Builds optimizer-visible guards for stored domain envelopes. */
final class DomainEnvelopeAggregationValidation {
  private static final int CONVERSION_FAILURE_CODE = 241;
  private static final String VALIDATION_FIELD =
      "__cbell_domain_envelope_validation_4d21c8a6__";
  private static final String FAILURE_MARKER =
      "__cbell_malformed_domain_envelope_4d21c8a6__";
  private static final List<String> ENVELOPE_KEYS =
      List.of("_id", "_kind", "schemaVersion", "payload");
  private static final List<String> ID_KEYS = List.of("kind", "legacyId");

  private DomainEnvelopeAggregationValidation() {}

  static List<Document> stages(String expectedKind, int expectedSchemaVersion) {
    var validation = new Document("$cond", List.of(
        validEnvelope(expectedKind, expectedSchemaVersion),
        true,
        controlledFailure()));
    var preserveValidatedEnvelope = new Document("$cond", List.of(
        "$" + VALIDATION_FIELD,
        new Document("_id", "$_id")
            .append("_kind", "$_kind")
            .append("schemaVersion", "$schemaVersion")
            .append("payload", "$payload"),
        "$$ROOT"));
    return List.of(
        new Document("$match", new Document("_kind", expectedKind)),
        new Document("$set", new Document(VALIDATION_FIELD, validation)),
        new Document("$replaceWith", preserveValidatedEnvelope));
  }

  static boolean isControlledFailure(Throwable failure) {
    for (var candidate = failure; candidate != null; candidate = candidate.getCause()) {
      if (candidate instanceof MongoException mongoFailure
          && mongoFailure.getCode() == CONVERSION_FAILURE_CODE
          && mongoFailure.getMessage() != null
          && mongoFailure.getMessage().contains(FAILURE_MARKER)) {
        return true;
      }
    }
    return false;
  }

  private static Document validEnvelope(String expectedKind, int expectedSchemaVersion) {
    return new Document("$and", List.of(
        equal(keyNames("$$ROOT"), ENVELOPE_KEYS),
        equal("$_kind", expectedKind),
        equal(new Document("$type", "$schemaVersion"), "int"),
        equal("$schemaVersion", expectedSchemaVersion),
        equal(new Document("$type", "$_id"), "object"),
        equal(keyNames("$_id"), ID_KEYS),
        equal("$_id.kind", "$_kind"),
        equal("$_id.kind", expectedKind),
        notEqual(new Document("$type", "$_id.legacyId"), "missing"),
        notEqual("$_id.legacyId", null),
        equal(new Document("$type", "$payload"), "object"),
        equal(new Document("$type", "$payload._id"), "missing")));
  }

  private static Document keyNames(String input) {
    var safeObject = new Document("$cond", List.of(
        equal(new Document("$type", input), "object"),
        input,
        new Document()));
    return new Document("$map", new Document("input", new Document("$objectToArray", safeObject))
        .append("as", "field")
        .append("in", "$$field.k"));
  }

  private static Document equal(Object left, Object right) {
    return new Document("$eq", List.of(left, right));
  }

  private static Document notEqual(Object left, Object right) {
    return new Document("$ne", java.util.Arrays.asList(left, right));
  }

  private static Document controlledFailure() {
    var rowSpecificMarker = new Document("$concat", List.of(FAILURE_MARKER, "$_kind"));
    return new Document("$convert", new Document("input", rowSpecificMarker).append("to", "int"));
  }
}
