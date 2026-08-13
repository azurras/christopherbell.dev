package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class LocalMongoComposeConfigurationTest {
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

  @Test
  void composePinsMongoPersistsDataAndPublishesOnlyLoopback() throws IOException {
    JsonNode compose = YAML.readTree(REPOSITORY_ROOT.resolve("compose.yaml").toFile());
    JsonNode mongo = compose.at("/services/mongodb");

    assertThat(mongo.path("image").asText()).isEqualTo("mongo:8.3.2");
    assertThat(textValues(mongo.path("ports"))).containsExactly("127.0.0.1:27017:27017");
    assertThat(textValues(mongo.path("volumes")))
        .contains("christopherbell_mongo_data:/data/db");
    assertThat(mongo.at("/healthcheck/test").toString())
        .contains("db.adminCommand('ping')");
    assertThat(compose.at("/volumes/christopherbell_mongo_data").isObject()).isTrue();
    assertThat(compose.toString()).doesNotContain("password", "RESEND", "APP_JWT_SECRET");
  }

  @Test
  void composePinsPostgresqlToLoopbackTestDatabaseWithoutAStoredPassword() throws IOException {
    JsonNode compose = YAML.readTree(REPOSITORY_ROOT.resolve("compose.yaml").toFile());
    JsonNode postgresql = compose.at("/services/postgresql");

    assertThat(postgresql.path("image").asText()).isEqualTo("postgres:18.4");
    assertThat(textValues(postgresql.path("ports"))).containsExactly("127.0.0.1:5432:5432");
    assertThat(postgresql.at("/environment/POSTGRES_DB").asText()).isEqualTo("test");
    assertThat(postgresql.at("/environment/POSTGRES_PASSWORD").asText())
        .isEqualTo("${POSTGRES_TEST_PASSWORD:?set POSTGRES_TEST_PASSWORD}");
    assertThat(textValues(postgresql.path("volumes")))
        .contains("christopherbell_postgresql_data:/var/lib/postgresql");
    assertThat(compose.at("/volumes/christopherbell_postgresql_data").isObject()).isTrue();
  }

  private static List<String> textValues(JsonNode values) {
    return StreamSupport.stream(values.spliterator(), false).map(JsonNode::asText).toList();
  }

  private static Path locateRepositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    if (Files.isDirectory(current.resolve(".github"))) {
      return current;
    }
    Path parent = current.getParent();
    if (parent != null && Files.isDirectory(parent.resolve(".github"))) {
      return parent;
    }
    throw new IllegalStateException("Cannot locate repository root from " + current);
  }
}
