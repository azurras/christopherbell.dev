package dev.christopherbell.post.discovery;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.configuration.security.ControllerSliceSecurityTestConfig;
import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.post.model.PostFeedItem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VoidDiscoveryController.class)
@Import({ControllerExceptionHandler.class, ControllerSliceSecurityTestConfig.class})
class VoidDiscoveryControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockitoBean private VoidDiscoveryService discovery;

  @Test
  void anonymousNewArrivalsAreNoStore() throws Exception {
    var item = PostFeedItem.builder().id("p1").username("artist").build();
    when(discovery.newArrivals(eq("cursor-1"), eq(24)))
        .thenReturn(new VoidDiscoveryPage<>(List.of(item), "cursor-2"));

    mockMvc.perform(get("/api/posts" + APIVersion.V20260728 + "/discovery/new")
            .param("cursor", "cursor-1")
            .param("size", "24"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.payload.items[0].id").value("p1"))
        .andExpect(jsonPath("$.payload.nextCursor").value("cursor-2"));
  }

  @Test
  void anonymousTopicPageUsesTheCanonicalPathValue() throws Exception {
    when(discovery.topic(eq("music"), eq(""), eq(12)))
        .thenReturn(new VoidDiscoveryPage<>(List.of(), null));

    mockMvc.perform(get("/api/posts" + APIVersion.V20260728 + "/discovery/topic/music"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
  }
}
