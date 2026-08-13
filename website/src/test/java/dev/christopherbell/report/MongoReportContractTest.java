package dev.christopherbell.report;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.account.MongoAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.post.MongoPostRepositoryTestFactory;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.report.query.ReportQueryPort;
import dev.christopherbell.report.query.ReportQueryService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoReportContractTest implements ReportParityContract {
  private static MongoClient client;
  private static MongoAccountRepository accounts;
  private static PostRepository posts;
  private static ReportRepository reports;
  private static ReportQueryPort queries;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(System.getenv("SPRING_MONGODB_URI"));
    if (!"test".equals(connection.getDatabase())) {
      throw new IllegalStateException("MongoDB contract tests require database test.");
    }
    client = MongoClients.create(connection);
    var factory = DomainMongoOperationsTestFactory.createForDisposableMongo(
        new MongoTemplate(client, "test"));
    accounts = new MongoAccountRepository(factory);
    posts = MongoPostRepositoryTestFactory.create(factory);
    reports = new MongoReportRepository(factory);
    queries = new ReportQueryService(factory, reports);
  }

  @AfterAll static void disconnect() { if (client != null) client.close(); }
  @Override public ReportRepository reports() { return reports; }
  @Override public ReportQueryPort queries() { return queries; }
  @Override public void ensureAccountAndPost(Account reported, Account reporter, Post post) {
    if (accounts.findById(reported.getId()).isEmpty()) accounts.save(reported);
    if (accounts.findById(reporter.getId()).isEmpty()) accounts.save(reporter);
    posts.save(post);
  }
}
