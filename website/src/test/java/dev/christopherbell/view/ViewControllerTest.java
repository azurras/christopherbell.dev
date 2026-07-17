package dev.christopherbell.view;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.view.account.AccountViewController;
import dev.christopherbell.view.content.ContentViewController;
import dev.christopherbell.view.tools.ToolsViewController;
import dev.christopherbell.view.voidroutes.VoidViewController;
import dev.christopherbell.view.wfl.WhatsForLunchViewController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {
    AccountViewController.class,
    ContentViewController.class,
    ToolsViewController.class,
    VoidViewController.class,
    WhatsForLunchViewController.class
})
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
        .andExpect(content().string(containsString("The Void preview for christopherbell.dev")))
        .andExpect(content().string(containsString("Drop into the Void.")))
        .andExpect(content().string(containsString("Enter Void")))
        .andExpect(content().string(containsString("home-void-gateway")))
        .andExpect(content().string(containsString("homeActivePost")))
        .andExpect(content().string(containsString("Signal Rail")))
        .andExpect(content().string(not(containsString("Secondary signals"))));
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
  @DisplayName("Raising Canes Box Index tool renders the chart app mount")
  public void getCanesBoxTrackerPage_rendersTrackerMount() throws Exception {
    mockMvc
        .perform(get("/canes-box-tracker"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CB | Raising Canes Box Index")))
        .andExpect(content().string(containsString("id=\"canesBoxChart\"")));
  }

  @Test
  @DisplayName("Raising Canes Box Index tool renders when requested with a trailing slash")
  public void getCanesBoxTrackerPageWithTrailingSlash_rendersTrackerMount() throws Exception {
    mockMvc
        .perform(get("/canes-box-tracker/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"canesBoxChart\"")));
  }

  @Test
  @DisplayName("ZIP coordinate tool renders the lookup app mount")
  public void getZipCoordinatesPage_rendersLookupMount() throws Exception {
    mockMvc
        .perform(get("/zip-coordinates"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CB | ZIP Coordinates")))
        .andExpect(content().string(containsString("id=\"zipCoordinateForm\"")));
  }

  @Test
  @DisplayName("Notifications page renders the notification list app mount")
  public void getNotificationsPage_rendersNotificationListMount() throws Exception {
    mockMvc
        .perform(get("/notifications"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CB | Notifications")))
        .andExpect(content().string(containsString("id=\"notificationsPage\"")))
        .andExpect(content().string(containsString("/js/notifications.js")));
  }

  @Test
  @DisplayName("Command center renders a hidden data-free public shell")
  public void getCommandCenterPage_rendersHiddenShell() throws Exception {
    mockMvc
        .perform(get("/command-center"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CB | Mission Control")))
        .andExpect(content().string(containsString("id=\"commandCenterRoot\"")))
        .andExpect(content().string(containsString("d-none")))
        .andExpect(content().string(not(containsString("applicationVersion"))))
        .andExpect(content().string(containsString("/js/command-center.js")));
  }

  @Test
  @DisplayName("Shared folder renders only a data-free public shell")
  public void getSharedFolderPage_rendersDataFreeShell() throws Exception {
    mockMvc
        .perform(get("/shared"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"shared-folder-app\"")))
        .andExpect(content().string(containsString("/js/shared-folder.js")))
        .andExpect(content().string(not(containsString("A:\\Shared"))));
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
