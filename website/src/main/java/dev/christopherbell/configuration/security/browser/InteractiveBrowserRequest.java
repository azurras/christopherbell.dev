package dev.christopherbell.configuration.security.browser;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Classifies requests that represent deliberate user activity for idle-session renewal. */
@Component
public class InteractiveBrowserRequest {

  public boolean matches(HttpServletRequest request) {
    String method = request.getMethod();
    String path = request.getRequestURI();
    if (isBackground(method, path) || isStaticAsset(path)) {
      return false;
    }
    if ("GET".equalsIgnoreCase(method)) {
      String accept = request.getHeader("Accept");
      return accept != null && accept.toLowerCase(Locale.ROOT).contains("text/html");
    }
    return "POST".equalsIgnoreCase(method)
        || "PUT".equalsIgnoreCase(method)
        || "PATCH".equalsIgnoreCase(method)
        || "DELETE".equalsIgnoreCase(method);
  }

  private boolean isBackground(String method, String path) {
    if (path == null) {
      return true;
    }
    var sharedFolderBackground = path.startsWith("/api/shared-folder/")
        && (path.contains("/media/") || path.endsWith("/radio/duration"));
    var musicBackground = path.startsWith("/api/music/")
        && (path.contains("/stream")
            || path.contains("/artwork")
            || ("GET".equalsIgnoreCase(method) && path.contains("/radio")));
    return sharedFolderBackground || musicBackground;
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
