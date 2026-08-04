package dev.christopherbell.view.wfl;

import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.view.ViewIndexingPolicy;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves What's For Lunch HTML pages and their social metadata.
 */
@Controller
@RequiredArgsConstructor
public class WhatsForLunchViewController {
  private static final String PUBLIC_ROOT = "https://www.christopherbell.dev";
  private final RestaurantProfilePageService restaurantProfiles;

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
    model.addAttribute("socialUrl", PUBLIC_ROOT + "/wfl/favorites");
    ViewIndexingPolicy.noIndex(model);
    model.addAttribute("listMode", "favorites");
    model.addAttribute("listTitle", "Favorite Restaurants");
    model.addAttribute("listDescription", "Restaurants you have saved from What's For Lunch.");
    return "wfl-list.html";
  }

  /**
   * Serves the public WFL top-liked restaurants page.
   *
   * @return {@code wfl-list.html}
   */
  @GetMapping(value = "/wfl/top-liked")
  public String getWhatsForLunchTopLikedPage(Model model) {
    model.addAttribute("socialTitle", "CB | Top 10 Liked Restaurants");
    model.addAttribute("socialUrl", PUBLIC_ROOT + "/wfl/top-liked");
    model.addAttribute("listMode", "top-liked");
    model.addAttribute("listTitle", "Top 10 Liked Restaurants");
    model.addAttribute("listDescription", "The restaurants with the highest member approval from What's For Lunch.");
    return "wfl-list.html";
  }

  /** Permanently redirects the legacy top-rated route to the canonical public list. */
  @GetMapping(value = "/wfl/top-rated")
  public ResponseEntity<Void> legacyTopRated() {
    return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT)
        .location(URI.create("/wfl/top-liked"))
        .build();
  }

  /**
   * Serves a public What's For Lunch restaurant profile.
   *
   * @return {@code restaurant.html}
   */
  @GetMapping(value = "/wfl/restaurants/{restaurantId}")
  public String getWhatsForLunchRestaurantPage(
      @PathVariable String restaurantId,
      HttpServletResponse response,
      Model model
  ) {
    try {
      model.addAttribute("restaurantProfile", restaurantProfiles.profile(restaurantId));
      return "restaurant.html";
    } catch (ResourceNotFoundException exception) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return "error/404";
    }
  }
}
