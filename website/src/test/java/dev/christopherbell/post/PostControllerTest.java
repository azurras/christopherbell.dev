package dev.christopherbell.post;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.test.TestUtil;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.model.PostCreateRequest;
import dev.christopherbell.post.model.PostDetail;
import dev.christopherbell.post.model.PostFeedItem;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import dev.christopherbell.configuration.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import static org.mockito.ArgumentMatchers.anyString;

@WebMvcTest(PostController.class)
@Import({ControllerExceptionHandler.class})
public class PostControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private PostService postService;

  // @BeforeEach
  // void setup() {
  //   // Allow method-security checks to pass for authenticated tests
  //   when(permissionService.hasAuthority(anyString())).thenReturn(true);
  // }

  @Test
  @DisplayName("Create post: USER authorized -> 201 with detail")
  @WithMockUser(authorities = {"USER"})
  public void testCreatePost_whenUser_Returns201() throws Exception {
    var request = TestUtil.readJsonAsString("/request/post-create-request.json");
    var requestObj = TestUtil.readJsonAsObject("/request/post-create-request.json", PostCreateRequest.class);
    var detail = PostDetail.builder().id("p1").accountId("acc-1").text("hello world").build();

    when(postService.createPost(eq(requestObj))).thenReturn(detail);

    mockMvc
        .perform(
            post("/api/posts" + APIVersion.V20250914 + "/create")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isCreated())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value("p1"))
        .andExpect(jsonPath("$.payload.text").value("hello world"));

    verify(postService).createPost(eq(requestObj));
  }

  @Test
  @DisplayName("Global feed: returns 200 with list")
  @WithMockUser(authorities = {"USER"})
  public void testGetGlobalFeed_returnsList() throws Exception {
    var items = List.of(
        PostFeedItem.builder().id("p1").username("user1").text("hello").build(),
        PostFeedItem.builder().id("p2").username("user2").text("world").build()
    );
    when(postService.getGlobalFeed(eq(null), eq(20))).thenReturn(items);

    mockMvc
        .perform(get("/api/posts" + APIVersion.V20250914 + "/feed").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].id").value("p1"))
        .andExpect(jsonPath("$.payload[0].username").value("user1"))
        .andExpect(jsonPath("$.payload[1].id").value("p2"));

    verify(postService).getGlobalFeed(eq(null), eq(20));
  }

  @Test
  @DisplayName("Global feed: supports before and limit query params")
  @WithMockUser(authorities = {"USER"})
  public void testGetGlobalFeed_withBeforeAndLimit() throws Exception {
    var ts = java.time.Instant.parse("2025-01-01T00:00:00Z");
    when(postService.getGlobalFeed(eq(ts), eq(10))).thenReturn(List.of());

    mockMvc
        .perform(get("/api/posts" + APIVersion.V20250914 + "/feed")
            .with(csrf())
            .param("before", ts.toString())
            .param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").isArray());

    verify(postService).getGlobalFeed(eq(ts), eq(10));
  }

  @Test
  @DisplayName("Create post: blank text returns 400 before service")
  @WithMockUser(authorities = {"USER"})
  public void testCreatePost_whenTextBlank_Returns400() throws Exception {
    var request = """
        {"text":"   "}
        """;

    mockMvc
        .perform(
            post("/api/posts" + APIVersion.V20250914 + "/create")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false));

    verifyNoInteractions(postService);
  }

  @Test
  @DisplayName("Create post: invalid -> 400")
  @WithMockUser(authorities = {"USER"})
  public void testCreatePost_whenInvalid_Returns400() throws Exception {
    var request = TestUtil.readJsonAsString("/request/post-create-request.json");
    var requestObj = TestUtil.readJsonAsObject("/request/post-create-request.json", PostCreateRequest.class);

    when(postService.createPost(eq(requestObj))).thenThrow(new InvalidRequestException("bad"));

    mockMvc
        .perform(
            post("/api/posts" + APIVersion.V20250914 + "/create")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Create post: unauthorized -> 401")
  public void testCreatePost_whenUnauthorized_Returns401() throws Exception {
    var request = TestUtil.readJsonAsString("/request/post-create-request.json");

    mockMvc
        .perform(
            post("/api/posts" + APIVersion.V20250914 + "/create")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(postService);
  }

  @Test
  @DisplayName("Get my posts: USER -> 200 list")
  @WithMockUser(authorities = {"USER"})
  public void testGetMyPosts_whenUser_Returns200() throws Exception {
    var list = List.of(PostDetail.builder().id("p1").text("a").build());
    when(postService.getMyPosts()).thenReturn(list);

    mockMvc
        .perform(get("/api/posts" + APIVersion.V20250914 + "/me").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].id").value("p1"));
  }

  @Test
  @DisplayName("Following feed: USER -> 200 list")
  @WithMockUser(authorities = {"USER"})
  public void testGetFollowingFeed_whenUser_Returns200() throws Exception {
    var list = List.of(PostFeedItem.builder().id("p1").username("target").text("hello").build());
    when(postService.getFollowingFeed(eq(null), eq(20))).thenReturn(list);

    mockMvc
        .perform(get("/api/posts" + APIVersion.V20250914 + "/following/feed")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].id").value("p1"))
        .andExpect(jsonPath("$.payload[0].username").value("target"));

    verify(postService).getFollowingFeed(eq(null), eq(20));
  }

  @Test
  @DisplayName("Get by account: ADMIN -> 200 list")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetPostsByAccount_whenAdmin_Returns200() throws Exception {
    var list = List.of(PostDetail.builder().id("p1").text("a").build());
    when(postService.getPostsByAccountId(eq("acc-1"))).thenReturn(list);

    mockMvc
        .perform(get("/api/posts" + APIVersion.V20250914 + "/account/{id}", "acc-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].id").value("p1"));

    verify(postService).getPostsByAccountId(eq("acc-1"));
  }

  @Test
  @DisplayName("Get by account: not found -> 404")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetPostsByAccount_whenNotFound_Returns404() throws Exception {
    when(postService.getPostsByAccountId(eq("missing"))).thenThrow(new ResourceNotFoundException("nope"));

    mockMvc
        .perform(get("/api/posts" + APIVersion.V20250914 + "/account/{id}", "missing"))
        .andExpect(status().isNotFound());
  }
}
