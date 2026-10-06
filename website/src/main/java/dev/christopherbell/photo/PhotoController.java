package dev.christopherbell.photo;

import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.photo.model.PhotoResponse;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for photo gallery content under {@code /api/photo}.
 */
@AllArgsConstructor
@RequestMapping("/api/photo")
@RestController
public class PhotoController {

  private final PhotoService photoService;

  /**
   * Lists every configured gallery photo.
   *
   * @return HTTP 200 with a {@link PhotoResponse} whose {@code images} are the gallery photos
   */
  @GetMapping(value = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<PhotoResponse>> listGalleryPhotos() {
    PhotoResponse galleryPhotos = photoService.listGalleryPhotos();
    return ResponseEntity.ok(
        Response.<PhotoResponse>builder()
            .payload(galleryPhotos)
            .success(true)
            .build());
  }
}
