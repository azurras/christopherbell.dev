package dev.christopherbell.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.christopherbell.configuration.security.SecurityConfig;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Unit tests for public route rules that should not require authentication.
 */
class SecurityConfigTest {

  @Test
  @DisplayName("Favicon is a public browser asset")
  void publicMatchers_whenFaviconRequested_matchesWithoutAuthentication() throws Exception {
    var request = new MockHttpServletRequest("GET", "/favicon.ico");
    request.setServletPath("/favicon.ico");

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @Test
  @DisplayName("WFL ZIP nearby endpoint is public")
  void publicMatchers_whenWflZipNearbyRequested_matchesWithoutAuthentication() throws Exception {
    var path = "/api/whatsforlunch/restaurant/2026-05-17/nearby/zip/78701";
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @Test
  void publicMatchers_whenWflFreshnessRequested_matchesOnlyGetWithoutAuthentication()
      throws Exception {
    var path = "/api/whatsforlunch/restaurant/2026-07-26/freshness";

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request("GET", path))));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request("POST", path))));
  }

  @Test
  void publicMatchers_whenVinBatchDecodeRequested_matchesOnlyExactPost() throws Exception {
    var path = "/api/vehicles/2026-07-26/vin/decode/batch";

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request("POST", path))));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request("GET", path))));
    assertFalse(publicMatchers().stream()
        .anyMatch(matcher -> matcher.matches(request("POST", path + "/extra"))));
  }

  @Test
  @DisplayName("Location ZIP coordinate endpoint is public")
  void publicMatchers_whenLocationZipRequested_matchesWithoutAuthentication() throws Exception {
    var path = "/api/location/zip/78701";
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @Test
  @DisplayName("ZIP coordinate tool page is public")
  void publicMatchers_whenZipCoordinateToolRequested_matchesWithoutAuthentication() throws Exception {
    var path = "/zip-coordinates";
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @Test
  @DisplayName("Cane's Box Tracker tool page is public")
  void publicMatchers_whenCanesBoxTrackerToolRequested_matchesWithoutAuthentication() throws Exception {
    var path = "/canes-box-tracker";
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @Test
  @DisplayName("Cane's Box Tracker tool page is public with a trailing slash")
  void publicMatchers_whenCanesBoxTrackerToolTrailingSlashRequested_matchesWithoutAuthentication() throws Exception {
    var path = "/canes-box-tracker/";
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @Test
  @DisplayName("Notifications page shell is public")
  void publicMatchers_whenNotificationsPageRequested_matchesWithoutAuthentication() throws Exception {
    var path = "/notifications";
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @Test
  @DisplayName("Shared folder shell is public while every shared-folder API stays protected")
  void publicMatchers_whenSharedFolderRequested_onlyMatchesPageShell() throws Exception {
    var shell = request("GET", "/shared");
    var entries = request("GET", "/api/shared-folder/2026-07-17/entries");
    var content = request("HEAD", "/api/shared-folder/2026-07-17/content");
    var preview = request("GET", "/api/shared-folder/2026-07-17/preview");

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(shell)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(entries)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(content)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(preview)));
  }

  @Test
  @DisplayName("Music shell and access probe are public while catalog and media stay protected")
  void publicMatchers_whenMusicRequested_matchesOnlyShellAndExactAccessProbe() throws Exception {
    var shell = request("GET", "/music");
    var access = request("GET", "/api/music/2026-07-28/access");
    var accessPost = request("POST", "/api/music/2026-07-28/access");
    var catalog = request("GET", "/api/music/2026-07-28/catalog");
    var stream = request("GET", "/api/music/2026-07-28/tracks/track-1/stream");
    var download = request("GET", "/api/music/2026-07-28/tracks/track-1/download");
    var radio = request("GET", "/api/music/2026-07-28/radio");
    var queueRead = request("GET", "/api/music/2026-07-28/queue");
    var queueWrite = request("POST", "/api/music/2026-07-28/queue");
    var playlistsRead = request("GET", "/api/music/2026-07-28/library/playlists");
    var playlistsWrite = request("POST", "/api/music/2026-07-28/library/playlists");
    var preferences = request(
        "PATCH", "/api/music/2026-07-28/library/tracks/track-1/preferences");
    var history = request("GET", "/api/music/2026-07-28/library/history");
    var metadata = request("PATCH", "/api/music/2026-07-28/tracks/track-1/metadata");
    var metadataUndo = request(
        "POST", "/api/music/2026-07-28/metadata-edits/edit-1/undo");

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(shell)));
    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(access)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(accessPost)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(catalog)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(stream)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(download)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(radio)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(queueRead)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(queueWrite)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(playlistsRead)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(playlistsWrite)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(preferences)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(history)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(metadata)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(metadataUndo)));
  }

  @Test
  @DisplayName("Shared-folder worker bootstrap is public only for its exact anonymous GET")
  void publicMatchers_whenSharedFolderWorkerRequested_matchesOnlyTheExactGet() throws Exception {
    var worker = request("GET", "/shared-folder-auth-sw.js");
    worker.setQueryString("cache=1");
    var post = request("POST", "/shared-folder-auth-sw.js");
    var extra = request("GET", "/shared-folder-auth-sw.js/extra");
    var sourceMap = request("GET", "/shared-folder-auth-sw.js.map");
    var sharedFolderApi = request("GET", "/api/shared-folder/2026-07-17/entries");

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(worker)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(post)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(extra)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(sourceMap)));
    assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(sharedFolderApi)));
  }

  @Test
  @DisplayName("Only legacy API login bypasses CSRF; browser cookie login does not")
  void csrfBypass_whenLoginModeChanges_matchesOnlyLegacyApiContract() {
    var legacyLogin = request("POST", "/api/accounts/2024-12-15/login");
    var browserLogin = request("POST", "/api/accounts/2024-12-15/login");
    browserLogin.addHeader("X-CBELL-Browser-Session", "cookie");
    var loginPage = request("GET", "/api/accounts/2024-12-15/login");
    var otherPost = request("POST", "/api/accounts/2024-12-15/create");

    assertTrue(SecurityConfig.isLegacyApiLogin(legacyLogin));
    assertFalse(SecurityConfig.isLegacyApiLogin(browserLogin));
    assertFalse(SecurityConfig.isLegacyApiLogin(loginPage));
    assertFalse(SecurityConfig.isLegacyApiLogin(otherPost));
  }

  @Test
  @DisplayName("Only GET public-content APIs and the pinned Bootstrap WebJar are public")
  void publicContentMatchersAreGetOnly() throws Exception {
    var paths = List.of(
        "/api/blog/v1/posts",
        "/api/blog/v1/posts/post-1",
        "/api/photo/v1",
        "/webjars/bootstrap/5.3.8/css/bootstrap.min.css",
        "/webjars/bootstrap/5.3.8/js/bootstrap.bundle.min.js");

    for (var path : paths) {
      assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request("GET", path))));
      assertFalse(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request("POST", path))));
    }
    assertFalse(publicMatchers().stream().anyMatch(matcher ->
        matcher.matches(request("GET", "/webjars/bootstrap/5.3.3/css/bootstrap.min.css"))));
    assertFalse(publicMatchers().stream().anyMatch(matcher ->
        matcher.matches(request("GET", "/webjars/other/1.0.0/example.js"))));
  }

  @Test
  @DisplayName("Unknown HTML GETs reach the 404 renderer without opening protected namespaces")
  void unknownHtmlFallbackExcludesProtectedNamespacesAndMutations() throws Exception {
    var matchers = publicMatchers();

    assertTrue(matchers.stream().anyMatch(matcher ->
        matcher.matches(request("GET", "/definitely-not-a-real-page"))));
    assertTrue(matchers.stream().anyMatch(matcher ->
        matcher.matches(request("GET", "/not-a-real/page"))));
    assertFalse(matchers.stream().anyMatch(matcher ->
        matcher.matches(request("POST", "/definitely-not-a-real-page"))));
    for (var path : List.of(
        "/api/admin/secret",
        "/actuator/health",
        "/v3/api-docs",
        "/swagger-ui/index.html",
        "/ap/private",
        "/.well-known/private",
        "/nodeinfo/private")) {
      assertFalse(matchers.stream().anyMatch(matcher -> matcher.matches(request("GET", path))));
    }

    var encodedApi = request("GET", "/%61pi/admin/secret");
    encodedApi.setServletPath("/api/admin/secret");
    assertFalse(matchers.stream().anyMatch(matcher -> matcher.matches(encodedApi)));

    var encodedActuator = request("GET", "/%61ctuator/health");
    encodedActuator.setServletPath("/actuator/health");
    assertFalse(matchers.stream().anyMatch(matcher -> matcher.matches(encodedActuator)));

    assertFalse(matchers.stream().anyMatch(matcher ->
        matcher.matches(request("GET", "/some%20unknown/page"))));

    var contextPathApi = request("GET", "/site/api/admin/secret");
    contextPathApi.setContextPath("/site");
    contextPathApi.setServletPath("/api/admin/secret");
    assertFalse(matchers.stream().anyMatch(matcher -> matcher.matches(contextPathApi)));
  }

  @Test
  @DisplayName("Federation exposes only exact read-only discovery routes")
  void federationMatchersAreReadOnlyAndDoNotExposeInboxMutation() throws Exception {
    var paths = List.of(
        "/.well-known/webfinger",
        "/.well-known/nodeinfo",
        "/nodeinfo/2.1",
        "/ap/users/chris",
        "/ap/users/chris/outbox",
        "/ap/users/chris/followers",
        "/ap/users/chris/following");

    for (var path : paths) {
      assertTrue(publicMatchers().stream().anyMatch(
          matcher -> matcher.matches(request("GET", path))));
      assertFalse(publicMatchers().stream().anyMatch(
          matcher -> matcher.matches(request("POST", path))));
    }
    assertFalse(publicMatchers().stream().anyMatch(
        matcher -> matcher.matches(request("POST", "/ap/users/chris/inbox"))));
    assertFalse(publicMatchers().stream().anyMatch(
        matcher -> matcher.matches(request("POST", "/ap/inbox"))));
  }

  private MockHttpServletRequest request(String method, String path) {
    var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return request;
  }

  @SuppressWarnings("unchecked")
  private List<RequestMatcher> publicMatchers() throws Exception {
    Method method = SecurityConfig.class.getDeclaredMethod("publicMatchersList");
    method.setAccessible(true);
    return (List<RequestMatcher>) method.invoke(new SecurityConfig());
  }
}
