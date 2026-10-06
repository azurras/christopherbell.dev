package dev.christopherbell.photo.model;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gallery photos bound from the {@code photo-properties} configuration prefix.
 *
 * @param photos the gallery photos in display order
 */
@ConfigurationProperties("photo-properties")
public record PhotoProperties(List<Photo> photos) {

  public PhotoProperties {
    photos = photos == null ? List.of() : List.copyOf(photos);
  }
}
