package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class PostExpirationConfigurationTest {
  @Test
  void applicationDefaultsEnablePostExpiration() {
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yml"));

    var properties = yaml.getObject();

    assertEquals("true", properties.getProperty("posts.expiration.enabled"));
  }
}
