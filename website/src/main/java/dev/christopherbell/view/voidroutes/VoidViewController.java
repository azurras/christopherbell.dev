package dev.christopherbell.view.voidroutes;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves Void feed, profile, messaging, notification, and post pages.
 */
@Controller
public class VoidViewController {

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
      @PathVariable String postId, HttpServletRequest request, Model model) {
    model.addAttribute("socialUrl", "https://www.christopherbell.dev/p/" + postId);
    return "post.html";
  }
}
