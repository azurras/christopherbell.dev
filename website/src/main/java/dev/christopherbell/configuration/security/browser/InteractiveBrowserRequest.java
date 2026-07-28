package dev.christopherbell.configuration.security.browser;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Classifies requests that represent deliberate user activity for idle-session renewal. */
@Component
public class InteractiveBrowserRequest {

  public boolean matches(HttpServletRequest request) {
    String method = request.getMethod();
    String path = request.getRequestURI();
    if (isBackground(method, path) || isStaticAsset(path)) return false;
    if ("GET".equalsIgnoreCase(method)) {
      String accept = request.getHeader("Accept");
      return accept != null && accept.toLowerCase().contains("text/html");
    }
    return "POST".equalsIgnoreCase(method)
        || "PUT".equalsIgnoreCase(method)
        || "PATCH".equalsIgnoreCase(method)
        || "DELETE".equalsIgnoreCase(method);
  }

  private boolean isBackground(String method, String path) {
    if (path == null) return true;
    if (path.startsWith("/api/shared-folder/") && path.contains("/media/")) return true;
    if (path.startsWith("/api/shared-folder/") && path.endsWith("/radio/duration")) return true;
    if (path.startsWith("/api/music/") && path.contains("/stream")) return true;
    if (path.startsWith("/api/music/") && path.contains("/artwork")) return true;
    return "GET".equalsIgnoreCase(method)
        && path.startsWith("/api/music/")
        && path.contains("/radio");
  }

  private boolean isStaticAsset(String path) {
    return path != null && (path.startsWith("/css/")
        || path.startsWith("/images/")
        || path.startsWith("/js/")
        || path.startsWith("/vendor/")
        || path.startsWith("/webjars/")
        || path.equals("/favicon.ico"));
  }
}
