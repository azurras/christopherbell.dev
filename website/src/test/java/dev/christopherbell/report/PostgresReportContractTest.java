package dev.christopherbell.report;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import dev.christopherbell.post.PostgresPostRepository;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.report.query.PostgresReportQueryService;
import dev.christopherbell.report.query.ReportQueryPort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresReportContractTest implements ReportParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostgresAccountRepository accounts;
  private static PostgresPostRepository posts;
  private static ReportRepository reports;
  private static ReportQueryPort queries;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(database.dsl());
    posts = new PostgresPostRepository(database.dsl());
    reports = new PostgresReportRepository(database.dsl());
    queries = new PostgresReportQueryService(database.dsl(), reports);
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Override public ReportRepository reports() { return reports; }
  @Override public ReportQueryPort queries() { return queries; }
  @Override public void ensureAccountAndPost(Account reported, Account reporter, Post post) {
    if (accounts.findById(reported.getId()).isEmpty()) accounts.save(reported);
    if (accounts.findById(reporter.getId()).isEmpty()) accounts.save(reporter);
    posts.save(post);
  }
}
