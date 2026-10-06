package dev.christopherbell.photo;

import dev.christopherbell.photo.model.PhotoProperties;
import dev.christopherbell.photo.model.PhotoResponse;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lists the configuration-backed photo gallery.
 */
@AllArgsConstructor
@Service
public class PhotoService {

  private final PhotoProperties photoProperties;

  /**
   * Lists every configured gallery photo in configuration order.
   *
   * @return a {@link PhotoResponse} holding the gallery photos
   */
  public PhotoResponse listGalleryPhotos() {
    return new PhotoResponse(photoProperties.photos());
  }
}
