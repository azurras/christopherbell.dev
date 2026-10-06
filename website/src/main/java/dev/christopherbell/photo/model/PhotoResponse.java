package dev.christopherbell.photo.model;

import java.util.List;

/**
 * Gallery API payload.
 *
 * @param images the gallery photos in display order; the name is the published JSON property
 */
public record PhotoResponse(List<Photo> images) {

  public PhotoResponse {
    images = List.copyOf(images);
  }
}
