package dev.christopherbell.report.api;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.report.query.ReportMigrationQueryVerifier;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

/** Published report-module cutover parity operation. */
@PostgresPersistenceSupport
public final class ReportMigrationVerifier {
  private ReportMigrationVerifier() {}

  public static boolean verifyModerationPage(
      Connection connection, String schema, List<Map<String, Object>> sourceRows) {
    return ReportMigrationQueryVerifier.verifyModerationPage(connection, schema, sourceRows);
  }

  public static boolean verify(
      Connection connection, String schema, String queryName,
      List<Map<String, Object>> sourceRows) {
    return ReportMigrationQueryVerifier.verify(connection, schema, queryName, sourceRows);
  }
}
