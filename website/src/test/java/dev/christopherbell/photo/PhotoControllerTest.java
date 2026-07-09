package dev.christopherbell.photo;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PhotoController.class)
@Import(ControllerExceptionHandler.class)
public class PhotoControllerTest {

  @MockitoBean
  private PhotoService photoService;
  @Autowired
  private MockMvc mockMvc;

  @Test
  @WithMockUser
  public void testGetImages_success() throws Exception {

    when(photoService.getAllImages()).thenReturn(PhotoStub.getPhotoResponseStub());

    mockMvc.perform(get("/api/photo/v1"))
        .andExpect(status().isOk());
  }
}