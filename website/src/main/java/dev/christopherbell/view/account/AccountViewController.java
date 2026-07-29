package dev.christopherbell.view.account;

import dev.christopherbell.federation.consent.FederationConsentService;
import dev.christopherbell.view.ViewIndexingPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves account and authentication HTML pages.
 */
@Controller
public class AccountViewController {
  private final FederationConsentService federationConsent;

  public AccountViewController(FederationConsentService federationConsent) {
    this.federationConsent = federationConsent;
  }

  /**
   * Serves the login page.
   *
   * @return {@code login.html}
   */
  @GetMapping(value = "/login")
  public String getLoginPage(HttpServletRequest request, Model model) {
    ViewIndexingPolicy.noIndex(model);
    return "login.html";
  }

  /**
   * Serves the forgot password page.
   *
   * @return {@code forgot-password.html}
   */
  @GetMapping(value = "/forgot-password")
  public String getForgotPasswordPage(HttpServletRequest request, Model model) {
    ViewIndexingPolicy.noIndex(model);
    return "forgot-password.html";
  }

  /**
   * Serves the reset password page.
   *
   * @return {@code reset-password.html}
   */
  @GetMapping(value = "/reset-password")
  public String getResetPasswordPage(HttpServletRequest request, Model model) {
    ViewIndexingPolicy.noIndex(model);
    return "reset-password.html";
  }

  /**
   * Serves the sign-up page.
   *
   * @return {@code signup.html}
   */
  @GetMapping(value = "/signup")
  public String getSignupPage(HttpServletRequest request, Model model) {
    ViewIndexingPolicy.noIndex(model);
    model.addAttribute("federationEnrollmentAvailable", federationConsent.enrollmentAvailable());
    return "signup.html";
  }

  /**
   * Serves the Void login page.
   *
   * @return {@code void/login.html}
   */
  @GetMapping(value = "/void/login")
  public String getVoidLoginPage(HttpServletRequest request, Model model) {
    ViewIndexingPolicy.noIndex(model);
    return "void/login.html";
  }

  /**
   * Serves the Void signup page.
   *
   * @return {@code void/sign_up.html}
   */
  @GetMapping(value = "/void/signup")
  public String getVoidCreateAccountPage(HttpServletRequest request, Model model) {
    ViewIndexingPolicy.noIndex(model);
    return "void/sign_up.html";
  }
}
