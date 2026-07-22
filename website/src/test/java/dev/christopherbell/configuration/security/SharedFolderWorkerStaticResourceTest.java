package dev.christopherbell.configuration.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.view.content.ContentViewController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the unauthenticated browser can install only the exact worker bootstrap asset. */
@WebMvcTest(controllers = ContentViewController.class)
@Import(ControllerSliceSecurityTestConfig.class)
class SharedFolderWorkerStaticResourceTest {
  @Autowired private MockMvc mockMvc;

  @Test
  void anonymousGetReturnsTheSharedFolderWorkerWhileTheApiStaysProtected() throws Exception {
    mockMvc.perform(get("/shared-folder-auth-sw.js"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("respondToSharedFolderFetch")));

    mockMvc.perform(get("/api/shared-folder/2026-07-17/entries"))
        .andExpect(status().isUnauthorized());
  }
}
