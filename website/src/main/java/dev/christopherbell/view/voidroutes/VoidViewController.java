package dev.christopherbell.view.voidroutes;

import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.util.UriUtils;

/**
 * Serves Void feed, profile, messaging, notification, and post pages.
 */
@Controller
@RequiredArgsConstructor
public class VoidViewController {
  private static final String PUBLIC_ROOT = "https://www.christopherbell.dev";
  private final VoidPostSocialPreviewService postPreviews;

  /**
   * Serves the Void home page.
   *
   * @return {@code void/index.html}
   */
  @GetMapping(value = "/void")
  public String getVoidHomePage(HttpServletRequest request) {
    return "void/index.html";
  }

  /**
   * Serves the Profile page.
   *
   * @return {@code profile.html}
   */
  @GetMapping(value = "/profile")
  public String getProfilePage(HttpServletRequest request) {
    return "profile.html";
  }

  /**
   * Serves the private messages page.
   *
   * @return {@code messages.html}
   */
  @GetMapping(value = "/messages")
  public String getMessagesPage(HttpServletRequest request) {
    return "messages.html";
  }

  /**
   * Serves the signed-in user's notification center page.
   *
   * @return {@code notifications.html}
   */
  @GetMapping(value = "/notifications")
  public String getNotificationsPage(HttpServletRequest request) {
    return "notifications.html";
  }

  /**
   * Serves a public user profile/feed page by username.
   */
  @GetMapping(value = "/u/{username}")
  public String getPublicUserPage(
      @PathVariable String username, HttpServletRequest request, Model model) {
    model.addAttribute("socialUrl", "https://www.christopherbell.dev/u/" + username);
    return "user.html";
  }

  /**
   * Serves an individual post page by id.
   */
  @GetMapping(value = "/p/{postId}")
  public String getPostPage(
      @PathVariable String postId,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    response.setHeader("Cache-Control", "no-store, max-age=0");
    String encodedPostId = UriUtils.encodePathSegment(postId, StandardCharsets.UTF_8);
    model.addAttribute("socialUrl", PUBLIC_ROOT + "/p/" + encodedPostId);
    try {
      VoidPostSocialPreview preview = postPreviews.preview(postId);
      model.addAttribute("postSocialTitle", preview.title());
      model.addAttribute("postSocialDescription", preview.description());
      return "post.html";
    } catch (ResourceNotFoundException exception) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return "post-vanished.html";
    }
  }
}
