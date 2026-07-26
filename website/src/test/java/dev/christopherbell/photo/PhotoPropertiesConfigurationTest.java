package dev.christopherbell.photo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.christopherbell.photo.model.PhotoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;

@SpringBootTest(
    classes = PhotoPropertiesConfigurationTest.Configuration.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PhotoPropertiesConfigurationTest {

  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PhotoProperties.class)
  static class Configuration {}

  private final PhotoProperties photoProperties;

  @Autowired
  PhotoPropertiesConfigurationTest(PhotoProperties photoProperties) {
    this.photoProperties = photoProperties;
  }

  @Test
  void applicationConfigurationBindsGalleryPhotos() {
    assertNotNull(photoProperties.getPhotos());
    assertFalse(photoProperties.getPhotos().isEmpty());
    assertEquals("The River Walk - San Antonio", photoProperties.getPhotos().getFirst().getName());
    assertEquals("/images/photos/IMG_0072.jpeg", photoProperties.getPhotos().getFirst().getPath());
  }
}
