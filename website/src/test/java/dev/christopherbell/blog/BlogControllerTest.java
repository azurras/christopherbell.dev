package dev.christopherbell.blog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BlogController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ControllerExceptionHandler.class, BlogControllerTest.MethodSecurityTestConfiguration.class})
class BlogControllerTest {
  @MockitoBean private BlogService blogService;
  @Autowired private MockMvc mockMvc;

  @TestConfiguration
  @EnableMethodSecurity
  static class MethodSecurityTestConfiguration {}

  @Test
  void anonymousPostByIdReturnsTheStandardEnvelope() throws Exception {
    when(blogService.getPostById(any())).thenReturn(BlogStub.getBlogResponseStub());
    mockMvc.perform(get("/api/blog/v1/posts/" + BlogStub.BLOG_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.posts").isArray());
  }

  @Test
  void malformedPostIdReturnsTheStandardBadRequestEnvelopeBeforeServiceInvocation() throws Exception {
    mockMvc.perform(get("/api/blog/v1/posts/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.messages[0].code").value("REQUEST_ERROR"))
        .andExpect(jsonPath("$.messages[0].description").value("The request is invalid."));

    verifyNoInteractions(blogService);
  }

  @Test
  void absentValidPostIdReturnsTheStandardNotFoundEnvelope() throws Exception {
    var absentId = "00000000-0000-0000-0000-000000000000";
    when(blogService.getPostById(absentId)).thenThrow(new ResourceNotFoundException("post absent"));

    mockMvc.perform(get("/api/blog/v1/posts/" + absentId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.messages[0].code").value("RESOURCE_NOT_FOUND"))
        .andExpect(jsonPath("$.messages[0].description")
            .value("The requested resource was not found."));
  }

  @Test
  void anonymousPostListReturnsTheStandardEnvelope() throws Exception {
    when(blogService.getPosts()).thenReturn(BlogStub.getBlogResponseStub());
    mockMvc.perform(get("/api/blog/v1/posts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.posts").isArray());
  }
}
