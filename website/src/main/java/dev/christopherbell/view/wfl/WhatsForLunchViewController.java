package dev.christopherbell.view.wfl;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves What's For Lunch HTML pages and their social metadata.
 */
@Controller
public class WhatsForLunchViewController {

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
}
