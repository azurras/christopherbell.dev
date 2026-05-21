package dev.christopherbell.view;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ViewController.class)
@AutoConfigureMockMvc(addFilters = false)
public class ViewControllerTest {
  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("Home page renders social preview metadata")
  public void getHomePage_rendersSocialPreviewMetadata() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("summary_large_image")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "https://www.christopherbell.dev/images/previews/christopherbell-dev.png")))
        .andExpect(content().string(containsString("The Void preview for christopherbell.dev")));
  }

  @Test
  @DisplayName("Void page renders Void social title")
  public void getVoidHomePage_rendersVoidSocialTitle() throws Exception {
    mockMvc
        .perform(get("/void"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CB | Void")))
        .andExpect(
            content()
                .string(
                    containsString("Short posts, quick replies, and the latest noise from the feed.")));
  }

  @Test
  @DisplayName("What's For Lunch page renders social preview metadata")
  public void getWhatsForLunchPage_rendersSocialPreviewMetadata() throws Exception {
    mockMvc
        .perform(get("/wfl"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CB | What's For Lunch?")))
        .andExpect(content().string(containsString("https://www.christopherbell.dev/wfl")));
  }

  @Test
  @DisplayName("WFL restaurant page renders social preview metadata")
  public void getWhatsForLunchRestaurantPage_rendersSocialPreviewMetadata() throws Exception {
    mockMvc
        .perform(get("/wfl/restaurants/restaurant-123"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CB | Restaurant")))
        .andExpect(content().string(containsString("https://www.christopherbell.dev/wfl/restaurants/restaurant-123")));
  }

  @Test
  @DisplayName("WFL favorites page renders the list app mount")
  public void getWhatsForLunchFavoritesPage_rendersListMount() throws Exception {
    mockMvc
        .perform(get("/wfl/favorites"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CB | Favorite Restaurants")))
        .andExpect(content().string(containsString("data-list-mode=\"favorites\"")));
  }

  @Test
  @DisplayName("WFL top-rated page renders the list app mount")
  public void getWhatsForLunchTopRatedPage_rendersListMount() throws Exception {
    mockMvc
        .perform(get("/wfl/top-rated"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CB | Top Rated Restaurants")))
        .andExpect(content().string(containsString("data-list-mode=\"top-rated\"")));
  }

  @Test
  @DisplayName("Public user page renders canonical username social URL")
  public void getPublicUserPage_rendersUsernameSocialUrl() throws Exception {
    mockMvc
        .perform(get("/u/some_user"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("https://www.christopherbell.dev/u/some_user")));
  }

  @Test
  @DisplayName("Post page renders canonical post social URL")
  public void getPostPage_rendersPostSocialUrl() throws Exception {
    mockMvc
        .perform(get("/p/post-123"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("https://www.christopherbell.dev/p/post-123")));
  }
}
