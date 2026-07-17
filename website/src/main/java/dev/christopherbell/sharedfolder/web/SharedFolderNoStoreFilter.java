package dev.christopherbell.sharedfolder.web;

import static dev.christopherbell.libs.api.APIVersion.V20260717;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prevents browser and intermediary caches from retaining any protected shared-folder response. */
public class SharedFolderNoStoreFilter extends OncePerRequestFilter {
  private static final String API_PREFIX = "/api/shared-folder" + V20260717 + "/";
  private static final String NO_STORE = "private, no-store";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(API_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
    filterChain.doFilter(request, response);
  }
}
