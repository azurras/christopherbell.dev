package dev.christopherbell.whatsforlunch.restaurant.favorite;

import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Stores member favorite restaurants for What's For Lunch. */
public interface RestaurantFavoriteRepository {
  RestaurantFavorite save(RestaurantFavorite favorite);
  void deleteByRestaurantIdAndAccountId(String restaurantId, String accountId);

  List<RestaurantFavorite> findByAccountIdOrderByCreatedOnDesc(String accountId);

  List<RestaurantFavorite> findByRestaurantIdInAndAccountId(Collection<String> restaurantIds, String accountId);

  Optional<RestaurantFavorite> findByRestaurantIdAndAccountId(String restaurantId, String accountId);
}
