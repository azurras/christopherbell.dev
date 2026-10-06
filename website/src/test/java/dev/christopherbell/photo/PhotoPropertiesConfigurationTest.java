package dev.christopherbell.photo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.photo.model.Photo;
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

    assertThat(photoProperties.photos()).isNotEmpty();
    Photo firstGalleryPhoto = photoProperties.photos().getFirst();
    assertThat(firstGalleryPhoto.name()).isEqualTo("The River Walk - San Antonio");
    assertThat(firstGalleryPhoto.path()).isEqualTo("/images/photos/IMG_0072.jpeg");
    assertThat(firstGalleryPhoto.createdOn()).isNull();
  }

  private PhotoProperties bindApplicationConfiguration() throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    List<PropertySource<?>> applicationYamlSources =
        YAML_LOADER.load("application.yml", new ClassPathResource("application.yml"));
    addInPrecedenceOrder(environment.getPropertySources(), applicationYamlSources);

    return Binder.get(environment)
        .bind("photo-properties", PhotoProperties.class)
        .orElseThrow(() -> new AssertionError("photo gallery configuration was not bound"));
  }

  private void addInPrecedenceOrder(
      MutablePropertySources environmentSources, List<PropertySource<?>> yamlSources) {
    for (int index = yamlSources.size() - 1; index >= 0; index--) {
      environmentSources.addFirst(yamlSources.get(index));
    }
  }
}
