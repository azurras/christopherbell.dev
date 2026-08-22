package dev.christopherbell.message.conversation;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.message.MessageRepository;
import dev.christopherbell.message.PostgresMessageRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresConversationContractTest implements ConversationParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostgresAccountRepository accounts;
  private static MessageRepository messages;
  private static ConversationQueryPort queries;
  private static ConversationArchivePort archives;
  private static StableCursorCodec cursors;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    messages = new PostgresMessageRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    cursors = new StableCursorCodec();
    queries = new PostgresConversationQueryRepository(
        database.jdbc(), database.schemas(), database.transactions(), cursors);
    archives = new PostgresConversationArchiveService(
        database.managedJdbc(), database.schemas(), database.transactions(),
        java.time.Clock.systemUTC());
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Override public MessageRepository messages() { return messages; }
  @Override public ConversationQueryPort queries() { return queries; }
  @Override public ConversationArchivePort archives() { return archives; }
  @Override public StableCursorCodec cursors() { return cursors; }
  @Override public void ensureAccount(Account account) {
    if (accounts.findById(account.getId()).isEmpty()) accounts.save(account);
  }
}
