package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class MongoProfileConfigurationTest {

  @ParameterizedTest
  @ValueSource(strings = {"application-local.yml", "application-prod.yml"})
  void profileUsesSpringBootFourMongoConnectionProperties(String resourceName) throws IOException {
    var sources = new YamlPropertySourceLoader()
        .load(resourceName, new ClassPathResource(resourceName));

    assertThat(sources).isNotEmpty();
    var source = sources.getFirst();
    assertThat(source.getProperty("spring.mongodb.database")).isEqualTo("christopherbell");
    assertThat(source.getProperty("spring.mongodb.uri")).isEqualTo("mongodb://localhost:27017");
    assertThat(source.getProperty("spring.data.mongodb.auto-index-creation")).isEqualTo(true);
    assertThat(Stream.of("spring.data.mongodb.database", "spring.data.mongodb.uri")
        .map(source::getProperty)
        .toList()).containsOnlyNulls();
  }
}
