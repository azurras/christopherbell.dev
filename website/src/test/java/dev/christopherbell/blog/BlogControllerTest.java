package dev.christopherbell.blog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.permission.PermissionService;
import org.springframework.security.test.context.support.WithMockUser;
import dev.christopherbell.configuration.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BlogController.class)
@Import({ControllerExceptionHandler.class})
public class BlogControllerTest {
  @MockitoBean private BlogService blogService;
  @MockitoBean private PermissionService permissionService;
  @Autowired private MockMvc mockMvc;

  @Test
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetBlogPostById_success() throws Exception {

    when(blogService.getPostById(any())).thenReturn(BlogStub.getBlogResponseStub());
    //when(permissionService.hasAuthority(anyString())).thenReturn(true);

    mockMvc.perform(get("/api/blog/v1/posts/1"))
        .andExpect(status().isOk());
  }
}
