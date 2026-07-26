package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class MongoProfileConfigurationTest {

  @Test
  void localProfileKeepsLoopbackMongoDefault() throws IOException {
    var source = load("application-local.yml");
    assertThat(source.getProperty("spring.mongodb.database")).isEqualTo("christopherbell");
    assertThat(source.getProperty("spring.mongodb.uri")).isEqualTo("mongodb://localhost:27017");
    assertThat(source.getProperty("spring.data.mongodb.auto-index-creation")).isEqualTo(true);
  }

  @Test
  void productionProfileRequiresEnvironmentMongoUriAndExplicitMailSwitch()
      throws IOException {
    var source = load("application-prod.yml");

    assertThat(source.getProperty("spring.mongodb.database"))
        .isEqualTo("${SPRING_MONGODB_DATABASE:christopherbell}");
    assertThat(source.getProperty("spring.mongodb.uri")).isEqualTo("${SPRING_MONGODB_URI:}");
    assertThat(source.getProperty("spring.data.mongodb.auto-index-creation")).isEqualTo(true);
    assertThat(source.getProperty("app.mail.enabled")).isEqualTo("${APP_MAIL_ENABLED:true}");
    assertThat(source.getProperty("spring.mail.password")).isEqualTo("${RESEND_API_KEY:}");
  }

  @Test
  void deploySmokeProfileDisablesMutationSources() throws IOException {
    var source = load("application-deploy-smoke.yml");
    assertThat(source.getProperty("app.scheduling.enabled")).isEqualTo(false);
    assertThat(source.getProperty("wfl.restaurant-import.monthly.enabled")).isEqualTo(false);
  }

  private org.springframework.core.env.PropertySource<?> load(String resourceName)
      throws IOException {
    var sources = new YamlPropertySourceLoader()
        .load(resourceName, new ClassPathResource(resourceName));
    assertThat(sources).isNotEmpty();
    return sources.getFirst();
  }
}
