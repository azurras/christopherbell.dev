package dev.christopherbell.photo.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One gallery photo, bound from configuration and returned by the gallery API.
 *
 * <p>Configuration never supplies {@code createdOn}, so the API reports it as {@code null};
 * the component stays to keep the published JSON shape.</p>
 *
 * @param createdOn when the photo was added, or {@code null} when unknown
 * @param description the caption, which may be the {@code n/a} placeholder
 * @param id the stable photo identifier
 * @param name the display name, also the fallback alternative text
 * @param path the site-relative image path
 */
public record Photo(Instant createdOn, String description, UUID id, String name, String path) {

  public Photo {
    Objects.requireNonNull(id, "photo id is required");
    requireText(name, "photo name");
    requireText(path, "photo path");
  }

  private static void requireText(String text, String fieldName) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }
}
