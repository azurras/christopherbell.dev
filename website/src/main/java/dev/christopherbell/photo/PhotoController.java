package dev.christopherbell.photo;

import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.photo.model.PhotoResponse;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
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
   * Retrieves all images.
   *
   * @return HTTP 200 with a {@link PhotoResponse} containing all images
   */
  @GetMapping(value = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<PhotoResponse>> getImages() {
    return new ResponseEntity<>(
        Response.<PhotoResponse>builder()
            .payload(photoService.getAllImages())
            .success(true)
            .build(), HttpStatus.OK);
  }
}
