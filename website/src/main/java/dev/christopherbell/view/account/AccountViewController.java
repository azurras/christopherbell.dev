package dev.christopherbell.view.account;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves account and authentication HTML pages.
 */
@Controller
public class AccountViewController {

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
}
