package dev.christopherbell.configuration.persistence;

import com.mongodb.ConnectionString;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Proves the application runs under exactly profile {@code test} against the disposable,
 * loopback-only {@code test} database, before test-only behavior is allowed to write.
 *
 * <p>Local candidate verification relies on this proof: the domain-collection cutover ledger
 * accepts an empty database only here, and test-only fixtures run only after it passes.
 */
@MongoPersistence
public class IsolatedTestDatabase {
  private static final String TEST_PROFILE = "test";
  private static final String TEST_DATABASE = "test";
  private static final int PRODUCTION_MONGO_PORT = 27017;
  private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "::1");

  private final MongoTemplate mongo;
  private final Environment environment;

  public IsolatedTestDatabase(MongoTemplate mongo, Environment environment) {
    this.mongo = Objects.requireNonNull(mongo, "mongo");
    this.environment = Objects.requireNonNull(environment, "environment");
  }

  /**
   * Requires exactly profile {@code test} and the isolated test connection.
   *
   * @throws IllegalStateException when the profile or connection is anything else
   */
  public void requireIsolatedTestConnection() {
    if (!isExplicitTestProfile(environment)) {
      throw new IllegalStateException("Test-only behavior requires exactly the test profile.");
    }
    requireConnection(environment, mongo);
  }

  /** True when the active profiles are exactly {@code test} and nothing else. */
  public static boolean isExplicitTestProfile(Environment environment) {
    return List.of(environment.getActiveProfiles()).equals(List.of(TEST_PROFILE));
  }

  /**
   * Requires one loopback server on a port other than production's, and database {@code test}
   * both as configured and as actually connected.
   *
   * @throws IllegalStateException naming which part of the connection is not isolated
   */
  public static void requireConnection(Environment environment, MongoTemplate mongo) {
    var connectionUri = environment.getProperty("spring.mongodb.uri");
    if (connectionUri == null || !connectionUri.startsWith("mongodb://")) {
      throw new IllegalStateException("Test Mongo URI must use one explicit loopback server.");
    }
    final ConnectionString connectionString;
    final URI serverUri;
    try {
      connectionString = new ConnectionString(connectionUri);
      serverUri = URI.create(connectionUri);
    } catch (IllegalArgumentException malformedUri) {
      throw new IllegalStateException("Test Mongo URI is invalid.");
    }
    var configuredHosts = connectionString.getHosts();
    var configuredDatabase = connectionString.getDatabase();
    var connectedDatabase = mongo.getDb().getName();
    if (!TEST_DATABASE.equals(configuredDatabase)
        || !TEST_DATABASE.equals(connectedDatabase)
        || configuredHosts.size() != 1) {
      throw new IllegalStateException("Test Mongo connection must target the isolated test database.");
    }

    var serverHost = serverUri.getHost();
    if (serverHost == null) {
      throw new IllegalStateException("Test Mongo URI must identify one loopback server.");
    }
    var loopbackHost = serverHost.replace("[", "").replace("]", "");
    var serverPort = serverUri.getPort();
    if (!LOOPBACK_HOSTS.contains(loopbackHost)
        || serverPort < 1 || serverPort > 65535 || serverPort == PRODUCTION_MONGO_PORT) {
      throw new IllegalStateException("Test Mongo connection must use a non-production loopback port.");
    }
  }
}
