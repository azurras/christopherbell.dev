package dev.christopherbell.photo;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PhotoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ControllerExceptionHandler.class, PhotoControllerTest.MethodSecurityTestConfiguration.class})
class PhotoControllerTest {

  @MockitoBean
  private PhotoService photoService;
  @Autowired
  private MockMvc mockMvc;

  @TestConfiguration
  @EnableMethodSecurity
  static class MethodSecurityTestConfiguration {}

  @Test
  void anonymousGalleryReadReturnsTheStandardEnvelope() throws Exception {
    when(photoService.listGalleryPhotos()).thenReturn(PhotoStub.galleryResponse());

    mockMvc.perform(get("/api/photo/v1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.images.length()").value(2))
        .andExpect(jsonPath("$.payload.images[0].id").value(PhotoStub.MIATA_PHOTO_ID.toString()))
        .andExpect(jsonPath("$.payload.images[0].name").value("Little Red Miata"))
        .andExpect(jsonPath("$.payload.images[0].path").value("/images/photos/miata.jpeg"))
        .andExpect(jsonPath("$.payload.images[0].description").value("The little red miata."))
        .andExpect(jsonPath("$.payload.images[0].createdOn").exists())
        .andExpect(jsonPath("$.payload.images[1].createdOn").value(nullValue()));
  }
}
