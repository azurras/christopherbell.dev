package dev.christopherbell.view.voidroutes;

import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.post.model.PostTopic;
import dev.christopherbell.view.ViewIndexingPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

/**
 * Serves Void feed, profile, messaging, notification, and post pages.
 */
@Controller
@RequiredArgsConstructor
public class VoidViewController {
  private static final String PUBLIC_ROOT = "https://www.christopherbell.dev";
  private final VoidPostSocialPreviewService postPreviews;
  private final VoidUserSocialPreviewService userPreviews;

  /**
   * Serves the Void home page.
   *
   * @return {@code void/index.html}
   */
  @GetMapping(value = "/void")
  public String getVoidHomePage(HttpServletRequest request) {
    return "void/index.html";
  }

  /** Serves the public Void discovery shell. */
  @GetMapping(value = "/void/explore")
  public String getVoidExplorePage(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store, max-age=0");
    return "void/explore.html";
  }

  /** Serves one normalized public topic shell without embedding post data. */
  @GetMapping(value = "/void/topic/{topic}")
  public String getVoidTopicPage(
      @PathVariable String topic, HttpServletResponse response, Model model) {
    try {
      model.addAttribute("topic", PostTopic.canonicalizeRoute(topic));
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid topic.");
    }
    response.setHeader("Cache-Control", "no-store, max-age=0");
    return "void/topic.html";
  }

  /**
   * Serves the Profile page.
   *
   * @return {@code profile.html}
   */
  @GetMapping(value = "/profile")
  public String getProfilePage(HttpServletRequest request, Model model) {
    ViewIndexingPolicy.noIndex(model);
    return "profile.html";
  }

  /**
   * Serves the private messages page.
   *
   * @return {@code messages.html}
   */
  @GetMapping(value = "/messages")
  public String getMessagesPage(HttpServletRequest request, Model model) {
    ViewIndexingPolicy.noIndex(model);
    return "messages.html";
  }

  /**
   * Serves the signed-in user's notification center page.
   *
   * @return {@code notifications.html}
   */
  @GetMapping(value = "/notifications")
  public String getNotificationsPage(HttpServletRequest request, Model model) {
    ViewIndexingPolicy.noIndex(model);
    return "notifications.html";
  }

  /**
   * Serves a public user profile/feed page by username.
   */
  @GetMapping(value = "/u/{username}")
  public String getPublicUserPage(
      @PathVariable String username,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    var encodedUsername = UriUtils.encodePathSegment(username, StandardCharsets.UTF_8);
    model.addAttribute("socialUrl", PUBLIC_ROOT + "/u/" + encodedUsername);
    try {
      var preview = userPreviews.preview(username);
      model.addAttribute("socialTitle", preview.title());
      model.addAttribute("socialDescription", preview.description());
      model.addAttribute("profileUsername", preview.username());
      model.addAttribute("profileHeroMetadata", preview.heroMetadata());
      return "user.html";
    } catch (ResourceNotFoundException | IllegalArgumentException exception) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return "error/404";
    }
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
