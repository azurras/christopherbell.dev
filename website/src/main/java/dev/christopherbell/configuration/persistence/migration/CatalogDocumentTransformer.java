package dev.christopherbell.configuration.persistence.migration;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;

/** Catalog-driven transformer shared by the 52 exact kind bindings. */
abstract class CatalogDocumentTransformer implements MigrationTransformer {
  private final PostgresqlMigrationCatalog.Kind kind;

  CatalogDocumentTransformer(String expectedKind, PostgresqlMigrationCatalog.Kind kind) {
    if (kind == null || !expectedKind.equals(kind.sourceKind())) {
      throw new IllegalArgumentException("PostgreSQL migration transformer binding is invalid.");
    }
    this.kind = kind;
  }

  @Override
  public final String sourceKind() {
    return kind.sourceKind();
  }

  @Override
  public final TransformedMigrationDocument transform(MigrationSourceDocument source) {
    requireSource(source);
    var rows = new RowSet(kind.targetSchema(), kind.targetTables(), source.sourceId());
    var key = Target.parse(kind.keyMapping().targetColumn());
    rows.root(key.table()).put(key.column(), source.sourceId());

    for (var entry : kind.fieldMappings().entrySet()) {
      var sourceField = entry.getKey();
      var mapping = entry.getValue();
      var present = source.payload().containsKey(sourceField);
      var value = source.payload().get(sourceField);
      if (!present) {
        applyAbsent(mapping, mapping.missing(), rows, false);
      } else if (value == null) {
        applyAbsent(mapping, mapping.nullValue(), rows, true);
      } else {
        applyPresent(mapping, value, rows);
      }
    }

    var sourceHash = CanonicalMigrationHasher.sha256(Map.of(
        "kind", source.sourceKind(),
        "schemaVersion", source.schemaVersion(),
        "sourceId", source.sourceId(),
        "payload", source.payload()));
    return new TransformedMigrationDocument(
        kind.sourceKind(), source.sourceId(), sourceHash, rows.finish());
  }

  private void requireSource(MigrationSourceDocument source) {
    if (source == null
        || !kind.sourceKind().equals(source.sourceKind())
        || kind.sourceSchemaVersion() != source.schemaVersion()
        || source.sourceId() == null
        || source.sourceId().isBlank()
        || source.payload() == null
        || !kind.fieldMappings().keySet().containsAll(source.payload().keySet())) {
      throw invalid();
    }
  }

  private static void applyAbsent(
      PostgresqlMigrationCatalog.FieldMapping mapping,
      String rule,
      RowSet rows,
      boolean explicitNull) {
    switch (rule) {
      case "reject" -> throw invalid();
      case "allow", "empty" -> setPresenceTargets(mapping, rows, false);
      case "default" -> {
        setPresenceTargets(mapping, rows, false);
        var defaultValue = defaultValue(mapping.conversion());
        if (defaultValue != null) {
          setScalarTargets(mapping, defaultValue, rows, explicitNull);
        }
      }
      default -> throw invalid();
    }
  }

  private static void applyPresent(
      PostgresqlMigrationCatalog.FieldMapping mapping, Object value, RowSet rows) {
    switch (mapping.conversion()) {
      case "constant-kind" -> {
        // Envelope kind is validated separately and has no relational value.
      }
      case "string", "uuid-string", "enum-name", "instant-utc", "local-date",
          "integer", "long", "boolean", "decimal-12-2", "decimal-20-9", "double",
          "byte-array" -> setScalarTargets(
              mapping, convertScalar(mapping.conversion(), value), rows, false);
      case "record-flattened", "preserve-ledger" ->
          setFlattenedTargets(mapping, asMap(value), rows);
      case "vin-response-flattened" -> setVinResponse(mapping, asMap(value), rows);
      case "record-child" -> setRecordChild(mapping, asMap(value), rows, 0);
      case "record-list-child" -> setRecordList(mapping, asCollection(value), rows);
      case "string-list-child", "string-set-child" ->
          setScalarList(mapping, asCollection(value), rows);
      case "string-map-child" -> setStringMap(mapping, asMap(value), rows);
      default -> throw invalid();
    }
    if (!mapping.conversion().equals("vin-response-flattened")) {
      setPresenceTargets(mapping, rows, true);
    }
  }

  private static void setScalarTargets(
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Object value,
      RowSet rows,
      boolean explicitNull) {
    for (var targetText : mapping.targets()) {
      var target = Target.parse(targetText);
      if (target.column().endsWith("_present")) {
        continue;
      }
      var mapped = normalizedValue(target.column(), value);
      if (mapped != null || explicitNull) {
        rows.root(target.table()).put(target.column(), mapped);
      }
    }
  }

  private static void setPresenceTargets(
      PostgresqlMigrationCatalog.FieldMapping mapping, RowSet rows, boolean present) {
    mapping.targets().stream()
        .map(Target::parse)
        .filter(target -> target.column().endsWith("_present"))
        .forEach(target -> rows.root(target.table()).put(target.column(), present));
  }

  private static void setFlattenedTargets(
      PostgresqlMigrationCatalog.FieldMapping mapping, Map<String, Object> value, RowSet rows) {
    for (var targetText : mapping.targets()) {
      var target = Target.parse(targetText);
      if (target.column().endsWith("_present")) {
        rows.root(target.table()).put(target.column(), true);
        continue;
      }
      var nested = findNested(value, target.column());
      if (nested.found()) {
        rows.root(target.table()).put(target.column(), nested.value());
      }
    }
  }

  private static void setVinResponse(
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Map<String, Object> value,
      RowSet rows) {
    var rawPresent = value.containsKey("rawDecodedValues");
    Map<String, Object> rawValues = Map.of();
    if (rawPresent) {
      rawValues = asMap(value.get("rawDecodedValues"));
    }
    for (var targetText : mapping.targets()) {
      var target = Target.parse(targetText);
      if (target.table().equals("vin_decode_raw_value")) {
        continue;
      }
      if (target.column().equals("response_present")) {
        rows.root(target.table()).put(target.column(), true);
      } else if (target.column().equals("raw_decoded_values_present")) {
        rows.root(target.table()).put(target.column(), rawPresent);
      } else {
        var nested = findNested(value, target.column());
        if (nested.found()) {
          rows.root(target.table()).put(target.column(), nested.value());
        }
      }
    }
    var entries = rawValues.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    for (var ordinal = 0; ordinal < entries.size(); ordinal++) {
      var entry = entries.get(ordinal);
      var row = rows.child("vin_decode_raw_value", ordinal);
      row.put("field_name", entry.getKey());
      row.put("field_value", entry.getValue() == null ? null : entry.getValue().toString());
    }
  }

  private static void setRecordChild(
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Map<String, Object> value,
      RowSet rows,
      int ordinal) {
    for (var table : targetTables(mapping)) {
      var row = rows.child(table, ordinal);
      for (var targetText : mapping.targets()) {
        var target = Target.parse(targetText);
        if (!table.equals(target.table())) {
          continue;
        }
        var nested = findNested(value, target.column());
        if (nested.found()) {
          row.put(target.column(), nested.value());
        } else if (target.column().equals("ordinal")) {
          row.put(target.column(), ordinal);
        }
      }
    }
  }

  private static void setRecordList(
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Collection<?> values,
      RowSet rows) {
    var ordinal = 0;
    for (var value : values) {
      setRecordChild(mapping, asMap(value), rows, ordinal++);
    }
  }

  private static void setScalarList(
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Collection<?> values,
      RowSet rows) {
    var ordinal = 0;
    for (var value : values) {
      for (var table : targetTables(mapping)) {
        var row = rows.child(table, ordinal);
        for (var targetText : mapping.targets()) {
          var target = Target.parse(targetText);
          if (!table.equals(target.table())) {
            continue;
          }
          row.put(target.column(), target.column().equals("ordinal") ? ordinal : value);
        }
      }
      ordinal++;
    }
  }

  private static void setStringMap(
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Map<String, Object> values,
      RowSet rows) {
    var entries = values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    for (var ordinal = 0; ordinal < entries.size(); ordinal++) {
      var entry = entries.get(ordinal);
      for (var table : targetTables(mapping)) {
        var row = rows.child(table, ordinal);
        for (var targetText : mapping.targets()) {
          var target = Target.parse(targetText);
          if (!table.equals(target.table())) {
            continue;
          }
          var column = target.column();
          if (column.equals("ordinal")) {
            row.put(column, ordinal);
          } else if (column.endsWith("key") || column.endsWith("name")) {
            row.put(column, entry.getKey());
          } else {
            row.put(column, entry.getValue());
          }
        }
      }
    }
  }

  private static Set<String> targetTables(PostgresqlMigrationCatalog.FieldMapping mapping) {
    return mapping.targets().stream().map(Target::parse).map(Target::table)
        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
  }

  private static NestedValue findNested(Map<String, Object> values, String targetColumn) {
    var exact = values.entrySet().stream()
        .filter(entry -> snake(entry.getKey()).equals(targetColumn))
        .findFirst();
    var match = exact.or(() -> values.entrySet().stream()
        .filter(entry -> targetColumn.endsWith("_" + snake(entry.getKey())))
        .max(Comparator.comparingInt(entry -> entry.getKey().length())));
    return match.<NestedValue>map(entry -> new NestedValue(true, normalizeBson(entry.getValue())))
        .orElseGet(() -> new NestedValue(false, null));
  }

  private static Object convertScalar(String conversion, Object value) {
    try {
      return switch (conversion) {
        case "string" -> value.toString();
        case "uuid-string" -> value instanceof UUID uuid ? uuid.toString() : value.toString();
        case "enum-name" -> value instanceof Enum<?> enumeration
            ? enumeration.name() : value.toString();
        case "instant-utc" -> value instanceof Instant instant ? instant
            : value instanceof Date date ? date.toInstant() : Instant.parse(value.toString());
        case "local-date" -> value instanceof LocalDate date ? date : LocalDate.parse(value.toString());
        case "integer" -> number(value).intValueExact();
        case "long" -> number(value).longValueExact();
        case "boolean" -> value instanceof Boolean flag ? flag : invalidValue();
        case "decimal-12-2", "decimal-20-9" -> number(value);
        case "double" -> {
          var result = number(value).doubleValue();
          if (!Double.isFinite(result)) {
            throw invalid();
          }
          yield result;
        }
        case "byte-array" -> value instanceof byte[] bytes ? bytes.clone()
            : value instanceof Binary binary ? binary.getData().clone() : invalidValue();
        default -> throw invalid();
      };
    } catch (ArithmeticException | java.time.format.DateTimeParseException failure) {
      throw invalid();
    }
  }

  private static BigDecimal number(Object value) {
    if (value instanceof Decimal128 decimal128) {
      return decimal128.bigDecimalValue();
    }
    if (value instanceof Number number) {
      return new BigDecimal(number.toString());
    }
    throw invalid();
  }

  private static Object defaultValue(String conversion) {
    return switch (conversion) {
      case "boolean" -> false;
      case "integer" -> 0;
      case "long" -> 0L;
      default -> null;
    };
  }

  private static Object normalizedValue(String column, Object value) {
    var normalized = normalizeBson(value);
    if (normalized instanceof String text
        && (column.startsWith("normalized_") || column.contains("_normalized_"))) {
      return text.toLowerCase(Locale.ROOT);
    }
    return normalized;
  }

  private static Object normalizeBson(Object value) {
    if (value instanceof Date date) {
      return date.toInstant();
    }
    if (value instanceof Decimal128 decimal128) {
      return decimal128.bigDecimalValue();
    }
    if (value instanceof Binary binary) {
      return binary.getData().clone();
    }
    return value;
  }

  private static Map<String, Object> asMap(Object value) {
    if (!(value instanceof Map<?, ?> raw)
        || raw.keySet().stream().anyMatch(key -> !(key instanceof String))) {
      throw invalid();
    }
    var result = new LinkedHashMap<String, Object>();
    raw.forEach((key, nested) -> result.put((String) key, nested));
    return result;
  }

  private static Collection<?> asCollection(Object value) {
    if (value instanceof Collection<?> collection) {
      return collection;
    }
    throw invalid();
  }

  private static String snake(String camel) {
    return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
  }

  private static Object invalidValue() {
    throw invalid();
  }

  private static MigrationTransformationException invalid() {
    return new MigrationTransformationException();
  }

  private record Target(String table, String column) {
    static Target parse(String text) {
      var separator = text.indexOf('.');
      if (separator < 1 || separator == text.length() - 1) {
        throw invalid();
      }
      return new Target(text.substring(0, separator), text.substring(separator + 1));
    }
  }

  private record NestedValue(boolean found, Object value) {}

  private static final class RowSet {
    private final String schema;
    private final List<String> tableOrder;
    private final String sourceId;
    private final Map<String, List<LinkedHashMap<String, Object>>> rows = new LinkedHashMap<>();

    private RowSet(String schema, List<String> tableOrder, String sourceId) {
      this.schema = schema;
      this.tableOrder = tableOrder;
      this.sourceId = sourceId;
    }

    private LinkedHashMap<String, Object> root(String table) {
      return rows.computeIfAbsent(table, ignored -> new ArrayList<>())
          .stream().findFirst().orElseGet(() -> {
            var row = new LinkedHashMap<String, Object>();
            rows.get(table).add(row);
            return row;
          });
    }

    private LinkedHashMap<String, Object> child(String table, int ordinal) {
      var tableRows = rows.computeIfAbsent(table, ignored -> new ArrayList<>());
      while (tableRows.size() <= ordinal) {
        tableRows.add(new LinkedHashMap<>());
      }
      return tableRows.get(ordinal);
    }

    private List<MigrationRelationalRow> finish() {
      var result = new ArrayList<MigrationRelationalRow>();
      for (var table : tableOrder) {
        var tableRows = rows.getOrDefault(table, List.of());
        for (var ordinal = 0; ordinal < tableRows.size(); ordinal++) {
          result.add(new MigrationRelationalRow(
              schema, table, sourceId, ordinal, tableRows.get(ordinal)));
        }
      }
      if (result.isEmpty()) {
        throw invalid();
      }
      return List.copyOf(result);
    }
  }
}
