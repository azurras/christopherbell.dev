package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class PersistenceProfileConfigurationTest {

  @Test
  void localAndTestProfilesSelectPostgresqlDatabaseTest() throws IOException {
    for (String resourceName : List.of("application-local.yml", "application-test.yml")) {
      var source = load(resourceName);

      assertThat(source.getProperty("app.persistence.backend")).isEqualTo("postgresql");
      assertThat(databaseFromConfiguredFallback(source.getProperty("spring.datasource.url").toString()))
          .isEqualTo("test");
    }
  }

  @Test
  void productionProfileRequiresPostgresqlBackendAndJdbcCredentials() throws IOException {
    var source = load("application-prod.yml");

    assertThat(source.getProperty("app.persistence.backend"))
        .isEqualTo("${APP_PERSISTENCE_BACKEND:}");
    assertThat(source.getProperty("spring.datasource.url")).isEqualTo("${SPRING_DATASOURCE_URL:}");
    assertThat(source.getProperty("spring.datasource.username"))
        .isEqualTo("${SPRING_DATASOURCE_USERNAME:}");
    assertThat(source.getProperty("spring.datasource.password"))
        .isEqualTo("${SPRING_DATASOURCE_PASSWORD:}");
    assertThat(source.getProperty("spring.flyway.enabled")).isEqualTo(false);
  }

  private static PropertySource<?> load(String resourceName) throws IOException {
    var sources = new YamlPropertySourceLoader().load(resourceName, new ClassPathResource(resourceName));
    assertThat(sources).isNotEmpty();
    return sources.getFirst();
  }

  private static String databaseFromConfiguredFallback(String configuredUrl) {
    int fallbackStart = configuredUrl.indexOf(":jdbc:");
    int fallbackEnd = configuredUrl.lastIndexOf('}');
    String jdbcUrl = configuredUrl.substring(fallbackStart + 1, fallbackEnd);
    return URI.create(jdbcUrl.substring("jdbc:".length())).getPath().substring(1);
  }
}
