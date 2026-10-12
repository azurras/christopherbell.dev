package dev.christopherbell.configuration.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.env.MockEnvironment;

class IsolatedTestDatabaseTest {
  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final MockEnvironment environment = new MockEnvironment();
  private final IsolatedTestDatabase isolatedTestDatabase = new IsolatedTestDatabase(mongo, environment);

  private void connectTo(String connectionUri, String connectedDatabase) {
    environment.setProperty("spring.mongodb.uri", connectionUri);
    var database = mock(MongoDatabase.class);
    when(database.getName()).thenReturn(connectedDatabase);
    when(mongo.getDb()).thenReturn(database);
  }

  @Test
  void acceptsExactlyTheTestProfileOnTheLoopbackTestDatabase() {
    environment.setActiveProfiles("test");
    connectTo("mongodb://127.0.0.1:63152/test", "test");

    assertThatCode(isolatedTestDatabase::requireIsolatedTestConnection).doesNotThrowAnyException();
  }

  @Test
  void refusesEveryOtherProfileSetBeforeReadingTheConnection() {
    for (var profiles : new String[][] {{}, {"default"}, {"test", "prod"}, {"prod"}}) {
      environment.setActiveProfiles(profiles);

      assertThatThrownBy(isolatedTestDatabase::requireIsolatedTestConnection)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Test-only behavior requires exactly the test profile.");
    }
    verifyNoInteractions(mongo);
  }

  @Test
  void refusesTheProductionPortAProductionDatabaseAndARemoteHost() {
    environment.setActiveProfiles("test");
    connectTo("mongodb://127.0.0.1:27017/test", "test");
    assertThatThrownBy(isolatedTestDatabase::requireIsolatedTestConnection)
        .hasMessage("Test Mongo connection must use a non-production loopback port.");

    connectTo("mongodb://127.0.0.1:63152/christopherbell", "christopherbell");
    assertThatThrownBy(isolatedTestDatabase::requireIsolatedTestConnection)
        .hasMessage("Test Mongo connection must target the isolated test database.");

    connectTo("mongodb://db.example.test:63152/test", "test");
    assertThatThrownBy(isolatedTestDatabase::requireIsolatedTestConnection)
        .hasMessage("Test Mongo connection must use a non-production loopback port.");

    connectTo("mongodb+srv://cluster.example.test/test", "test");
    assertThatThrownBy(isolatedTestDatabase::requireIsolatedTestConnection)
        .hasMessage("Test Mongo URI must use one explicit loopback server.");
  }
}
