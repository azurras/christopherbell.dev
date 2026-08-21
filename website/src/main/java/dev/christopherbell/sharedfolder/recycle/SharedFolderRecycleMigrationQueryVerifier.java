package dev.christopherbell.sharedfolder.recycle;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import java.sql.Connection;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jooq.SQLDialect;
import org.jooq.conf.MappedSchema;
import org.jooq.conf.RenderMapping;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.data.domain.PageRequest;

/** Executes the production recycle adapter for cutover parity checks. */
@PostgresPersistenceSupport
public final class SharedFolderRecycleMigrationQueryVerifier {
  private SharedFolderRecycleMigrationQueryVerifier() {}

  public static boolean verifyStateDeletedPage(
      Connection connection, String schema, List<Map<String, Object>> sourceRows) {
    var repository = new PostgresSharedFolderRecycleRepository(
        DSL.using(connection, SQLDialect.POSTGRES, settings(schema)));
    for (var state : sourceRows.stream().map(row -> text(row.get("state")))
        .filter(Objects::nonNull).distinct().map(SharedFolderRecycleState::valueOf).toList()) {
      var expected = sourceRows.stream().filter(row -> state.name().equals(text(row.get("state"))))
          .sorted(Comparator.comparing(SharedFolderRecycleMigrationQueryVerifier::deletedAt).reversed()
              .thenComparing(row -> text(row.get("recycle_item_id")), Comparator.reverseOrder()))
          .toList();
      var first = repository.findByStateOrderByDeletedAtDescIdDesc(state, PageRequest.of(0, 1));
      if (!first.getContent().stream().map(SharedFolderRecycleItem::id).toList().equals(ids(expected, 0))
          || first.hasNext() != (expected.size() > 1)) {
        return false;
      }
      var second = repository.findByStateOrderByDeletedAtDescIdDesc(state, PageRequest.of(1, 1));
      if (!second.getContent().stream().map(SharedFolderRecycleItem::id).toList().equals(ids(expected, 1))
          || second.hasNext() != (expected.size() > 2)) {
        return false;
      }
    }
    return true;
  }

  private static List<String> ids(List<Map<String, Object>> rows, int offset) {
    return rows.stream().skip(offset).limit(1)
        .map(row -> text(row.get("recycle_item_id"))).toList();
  }

  private static Settings settings(String schema) {
    var prefix = prefix(schema, "shared_folder");
    return new Settings().withRenderMapping(new RenderMapping().withSchemata(
        new MappedSchema().withInput("shared_folder").withOutput(prefix + "shared_folder"),
        new MappedSchema().withInput("identity").withOutput(prefix + "identity")));
  }

  private static String prefix(String schema, String suffix) {
    if (!schema.endsWith(suffix)) {
      throw new IllegalArgumentException("Unexpected PostgreSQL schema.");
    }
    return schema.substring(0, schema.length() - suffix.length());
  }

  private static Instant deletedAt(Map<String, Object> row) {
    return (Instant) row.get("deleted_at");
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}
