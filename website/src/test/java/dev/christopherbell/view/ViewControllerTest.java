package dev.christopherbell.view;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.view.account.AccountViewController;
import dev.christopherbell.view.content.ContentViewController;
import dev.christopherbell.view.tools.ToolsViewController;
import dev.christopherbell.view.voidroutes.VoidViewController;
import dev.christopherbell.view.voidroutes.VoidPostSocialPreview;
import dev.christopherbell.view.voidroutes.VoidPostSocialPreviewService;
import dev.christopherbell.view.voidroutes.VoidUserSocialPreview;
import dev.christopherbell.view.voidroutes.VoidUserSocialPreviewService;
import dev.christopherbell.view.wfl.RestaurantProfilePage;
import dev.christopherbell.view.wfl.RestaurantProfilePageService;
import dev.christopherbell.view.wfl.WhatsForLunchViewController;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.federation.consent.FederationConsentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
  @MockitoBean private VoidPostSocialPreviewService postPreviews;
  @MockitoBean private VoidUserSocialPreviewService userPreviews;
  @MockitoBean private RestaurantProfilePageService restaurantProfiles;
  @MockitoBean private FederationConsentService federationConsent;

  @ParameterizedTest
  @ValueSource(strings = {
      "/login", "/signup", "/forgot-password", "/reset-password",
      "/profile", "/messages", "/notifications", "/report", "/shared", "/music",
      "/back-office", "/command-center", "/void/login", "/void/signup"
  })
  void privateAuthenticationAndAdministrativeShellsRenderNoIndex(String route) throws Exception {
    mockMvc.perform(get(route))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(
            "name=\"robots\" content=\"noindex,nofollow\"")));
  }

  @Test
  @DisplayName("Signup offers available federation enrollment as an affirmative opt-in")
  void getSignupPageWhenFederationAvailableRendersEnabledUncheckedChoice() throws Exception {
    when(federationConsent.enrollmentAvailable()).thenReturn(true);

    mockMvc.perform(get("/signup"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"federatePublicVoidPosts\"")))
        .andExpect(content().string(not(containsString("checked=\"checked\""))))
        .andExpect(content().string(not(containsString("disabled=\"disabled\""))))
        .andExpect(content().string(containsString(
            "This choice is off until you explicitly enable it. "
                + "You can change it later from Profile.")))
        .andExpect(content().string(containsString("Messages, Music, and Shared Folder")));
  }

  @Test
  @DisplayName("Signup shows a disabled federation choice when enrollment is unavailable")
  void getSignupPageWhenFederationUnavailableRendersDisabledChoice() throws Exception {
    when(federationConsent.enrollmentAvailable()).thenReturn(false);

    mockMvc.perform(get("/signup"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("disabled=\"disabled\"")))
        .andExpect(content().string(containsString("Federation enrollment is not available")));
  }

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
        .andExpect(content().string(containsString("Nothing lasts unless people care.")))
        .andExpect(content().string(containsString("Every thread starts with 24 hours.")))
        .andExpect(content().string(containsString("Each keep-alive adds 24 hours.")))
        .andExpect(content().string(containsString("Each reply adds 24 hours to the whole thread.")))
        .andExpect(content().string(containsString("href=\"/login?redirect=/void\"")))
        .andExpect(content().string(containsString("href=\"/signup?redirect=/void\"")))
        .andExpect(content().string(not(containsString("value=\"active\""))));
  }

  @Test
  @DisplayName("Void Explore renders the public failure-isolated shell")
  public void getVoidExplorePage_rendersDiscoveryShellWithoutCaching() throws Exception {
    mockMvc.perform(get("/void/explore"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(content().string(containsString("Explore the signal")))
        .andExpect(content().string(containsString("data-discovery-section=\"people\"")));
  }

  @Test
  @DisplayName("Void topic renders a canonical topic shell")
  public void getVoidTopicPage_normalizesTopicAndRejectsMalformedValues() throws Exception {
    mockMvc.perform(get("/void/topic/MUSIC"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(content().string(containsString("data-topic=\"music\"")));

    mockMvc.perform(get("/void/topic/bad.topic"))
        .andExpect(status().isBadRequest());
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
  @DisplayName("WFL restaurant page renders complete public indexable content")
  void getWhatsForLunchRestaurantPageRendersPublicProfile() throws Exception {
    when(restaurantProfiles.profile("restaurant-123")).thenReturn(profilePage());

    mockMvc.perform(get("/wfl/restaurants/restaurant-123"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CB | Taco Place")))
        .andExpect(content().string(containsString(
            "rel=\"canonical\" href=\"https://www.christopherbell.dev/wfl/restaurants/restaurant-123\"")))
        .andExpect(content().string(containsString("100 Main St, Austin, TX, 78701")))
        .andExpect(content().string(containsString("4.5/5 from 2 ratings")))
        .andExpect(content().string(containsString("href=\"/css/whats-for-lunch.css\"")))
        .andExpect(content().string(containsString(
            "void-shell-page lunch-page lunch-void-page restaurant-profile-page")))
        .andExpect(content().string(containsString(
            "<h1 id=\"restaurantTitle\">Taco Place</h1>")))
        .andExpect(content().string(containsString("512-555-0100")))
        .andExpect(content().string(containsString("https://example.com/menu")))
        .andExpect(content().string(containsString("type=\"application/ld+json\"")))
        .andExpect(content().string(containsString("\"@type\":\"Restaurant\"")))
        .andExpect(content().string(not(containsString("noindex"))))
        .andExpect(content().string(not(containsString("myVote"))))
        .andExpect(content().string(not(containsString("private-account"))));
  }

  private static RestaurantProfilePage profilePage() {
    return new RestaurantProfilePage(
        "restaurant-123",
        "/wfl/restaurants/restaurant-123",
        "https://www.christopherbell.dev/wfl/restaurants/restaurant-123",
        "CB | Taco Place",
        "Mexican restaurant in Austin, TX. Details and ratings from What's For Lunch.",
        "Taco Place",
        "Mexican",
        "Mexican restaurant in Austin, TX.",
        new RestaurantProfilePage.Address(
            "100 Main St", null, "Austin", "TX", "78701", "US", 30.2672, -97.7431),
        new RestaurantProfilePage.Rating(2, 9),
        "512-555-0100",
        "https://example.com/menu",
        "restaurant",
        "https://www.google.com/maps/search/?api=1&destination=30.2672%2C-97.7431",
        "{\"@context\":\"https://schema.org\",\"@type\":\"Restaurant\",\"name\":\"Taco Place\"}");
  }

  @Test
  void getWhatsForLunchRestaurantPageEscapesHtmlAndPreservesSafeJsonLd() throws Exception {
    var base = profilePage();
    when(restaurantProfiles.profile("hostile")).thenReturn(new RestaurantProfilePage(
        "hostile",
        "/wfl/restaurants/hostile",
        "https://www.christopherbell.dev/wfl/restaurants/hostile",
        "CB | Hostile Restaurant",
        base.description(),
        "</h1><script>alert(1)</script>",
        base.cuisine(),
        base.heroMetadata(),
        base.address(),
        base.rating(),
        base.phoneNumber(),
        base.website(),
        base.sourceType(),
        base.directionsUrl(),
        "{\"@context\":\"https://schema.org\",\"@type\":\"Restaurant\","
            + "\"name\":\"\\u003c/script\\u003e\"}"));

    mockMvc.perform(get("/wfl/restaurants/hostile"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(
            "&lt;/h1&gt;&lt;script&gt;alert(1)&lt;/script&gt;")))
        .andExpect(content().string(containsString("\\u003c/script\\u003e")))
        .andExpect(content().string(not(containsString("</h1><script>alert(1)</script>"))));
  }

  @Test
  void unknownPublicRouteReturnsAContentFreeNoIndex404() throws Exception {
    mockMvc.perform(get("/definitely-not-a-real-page"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(containsString("Page not found")))
        .andExpect(content().string(containsString("noindex,nofollow")))
        .andExpect(content().string(not(containsString("Whitelabel Error Page"))));
  }

  @Test
  void unknownApiRouteReturnsStructured404() throws Exception {
    mockMvc.perform(get("/api/definitely-not-a-real-resource"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(content().string(containsString("RESOURCE_NOT_FOUND")))
        .andExpect(content().string(not(containsString("No static resource"))));
  }

  @Test
  void getWhatsForLunchRestaurantPage_whenMissing_returnsNoIndex404() throws Exception {
    when(restaurantProfiles.profile("missing-restaurant"))
        .thenThrow(new ResourceNotFoundException("SECRET_RESTAURANT"));

    mockMvc.perform(get("/wfl/restaurants/missing-restaurant"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(containsString("Page not found")))
        .andExpect(content().string(containsString("noindex,nofollow")))
        .andExpect(content().string(not(containsString("application/ld+json"))))
        .andExpect(content().string(not(containsString("SECRET_RESTAURANT"))));
  }

  @Test
  @DisplayName("WFL favorites page renders the list app mount")
  public void getWhatsForLunchFavoritesPage_rendersListMount() throws Exception {
    mockMvc
        .perform(get("/wfl/favorites"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CB | Favorite Restaurants")))
        .andExpect(content().string(containsString(
            "name=\"robots\" content=\"noindex,nofollow\"")))
        .andExpect(content().string(containsString(
            "rel=\"canonical\" href=\"https://www.christopherbell.dev/wfl/favorites\"")))
        .andExpect(content().string(containsString("data-list-mode=\"favorites\"")));
  }

  @Test
  @DisplayName("WFL top-rated page renders the list app mount")
  public void getWhatsForLunchTopRatedPage_rendersListMount() throws Exception {
    mockMvc
        .perform(get("/wfl/top-rated"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CB | Top Rated Restaurants")))
        .andExpect(content().string(not(containsString("noindex,nofollow"))))
        .andExpect(content().string(containsString(
            "rel=\"canonical\" href=\"https://www.christopherbell.dev/wfl/top-rated\"")))
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
  @DisplayName("Music renders a public access-aware library shell")
  public void getMusicPage_rendersAccessAwareShell() throws Exception {
    mockMvc
        .perform(get("/music"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"music-access\"")))
        .andExpect(content().string(containsString("/js/music.js")));
  }

  @Test
  @DisplayName("Public user page renders canonical username social URL")
  public void getPublicUserPage_rendersUsernameSocialUrl() throws Exception {
    when(userPreviews.preview("some_user")).thenReturn(new VoidUserSocialPreview(
        "CB | @some_user in the Void",
        "@some_user has 3 active posts and 4 replies in the Void.",
        "some_user",
        "3 posts · 4 replies"));

    mockMvc
        .perform(get("/u/some_user"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CB | @some_user in the Void")))
        .andExpect(content().string(containsString("3 active posts and 4 replies")))
        .andExpect(content().string(containsString("<h1 id=\"userHeroTitle\">@some_user</h1>")))
        .andExpect(content().string(containsString("https://www.christopherbell.dev/u/some_user")));
  }

  @Test
  void getPublicUserPage_usesResolvedUsernameForCanonicalUrl() throws Exception {
    when(userPreviews.preview("some!user")).thenReturn(new VoidUserSocialPreview(
        "CB | @someuser in the Void",
        "@someuser has 3 active posts and 4 replies in the Void.",
        "someuser",
        "3 posts · 4 replies"));

    mockMvc.perform(get("/u/some!user"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(
            "rel=\"canonical\" href=\"https://www.christopherbell.dev/u/someuser\"")))
        .andExpect(content().string(not(containsString(
            "rel=\"canonical\" href=\"https://www.christopherbell.dev/u/some!user\""))));
  }

  @Test
  void getPublicUserPage_whenMissing_returnsNoIndex404() throws Exception {
    when(userPreviews.preview("missing-user"))
        .thenThrow(new ResourceNotFoundException("SECRET_ACCOUNT"));

    mockMvc.perform(get("/u/missing-user"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(containsString("Page not found")))
        .andExpect(content().string(containsString("noindex,nofollow")))
        .andExpect(content().string(not(containsString("SECRET_ACCOUNT"))));
  }

  @Test
  @DisplayName("Photography usage page is public and linked from the gallery")
  void getPhotographyUsagePage_rendersUsageContract() throws Exception {
    mockMvc
        .perform(get("/photos"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("href=\"/photos/usage\"")));
    mockMvc
        .perform(get("/photos/usage"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Photography Usage")));
  }

  @Test
  @DisplayName("Post page renders canonical post social URL")
  public void getPostPage_rendersPostSocialUrl() throws Exception {
    when(postPreviews.preview("post-123"))
        .thenReturn(new VoidPostSocialPreview(
            "@alice in the Void",
            "Hello & goodbye <script>alert('no')</script> · Temporary thread."));

    mockMvc
        .perform(get("/p/post-123"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(content().string(containsString("https://www.christopherbell.dev/p/post-123")))
        .andExpect(content().string(containsString("@alice in the Void")))
        .andExpect(content().string(containsString("Hello &amp; goodbye &lt;script&gt;")))
        .andExpect(content().string(not(containsString("<script>alert('no')</script>"))));
  }

  @Test
  @DisplayName("Missing or expired post renders a content-free vanished page")
  public void getPostPage_whenUnavailable_rendersContentFree404() throws Exception {
    when(postPreviews.preview("missing-post"))
        .thenThrow(new ResourceNotFoundException("SECRET_SENTINEL_POST_BODY"));

    mockMvc
        .perform(get("/p/missing-post"))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(content().string(containsString("This post vanished into the Void")))
        .andExpect(content().string(containsString(
            "name=\"robots\" content=\"noindex,nofollow\"")))
        .andExpect(content().string(containsString("href=\"/void\"")))
        .andExpect(content().string(not(containsString("SECRET_SENTINEL_POST_BODY"))))
        .andExpect(content().string(not(containsString("/api/posts/"))));
  }
}
