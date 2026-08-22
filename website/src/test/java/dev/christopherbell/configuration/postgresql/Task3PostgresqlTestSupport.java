package dev.christopherbell.configuration.postgresql;

import dev.christopherbell.configuration.persistence.PostgresqlDatabaseIdentity;
import dev.christopherbell.configuration.persistence.PostgresqlTestDatabaseGuard;
import dev.christopherbell.configuration.persistence.PostgresqlTestDatabaseGuardProperties;
import dev.christopherbell.configuration.persistence.PostgresqlTestSchemaName;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.configuration.persistence.PostgresqlPhysicalNamingStrategy;
import dev.christopherbell.configuration.security.browser.PostgresBrowserSessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Owns one isolated PostgreSQL schema set for Task 3 adapter integration tests. */
public final class Task3PostgresqlTestSupport implements AutoCloseable {
  private static final List<String> DOMAINS = List.of(
      "identity", "social", "communication", "federation", "music", "shared_folder",
      "mobility", "lunch", "canes", "platform");
  private static final Pattern OWNED_PREFIX = Pattern.compile("cbtest_t3_[a-z0-9_]+_");
  private static final PostgresqlTestDatabaseGuardProperties GUARD =
      new PostgresqlTestDatabaseGuardProperties("test", "cbtest_");

  private final String url;
  private final String username;
  private final String password;
  private final String prefix;

  private Task3PostgresqlTestSupport(
      String url, String username, String password, String prefix) {
    this.url = url;
    this.username = username;
    this.password = password;
    this.prefix = prefix;
  }

  public static Task3PostgresqlTestSupport migrate() throws SQLException {
    var prefix = PostgresqlTestSchemaName.create("cbtest_t3_").value() + '_';
    requireOwnedPrefix(prefix);
    var url = requiredEnvironment("SPRING_DATASOURCE_URL");
    var username = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
    var password = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");
    requireSafeDatabase(url, username, password, prefix);
    System.out.println("Task 3 PostgreSQL database: test");
    System.out.println("Task 3 PostgreSQL owned schemas: "
        + DOMAINS.stream().map(prefix::concat).toList());
    try {
      Flyway.configure()
          .dataSource(url, username, password)
          .locations("classpath:db/migration")
          .schemas("public")
          .defaultSchema("public")
          .table(historyTable(prefix))
          .createSchemas(false)
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .baselineDescription("Task 3 isolated schema-history bootstrap")
          .cleanDisabled(true)
          .placeholders(Map.of("schema_prefix", prefix))
          .validateMigrationNaming(true)
          .load()
          .migrate();
      return new Task3PostgresqlTestSupport(url, username, password, prefix);
    } catch (RuntimeException failure) {
      dropOwnedSchemas(url, username, password, prefix);
      throw failure;
    }
  }

  public Database openDatabase() throws SQLException {
    var poolConfiguration = new HikariConfig();
    poolConfiguration.setJdbcUrl(url);
    poolConfiguration.setUsername(username);
    poolConfiguration.setPassword(password);
    poolConfiguration.setMaximumPoolSize(4);
    poolConfiguration.setMinimumIdle(0);
    poolConfiguration.setPoolName("postgresql-adapter-contract-" + prefix);
    var dataSource = new HikariDataSource(poolConfiguration);
    var connection = dataSource.getConnection();
    var schemas = PostgresqlSchemaNames.testOwned(prefix);
    var jpa = new AnnotationConfigApplicationContext();
    jpa.getEnvironment().getPropertySources().addFirst(
        new org.springframework.core.env.MapPropertySource(
            "postgresql-adapter-test", Map.of("app.persistence.backend", "postgresql")));
    jpa.registerBean(javax.sql.DataSource.class, () -> dataSource);
    jpa.registerBean(PostgresqlSchemaNames.class, () -> schemas);
    jpa.register(JpaTestConfiguration.class);
    jpa.registerBean(PostgresBrowserSessionRepository.class);
    jpa.refresh();
    var transactions = new org.springframework.transaction.support.TransactionTemplate(
        jpa.getBean(PlatformTransactionManager.class));
    return new Database(
        connection,
        dataSource,
        JdbcClient.create(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
        JdbcClient.create(dataSource),
        schemas,
        transactions,
        jpa);
  }

  public String prefix() {
    return prefix;
  }

  @Override
  public void close() {
    dropOwnedSchemas(url, username, password, prefix);
  }

  private static void requireSafeDatabase(
      String url, String username, String password, String prefix) throws SQLException {
    try (var connection = DriverManager.getConnection(url, username, password);
         var statement = connection.createStatement();
         var result = statement.executeQuery("select current_database()")) {
      result.next();
      PostgresqlTestDatabaseGuard.requireSafeIdentity(
          new PostgresqlDatabaseIdentity(result.getString(1), prefix + "identity"), GUARD);
    }
  }

  private static void dropOwnedSchemas(
      String url, String username, String password, String prefix) {
    requireOwnedPrefix(prefix);
    try (var connection = DriverManager.getConnection(url, username, password)) {
      requireSafeDatabase(url, username, password, prefix);
      try (var statement = connection.createStatement()) {
        statement.execute("drop table if exists public.\"" + historyTable(prefix) + "\"");
        for (var index = DOMAINS.size() - 1; index >= 0; index--) {
          statement.execute("drop schema if exists \"" + prefix + DOMAINS.get(index)
              + "\" cascade");
        }
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Owned PostgreSQL Task 3 schemas could not be removed.", failure);
    }
  }

  private static void requireOwnedPrefix(String prefix) {
    if (!OWNED_PREFIX.matcher(prefix).matches()
        || DOMAINS.stream().anyMatch(domain -> prefix.length() + domain.length() > 63)) {
      throw new IllegalStateException("Refusing to use an unowned PostgreSQL Task 3 schema prefix.");
    }
  }

  private static String historyTable(String prefix) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var hash = HexFormat.of().formatHex(
          digest.digest(prefix.getBytes(StandardCharsets.US_ASCII)));
      return "flyway_cbtest_" + hash.substring(0, 24) + "_history";
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }

  private static String requiredEnvironment(String key) {
    var value = System.getenv(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(key + " must be set for PostgreSQL integration tests.");
    }
    return value;
  }

  public record Database(
      Connection connection,
      HikariDataSource dataSource,
      JdbcClient jdbc,
      JdbcClient managedJdbc,
      PostgresqlSchemaNames schemas,
      org.springframework.transaction.support.TransactionOperations transactions,
      AnnotationConfigApplicationContext jpa) implements AutoCloseable {
    public <T> T bean(Class<T> type) {
      return jpa.getBean(type);
    }

    @Override
    public void close() throws SQLException {
      connection.close();
      jpa.close();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  @EnableJpaRepositories(basePackages =
      "dev.christopherbell.configuration.security.browser")
  static class JpaTestConfiguration {
    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(
        javax.sql.DataSource dataSource, PostgresqlSchemaNames schemas) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPackagesToScan("dev.christopherbell.configuration.security.browser");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      var properties = new java.util.Properties();
      properties.put("hibernate.hbm2ddl.auto", "none");
      properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
      properties.put("hibernate.physical_naming_strategy",
          new PostgresqlPhysicalNamingStrategy(schemas));
      factory.setJpaProperties(properties);
      return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(
        jakarta.persistence.EntityManagerFactory entityManagerFactory) {
      return new JpaTransactionManager(entityManagerFactory);
    }
  }
}
