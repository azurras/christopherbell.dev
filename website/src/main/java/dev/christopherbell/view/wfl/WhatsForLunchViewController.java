package dev.christopherbell.view.wfl;

import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.view.ViewIndexingPolicy;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.util.UriUtils;

/**
 * Serves What's For Lunch HTML pages and their social metadata.
 */
@Controller
@RequiredArgsConstructor
public class WhatsForLunchViewController {
  private static final String PUBLIC_ROOT = "https://www.christopherbell.dev";
  private final RestaurantSocialPreviewService restaurantPreviews;

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
   * Serves the public WFL top-rated restaurants page.
   *
   * @return {@code wfl-list.html}
   */
  @GetMapping(value = "/wfl/top-rated")
  public String getWhatsForLunchTopRatedPage(Model model) {
    model.addAttribute("socialTitle", "CB | Top Rated Restaurants");
    model.addAttribute("socialUrl", PUBLIC_ROOT + "/wfl/top-rated");
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
      HttpServletResponse response,
      Model model
  ) {
    var encodedRestaurantId = UriUtils.encodePathSegment(restaurantId, StandardCharsets.UTF_8);
    model.addAttribute("socialUrl", PUBLIC_ROOT + "/wfl/restaurants/" + encodedRestaurantId);
    try {
      var preview = restaurantPreviews.preview(restaurantId);
      model.addAttribute("socialTitle", preview.title());
      model.addAttribute("socialDescription", preview.description());
      model.addAttribute("restaurantName", preview.name());
      model.addAttribute("restaurantHeroMetadata", preview.heroMetadata());
      return "restaurant.html";
    } catch (ResourceNotFoundException exception) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return "error/404";
    }
  }
}
