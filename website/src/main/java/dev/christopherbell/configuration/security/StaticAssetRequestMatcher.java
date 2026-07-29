package dev.christopherbell.configuration.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Matches only public cacheable resources that never consume an authenticated principal. */
public final class StaticAssetRequestMatcher implements RequestMatcher {
  private static final List<RequestMatcher> MATCHERS = List.of(
      get("/favicon.ico"),
      get("/css/**"),
      get("/images/**"),
      get("/js/**"),
      get("/vendor/**"),
      get("/webjars/bootstrap/5.3.3/**"),
      get("/{assetVersion}/favicon.ico"),
      get("/{assetVersion}/css/**"),
      get("/{assetVersion}/images/**"),
      get("/{assetVersion}/js/**"),
      get("/{assetVersion}/vendor/**"));

  @Override
  public boolean matches(HttpServletRequest request) {
    return MATCHERS.stream().anyMatch(matcher -> matcher.matches(request));
  }

  private static RequestMatcher get(String pattern) {
    return PathPatternRequestMatcher.pathPattern(HttpMethod.GET, pattern);
  }
}
