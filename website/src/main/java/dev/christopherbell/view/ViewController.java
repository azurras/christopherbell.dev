package dev.christopherbell.view;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
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
  public String getWhatsForLunchPage() {
    return "whatsforlunch.html";
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
   * Serves the login page.
   *
   * @return {@code login.html}
   */
  @GetMapping(value = "/login")
  public String getLoginPage(HttpServletRequest request) {
    return "login.html";
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
  public String getPublicUserPage(HttpServletRequest request) {
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
  public String getPostPage(HttpServletRequest request) {
    return "post.html";
  }
}
