package dev.christopherbell.photo;

import dev.christopherbell.photo.model.Photo;
import dev.christopherbell.photo.model.PhotoResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Gallery photos with fixed values for photo tests. */
final class PhotoStub {

  static final UUID MIATA_PHOTO_ID = UUID.fromString("a6589389-fd29-47eb-b795-1d600862eab5");
  static final UUID SKYLINE_PHOTO_ID = UUID.fromString("3a592438-de79-4842-a0b1-9aed06605486");
  static final Instant PHOTO_ADDED_ON = Instant.parse("2021-08-31T00:00:00Z");

  private PhotoStub() {
  }

  static Photo miataPhoto() {
    return new Photo(
        PHOTO_ADDED_ON,
        "The little red miata.",
        MIATA_PHOTO_ID,
        "Little Red Miata",
        "/images/photos/miata.jpeg");
  }

  static Photo skylinePhoto() {
    return new Photo(null, "n/a", SKYLINE_PHOTO_ID, "The Skyline", "/images/photos/skyline.jpeg");
  }

  static List<Photo> galleryPhotos() {
    return List.of(miataPhoto(), skylinePhoto());
  }

  static PhotoResponse galleryResponse() {
    return new PhotoResponse(galleryPhotos());
  }
}
