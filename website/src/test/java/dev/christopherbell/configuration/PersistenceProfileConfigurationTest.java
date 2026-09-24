package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class PersistenceProfileConfigurationTest {

  @Test
  void applicationProfilesDoNotConfigureRelationalPersistence() throws IOException {
    for (String resourceName : List.of("application-local.yml", "application-test.yml")) {
      var source = load(resourceName);

      assertThat(source.getProperty("spring.datasource.url")).isNull();
      assertThat(source.getProperty("spring.flyway.enabled")).isNull();
    }
  }

  @Test
  void productionProfileDoesNotConfigureRelationalPersistence() throws IOException {
    var source = load("application-prod.yml");

    assertThat(source.getProperty("app.persistence.backend")).isNull();
    assertThat(source.getProperty("spring.datasource.url")).isNull();
    assertThat(source.getProperty("spring.flyway.enabled")).isNull();
  }

  private static PropertySource<?> load(String resourceName) throws IOException {
    var sources = new YamlPropertySourceLoader().load(resourceName, new ClassPathResource(resourceName));
    assertThat(sources).isNotEmpty();
    return sources.getFirst();
  }

}
