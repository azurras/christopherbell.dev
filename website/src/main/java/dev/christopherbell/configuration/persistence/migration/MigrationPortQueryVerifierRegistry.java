package dev.christopherbell.configuration.persistence.migration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed executable registry for every Mongo port query retained by the migration catalog. */
final class MigrationPortQueryVerifierRegistry {
  private static final Pattern SQL_TYPE = Pattern.compile("[a-z][a-z0-9_ ]*(?:\\[\\])?");
  private static final Set<String> NAMES = Set.of(
      "account-favorite-page", "account-page", "action-page", "active-job-page",
      "active-session", "actor-created-page", "actor-page", "album-page", "artist-page",
      "author-feed-page", "city-inventory-page", "claim-expired-lease", "cleanup-due-page",
      "collector-state", "conversation-page", "created-page", "creator-session-page",
      "discovery-page", "due-job-page", "expiration-page", "expired-claim-page",
      "expiry-page", "federation-actor-page", "find-by-account",
      "find-by-account-and-root", "find-by-email", "find-by-id",
      "find-by-normalized-name", "find-by-owner-and-conversation", "find-by-path",
      "find-by-pick-date", "find-by-post-and-account", "find-by-post-and-peer",
      "find-by-restaurant-and-account", "find-by-url", "find-by-username", "find-by-vin",
      "find-by-zip-code", "find-open-dedupe", "followed-page", "follower-page",
      "genre-page", "global-queue", "global-radio", "import-state", "incoming-block-page",
      "lease-recovery-page", "least-recently-used-page", "location-inventory-page",
      "maintenance-due-page", "moderation-page", "occurred-at-page", "occurred-page",
      "outbound-create-cursor", "outcome-page", "owner-page", "owner-state-page",
      "participant-page", "participant-session-page", "path-page", "pending-machine-power",
      "playlist-track-order", "post-like-page", "public-feed-page", "radio-candidate-page",
      "recovery-due-page", "restaurant-vote-page", "scheduler-state", "state-deleted-page",
      "state-inventory-page", "station-sequence-page", "station-state",
      "status-completed-page", "status-page", "target-active-ledger", "target-page",
      "thread-page", "track-edit-page", "unread-by-account", "unread-by-sender",
      "updated-page", "weekly-snapshot-page");
  private static final Set<String> STOP_WORDS = Set.of(
      "find", "by", "and", "page", "global", "inventory", "cursor", "feed", "order");
  private static final Set<String> DEADLINE_WORDS = Set.of(
      "expiration", "expiry", "expired", "due", "cleanup", "recovery", "lease");

  private MigrationPortQueryVerifierRegistry() {}

  static MigrationPortQueryVerifierRegistry standard() {
    return new MigrationPortQueryVerifierRegistry();
  }

  static MigrationPortQueryVerifierRegistry from(PostgresqlMigrationCatalog catalog) {
    var declared = new LinkedHashSet<String>();
    catalog.kinds().forEach(kind -> declared.addAll(kind.portQueries()));
    if (!declared.equals(NAMES)) {
      throw new IllegalArgumentException("PostgreSQL migration port-query registry is invalid.");
    }
    return standard();
  }

  Set<String> names() {
    return NAMES;
  }

  boolean verify(
      Connection connection,
      String schemaPrefix,
      String platformSchema,
      UUID runId,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationRowCodec codec) throws SQLException {
    if (kind.portQueries().isEmpty() || !NAMES.containsAll(kind.portQueries())) {
      return false;
    }
    var snapshots = new ArrayList<TableSnapshot>();
    for (var table : kind.targetTables()) {
      var metadata = metadata(connection, schemaPrefix + kind.targetSchema(), table);
      var expected = expectedRows(
          connection, platformSchema, runId, kind, table, metadata.primaryKeys(), codec);
      snapshots.add(new TableSnapshot(table, metadata, expected));
    }
    for (var queryName : kind.portQueries()) {
      var snapshot = selectSnapshot(queryName, snapshots, kind.targetTables().getFirst());
      if (!executeRule(
          connection, schemaPrefix + kind.targetSchema(), snapshot,
          queryName)) {
        return false;
      }
    }
    return true;
  }

  private static boolean executeRule(
      Connection connection,
      String schema,
      TableSnapshot snapshot,
      String queryName) throws SQLException {
    var metadata = snapshot.metadata();
    var sourceRows = snapshot.rows();
    var identity = metadata.primaryKeys();
    if (identity.isEmpty()) {
      return false;
    }
    var deadline = deadline(queryName);
    var selectedCandidates = selectColumns(
        queryName, metadata.types().keySet(), identity.getFirst());
    var selected = deadline
        ? deadlineFirst(selectedCandidates, metadata.types().keySet()) : selectedCandidates;
    var filters = filterColumns(queryName, selected, identity.getFirst(), deadline);
    var representative = sourceRows.isEmpty() ? null : sourceRows.getFirst();
    var boundary = deadline ? maximum(sourceRows, selected.getFirst()) : null;
    var expected = sourceRows.stream()
        .filter(row -> matches(row, filters, representative, selected.getFirst(), boundary, deadline))
        .sorted(expectedComparator(selected, identity))
        .map(row -> identity(row, identity))
        .toList();
    var sql = new StringBuilder("select ")
        .append(identity.stream().map(key -> quoted(key) + "::text")
            .collect(java.util.stream.Collectors.joining(", ")))
        .append(" from ").append(quoted(schema)).append('.').append(quoted(snapshot.table()));
    var parameters = new ArrayList<Parameter>();
    if (representative != null && deadline) {
      var deadlineColumn = selected.getFirst();
      sql.append(" where ").append(quoted(deadlineColumn)).append(" is not null");
      if (boundary != null) {
        sql.append(" and ").append(quoted(deadlineColumn)).append("<=cast(? as ")
            .append(sqlType(metadata.types().get(deadlineColumn))).append(')');
        parameters.add(new Parameter(boundary));
      }
    } else if (representative != null && !filters.isEmpty()) {
      sql.append(" where ");
      for (var index = 0; index < filters.size(); index++) {
        if (index > 0) {
          sql.append(" and ");
        }
        var filter = filters.get(index);
        var value = representative.values().get(filter);
        if (value == null) {
          sql.append(quoted(filter)).append(" is null");
        } else {
          sql.append(quoted(filter)).append("=cast(? as ")
              .append(sqlType(metadata.types().get(filter))).append(')');
          parameters.add(new Parameter(value));
        }
      }
    }
    sql.append(" order by ");
    for (var index = 0; index < selected.size(); index++) {
      if (index > 0) {
        sql.append(", ");
      }
      sql.append(quoted(selected.get(index))).append(" nulls last");
    }
    for (var key : identity) {
      if (!selected.contains(key)) {
        sql.append(", ").append(quoted(key));
      }
    }
    try (var statement = connection.prepareStatement(sql.toString())) {
      for (var index = 0; index < parameters.size(); index++) {
        statement.setString(index + 1, parameterText(parameters.get(index).value()));
      }
      try (var rows = statement.executeQuery()) {
        var actual = new ArrayList<List<String>>();
        while (rows.next()) {
          var rowIdentity = new ArrayList<String>();
          for (var index = 0; index < identity.size(); index++) {
            rowIdentity.add(rows.getString(index + 1));
          }
          actual.add(List.copyOf(rowIdentity));
        }
        return actual.equals(expected);
      }
    }
  }

  private static List<ExpectedRow> expectedRows(
      Connection connection,
      String platformSchema,
      UUID runId,
      PostgresqlMigrationCatalog.Kind kind,
      String table,
      List<String> primaryKeys,
      MigrationRowCodec codec) throws SQLException {
    var result = new ArrayList<ExpectedRow>();
    try (var statement = connection.prepareStatement(
        "select source_id, target_ordinal, row_payload from " + quoted(platformSchema)
            + ".persistence_migration_staged_row where run_id=? and source_kind=? "
            + "and target_table=? order by source_id, row_ordinal")) {
      statement.setObject(1, runId);
      statement.setString(2, kind.sourceKind());
      statement.setString(3, table);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          var sourceId = rows.getString(1);
          var targetOrdinal = rows.getInt(2);
          var values = new LinkedHashMap<>(codec.decode(rows.getBytes(3)));
          for (var key : primaryKeys) {
            if (!values.containsKey(key)) {
              values.put(key, key.equals("ordinal") ? targetOrdinal : sourceId);
            }
          }
          result.add(new ExpectedRow(
              java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values))));
        }
      }
    }
    return List.copyOf(result);
  }

  private static TableMetadata metadata(
      Connection connection, String schema, String table) throws SQLException {
    var types = new LinkedHashMap<String, String>();
    try (var columns = connection.getMetaData().getColumns(null, schema, table, null)) {
      while (columns.next()) {
        types.put(columns.getString("COLUMN_NAME"), columns.getString("TYPE_NAME"));
      }
    }
    var primaryKeys = new ArrayList<Map.Entry<Short, String>>();
    try (var keys = connection.getMetaData().getPrimaryKeys(null, schema, table)) {
      while (keys.next()) {
        primaryKeys.add(Map.entry(keys.getShort("KEY_SEQ"), keys.getString("COLUMN_NAME")));
      }
    }
    primaryKeys.sort(Map.Entry.comparingByKey());
    return new TableMetadata(
        Map.copyOf(types), primaryKeys.stream().map(Map.Entry::getValue).toList());
  }

  private static TableSnapshot selectSnapshot(
      String queryName, List<TableSnapshot> snapshots, String rootTable) {
    return snapshots.stream().max(Comparator
        .comparingInt((TableSnapshot snapshot) -> tableScore(queryName, snapshot))
        .thenComparing(snapshot -> snapshot.table().equals(rootTable) ? 1 : 0))
        .orElseThrow();
  }

  private static int tableScore(String queryName, TableSnapshot snapshot) {
    var score = 0;
    for (var token : queryName.split("-")) {
      if (STOP_WORDS.contains(token)) {
        continue;
      }
      for (var alias : aliases(token)) {
        if (snapshot.table().contains(alias)) {
          score += 4;
        }
        if (snapshot.metadata().types().keySet().stream()
            .anyMatch(column -> column.contains(alias))) {
          score++;
        }
      }
    }
    return score;
  }

  private static List<String> selectColumns(
      String queryName, Set<String> columns, String rootKey) {
    var result = new LinkedHashSet<String>();
    for (var token : queryName.split("-")) {
      if (STOP_WORDS.contains(token)) {
        continue;
      }
      var aliases = aliases(token);
      columns.stream().filter(candidate -> aliases.stream().anyMatch(alias ->
              candidate.equals(alias) || candidate.startsWith(alias + "_")
                  || candidate.endsWith("_" + alias) || candidate.contains("_" + alias + "_")))
          .sorted().findFirst().ifPresent(result::add);
    }
    if (result.isEmpty() || queryName.equals("find-by-id")) {
      result.add(rootKey);
    }
    return List.copyOf(result);
  }

  private static List<String> deadlineFirst(List<String> selected, Set<String> columns) {
    var deadline = columns.stream().filter(column -> Set.of(
            "expires_at", "expires_on", "delete_at", "delete_on", "retry_at",
            "maintenance_retry_at", "next_attempt_on", "claim_until", "disabled_until")
        .contains(column)).sorted().findFirst();
    if (deadline.isEmpty()) {
      return selected;
    }
    var result = new LinkedHashSet<String>();
    result.add(deadline.orElseThrow());
    result.addAll(selected);
    return List.copyOf(result);
  }

  private static Set<String> aliases(String token) {
    return switch (token) {
      case "expiration", "expiry", "expired" ->
          Set.of("expires", "expiry", "delete", "claim_until");
      case "due", "cleanup", "recovery" ->
          Set.of("expires", "retry", "next_attempt", "delete", "maintenance");
      case "created", "create" -> Set.of("created", "enqueued");
      case "updated", "edit" -> Set.of("updated", "edited", "last_updated");
      case "occurred" -> Set.of("occurred", "created");
      case "active", "completed", "open" -> Set.of("status", "state", "expires");
      case "normalized" -> Set.of("normalized_name", "normalized_email");
      case "zip" -> Set.of("zip_code");
      case "pick" -> Set.of("pick_date");
      case "least", "recently", "used" -> Set.of("last_seen", "last_updated", "updated");
      case "weekly", "snapshot" -> Set.of("week_start_date", "collected_on");
      case "sequence" -> Set.of("station_sequence", "ordinal");
      case "unread" -> Set.of("read_on", "created_on");
      default -> Set.of(token);
    };
  }

  private static List<String> filterColumns(
      String queryName, List<String> selected, String rootKey, boolean deadline) {
    if (deadline) {
      return List.of();
    }
    var filtered = queryName.startsWith("find-")
        || queryName.contains("-state")
        || queryName.contains("status-")
        || queryName.startsWith("unread-")
        || queryName.contains("owner-")
        || queryName.contains("account-")
        || queryName.contains("actor-")
        || queryName.contains("participant-")
        || queryName.contains("target-")
        || queryName.contains("station-")
        || queryName.contains("active-");
    if (!filtered || selected.equals(List.of(rootKey)) && !queryName.startsWith("find-")) {
      return List.of();
    }
    return selected;
  }

  private static boolean deadline(String queryName) {
    for (var token : queryName.split("-")) {
      if (DEADLINE_WORDS.contains(token)) {
        return true;
      }
    }
    return false;
  }

  private static Object maximum(List<ExpectedRow> rows, String column) {
    return rows.stream().map(row -> row.values().get(column)).filter(java.util.Objects::nonNull)
        .max(MigrationPortQueryVerifierRegistry::compareValues).orElse(null);
  }

  private static boolean matches(
      ExpectedRow row,
      List<String> filters,
      ExpectedRow representative,
      String deadlineColumn,
      Object boundary,
      boolean deadline) {
    if (deadline) {
      var value = row.values().get(deadlineColumn);
      return boundary != null && value != null && compareValues(value, boundary) <= 0;
    }
    if (representative == null) {
      return true;
    }
    return filters.stream().allMatch(column -> java.util.Objects.deepEquals(
        row.values().get(column), representative.values().get(column)));
  }

  private static Comparator<ExpectedRow> expectedComparator(
      List<String> selected, List<String> identity) {
    return (left, right) -> {
      for (var column : selected) {
        var compared = compareNullable(left.values().get(column), right.values().get(column));
        if (compared != 0) {
          return compared;
        }
      }
      for (var key : identity) {
        var compared = compareNullable(left.values().get(key), right.values().get(key));
        if (compared != 0) {
          return compared;
        }
      }
      return 0;
    };
  }

  private static List<String> identity(ExpectedRow row, List<String> primaryKeys) {
    return primaryKeys.stream().map(key -> parameterText(row.values().get(key))).toList();
  }

  private static int compareNullable(Object left, Object right) {
    if (left == right) {
      return 0;
    }
    if (left == null) {
      return 1;
    }
    if (right == null) {
      return -1;
    }
    return compareValues(left, right);
  }

  private static int compareValues(Object left, Object right) {
    if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
      return new BigDecimal(leftNumber.toString()).compareTo(new BigDecimal(rightNumber.toString()));
    }
    if (left instanceof Instant leftInstant && right instanceof Instant rightInstant) {
      return leftInstant.compareTo(rightInstant);
    }
    if (left instanceof LocalDate leftDate && right instanceof LocalDate rightDate) {
      return leftDate.compareTo(rightDate);
    }
    if (left instanceof Boolean leftBoolean && right instanceof Boolean rightBoolean) {
      return leftBoolean.compareTo(rightBoolean);
    }
    return parameterText(left).compareTo(parameterText(right));
  }

  private static String parameterText(Object value) {
    if (value instanceof byte[] bytes) {
      return "\\x" + HexFormat.of().formatHex(bytes);
    }
    if (value instanceof UUID uuid) {
      return uuid.toString();
    }
    return value.toString();
  }

  private static String sqlType(String type) {
    var normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
    if (!SQL_TYPE.matcher(normalized).matches()) {
      throw new IllegalArgumentException("PostgreSQL migration port-query type is invalid.");
    }
    return normalized;
  }

  private static String quoted(String identifier) {
    if (!identifier.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("PostgreSQL migration port-query identifier is invalid.");
    }
    return '"' + identifier + '"';
  }

  private record ExpectedRow(Map<String, Object> values) {}

  private record TableMetadata(Map<String, String> types, List<String> primaryKeys) {}

  private record TableSnapshot(String table, TableMetadata metadata, List<ExpectedRow> rows) {}

  private record Parameter(Object value) {}
}
