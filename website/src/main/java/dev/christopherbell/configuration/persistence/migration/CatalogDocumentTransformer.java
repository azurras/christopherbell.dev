package dev.christopherbell.configuration.persistence.migration;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;

/** Catalog-driven transformer shared by the 52 exact kind bindings. */
abstract class CatalogDocumentTransformer implements MigrationTransformer {
  private static final Map<String, Map<String, String>> COMPLEX_PATHS = Map.ofEntries(
      paths("account.federationIdentity",
          "account_federation_identity.actor_id=actorId",
          "account_federation_identity.key_id=keyId",
          "account_federation_identity.public_key_pem=publicKeyPem",
          "account_federation_identity.private_key_nonce=encryptedPrivateKey.nonce",
          "account_federation_identity.private_key_ciphertext=encryptedPrivateKey.ciphertext",
          "account_federation_identity.key_version=keyVersion",
          "account_federation_identity.created_on=createdOn"),
      paths("post.editAudit", "post_edit_audit.ordinal=$ordinal",
          "post_edit_audit.editor_account_id=editorAccountId",
          "post_edit_audit.before_text=beforeText", "post_edit_audit.after_text=afterText",
          "post_edit_audit.edited_on=editedOn"),
      paths("post.topics", "post_topic.ordinal=$ordinal", "post_topic.canonical=canonical",
          "post_topic.display=display"),
      paths("post.linkPreviews", "post_link_preview.ordinal=$ordinal",
          "post_link_preview.url=url", "post_link_preview.domain_name=domain",
          "post_link_preview.title=title", "post_link_preview.description=description",
          "post_link_preview.image_url=imageUrl"),
      paths("post_link_preview_cache.preview", "post_link_preview_cache.preview_url=url",
          "post_link_preview_cache.preview_domain=domain",
          "post_link_preview_cache.preview_title=title",
          "post_link_preview_cache.preview_description=description",
          "post_link_preview_cache.preview_image_url=imageUrl"),
      paths("music_runtime_state.radio", "runtime_state.station_sequence=stationSequence",
          "runtime_state.track_id=trackId", "runtime_state.observed_token=observedToken",
          "runtime_state.started_at=startedAt", "runtime_state.duration_seconds=durationSeconds",
          "runtime_state.radio_source=source", "runtime_state.queue_entry_id=queueEntryId"),
      paths("restaurant.address", "restaurant.city=city", "restaurant.county=county",
          "restaurant.country=country", "restaurant.latitude=latitude",
          "restaurant.longitude=longitude", "restaurant.postal_code=postalCode",
          "restaurant.region=state", "restaurant.street_1=street1",
          "restaurant.street_2=street2"),
      paths("import_state.lastResult", "restaurant_import_state.result_source=source",
          "restaurant_import_state.result_fetched=fetched",
          "restaurant_import_state.result_imported=imported",
          "restaurant_import_state.result_updated=updated",
          "restaurant_import_state.result_skipped_existing=skippedExisting",
          "restaurant_import_state.result_skipped_invalid=skippedInvalid"),
      paths("import_preview.counts", "restaurant_import_preview.fetched_count=fetched",
          "restaurant_import_preview.created_count=created",
          "restaurant_import_preview.updated_count=updated",
          "restaurant_import_preview.deleted_count=deleted",
          "restaurant_import_preview.unchanged_count=unchanged",
          "restaurant_import_preview.invalid_count=invalid"),
      paths("radio_state.knownDurations", "radio_track_duration.ordinal=$ordinal",
          "radio_track_duration.relative_path=path",
          "radio_track_duration.observed_token=observedToken",
          "radio_track_duration.duration_seconds=durationSeconds"),
      paths("random_vin_import_state.robotsPolicy",
          "random_vin_import_state.robots_policy_present=$present",
          "random_vin_import_state.robots_checked_on=checkedOn",
          "random_vin_import_state.robots_allowed=allowed",
          "random_vin_import_state.robots_reason=reason",
          "random_vin_import_state.robots_fail_closed=failClosed"),
      paths("zip_import_state.result", "zip_import_state.result_processed=processed",
          "zip_import_state.result_created=created", "zip_import_state.result_updated=updated",
          "zip_import_state.result_unchanged=unchanged", "zip_import_state.result_deleted=deleted",
          "zip_import_state.result_source=source", "zip_import_state.result_source_year=sourceYear",
          "zip_import_state.result_checksum=checksum",
          "zip_import_state.result_imported_on=importedOn",
          "zip_import_state.result_no_op=noOp"),
      paths("price_snapshot.metroPrices", "metro_price.ordinal=$ordinal",
          "metro_price.metro_name=metroName", "metro_price.city=city", "metro_price.region=state",
          "metro_price.restaurant_ref=restaurantRef",
          "metro_price.restaurant_name=restaurantName", "metro_price.address=address",
          "metro_price.source_url=sourceUrl", "metro_price.price=price",
          "metro_price.currency=currency", "metro_price.status=status",
          "metro_price.source_name=sourceName", "metro_price.quality_status=qualityStatus",
          "metro_price.confidence_level=confidenceLevel",
          "metro_price.raw_response_hash=rawResponseHash",
          "metro_price.matched_item_name=matchedItemName",
          "metro_price.failure_reason=failureReason", "metro_price.review_note=reviewNote",
          "metro_price.collected_on=collectedOn",
          "metro_price.source_fetched_on=sourceFetchedOn",
          "metro_price.reviewed_on=reviewedOn"),
      paths("domain_collection_cutover.expectedKindMetrics",
          "domain_collection_cutover_metric.ordinal=$ordinal",
          "domain_collection_cutover_metric.source_kind=kind",
          "domain_collection_cutover_metric.source_count=count",
          "domain_collection_cutover_metric.checksum=checksum"));
  private static final Set<String> SPECIAL_COMPLEX_KEYS = Set.of(
      "account.pendingModerationAudit", "post_report.pendingModerationAudit",
      "music_runtime_state.queue", "session.participantUsernamesByAccountId",
      "session.votesByAccountId", "session.restaurantResetAudit",
      "upload_session.chunkDigests", "upload_session.chunkLengths",
      "vehicle.nhtsaDecodedValues", "vin_decode_cache.response",
      "admin_activity.beforeValues", "admin_activity.afterValues", "admin_activity.metadata");

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
    validateCrossFieldShape(source);
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
        applyPresent(sourceField, mapping, value, rows);
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

  private void validateCrossFieldShape(MigrationSourceDocument source) {
    if (!"upload_session".equals(kind.sourceKind())) {
      return;
    }
    var digests = asMap(source.payload().getOrDefault("chunkDigests", Map.of()));
    var lengths = asMap(source.payload().getOrDefault("chunkLengths", Map.of()));
    if (!digests.keySet().equals(lengths.keySet())) {
      throw invalid();
    }
  }

  private void requireSource(MigrationSourceDocument source) {
    if (source == null
        || !kind.sourceKind().equals(source.sourceKind())
        || kind.sourceSchemaVersion() != source.schemaVersion()
        || source.sourceId() == null
        || source.sourceId().isBlank()
        || !validIdentifier(source.sourceId())
        || source.payload() == null
        || !kind.fieldMappings().keySet().containsAll(source.payload().keySet())) {
      throw invalid();
    }
  }

  private boolean validIdentifier(String sourceId) {
    return switch (kind.identifierType()) {
      case "string" -> true;
      case "uuid-string" -> {
        try {
          yield UUID.fromString(sourceId).toString().equals(sourceId);
        } catch (IllegalArgumentException failure) {
          yield false;
        }
      }
      default -> false;
    };
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

  private void applyPresent(
      String sourceField,
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Object value,
      RowSet rows) {
    var mappingKey = kind.sourceKind() + "." + sourceField;
    if (Set.of("record-flattened", "preserve-ledger", "vin-response-flattened",
            "record-child", "record-list-child", "string-map-child")
        .contains(mapping.conversion())
        && !COMPLEX_PATHS.containsKey(mappingKey)
        && !SPECIAL_COMPLEX_KEYS.contains(mappingKey)) {
      throw invalid();
    }
    switch (mapping.conversion()) {
      case "constant-kind" -> {
        // Envelope kind is validated separately and has no relational value.
      }
      case "string", "uuid-string", "enum-name", "instant-utc", "local-date",
          "integer", "long", "boolean", "decimal-12-2", "decimal-20-9", "double",
          "byte-array" -> setScalarTargets(
              mapping, convertScalar(mapping.conversion(), value), rows, false);
      case "record-flattened", "preserve-ledger" ->
          setFlattenedTargets(mappingKey, mapping, asMap(value), rows);
      case "vin-response-flattened" -> setVinResponse(mapping, asMap(value), rows);
      case "record-child" -> setRecordChild(mappingKey, mapping, asMap(value), rows);
      case "record-list-child" ->
          setRecordList(mappingKey, mapping, asCollection(value), rows);
      case "string-list-child", "string-set-child" ->
          setScalarList(mappingKey, mapping, asCollection(value), rows);
      case "string-map-child" -> setStringMap(mappingKey, mapping, asMap(value), rows);
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
      String mappingKey,
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Map<String, Object> value,
      RowSet rows) {
    projectExact(mappingKey, mapping, value, rows, null, 0);
  }

  private static void setVinResponse(
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Map<String, Object> value,
      RowSet rows) {
    requireExactKeys(value, Set.of(
        "vin", "make", "model", "year", "body", "plantCity", "plantState",
        "plantCountry", "errorCode", "errorText", "rawDecodedValues"));
    var rawPresent = value.containsKey("rawDecodedValues");
    Map<String, Object> rawValues = Map.of();
    if (rawPresent) {
      rawValues = asMap(value.get("rawDecodedValues"));
      requireScalarValues(rawValues);
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
        var sourcePath = switch (target.column()) {
          case "response_vin" -> "vin";
          case "make" -> "make";
          case "model" -> "model";
          case "body" -> "body";
          case "plant_city" -> "plantCity";
          case "plant_state" -> "plantState";
          case "plant_country" -> "plantCountry";
          case "error_code" -> "errorCode";
          case "error_text" -> "errorText";
          case "model_year" -> "year";
          default -> throw invalid();
        };
        var nested = readExact(value, sourcePath);
        if (nested.found()) {
          rows.root(target.table()).put(target.column(), nested.value());
        }
      }
    }
    var entries = rawValues.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    for (var ordinal = 0; ordinal < entries.size(); ordinal++) {
      var entry = entries.get(ordinal);
      var row = rows.child("vin_decode_raw_value", "field:" + entry.getKey());
      row.put("field_name", entry.getKey());
      row.put("field_value", entry.getValue() == null ? null : entry.getValue().toString());
    }
  }

  private static void setRecordChild(
      String mappingKey,
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Map<String, Object> value,
      RowSet rows) {
    if (mappingKey.endsWith(".pendingModerationAudit")) {
      setModerationAudit(mapping, value, rows);
      return;
    }
    if ("music_runtime_state.queue".equals(mappingKey)) {
      requireExactKeys(value, Set.of("entries"));
      var entries = readExact(value, "entries");
      if (!entries.found()) {
        throw invalid();
      }
      setQueueEntries(mapping, asCollection(entries.value()), rows);
      return;
    }
    projectExact(mappingKey, mapping, value, rows, "record", 0);
  }

  private static void setRecordList(
      String mappingKey,
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Collection<?> values,
      RowSet rows) {
    if ("session.restaurantResetAudit".equals(mappingKey)) {
      setLunchResetAudit(mapping, values, rows);
      return;
    }
    var ordinal = 0;
    for (var value : values) {
      projectExact(mappingKey, mapping, asMap(value), rows, "ordinal:" + ordinal, ordinal++);
    }
  }

  private static void setScalarList(
      String mappingKey,
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Collection<?> values,
      RowSet rows) {
    var ordered = new ArrayList<>(values);
    if ("string-set-child".equals(mapping.conversion())) {
      ordered.sort(java.util.Comparator.comparing(Object::toString));
    }
    var ordinal = 0;
    for (var value : ordered) {
      for (var table : targetTables(mapping)) {
        var rowKey = "session.participantAccountIds".equals(mappingKey)
            ? "account:" + value : "ordinal:" + ordinal;
        var row = rows.child(table, rowKey);
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
      String mappingKey,
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Map<String, Object> values,
      RowSet rows) {
    var entries = values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    for (var ordinal = 0; ordinal < entries.size(); ordinal++) {
      var entry = entries.get(ordinal);
      setExactMapEntry(mappingKey, mapping, entry.getKey(), entry.getValue(), ordinal, rows);
    }
  }

  private static Set<String> targetTables(PostgresqlMigrationCatalog.FieldMapping mapping) {
    var result = new java.util.LinkedHashSet<String>();
    mapping.targets().stream().map(Target::parse).map(Target::table).forEach(result::add);
    return result;
  }

  private static void setModerationAudit(
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Map<String, Object> value,
      RowSet rows) {
    requireExactKeys(value, Set.of(
        "eventId", "actorAccountId", "actorUsername", "action", "targetType", "targetId",
        "targetLabel", "reason", "message", "beforeValues", "afterValues", "metadata",
        "metadataValues"));
    var auditTable = mapping.targets().stream().map(Target::parse)
        .map(Target::table).filter(table -> !table.endsWith("_value")).findFirst()
        .orElseThrow(CatalogDocumentTransformer::invalid);
    var valueTable = mapping.targets().stream().map(Target::parse)
        .map(Target::table).filter(table -> table.endsWith("_value")).findFirst()
        .orElseThrow(CatalogDocumentTransformer::invalid);
    var audit = rows.child(auditTable, "audit");
    var auditColumns = Map.ofEntries(
        Map.entry("event_id", "eventId"), Map.entry("actor_account_id", "actorAccountId"),
        Map.entry("actor_username", "actorUsername"), Map.entry("action", "action"),
        Map.entry("target_type", "targetType"), Map.entry("target_id", "targetId"),
        Map.entry("target_label", "targetLabel"), Map.entry("reason", "reason"),
        Map.entry("message", "message"));
    auditColumns.forEach((column, sourcePath) -> {
      var nested = readExact(value, sourcePath);
      if (nested.found()) {
        audit.put(column, nested.value());
      }
    });
    for (var partition : List.of("before", "after", "metadata")) {
      var nested = readExact(value, partition + "Values");
      if (!nested.found() && "metadata".equals(partition)) {
        nested = readExact(value, "metadata");
      }
      if (!nested.found()) {
        continue;
      }
      var partitionValues = asMap(nested.value());
      requireScalarValues(partitionValues);
      for (var entry : partitionValues.entrySet().stream()
          .sorted(Map.Entry.comparingByKey()).toList()) {
        var row = rows.child(valueTable, partition + ":" + entry.getKey());
        row.put("partition_name", partition);
        row.put("value_key", entry.getKey());
        row.put("value", Objects.toString(entry.getValue(), null));
      }
    }
  }

  private static void setQueueEntries(
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Collection<?> entries,
      RowSet rows) {
    var ordinal = 0;
    for (var rawEntry : entries) {
      var entry = asMap(rawEntry);
      requireExactKeys(entry, Set.of(
          "id", "trackId", "observedToken", "enqueuedByAccountId", "enqueuedAt"));
      var row = rows.child("queue_entry", "ordinal:" + ordinal);
      row.put("ordinal", ordinal++);
      putExact(row, "queue_entry_id", entry, "id");
      putExact(row, "track_id", entry, "trackId");
      putExact(row, "observed_token", entry, "observedToken");
      putExact(row, "enqueued_by_account_id", entry, "enqueuedByAccountId");
      putExact(row, "enqueued_at", entry, "enqueuedAt");
    }
  }

  private static void setLunchResetAudit(
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Collection<?> values,
      RowSet rows) {
    var resetOrdinal = 0;
    for (var rawValue : values) {
      var value = asMap(rawValue);
      requireExactKeys(value, Set.of(
          "revision", "accountId", "username", "restaurantIds", "occurredOn"));
      var audit = rows.child("lunch_session_reset_audit", "reset:" + resetOrdinal);
      audit.put("ordinal", resetOrdinal);
      putExact(audit, "revision", value, "revision");
      putExact(audit, "account_id", value, "accountId");
      putExact(audit, "username", value, "username");
      putExact(audit, "occurred_on", value, "occurredOn");
      var restaurants = readExact(value, "restaurantIds");
      if (!restaurants.found()) {
        throw invalid();
      }
      var restaurantOrdinal = 0;
      for (var restaurantId : asCollection(restaurants.value())) {
        var child = rows.child("lunch_session_reset_restaurant",
            "reset:" + resetOrdinal + ":restaurant:" + restaurantOrdinal);
        child.put("reset_ordinal", resetOrdinal);
        child.put("restaurant_ordinal", restaurantOrdinal++);
        child.put("restaurant_id", normalizeBson(restaurantId));
      }
      resetOrdinal++;
    }
  }

  private static void setExactMapEntry(
      String mappingKey,
      PostgresqlMigrationCatalog.FieldMapping mapping,
      String key,
      Object value,
      int ordinal,
      RowSet rows) {
    requireScalarValue(value);
    switch (mappingKey) {
      case "session.participantUsernamesByAccountId" -> {
        var row = rows.child("lunch_session_participant", "account:" + key);
        row.put("account_id", key);
        row.put("username", normalizeBson(value));
      }
      case "session.votesByAccountId" -> {
        var row = rows.child("lunch_session_vote", "account:" + key);
        row.put("account_id", key);
        row.put("restaurant_id", normalizeBson(value));
      }
      case "upload_session.chunkDigests" -> {
        var row = rows.child("upload_chunk", "chunk:" + key);
        row.put("chunk_key", key);
        row.put("digest", normalizeBson(value));
      }
      case "upload_session.chunkLengths" -> {
        var row = rows.child("upload_chunk", "chunk:" + key);
        row.put("chunk_key", key);
        row.put("chunk_length", convertScalar("long", value));
      }
      case "vehicle.nhtsaDecodedValues" -> {
        var row = rows.child("vehicle_decoded_value", "field:" + key);
        row.put("field_name", key);
        row.put("field_value", Objects.toString(value, null));
      }
      case "admin_activity.beforeValues", "admin_activity.afterValues",
          "admin_activity.metadata" -> {
        var partition = mappingKey.substring("admin_activity.".length())
            .replace("Values", "");
        var row = rows.child("admin_activity_value", partition + ":" + key);
        row.put("partition_name", partition);
        row.put("value_key", key);
        row.put("value_text", Objects.toString(value, null));
      }
      default -> throw invalid();
    }
  }

  private static void projectExact(
      String mappingKey,
      PostgresqlMigrationCatalog.FieldMapping mapping,
      Map<String, Object> value,
      RowSet rows,
      String rowKey,
      int ordinal) {
    var sourcePaths = COMPLEX_PATHS.get(mappingKey);
    if (sourcePaths == null || !sourcePaths.keySet().equals(Set.copyOf(mapping.targets()))) {
      throw invalid();
    }
    requireExactPaths(value, sourcePaths.values().stream()
        .filter(path -> !path.startsWith("$"))
        .collect(java.util.stream.Collectors.toUnmodifiableSet()), "");
    for (var targetText : mapping.targets()) {
      var target = Target.parse(targetText);
      var expression = sourcePaths.get(targetText);
      var destination = rowKey == null ? rows.root(target.table())
          : rows.child(target.table(), rowKey);
      if ("$ordinal".equals(expression)) {
        destination.put(target.column(), ordinal);
      } else if ("$present".equals(expression)) {
        destination.put(target.column(), true);
      } else {
        var nested = readExact(value, expression);
        if (nested.found()) {
          destination.put(target.column(), nested.value());
        }
      }
    }
  }

  private static void putExact(
      Map<String, Object> destination,
      String targetColumn,
      Map<String, Object> source,
      String sourcePath) {
    var nested = readExact(source, sourcePath);
    if (nested.found()) {
      destination.put(targetColumn, nested.value());
    }
  }

  private static NestedValue readExact(Map<String, Object> values, String path) {
    Object current = values;
    for (var segment : path.split("\\.")) {
      if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
        return new NestedValue(false, null);
      }
      current = map.get(segment);
    }
    return new NestedValue(true, normalizeBson(current));
  }

  private static void requireExactPaths(
      Map<String, Object> values, Set<String> paths, String prefix) {
    for (var entry : values.entrySet()) {
      var path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
      var exact = paths.contains(path);
      var nested = paths.stream().anyMatch(candidate -> candidate.startsWith(path + "."));
      if (!exact && !nested) {
        throw invalid();
      }
      if (nested && entry.getValue() != null) {
        requireExactPaths(asMap(entry.getValue()), paths, path);
      } else if (exact) {
        requireScalarValue(entry.getValue());
      }
    }
  }

  private static void requireExactKeys(Map<String, Object> values, Set<String> allowed) {
    if (!allowed.containsAll(values.keySet())) {
      throw invalid();
    }
  }

  private static void requireScalarValues(Map<String, Object> values) {
    values.values().forEach(CatalogDocumentTransformer::requireScalarValue);
  }

  private static void requireScalarValue(Object value) {
    if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
      throw invalid();
    }
  }

  private static Map.Entry<String, Map<String, String>> paths(
      String mappingKey, String... bindings) {
    var result = new LinkedHashMap<String, String>();
    for (var binding : bindings) {
      var separator = binding.indexOf('=');
      if (separator < 1 || separator == binding.length() - 1
          || result.put(binding.substring(0, separator), binding.substring(separator + 1)) != null) {
        throw new IllegalStateException("Invalid production migration mapping: " + mappingKey);
      }
    }
    return Map.entry(mappingKey, Map.copyOf(result));
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
    if ("vote_value".equals(column) && normalized instanceof String vote) {
      return switch (vote) {
        case "UP" -> 1;
        case "DOWN" -> -1;
        default -> throw invalid();
      };
    }
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
    private final Map<String, LinkedHashMap<String, LinkedHashMap<String, Object>>> rows =
        new LinkedHashMap<>();

    private RowSet(String schema, List<String> tableOrder, String sourceId) {
      this.schema = schema;
      this.tableOrder = tableOrder;
      this.sourceId = sourceId;
    }

    private LinkedHashMap<String, Object> root(String table) {
      return rows.computeIfAbsent(table, ignored -> new LinkedHashMap<>())
          .computeIfAbsent("$root", ignored -> new LinkedHashMap<>());
    }

    private LinkedHashMap<String, Object> child(String table, String rowKey) {
      return rows.computeIfAbsent(table, ignored -> new LinkedHashMap<>())
          .computeIfAbsent(rowKey, ignored -> new LinkedHashMap<>());
    }

    private List<MigrationRelationalRow> finish() {
      var result = new ArrayList<MigrationRelationalRow>();
      for (var table : tableOrder) {
        var tableRows = rows.getOrDefault(table, new LinkedHashMap<>()).values().stream().toList();
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
