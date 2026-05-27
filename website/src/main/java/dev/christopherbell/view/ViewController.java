package dev.christopherbell.view;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * MVC controller serving static application views.
 */
@Controller
public class ViewController {

  /**
   * Serves the home page.
   *
   * @return {@code index.html}
   */
  @RequestMapping(value = "/")
  public String getHomePage() {
    return "index.html";
  }

  /**
   * Serves the Blog page.
   *
   * @return {@code blog.html}
   */
  @GetMapping(value = "/blog")
  public String getBlogPage() {
    return "blog.html";
  }

  /**
   * Serves the Photos page.
   *
   * @return {@code photo/photography.html}
   */
  @GetMapping(value = "/photos")
  public String getPhotosPage() {
    return "photo/photography.html";
  }

  /**
   * Serves the What's For Lunch page.
   *
   * @return {@code whatsforlunch.html}
   */
  @GetMapping(value = "/wfl")
  public String getWhatsForLunchPage(Model model) {
    model.addAttribute("socialTitle", "CB | What's For Lunch?");
    return "whatsforlunch.html";
  }

  /**
   * Serves the signed-in user's WFL favorites page.
   *
   * @return {@code wfl-list.html}
   */
  @GetMapping(value = "/wfl/favorites")
  public String getWhatsForLunchFavoritesPage(Model model) {
    model.addAttribute("socialTitle", "CB | Favorite Restaurants");
    model.addAttribute("listMode", "favorites");
    model.addAttribute("listTitle", "Favorite Restaurants");
    model.addAttribute("listDescription", "Restaurants you have saved from What's For Lunch.");
    return "wfl-list.html";
  }

  /**
   * Serves the public WFL top-rated restaurants page.
   *
   * @return {@code wfl-list.html}
   */
  @GetMapping(value = "/wfl/top-rated")
  public String getWhatsForLunchTopRatedPage(Model model) {
    model.addAttribute("socialTitle", "CB | Top Rated Restaurants");
    model.addAttribute("listMode", "top-rated");
    model.addAttribute("listTitle", "Top 10 Rated Restaurants");
    model.addAttribute("listDescription", "The highest rated restaurants from What's For Lunch.");
    return "wfl-list.html";
  }

  /**
   * Serves a public What's For Lunch restaurant profile.
   *
   * @return {@code restaurant.html}
   */
  @GetMapping(value = "/wfl/restaurants/{restaurantId}")
  public String getWhatsForLunchRestaurantPage(
      @PathVariable String restaurantId,
      Model model
  ) {
    model.addAttribute("socialTitle", "CB | Restaurant");
    model.addAttribute("socialDescription", "Restaurant details from What's For Lunch.");
    model.addAttribute("socialUrl", "https://www.christopherbell.dev/wfl/restaurants/" + restaurantId);
    return "restaurant.html";
  }

  /**
   * Serves the VIN decoder tool.
   *
   * @return {@code vin-decoder.html}
   */
  @GetMapping(value = "/vin-decoder")
  public String getVinDecoderPage() {
    return "vin-decoder.html";
  }

  /**
   * Serves the ZIP coordinate lookup tool.
   *
   * @return {@code zip-coordinates.html}
   */
  @GetMapping(value = "/zip-coordinates")
  public String getZipCoordinatesPage() {
    return "zip-coordinates.html";
  }

  /**
   * Serves the Back Office page shell (role gated on the client).
   *
   * @return {@code back-office.html}
   */
  @GetMapping(value = "/back-office")
  public String getBackOfficePage() {
    return "back-office.html";
  }

  /**
   * Serves the report page.
   *
   * @return {@code report.html}
   */
  @GetMapping(value = "/report")
  public String getReportPage() {
    return "report.html";
  }


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
   * Serves the login page.
   *
   * @return {@code login.html}
   */
  @GetMapping(value = "/login")
  public String getLoginPage(HttpServletRequest request) {
    return "login.html";
  }

  /**
   * Serves the forgot password page.
   *
   * @return {@code forgot-password.html}
   */
  @GetMapping(value = "/forgot-password")
  public String getForgotPasswordPage(HttpServletRequest request) {
    return "forgot-password.html";
  }

  /**
   * Serves the reset password page.
   *
   * @return {@code reset-password.html}
   */
  @GetMapping(value = "/reset-password")
  public String getResetPasswordPage(HttpServletRequest request) {
    return "reset-password.html";
  }

  /**
   * Serves the sign-up page.
   *
   * @return {@code signup.html}
   */
  @GetMapping(value = "/signup")
  public String getSignupPage(HttpServletRequest request) {
    return "signup.html";
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
   * Serves the Void login page.
   *
   * @return {@code void/login.html}
   */
  @GetMapping(value = "/void/login")
  public String getVoidLoginPage(HttpServletRequest request) {
    return "void/login.html";
  }

  /**
   * Serves the Void signup page.
   *
   * @return {@code void/sign_up.html}
   */
  @GetMapping(value = "/void/signup")
  public String getVoidCreateAccountPage(HttpServletRequest request) {
    return "void/sign_up.html";
  }

  /**
   * Serves The Bell home page.
   *
   * @return {@code thebell/index.html}
   */
  @GetMapping(value = "/thebell")
  public String getTheBellHomePage(HttpServletRequest request) {
    return "thebell/index.html";
  }

  /**
   * Serves The Bell "Tony" page.
   *
   * @return {@code thebell/tony.html}
   */
  @GetMapping(value = "/thebell/tony")
  public String getTheBellTonyPage(HttpServletRequest request) {
    return "thebell/tony.html";
  }

  /** Serves an individual post page by id. */
  @GetMapping(value = "/p/{postId}")
  public String getPostPage(
      @PathVariable String postId, HttpServletRequest request, Model model) {
    model.addAttribute("socialUrl", "https://www.christopherbell.dev/p/" + postId);
    return "post.html";
  }
}
