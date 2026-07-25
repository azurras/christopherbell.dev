package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.christopherbell.configuration.filter.VersionedStaticAssetCacheFilter;
import dev.christopherbell.configuration.security.SecurityConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.util.matcher.RequestMatcher;

class PublicDeliveryConfigurationTest {
  private static final Path RESOURCES = Path.of("src/main/resources");
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final Pattern LOCAL_ASSET_TAG = Pattern.compile(
      "<(?:link|script|img)\\b[^>]*(?<attribute>href|src)=\\\"(?<path>/(?:css|js|images)/[^\\\"]+)\\\"[^>]*>",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  @Test
  void publicMetadataResourcesAreCanonicalAndParseable() throws Exception {
    var robots = Files.readString(RESOURCES.resolve("static/robots.txt"));
    assertThat(robots)
        .contains("User-agent: *", "Allow: /")
        .contains("Sitemap: https://www.christopherbell.dev/sitemap.xml");

    var sitemap = RESOURCES.resolve("static/sitemap.xml").toFile();
    var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(sitemap);
    assertThat(document.getDocumentElement().getNodeName()).isEqualTo("urlset");
    assertThat(document.getElementsByTagName("loc").getLength()).isGreaterThanOrEqualTo(8);
    assertThat(Files.readString(sitemap.toPath()))
        .contains("https://www.christopherbell.dev/")
        .contains("https://www.christopherbell.dev/blog")
        .contains("https://www.christopherbell.dev/photos")
        .contains("https://www.christopherbell.dev/void")
        .contains("https://www.christopherbell.dev/wfl")
        .contains("https://www.christopherbell.dev/canes-box-tracker")
        .contains("https://www.christopherbell.dev/vin-decoder")
        .contains("https://www.christopherbell.dev/zip-coordinates");
  }

  @Test
  void publicMetadataOverridesTheLongLivedAssetCache() {
    var controller = new PublicMetadataController();

    assertThat(controller.robots().getHeaders().getCacheControl()).isEqualTo("no-cache");
    assertThat(controller.sitemap().getHeaders().getCacheControl()).isEqualTo("no-cache");
  }

  @Test
  void healthGroupsAreDetailFreeAndOnlyProbePathsArePublic() throws Exception {
    var configuration = applicationConfiguration();
    assertThat(configuration.at("/management/endpoints/web/exposure/include").asText())
        .isEqualTo("health");
    assertThat(configuration.at("/management/endpoint/health/show-details").asText())
        .isEqualTo("never");
    assertThat(configuration.at("/management/endpoint/health/probes/enabled").asBoolean())
        .isTrue();
    assertThat(configuration.at("/management/endpoint/health/group/readiness/include").asText())
        .isEqualTo("readinessState,mongo");

    assertThat(isPublic("GET", "/actuator/health/liveness")).isTrue();
    assertThat(isPublic("GET", "/actuator/health/readiness")).isTrue();
    assertThat(isPublic("GET", "/actuator/health")).isFalse();
    assertThat(isPublic("GET", "/actuator/health/mongo")).isFalse();
  }

  @Test
  void staticAssetsUseReleaseScopedImmutableCaching() throws Exception {
    var configuration = applicationConfiguration();
    assertThat(configuration.at("/spring/web/resources/cache/cachecontrol/max-age").asText())
        .isEqualTo("1h");
    assertThat(configuration.at("/spring/web/resources/cache/cachecontrol/cache-public").asBoolean())
        .isTrue();
    assertThat(configuration.at("/spring/web/resources/cache/cachecontrol/immutable").isMissingNode())
        .isTrue();
    assertThat(configuration.at("/spring/web/resources/chain/strategy/fixed/enabled").asBoolean())
        .isTrue();
    assertThat(configuration.at("/spring/web/resources/chain/strategy/fixed/version").asText())
        .isEqualTo("${GIT_COMMIT:dev}");
    assertThat(configuration.at("/spring/web/resources/chain/strategy/fixed/paths").asText())
        .isEqualTo("/css/**,/js/**,/images/**,/favicon.ico");
    assertThat(isPublic("GET", "/release-sha/css/main.css")).isTrue();
    assertThat(isPublic("GET", "/release-sha/js/app.js")).isTrue();
    assertThat(isPublic("GET", "/release-sha/images/previews/site.png")).isTrue();
    assertThat(isPublic("GET", "/release-sha/favicon.ico")).isTrue();
    assertThat(isPublic("POST", "/release-sha/js/app.js")).isFalse();
  }

  @Test
  void onlyVersionedAssetsReceiveTheLongLivedImmutableDirective() throws Exception {
    var filter = new VersionedStaticAssetCacheFilter();
    var versionedResponse = filterResponse(filter, "GET", "/release-sha/js/app.js");
    var unversionedResponse = filterResponse(filter, "GET", "/js/app.js");

    assertThat(versionedResponse.getHeader("Cache-Control"))
        .isEqualTo("max-age=31536000, public, immutable");
    assertThat(unversionedResponse.getHeader("Cache-Control"))
        .isEqualTo("max-age=3600, public");
  }

  @Test
  void everyTemplateLocalAssetUsesAThymeleafVersionedUrl() throws IOException {
    try (Stream<Path> paths = Files.walk(RESOURCES.resolve("templates"))) {
      var missingVersionedUrl = paths
          .filter(path -> path.toString().endsWith(".html"))
          .flatMap(this::unversionedAssetTags)
          .toList();

      assertThat(missingVersionedUrl).isEmpty();
    }
  }

  private Stream<String> unversionedAssetTags(Path path) {
    try {
      var content = Files.readString(path);
      var matcher = LOCAL_ASSET_TAG.matcher(content);
      var failures = Stream.<String>builder();
      while (matcher.find()) {
        var attribute = matcher.group("attribute").toLowerCase();
        var assetPath = matcher.group("path");
        var tag = matcher.group();
        if (!tag.contains("th:" + attribute + "=\"@{" + assetPath + "}\"")) {
          failures.add(path + ": " + tag.replaceAll("\\s+", " "));
        }
      }
      return failures.build();
    } catch (IOException error) {
      throw new IllegalStateException("Cannot inspect template " + path, error);
    }
  }

  private JsonNode applicationConfiguration() throws IOException {
    return YAML.readTree(RESOURCES.resolve("application.yml").toFile());
  }

  private MockHttpServletResponse filterResponse(
      VersionedStaticAssetCacheFilter filter, String method, String path) throws Exception {
    var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (ignoredRequest, downstreamResponse) ->
        ((jakarta.servlet.http.HttpServletResponse) downstreamResponse)
            .setHeader("Cache-Control", "max-age=3600, public"));
    return response;
  }

  private boolean isPublic(String method, String path) {
    var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return publicMatchers().stream().anyMatch(matcher -> matcher.matches(request));
  }

  private List<RequestMatcher> publicMatchers() {
    return SecurityConfig.publicMatchersList();
  }
}
