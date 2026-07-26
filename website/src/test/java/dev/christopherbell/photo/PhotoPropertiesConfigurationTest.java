package dev.christopherbell.photo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.photo.model.PhotoProperties;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class PhotoPropertiesConfigurationTest {
  private static final YamlPropertySourceLoader YAML_LOADER = new YamlPropertySourceLoader();

  @Test
  void applicationConfigurationBindsGalleryPhotos() throws IOException {
    PhotoProperties photoProperties = bindApplicationConfiguration();

    assertThat(photoProperties.getPhotos()).isNotEmpty();
    assertThat(photoProperties.getPhotos().getFirst().getName())
        .isEqualTo("The River Walk - San Antonio");
    assertThat(photoProperties.getPhotos().getFirst().getPath())
        .isEqualTo("/images/photos/IMG_0072.jpeg");
  }

  private PhotoProperties bindApplicationConfiguration() throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    MutablePropertySources sources = environment.getPropertySources();
    addFirst(sources, YAML_LOADER.load("application.yml", new ClassPathResource("application.yml")));

    return Binder.get(environment)
        .bind("photo-properties", PhotoProperties.class)
        .orElseThrow(() -> new AssertionError("photo gallery configuration was not bound"));
  }

  private void addFirst(
      MutablePropertySources sources, List<PropertySource<?>> propertySources) {
    for (int index = propertySources.size() - 1; index >= 0; index--) {
      sources.addFirst(propertySources.get(index));
    }
  }
}
