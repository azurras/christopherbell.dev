package dev.christopherbell.canesboxtracker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class CanesBoxTrackerConfigurationTest {
  private static final Pattern METRO_NAME_KEY =
      Pattern.compile("canes-box-tracker\\.metros\\[\\d+]\\.metro-name");

  @Test
  void defaultConfigurationTracksFiftyUniqueMetros() {
    var metroNames = configuredMetroNames();

    assertEquals(50, metroNames.size());
    assertEquals(50, new HashSet<>(metroNames).size());
  }

  private List<String> configuredMetroNames() {
    var factory = new YamlPropertiesFactoryBean();
    factory.setResources(new ClassPathResource("application.yml"));
    Properties properties = factory.getObject();

    return properties.stringPropertyNames().stream()
        .filter(key -> METRO_NAME_KEY.matcher(key).matches())
        .map(properties::getProperty)
        .sorted()
        .toList();
  }
}
