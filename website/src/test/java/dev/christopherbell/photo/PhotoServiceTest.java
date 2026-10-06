package dev.christopherbell.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.christopherbell.photo.model.Photo;
import dev.christopherbell.photo.model.PhotoProperties;
import dev.christopherbell.photo.model.PhotoResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhotoServiceTest {

  @Test
  void listsConfiguredGalleryPhotosInConfigurationOrder() {
    PhotoService photoService = new PhotoService(new PhotoProperties(PhotoStub.galleryPhotos()));

    PhotoResponse galleryPhotos = photoService.listGalleryPhotos();

    assertThat(galleryPhotos.images())
        .containsExactly(PhotoStub.miataPhoto(), PhotoStub.skylinePhoto());
  }

  @Test
  void listsNoPhotosWhenNoneAreConfigured() {
    PhotoService photoService = new PhotoService(new PhotoProperties(null));

    assertThat(photoService.listGalleryPhotos().images()).isEmpty();
  }

  @Test
  void rejectsAConfiguredPhotoWithoutAnImagePath() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Photo(null, "n/a", PhotoStub.MIATA_PHOTO_ID, "Little Red Miata", " "))
        .withMessage("photo path is required");
  }

  @Test
  void galleryPhotosCannotBeChangedAfterBinding() {
    PhotoProperties photoProperties = new PhotoProperties(PhotoStub.galleryPhotos());

    List<Photo> boundPhotos = photoProperties.photos();

    assertThat(boundPhotos).isUnmodifiable();
  }
}
