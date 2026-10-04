package dev.christopherbell.configuration.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/** Matches only public cacheable resources that never consume an authenticated principal. */
public final class StaticAssetRequestMatcher implements RequestMatcher {
  private static final List<RequestMatcher> MATCHERS = List.of(
      read("/favicon.ico"),
      read("/css/**"),
      read("/images/**"),
      read("/js/**"),
      read("/vendor/**"),
      read("/webjars/bootstrap/5.3.8/**"),
      read("/{assetVersion}/favicon.ico"),
      read("/{assetVersion}/css/**"),
      read("/{assetVersion}/images/**"),
      read("/{assetVersion}/js/**"),
      read("/{assetVersion}/vendor/**"));

  @Override
  public boolean matches(HttpServletRequest request) {
    return MATCHERS.stream().anyMatch(matcher -> matcher.matches(request));
  }

  private static RequestMatcher read(String pattern) {
    return new OrRequestMatcher(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, pattern),
        PathPatternRequestMatcher.pathPattern(HttpMethod.HEAD, pattern));
  }
}
